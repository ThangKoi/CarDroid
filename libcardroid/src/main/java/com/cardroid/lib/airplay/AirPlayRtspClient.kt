package com.cardroid.lib.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.LinkedHashMap

/**
 * Tiny RTSP/1.0 client. RTSP shares its line-based framing with HTTP/1.x so
 * we keep it simple: write request -> read status line + headers + (optional)
 * Content-Length-sized body.
 *
 * Sockets are created via the supplied factory so callers can bind them to
 * a specific Android Network via `network.socketFactory.createSocket()`.
 */
class AirPlayRtspClient(
    private val params: AirPlayParams,
    private val socketFactory: () -> Socket = { Socket() },
    private val log: (String) -> Unit = {},
    /**
     * Optional address resolver. When non-null we use it to obtain the
     * connect-target `InetSocketAddress` rather than parsing
     * [AirPlayParams.host] via `Inet6Address.getByName`. Android needs this so
     * the caller can build a link-local IPv6 with the correct scope id (the
     * `NetworkInterface` actually backing the joined accessory AP, which may
     * not be `wlan0`). When this is supplied we also call
     * `addressBinder?.invoke(socket)` BEFORE `connect()` so the platform can
     * `network.bindSocket(fd)` on Android.
     */
    private val addressResolver: (() -> InetSocketAddress)? = null,
    private val addressBinder: ((Socket) -> Unit)? = null,
    private val addressCandidates: (() -> List<InetSocketAddress>)? = null,
) {
    private var socket: Socket? = null
    val connectedAddress: java.net.InetAddress? get() = socket?.inetAddress
    private var cseq = -1
    private var controlInput: InputStream? = null
    private var controlOutput: java.io.OutputStream? = null

    data class Response(
        val statusLine: String,
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    fun connect() {
        close()
        val attempted = mutableSetOf<String>()
        var lastError: Exception? = null
        var unreachableRounds = 0
        var sawUnreachable = false
        while (true) {
            val candidates = addressCandidates?.invoke() ?: listOf(addressResolver?.invoke()
                ?: InetSocketAddress(java.net.InetAddress.getByName(params.host),params.airPlayPort))
            var target = candidates.firstOrNull { it.toString() !in attempted }
            if (target == null && sawUnreachable && unreachableRounds < 4) {
                unreachableRounds++
                log("RTSP: receiver unreachable (${lastError?.message}); retry round $unreachableRounds/4 in 750ms")
                Thread.sleep(750)
                attempted.clear(); sawUnreachable = false
                continue
            }
            target = target ?: throw (lastError ?: IllegalStateException("No receiver address"))
            attempted += target.toString()
            val s = socketFactory()
            try {
                s.tcpNoDelay = true; s.soTimeout = 10000
                addressBinder?.invoke(s)
                log("RTSP: connecting to [${target.address?.hostAddress}]:${target.port}")
                s.connect(target,5000)
                socket = s
                params.host = target.address.hostAddress.orEmpty()
                controlInput = s.getInputStream(); controlOutput = s.getOutputStream()
                log("RTSP: connected (local=${s.localAddress.hostAddress}:${s.localPort})")
                return
            } catch (e: java.io.IOException) {
                runCatching { s.close() }
                lastError = e
                // "Address not available" (EADDRNOTAVAIL): our IPv6 link-local is still tentative right after joining the car's Wi-Fi.
                if (e.message.orEmpty().let { it.contains("Network is unreachable", true) || it.contains("No route to host", true) || it.contains("ENETUNREACH") || it.contains("EHOSTUNREACH") ||
                        it.contains("Address not available", true) || it.contains("EADDRNOTAVAIL") }) sawUnreachable = true
                log("RTSP: receiver address failed: ${e.message}; trying remaining verified addresses")
            } catch (e: Exception) { s.close(); throw e }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        controlInput = null
        controlOutput = null
    }

    private fun nextCseq(): Int = ++cseq

    private fun baseHeaders(extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
        val h = LinkedHashMap<String, String>()
        h["CSeq"] = nextCseq().toString()
        h["DACP-ID"] = params.dacpId
        h["Active-Remote"] = params.activeRemote
        h["User-Agent"] = "AirPlay/${params.sourceVersion}"
        h.putAll(extra)
        return h
    }

    fun authSetup(body: ByteArray): Response {
        // OkcarOS builds /auth-setup with Netty in this exact header order.
        val headers = LinkedHashMap<String, String>()
        headers["CSeq"] = nextCseq().toString()
        headers["Content-Type"] = "application/octet-stream"
        headers["User-Agent"] = "AirPlay/${params.sourceVersion}"
        headers["Content-Length"] = body.size.toString()
        return send("POST", "/auth-setup", headers, body, contentType = null)
    }

    /** POST /pair-setup with a raw TLV8 body (application/octet-stream). */
    fun pairSetup(tlv: ByteArray, extraHeaders: Map<String, String> = emptyMap()): Response {
        val headers = baseHeaders()
        headers["Content-Type"] = "application/octet-stream"
        headers["Content-Length"] = tlv.size.toString()
        headers.putAll(extraHeaders)
        return send("POST", "/pair-setup", headers, tlv, contentType = null)
    }

    fun pairVerify(
        tlv: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
        contentType: String = "application/pairing+tlv8",
    ): Response {
        val headers = baseHeaders()
        headers["Content-Type"] = contentType
        headers["Content-Length"] = tlv.size.toString()
        headers.putAll(extraHeaders)
        return send("POST", "/pair-verify", headers, tlv, contentType = null)
    }

    fun enableEncryption(readKey: ByteArray, writeKey: ByteArray) {
        val s = socket ?: error("RTSP not connected")
        controlInput = CarPlayRecordInput(s.getInputStream(), readKey)
        controlOutput = CarPlayRecordOutput(s.getOutputStream(), writeKey)
    }

    fun getInfo(): Response = send(
        "GET",
        "/info",
        baseHeaders(mapOf("X-Apple-ProtocolVersion" to "1")),
        body = ByteArray(0),
        contentType = null,
    )

    fun setup(plist: ByteArray): Response = sendBody(
        "SETUP",
        "rtsp://${stripScope(params.host)}/${params.rtspSessionId}",
        plist,
        "application/x-apple-binary-plist",
    )

    fun command(plist: ByteArray): Response = sendBody(
        "POST",
        "/command",
        plist,
        "application/x-apple-binary-plist",
    )

    fun record(): Response = send(
        "RECORD",
        "rtsp://${stripScope(params.host)}/${params.rtspSessionId}",
        baseHeaders(),
        ByteArray(0),
        null,
    )

    fun feedback(): Response = send(
        "POST",
        "/feedback",
        baseHeaders(),
        ByteArray(0),
        null,
    )

    fun teardown(plist: ByteArray = ByteArray(0)): Response =
        if (plist.isEmpty()) send(
            "TEARDOWN",
            "rtsp://${stripScope(params.host)}/${params.rtspSessionId}",
            baseHeaders(),
            ByteArray(0),
            null,
        ) else sendBody(
            "TEARDOWN",
            "rtsp://${stripScope(params.host)}/${params.rtspSessionId}",
            plist,
            "application/x-apple-binary-plist",
        )

    private fun sendBody(method: String, path: String, body: ByteArray, contentType: String): Response {
        val headers = baseHeaders()
        headers["Content-Type"] = contentType
        headers["Content-Length"] = body.size.toString()
        return send(method, path, headers, body, contentType = null)
    }

    @Synchronized
    private fun send(
        method: String,
        path: String,
        headers: LinkedHashMap<String, String>,
        body: ByteArray,
        contentType: String?,
    ): Response {
        val s = socket ?: error("RTSP not connected")
        val out = controlOutput ?: error("RTSP output missing")
        val sb = StringBuilder()
        sb.append("$method $path RTSP/1.0\r\n")
        if (body.isNotEmpty()) {
            if (contentType != null && !headers.containsKey("Content-Type")) {
                headers["Content-Type"] = contentType
            }
            headers["Content-Length"] = body.size.toString()
        }
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.US_ASCII)
        out.write(head)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
        log("RTSP > $method $path (CSeq=${headers["CSeq"]}, body=${body.size}B headers=${headers.keys.joinToString(",")})")
        return readResponse(controlInput ?: error("RTSP input missing"))
    }

    private fun readResponse(input: InputStream): Response {
        // Read headers (line-based)
        val headerBytes = ByteArrayOutputStream()
        var prev = -1
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) error("RTSP: EOF reading headers")
            check(headerBytes.size() < 65536) { "RTSP header too large" }
            headerBytes.write(b)
            if (b == 0x0A) {
                if (prev == 0x0D) matched++ else matched = 0
                if (matched == 2) break
            } else if (b != 0x0D) {
                matched = 0
            }
            prev = b
        }
        val headerStr = headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = headerStr.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) error("RTSP: empty response")
        val status = lines[0]
        val statusCode = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val hdrMap = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) hdrMap[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }
        val contentLength = hdrMap["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..(4 * 1024 * 1024)) { "RTSP content too large" }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(buf, off, contentLength - off)
                check(n > 0) { "RTSP truncated body" }
                off += n
            }
            buf.copyOf(off)
        } else ByteArray(0)
        log("RTSP < $status (body=${body.size}B)")
        if (statusCode !in 200..299) {
            val headerSummary = hdrMap.entries.joinToString(",") { (k, v) -> "$k=$v" }
            log("RTSP < failed response headers: ${headerSummary.ifEmpty { "none" }}")
        }
        return Response(status, statusCode, hdrMap, body)
    }

    private fun stripScope(host: String): String {
        val idx = host.indexOf('%')
        return if (idx < 0) host else host.substring(0, idx)
    }
}
