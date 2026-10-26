package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayTimingServer
import com.cardroid.lib.airplay.AudioSyncPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AirPlayTimingServerTest {
    /** Request shaped like Apple's _TimingSendRequest: v2, pt 0xD2, length 7, transmit time in bytes 24..32. */
    private fun request(transmit: ByteArray): ByteArray =
        ByteArray(32).also {
            it[0] = 0x80.toByte(); it[1] = 0xD2.toByte(); it[3] = 7
            transmit.copyInto(it, 24)
        }

    @Test
    fun replyEchoesOriginateAndUsesSyncClock() {
        val t1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val reply = assertNotNull(AirPlayTimingServer.buildResponse(request(t1), 5_000_000_000L, 5_000_000_500L))
        assertEquals(32, reply.size)
        assertEquals(0x80, reply[0].toInt() and 0xff)
        assertEquals(0xD3, reply[1].toInt() and 0xff)
        assertEquals(7, reply[3].toInt())
        assertContentEquals(t1, reply.copyOfRange(8, 16))
        // Same time base as the audio sync anchors.
        assertContentEquals(AudioSyncPacket.ntpFromNanos(5_000_000_000L), reply.copyOfRange(16, 24))
        assertContentEquals(AudioSyncPacket.ntpFromNanos(5_000_000_500L), reply.copyOfRange(24, 32))
    }

    @Test
    fun ignoresNonTimesyncPackets() {
        assertNull(AirPlayTimingServer.buildResponse(ByteArray(8), 0, 0))
        val wrongType = request(ByteArray(8)).also { it[1] = 0xD4.toByte() }
        assertNull(AirPlayTimingServer.buildResponse(wrongType, 0, 0))
    }

    @Test
    fun answersOverUdp() {
        val server = AirPlayTimingServer()
        val port = server.start()
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 2000
                val req = request(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
                client.send(DatagramPacket(req, req.size, InetAddress.getLoopbackAddress(), port))
                val buf = ByteArray(64)
                val packet = DatagramPacket(buf, buf.size)
                client.receive(packet)
                assertEquals(32, packet.length)
                assertEquals(0xD3, buf[1].toInt() and 0xff)
                assertEquals(1L, server.requestCount)
            }
        } finally {
            server.stop()
        }
    }
}
