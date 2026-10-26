package com.cardroid.lib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Test-only iAP2 transport that runs the iPhone (DEVICE) side of the link
 * against a TCP server (the Python test receiver in cardroid/tools/).
 *
 * Flow (verified against carplay-wifi-extractor's link_layer_client.py):
 *   1. TCP connect to host:port.
 *   2. Wait for server's IAP2_MARKER (FF 55 02 00 EE 10).
 *   3. Echo the marker back.
 *   4. Wait for server's SYN with LSP.
 *   5. Send SYN|ACK with our LSP.
 *   6. Optionally wait for a final bare ACK from server (some impls send one,
 *      others go straight to data).
 *   7. NORMAL state: forward incoming control-session payloads to CarPlaySession
 *      and serialize outgoing control messages.
 */
class TcpClientTransport(
    private val host: String,
    private val port: Int,
    private val linkSyncParams: LinkSyncParameters = LinkSyncParameters(),
    private val onLog: (String) -> Unit = {},
) : IapTransport {
    private val _incomingControl = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 16)
    override val incomingControl: SharedFlow<Pair<Int, ByteArray>> = _incomingControl

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var loopJob: Job? = null

    /** reference/reference client starts at 1. */
    private var outSeq: Byte = 0x01
    private var lastAck: Byte = 0
    private var negotiatedControlSession: Byte = Iap2Constants.CONTROL_SESSION_ID
    private var linkNormal = false

    @Volatile override var onLinkUp: () -> Unit = {}
    @Volatile override var onSessionEnded: (String) -> Unit = {}

    override fun start(scope: CoroutineScope) {
        loopJob = scope.launch(Dispatchers.IO) { runSession() }
    }

    override fun stop() {
        runCatching { socket?.close() }
        loopJob?.cancel()
    }

    private suspend fun runSession() = withContext(Dispatchers.IO) {
        try {
            onLog("TCP connecting to $host:$port…")
            val s = Socket(host, port).also { socket = it }
            input = s.getInputStream()
            output = s.getOutputStream()
            onLog("TCP connected")

            val inp = input!!
            val out = output!!

            // Step 1: wait for server's marker.
            onLog("waiting for IAP2_MARKER from server…")
            val marker = readExact(inp, Iap2LinkSync.DETECT_MARKER.size)
                ?: run { onLog("EOF before marker"); return@withContext }
            if (!marker.contentEquals(Iap2LinkSync.DETECT_MARKER)) {
                onLog("unexpected marker: ${marker.joinToString("") { "%02X".format(it) }}")
                return@withContext
            }
            onLog("got IAP2_MARKER: ${marker.joinToString("") { "%02X".format(it) }}")

            // Step 2: echo it back.
            out.write(Iap2LinkSync.DETECT_MARKER)
            out.flush()
            onLog("echoed IAP2_MARKER")

            // Step 3: wait for server's SYN.
            val syn = Iap2Frame.readFrom(inp) ?: run {
                onLog("EOF waiting for server SYN")
                return@withContext
            }
            val synCtrl = syn.control.toInt() and 0xFF
            if (synCtrl and 0x80 == 0) {
                onLog("expected SYN, got ctrl=0x%02X".format(synCtrl))
                return@withContext
            }
            lastAck = syn.seq
            val peerLsp = Iap2LinkSync.parsePayload(syn.payload)
            onLog("got server SYN seq=0x%02X sessions=%s".format(
                syn.seq, peerLsp?.sessions?.toString() ?: "?"))
            peerLsp?.sessions?.firstOrNull { it.type == 0x00.toByte() }?.let {
                negotiatedControlSession = it.id
                onLog(" -> using control session id=0x%02X".format(it.id))
            }

            // Step 4: reply with SYN|ACK + ECHO peer's LSP (reference _handle_syn
            // adopts peer's LSP via self.lsp = lsp, then _send_negotiate uses
            // self.lsp.pack()). Sending our own LSP back instead may break
            // strict peers.
            val lspToEcho = peerLsp ?: linkSyncParams
            val replyFrame = Iap2LinkSync.buildSyncFrame(
                lspToEcho.copy(controlByte = 0xC0.toByte()),
                seq = outSeq,
                ack = syn.seq,
            )
            out.writeFrame(replyFrame)
            val wire = replyFrame.encode()
            onLog("sent SYN|ACK seq=0x%02X ack=0x%02X (wire ${wire.size}B): %s".format(
                outSeq, syn.seq, wire.joinToString("") { "%02X".format(it) }))

            linkNormal = true
            onLog("*** LINK UP ***")
            onLinkUp()

            // Step 5: NORMAL — process incoming control frames.
            while (true) {
                val frame = try {
                    Iap2Frame.readFrom(inp) ?: break
                } catch (e: java.io.IOException) {
                    onLog("TCP closed by peer: ${e.message}")
                    break
                }
                lastAck = frame.seq
                val ctrl = frame.control.toInt() and 0xFF
                val flags = buildString {
                    if (ctrl and 0x80 != 0) append("SYN ")
                    if (ctrl and 0x40 != 0) append("ACK ")
                    if (ctrl and 0x20 != 0) append("EAK ")
                    if (ctrl and 0x10 != 0) append("RST ")
                }.trim()
                onLog("rx ctrl=0x%02X(%s) seq=0x%02X ack=0x%02X sid=0x%02X len=%d".format(
                    frame.control, flags, frame.seq, frame.ack, frame.session, frame.payload.size))
                if (frame.payload.isEmpty()) continue
                if (frame.payload.isNotEmpty()) {
                    onLog("  payload hex: " + frame.payload.take(64).joinToString("") { "%02X".format(it) })
                }
                // Send bare ACK for any data frame to keep the link happy.
                sendBareAck(frame.seq)
                handleControlPayload(frame.payload)
            }
        } catch (t: Throwable) {
            onLog("runSession exception: ${t.javaClass.simpleName}: ${t.message}")
        }
        onLog("TCP session ended")
        runCatching { onSessionEnded("TCP closed") }
    }

    private fun sendBareAck(peerSeq: Byte) {
        val out = output ?: return
        out.writeFrame(Iap2Frame(
            control = 0x40.toByte(),
            seq = outSeq,
            ack = peerSeq,
            session = Iap2Constants.LINK_SESSION_ID,
            payload = ByteArray(0),
        ))
    }

    private suspend fun handleControlPayload(payload: ByteArray) {
        val parsed = Iap2ControlSession.parse(payload) ?: return
        _incomingControl.emit(parsed)
    }

    override fun sendControl(controlMessageBytes: ByteArray) {
        val out = output ?: return
        outSeq = ((outSeq.toInt() + 1) and 0xFF).toByte()
        val frame = Iap2Frame(
            control = 0x40.toByte(),
            seq = outSeq,
            ack = lastAck,
            session = negotiatedControlSession,
            payload = controlMessageBytes,
        )
        out.writeFrame(frame)
        val messageId = Iap2ControlSession.parse(controlMessageBytes)?.first
        val idText = messageId?.let { " msg=0x%04X".format(it) }.orEmpty()
        val wire = frame.encode()
        onLog("tx ctrl=0x40 seq=0x%02X ack=0x%02X sid=0x%02X$idText len=%d wire=%s".format(
            outSeq, lastAck, negotiatedControlSession, controlMessageBytes.size,
            wire.joinToString("") { "%02X".format(it) }))
    }

    override fun sendMfiCertResponse(hash: ByteArray) {
        sendControl(Iap2ControlSession.build(
            Iap2ControlSession.Msg.MFI_AUTH_CHALLENGE,
            Iap2ControlSession.param(0x0000, hash),
        ))
    }

    override fun sendAuthStatusOk() =
        sendControl(Iap2ControlSession.build(Iap2ControlSession.Msg.MFI_AUTH_SUCCEEDED))

    override fun sendAuthenticationResponse(signature: ByteArray) {
        sendControl(Iap2ControlSession.build(
            Iap2ControlSession.Msg.MFI_AUTH_CHALLENGE,
            Iap2ControlSession.param(0x0000, signature),
        ))
    }

    override fun diagnostic(msg: String) = onLog(msg)

    private fun readExact(stream: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = stream.read(out, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return out
    }
}
