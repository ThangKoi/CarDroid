package com.cardroid.runner

import com.cardroid.lib.airplay.AudioSyncPacket
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class AudioSyncPacketTest {
    @Test
    fun buildUsesRtpSyncHeaderAndBigEndianFields() {
        val ntp = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val packet = AudioSyncPacket.build(0x1234, 352, 1376, ntp, true)
        assertEquals(AudioSyncPacket.SIZE, packet.size)
        assertEquals(0x90, packet[0].toInt() and 0xff)
        assertEquals(0xd4, packet[1].toInt() and 0xff)
        assertEquals(0x1234, ByteBuffer.wrap(packet, 2, 2).short.toInt() and 0xffff)
        assertEquals(352, ByteBuffer.wrap(packet, 4, 4).int)
        assertContentEquals(ntp, packet.copyOfRange(8, 16))
        assertEquals(1376, ByteBuffer.wrap(packet, 16, 4).int)
    }
}
