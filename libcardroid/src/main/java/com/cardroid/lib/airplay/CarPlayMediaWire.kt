package com.cardroid.lib.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encoder of Receiver ScreenStream's 128-byte header and AudioStream RTP records. */
object CarPlayMediaWire {
    fun streamKey(shared: ByteArray, connection: Long) = CarPlayCrypto.hkdf(shared,
        "DataStream-Salt${java.lang.Long.toUnsignedString(connection)}", "DataStream-Output-Encryption-Key")
    fun videoConfig(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size in 4..65535 && pps.size in 1..65535)
        return byteArrayOf(1,sps[1],sps[2],sps[3],0xff.toByte(),0xe1.toByte(),
            (sps.size ushr 8).toByte(),sps.size.toByte()) + sps +
            byteArrayOf(1,(pps.size ushr 8).toByte(),pps.size.toByte()) + pps
    }
    fun nalUnits(data: ByteArray): List<ByteArray> {
        fun start(at: Int): Int = when {
            at + 3 <= data.size && data[at] == 0.toByte() && data[at+1] == 0.toByte() && data[at+2] == 1.toByte() -> 3
            at + 4 <= data.size && data[at] == 0.toByte() && data[at+1] == 0.toByte() && data[at+2] == 0.toByte() && data[at+3] == 1.toByte() -> 4
            else -> 0
        }
        val result = mutableListOf<ByteArray>()
        if (start(0) != 0) {
            var begin = start(0); var pos = begin
            while (pos < data.size) {
                val prefix = start(pos)
                if (prefix > 0) { if (pos > begin) result += data.copyOfRange(begin,pos); pos += prefix; begin = pos }
                else pos++
            }
            if (begin < data.size) result += data.copyOfRange(begin, data.size)
        } else {
            var pos = 0
            while (pos + 4 <= data.size) {
                val len = ByteBuffer.wrap(data,pos,4).int; pos += 4
                require(len > 0 && len <= data.size - pos) { "Invalid NAL length" }
                result += data.copyOfRange(pos,pos+len); pos += len
            }
            require(pos == data.size)
        }
        return result
    }
    fun videoFrame(data: ByteArray): ByteArray {
        val nals = nalUnits(data)
        return ByteBuffer.allocate(nals.sumOf { it.size + 4 }).also { b -> nals.forEach { b.putInt(it.size); b.put(it) } }.array()
    }
    fun screenPacket(key: ByteArray, counter: Long, config: Boolean, body: ByteArray, timestamp: Long): ByteArray {
        val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(body.size + if (config) 0 else 16).putShort((if (config) 1 else 0).toShort()).putShort(0).putLong(timestamp).array()
        return header + if (config) body else CarPlayCrypto.seal(key, CarPlayCrypto.nonce(counter), body, header)
    }
    /**
     * Encrypt one RTP audio record. PCM is captured as little-endian Android
     * samples and is swapped to the protocol's big-endian representation;
     * AAC access units are already a byte stream and must never be swapped.
     * The old six-argument overload remains PCM/type-100 compatible.
     */
    fun audioPacket(
        key: ByteArray,
        counter: Long,
        sequence: Int,
        timestamp: Int,
        ssrc: Int,
        payload: ByteArray,
        payloadType: Int = 100,
        pcmLittleEndian: Boolean = true,
        marker: Boolean = false,
    ): ByteArray {
        require(!pcmLittleEndian || payload.size % 4 == 0)
        val wirePayload = payload.copyOf()
        if (pcmLittleEndian) {
            for (i in wirePayload.indices step 2) {
                val b = wirePayload[i]
                wirePayload[i] = wirePayload[i + 1]
                wirePayload[i + 1] = b
            }
        }
        val rtpPayloadType = (payloadType and 0x7f) or (if (marker) 0x80 else 0)
        val header = ByteBuffer.allocate(12).put(0x80.toByte()).put(rtpPayloadType.toByte()).putShort(sequence.toShort()).putInt(timestamp).putInt(ssrc).array()
        return header + CarPlayCrypto.seal(key, CarPlayCrypto.nonce(counter), wirePayload, header.copyOfRange(4,12)) +
            CarPlayCrypto.nonce(counter).copyOfRange(4,12)
    }
}
