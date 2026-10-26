package com.cardroid.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetMatchTest {
    private val lan24 = listOf("192.168.1.73" to 24)

    @Test fun slash24InAndOut() {
        assertTrue(SubnetMatch.inAnyPrefix("192.168.1.228", lan24))
        assertFalse(SubnetMatch.inAnyPrefix("192.168.2.228", lan24))
    }
    @Test fun slash16() {
        val p = listOf("10.1.5.5" to 16)
        assertTrue(SubnetMatch.inAnyPrefix("10.1.200.9", p))
        assertFalse(SubnetMatch.inAnyPrefix("10.2.0.1", p))
    }
    @Test fun slash32Exact() {
        val p = listOf("1.2.3.4" to 32)
        assertTrue(SubnetMatch.inAnyPrefix("1.2.3.4", p))
        assertFalse(SubnetMatch.inAnyPrefix("1.2.3.5", p))
    }
    @Test fun slash0MatchesAnyIpv4() = assertTrue(SubnetMatch.inAnyPrefix("8.8.8.8", listOf("1.2.3.4" to 0)))
    @Test fun multiplePrefixes() = assertTrue(SubnetMatch.inAnyPrefix("172.16.0.9", listOf("10.0.0.1" to 8, "172.16.0.1" to 24)))
    @Test fun invalidPrefixLenIgnored() {
        assertFalse(SubnetMatch.inAnyPrefix("192.168.1.2", listOf("192.168.1.1" to 33, "192.168.1.1" to -1)))
    }
    @Test fun ipv6Ignored() {
        assertFalse(SubnetMatch.inAnyPrefix("fe80::1", lan24))
        assertFalse(SubnetMatch.inAnyPrefix("fe80::1%wlan0", lan24))
        assertFalse(SubnetMatch.inAnyPrefix("192.168.1.228", listOf("fe80::1" to 64)))
    }
    @Test fun malformedIgnored() {
        for (s in listOf("", "999.1.1.1", "1.2.3", "1.2.3.4.5", "host.local", "1.2.3.-4", "1..3.4", "a.b.c.d", " 1.2.3.4")) {
            assertFalse(s, SubnetMatch.inAnyPrefix(s, listOf("0.0.0.0" to 0)))
        }
        assertFalse(SubnetMatch.inAnyPrefix(null, lan24))
        assertNull(SubnetMatch.parseIpv4("256.0.0.1"))
        assertEquals(0xC0A80101L, SubnetMatch.parseIpv4("192.168.1.1"))
    }
}
