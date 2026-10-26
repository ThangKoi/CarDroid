package com.cardroid.app.net

/** Pure IPv4 prefix matching (no Android deps). IPv6 / malformed input never matches. */
object SubnetMatch {
    /** Parses a dotted-quad IPv4 literal into an unsigned 32-bit value, or null. */
    fun parseIpv4(s: String?): Long? {
        if (s == null) return null
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    /** True when [candidate] lies in any (IPv4 base, prefixLen) of [prefixes]. */
    fun inAnyPrefix(candidate: String?, prefixes: List<Pair<String, Int>>): Boolean {
        val c = parseIpv4(candidate) ?: return false
        for ((base, len) in prefixes) {
            if (len !in 0..32) continue
            val b = parseIpv4(base) ?: continue
            val mask = if (len == 0) 0L else (0xFFFFFFFFL shl (32 - len)) and 0xFFFFFFFFL
            if ((c and mask) == (b and mask)) return true
        }
        return false
    }
}
