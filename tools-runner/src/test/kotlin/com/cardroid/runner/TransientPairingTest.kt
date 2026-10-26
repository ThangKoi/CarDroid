package com.cardroid.runner

import com.cardroid.lib.airplay.*
import kotlin.test.*

class TransientPairingTest {
    @Test fun m1CarriesTransientFlag() {
        val expected = Tlv8.encode(Tlv8.STATE to Tlv8.u8(1), Tlv8.METHOD to Tlv8.u8(0), Tlv8.FLAGS to Tlv8.u8(0x10))
        assertEquals("060101000100130110", expected.joinToString("") { "%02x".format(it) })
        assertContentEquals(expected, TransientPairing.m1())
    }
}
