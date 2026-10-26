package com.cardroid.lib.airplay

/** Bounded binary-plist reader for Receiver /info and SETUP responses. */
object ReceiverPlist {
    fun decode(data: ByteArray): Any? {
        require(data.size in 40..(4 * 1024 * 1024) && String(data, 0, 8, Charsets.US_ASCII) == "bplist00")
        fun uint(offset: Int, size: Int): Long {
            require(size in 1..8 && offset >= 0 && offset + size <= data.size)
            var n = 0L; for (i in offset until offset + size) n = (n shl 8) or (data[i].toLong() and 255)
            return n
        }
        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 255
        val refSize = data[trailer + 7].toInt() and 255
        val count = uint(trailer + 8, 8).also { require(it in 1..100000) }.toInt()
        val root = uint(trailer + 16, 8).also { require(it in 0 until count.toLong()) }.toInt()
        val table = uint(trailer + 24, 8).also { require(it in 8 until trailer.toLong()) }.toInt()
        require(offsetSize in 1..8 && refSize in 1..8 && table.toLong() + count.toLong() * offsetSize <= trailer)
        val offsets = IntArray(count) { uint(table + it * offsetSize, offsetSize).also { o -> require(o in 8 until table.toLong()) }.toInt() }
        var visits = 0
        fun objectAt(ref: Int, depth: Int): Any? {
            require(ref in offsets.indices && depth < 32 && ++visits < 200000)
            var pos = offsets[ref]
            val marker = data[pos++].toInt() and 255
            val kind = marker ushr 4
            var length = marker and 15
            if (length == 15 && kind in listOf(4,5,6,10,13)) {
                val m = data[pos++].toInt() and 255
                require(m ushr 4 == 1 && (m and 15) <= 3)
                val width = 1 shl (m and 15)
                length = uint(pos, width).also { require(it in 0..1000000) }.toInt(); pos += width
            }
            fun reference(at: Int) = uint(at, refSize).also { require(it in 0 until count.toLong()) }.toInt()
            when (kind) {
                0 -> return when (marker) { 8 -> false; 9 -> true; else -> null }
                1 -> { require(length <= 3); return uint(pos, 1 shl length) }
                2 -> return when (length) { 2 -> java.lang.Float.intBitsToFloat(uint(pos, 4).toInt()).toDouble(); 3 -> java.lang.Double.longBitsToDouble(uint(pos, 8)); else -> error("Unsupported real") }
                3 -> return java.lang.Double.longBitsToDouble(uint(pos, 8))
                4,5,6 -> {
                    val size = length * if (kind == 6) 2 else 1
                    require(pos + size <= table)
                    return if (kind == 4) data.copyOfRange(pos, pos + size) else String(data, pos, size, if (kind == 6) Charsets.UTF_16BE else Charsets.US_ASCII)
                }
                10 -> { require(pos.toLong() + length.toLong() * refSize <= table); return List(length) { objectAt(reference(pos + it * refSize), depth + 1) } }
                13 -> {
                    require(pos.toLong() + length.toLong() * refSize * 2 <= table)
                    return (0 until length).associate { i ->
                        val key = objectAt(reference(pos + i * refSize), depth + 1) as? String ?: error("Invalid plist key")
                        key to objectAt(reference(pos + (length + i) * refSize), depth + 1)
                    }
                }
                else -> error("Unsupported plist type $kind")
            }
        }
        return objectAt(root, 0)
    }
    @Suppress("UNCHECKED_CAST") fun map(data: ByteArray): Map<String, Any?> = decode(data) as? Map<String, Any?> ?: error("Expected plist dictionary")
}
