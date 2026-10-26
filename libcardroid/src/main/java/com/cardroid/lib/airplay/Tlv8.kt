package com.cardroid.lib.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal HomeKit TLV8 codec (Apple Pairing protocol).
 *
 * Wire format: a flat sequence of `<type:1><len:1><value:len>` records.
 * Values longer than 255 bytes are split into multiple consecutive
 * fragments of the same type (each <=255); a reader concatenates adjacent
 * same-type fragments. We implement that fragmentation on encode and the
 * concatenation on decode.
 */
object Tlv8 {
    // Standard HomeKit pairing TLV types.
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val RETRY_DELAY = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FRAGMENT_DATA = 0x0C
    const val FLAGS = 0x13
    const val SEPARATOR = 0xFF

    fun typeName(t: Int): String = when (t) {
        METHOD -> "Method"; IDENTIFIER -> "Identifier"; SALT -> "Salt"
        PUBLIC_KEY -> "PublicKey"; PROOF -> "Proof"; ENCRYPTED_DATA -> "EncryptedData"
        STATE -> "State"; ERROR -> "Error"; RETRY_DELAY -> "RetryDelay"
        CERTIFICATE -> "Certificate"; SIGNATURE -> "Signature"; PERMISSIONS -> "Permissions"
        FLAGS -> "Flags"; SEPARATOR -> "Separator"; else -> "0x%02X".format(t)
    }

    /** Build a TLV8 blob from ordered (type, value) records. */
    fun encode(vararg records: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in records) {
            if (value.isEmpty()) {
                out.write(type); out.write(0)
                continue
            }
            var off = 0
            while (off < value.size) {
                val chunk = minOf(255, value.size - off)
                out.write(type)
                out.write(chunk)
                out.write(value, off, chunk)
                off += chunk
            }
        }
        return out.toByteArray()
    }

    fun u8(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte())

    /** Decode into a map; adjacent same-type fragments are concatenated. */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArrayOutputStream>()
        var i = 0
        var lastType = -1
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > data.size) break
            // A same-type record immediately following another same-type
            // record is a continuation fragment (concatenate). A different
            // type ends the previous fragment group.
            val sink = result.getOrPut(type) { ByteArrayOutputStream() }
            if (type != lastType && sink.size() > 0) {
                // type seen before but not contiguous: start fresh group
                sink.reset()
            }
            sink.write(data, i, len)
            i += len
            lastType = type
        }
        return result.mapValues { it.value.toByteArray() }
    }

    fun describe(data: ByteArray): String {
        val m = decode(data)
        return m.entries.joinToString(", ") { (t, v) ->
            "${typeName(t)}=${v.size}B" + if (v.size <= 4) "(${v.joinToString("") { "%02x".format(it.toInt() and 0xff) }})" else ""
        }.ifEmpty { "empty" }
    }
}
