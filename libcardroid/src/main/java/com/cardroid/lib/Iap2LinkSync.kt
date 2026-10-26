package com.cardroid.lib

/**
 * Link Synchronization Payload.
 *
 * Structure verified against public iAP2 research (link_layer.py):
 *
 *   off  bytes  field
 *   0    1      version              = 0x01 (constant)
 *   1    1      maxOutgoing          = max outstanding packets the peer can send us
 *   2    2      maxRxPacketLen       = max packet length we can receive, big-endian u16
 *   4    2      retransmitTimeoutMs  = ms before retransmit, big-endian u16
 *   6    2      cumulativeAckTimeoutMs = ms before forced ACK, big-endian u16
 *   8    1      maxRetransmissions   = max retransmits before link-down
 *   9    1      maxCumulativeAcks    = max cumulative acks before forced send
 *   10   N*3    sessions             = N session descriptors, each: (id, type, version)
 *
 * IMPORTANT: There is NO session-count byte. The number of sessions is implicit
 * from (length - 10) / 3.
 *
 * Default session IDs for the wireless client role:
 *   id=1 (0x01), type=0, version=2  -> control session
 *   id=2 (0x02), type=2, version=2  -> external accessory session
 */
data class LinkSyncParameters(
    /** Link Sync SYN control byte: SYN bit only (0x80) on first send. */
    val controlByte: Byte = 0x80.toByte(),
    /**
     * If true, send our LinkSync immediately on connect. Current reference-aligned
     * testing still completes the accessory's SYN by echoing our LSP as SYN|ACK
     * and waiting for the peer ACK before control messages.
     */
    val proactiveSend: Boolean = true,
    /** Protocol version (constant 0x01). */
    val version: Byte = 0x01,
    /** Max outstanding packets the peer can buffer. */
    val maxOutgoing: Byte = 0x7F,
    /** Max receive packet length, big-endian u16. */
    val maxRxPacketLen: Int = 65535,
    /** Retransmission timeout in ms. */
    val retransmitTimeoutMs: Int = 2000,
    /** Cumulative-ack timeout in ms. */
    val cumulativeAckTimeoutMs: Int = 1000,
    /** Max retransmissions before link-down. */
    val maxRetransmits: Byte = 30,
    /** Max cumulative acks before forced send. */
    val maxCumulativeAcks: Byte = 1,
    /**
     * Triplets describing each session: (id, type, version).
     * Defaults match reference's wireless client-mode extractor:
     *   id=1, type=0, version=2  -> control (iAP2 messages)
     *   id=2, type=2, version=2  -> external accessory (optional)
     * The transport adopts the peer's session IDs after SYN exchange.
     */
    val sessions: List<SessionDescriptor> = listOf(
        SessionDescriptor(id = 0x01, type = 0x00, version = 0x02),  // control
        SessionDescriptor(id = 0x02, type = 0x02, version = 0x02),  // external accessory
    ),
) {
    data class SessionDescriptor(val id: Byte, val type: Byte, val version: Byte)
}

object Iap2LinkSync {

    /**
     * iAP2 USB/Lightning DETECT marker. Sent as raw bytes (no link framing)
     * to signal the peer that we speak iAP2. Bluetooth transport usually skips
     * this and jumps straight to SYN, but some head units may send it first.
     */
    val DETECT_MARKER: ByteArray = byteArrayOf(
        0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10,
    )

    fun buildSyncFrame(params: LinkSyncParameters, seq: Byte = 0, ack: Byte = 0): Iap2Frame {
        return Iap2Frame(
            control = params.controlByte,
            seq = seq,
            ack = ack,
            session = Iap2Constants.LINK_SESSION_ID,
            payload = buildPayload(params),
        )
    }

    fun buildPayload(p: LinkSyncParameters): ByteArray {
        // 10 fixed bytes + 3 per session. NO session-count byte.
        val sessionBytes = p.sessions.size * 3
        val out = ByteArray(10 + sessionBytes)
        out[0] = p.version
        out[1] = p.maxOutgoing
        out[2] = ((p.maxRxPacketLen ushr 8) and 0xFF).toByte()
        out[3] = (p.maxRxPacketLen and 0xFF).toByte()
        out[4] = ((p.retransmitTimeoutMs ushr 8) and 0xFF).toByte()
        out[5] = (p.retransmitTimeoutMs and 0xFF).toByte()
        out[6] = ((p.cumulativeAckTimeoutMs ushr 8) and 0xFF).toByte()
        out[7] = (p.cumulativeAckTimeoutMs and 0xFF).toByte()
        out[8] = p.maxRetransmits
        out[9] = p.maxCumulativeAcks
        var i = 10
        for (s in p.sessions) {
            out[i++] = s.id
            out[i++] = s.type
            out[i++] = s.version
        }
        return out
    }

    /** Parse a SYN payload from the wire. */
    fun parsePayload(bytes: ByteArray): LinkSyncParameters? {
        if (bytes.size < 10) return null
        if (bytes[0] != 0x01.toByte()) return null
        val sessionsBytes = bytes.size - 10
        if (sessionsBytes < 0 || sessionsBytes % 3 != 0) return null
        val sessions = mutableListOf<LinkSyncParameters.SessionDescriptor>()
        var i = 10
        while (i < bytes.size) {
            sessions += LinkSyncParameters.SessionDescriptor(bytes[i], bytes[i + 1], bytes[i + 2])
            i += 3
        }
        return LinkSyncParameters(
            controlByte = 0x80.toByte(),
            version = bytes[0],
            maxOutgoing = bytes[1],
            maxRxPacketLen = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF),
            retransmitTimeoutMs = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF),
            cumulativeAckTimeoutMs = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF),
            maxRetransmits = bytes[8],
            maxCumulativeAcks = bytes[9],
            sessions = sessions,
        )
    }
}
