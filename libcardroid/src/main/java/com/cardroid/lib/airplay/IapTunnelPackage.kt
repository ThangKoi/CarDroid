package com.cardroid.lib.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * APTransportPackage framing for the CarPlay iAP2 tunnel (stream type 130), car-touch-iap2-tunnel Stage 1.
 * 32-byte big-endian header: size u32@0 (header + body), messageType u32@16 = 'comm' (0x636F6D6D);
 * every other byte is zero (no source gives values; receivers read only these two fields).
 * Mirrors TestReceiver iapTunnel.ts:101-113 so tests can act as the receiver.
 */
object IapTunnelPackage {
    const val HEADER_SIZE = 32
    const val MAX_PACKAGE = 4 * 1024 * 1024
    const val TYPE_COMM = 0x636F6D6D
    private const val TYPE_OFFSET = 16

    fun wrap(iap2: ByteArray): ByteArray {
        require(iap2.isNotEmpty()) { "Empty iAP2 body" }
        require(HEADER_SIZE.toLong() + iap2.size <= MAX_PACKAGE) { "iAP2 body too large: ${iap2.size}" }
        val out = ByteBuffer.allocate(HEADER_SIZE + iap2.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0, HEADER_SIZE + iap2.size)
        out.putInt(TYPE_OFFSET, TYPE_COMM)
        out.position(HEADER_SIZE); out.put(iap2)
        return out.array()
    }

    /** Returns the iAP2 body of one complete package, or null if size/type are implausible or the type is not 'comm'. */
    fun unwrap(plain: ByteArray): ByteArray? {
        if (plain.size < HEADER_SIZE) return null
        val b = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
        val size = b.getInt(0).toLong() and 0xFFFFFFFFL
        if (size < HEADER_SIZE || size > MAX_PACKAGE || size > plain.size) return null
        if (b.getInt(TYPE_OFFSET) != TYPE_COMM) return null
        return plain.copyOfRange(HEADER_SIZE, size.toInt())
    }

    /** Size field of the package starting at [offset] in a plaintext stream, or null if fewer than 4 bytes or implausible. */
    fun sizeAt(plain: ByteArray, offset: Int = 0): Int? {
        if (offset < 0 || plain.size - offset < 4) return null
        val size = ByteBuffer.wrap(plain, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        return if (size in HEADER_SIZE.toLong()..MAX_PACKAGE.toLong()) size.toInt() else null
    }
}
