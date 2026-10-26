package com.cardroid.lib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bare-minimum AirPlay client for CarPlay Phase 2A.
 *
 * Goal: prove we can reach the car's AirPlay endpoint over the joined WiFi
 * and get an HTTP/RTSP response. This skeleton does not yet implement
 * MFi-SAP, SETUP, RECORD, or video streaming -- those are Phase 2B/C/D.
 *
 * Expected behavior:
 *   1. TCP connect to [endpoint.ipv6%scope]:endpoint.port (usually 7000).
 *   2. Send RTSP OPTIONS request.
 *   3. Log the response. A real CarPlay server replies with a 200 OK and
 *      a Public: header listing supported methods (OPTIONS, ANNOUNCE,
 *      SETUP, RECORD, FLUSH, TEARDOWN, ...).
 */
class AirPlayClient(
    private val endpoint: CarPlaySession.AirPlayEndpoint,
    private val socketFactory: () -> Socket = { Socket() },
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var socket: Socket? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        val addr = endpoint.toScopedAddressOrNull()
        if (addr == null) {
            log("AirPlay: cannot resolve IPv6 ${endpoint.ipv6}")
            return@withContext
        }

        // Try the car-declared port first, then a list of well-known AirPlay /
        // CarPlay receiver ports. ECONNREFUSED on one doesn't mean others
        // won't answer — different builds/firmwares use different ports.
        val candidatePorts = buildList {
            add(endpoint.port)
            // Standard AirPlay receiver ports observed across CarPlay impls.
            listOf(7000, 7100, 49152, 5000, 5001, 8009).forEach {
                if (it != endpoint.port) add(it)
            }
        }
        val openPort = candidatePorts.firstNotNullOfOrNull { port ->
            tryConnect(addr, port)?.also { sock ->
                log("AirPlay: TCP connected on port $port")
                socket = sock
            }?.let { port }
        }
        if (openPort == null) {
            log("AirPlay: no candidate port accepted TCP. Tried ${candidatePorts.joinToString()}")
            return@withContext
        }

        try {
            val s = socket!!
            val req = buildOptionsRequest(endpoint)
            s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            log("AirPlay: sent RTSP OPTIONS (${req.length}B) on port $openPort")
            val resp = readHttpResponse(s.getInputStream())
            log("AirPlay: response:\n$resp")
        } catch (e: Throwable) {
            log("AirPlay: post-connect exchange failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun tryConnect(addr: java.net.InetAddress, port: Int): Socket? {
        return try {
            val s = socketFactory().apply {
                tcpNoDelay = true
                soTimeout = 3_000
            }
            s.connect(InetSocketAddress(addr, port), 2_000)
            log("AirPlay: probe ${addr.hostAddress}:$port -> OK")
            s
        } catch (e: Throwable) {
            log("AirPlay: probe ${addr.hostAddress}:$port -> ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun buildOptionsRequest(ep: CarPlaySession.AirPlayEndpoint): String {
        val host = "[${ep.ipv6}]:${ep.port}"
        return buildString {
            append("OPTIONS * RTSP/1.0\r\n")
            append("CSeq: 1\r\n")
            append("Host: $host\r\n")
            append("User-Agent: AirPlay/420.5\r\n")
            append("X-Apple-Session-ID: ${ep.sessionUuid}\r\n")
            append("\r\n")
        }
    }

    private fun readHttpResponse(input: java.io.InputStream): String {
        val buf = ByteArray(8192)
        val out = StringBuilder()
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { -1 }
            if (n <= 0) break
            out.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (out.contains("\r\n\r\n")) break
        }
        return out.toString().ifEmpty { "(empty response)" }
    }

    private fun CarPlaySession.AirPlayEndpoint.toScopedAddressOrNull(): java.net.InetAddress? {
        // Link-local IPv6 needs an interface scope. Try without first; on
        // Android with bindProcessToNetwork the joined network supplies it.
        return try {
            java.net.InetAddress.getByName(ipv6)
        } catch (e: Throwable) {
            // Try with %wlan0 scope.
            try {
                java.net.InetAddress.getByName("$ipv6%wlan0")
            } catch (e2: Throwable) {
                null
            }
        }
    }
}
