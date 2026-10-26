package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class VideoReturnTest {
    @Test fun returnReplaysConfigBeforeFreshIdrWithoutResettingEncryptionCounter() {
        val writes = LinkedBlockingQueue<ByteArray>()
        val key = ByteArray(32) { it.toByte() }
        val socket = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(b: Int) = error("unexpected single byte write")
                override fun write(b: ByteArray) { writes.add(b.copyOf()) }
            }
        }
        val transport = CarPlayMediaTransport(socket, null, null, key, key, {}, 640, 480, 640, 480)
        fun packet(): ByteArray = assertNotNull(writes.poll(2, TimeUnit.SECONDS))
        fun type(p: ByteArray) = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt()
        val sps = byteArrayOf(0x67, 0x64, 0, 0x28)
        val pps = byteArrayOf(0x68, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 9)
        try {
            transport.sendVideoConfig(sps, pps)
            val initial = packet()
            sps[1] = 0 // transport must retain its own immutable config
            transport.sendVideoFrame(idr, 1)
            assertContentEquals(initial, packet())
            val first = packet()
            assertEquals(0, type(first))
            assertContentEquals(CarPlayMediaWire.videoFrame(idr),
                CarPlayCrypto.open(key, CarPlayCrypto.nonce(0), first.copyOfRange(128, first.size), first.copyOfRange(0,128)))
            var requested = false
            transport.onKeyFrameNeeded = { requested = true }
            transport.requestVideoRefresh()
            assertTrue(requested)
            transport.sendVideoFrame(byteArrayOf(0,0,0,1,0x41,2), 2) // cannot decode without a new IDR
            transport.sendVideoFrame(idr, 3)
            assertContentEquals(initial, packet())
            val resumed = packet()
            assertEquals(0, type(resumed))
            assertContentEquals(CarPlayMediaWire.videoFrame(idr),
                CarPlayCrypto.open(key, CarPlayCrypto.nonce(1), resumed.copyOfRange(128,resumed.size), resumed.copyOfRange(0,128)))
            assertTrue(writes.isEmpty())
        } finally { transport.close() }
    }
    @Test fun videoFailureClosesResourcesAndNotifiesOwnerExactlyOnce() {
        val videoClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val ended = java.util.concurrent.CountDownLatch(1)
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val audio = java.net.DatagramSocket()
        val socket = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(b: Int) { throw java.io.IOException("receiver screen closed") }
                override fun write(b: ByteArray) { throw java.io.IOException("receiver screen closed") }
            }
            override fun close() { videoClosed.set(true) }
        }
        val key = ByteArray(32)
        val transport = CarPlayMediaTransport(socket, audio, null, key, key, {}, 640, 480, 640, 480)
        transport.onPeerEnded = { reason ->
            if (videoClosed.get() && audio.isClosed && reason.contains("video send failed")) {
                notifications.incrementAndGet()
                transport.close() // owner teardown is reentrant
                ended.countDown()
            }
        }
        try {
            transport.sendVideoConfig(byteArrayOf(0x67, 0x64, 0, 0x28), byteArrayOf(0x68, 1))
            assertTrue(ended.await(2, TimeUnit.SECONDS), "owner must be notified after sockets are closed")
            transport.close()
            assertEquals(1, notifications.get())
            assertFailsWith<IllegalStateException> { transport.sendVideoFrame(byteArrayOf(0,0,0,1,0x65), 1) }
        } finally { transport.close(); audio.close() }
    }

    @Test fun intentionalCloseDoesNotReportPeerFailure() {
        val socket = object : Socket() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
        }
        val key = ByteArray(32)
        val transport = CarPlayMediaTransport(socket, null, null, key, key, {}, 640, 480, 640, 480)
        var notified = false
        transport.onPeerEnded = { notified = true }
        transport.close(); transport.close()
        assertFalse(notified)
    }
    @Test fun screenRestartKeepsAudioAndReplaysConfigWithNewKeyAndCounter() {
        val before = LinkedBlockingQueue<ByteArray>()
        val after = LinkedBlockingQueue<ByteArray>()
        val oldClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun socket(writes: LinkedBlockingQueue<ByteArray>, closed: () -> Unit = {}) = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(b: Int) = error("single-byte write")
                override fun write(b: ByteArray) { writes.add(b.copyOf()) }
            }
            override fun close() { closed() }
        }
        val receiver = java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).apply { soTimeout = 2000 }
        val audio = java.net.DatagramSocket()
        val oldKey = ByteArray(32) { 1 }
        val newKey = ByteArray(32) { 2 }
        val transport = CarPlayMediaTransport(socket(before) { oldClosed.set(true) }, audio,
            java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), receiver.localPort),
            oldKey, oldKey, {}, 640, 480, 640, 480)
        var failed = false
        transport.onPeerEnded = { failed = true }
        try {
            transport.sendVideoConfig(byteArrayOf(0x67, 0x64, 0, 0x28), byteArrayOf(0x68, 1))
            assertNotNull(before.poll(2, TimeUnit.SECONDS))
            transport.suspendVideo()
            assertTrue(oldClosed.get())
            transport.sendVideoFrame(byteArrayOf(0,0,0,1,0x65,5), 1) // ignored, display keeps running
            transport.sendAudio(ByteArray(4 * 352))
            receiver.receive(java.net.DatagramPacket(ByteArray(4096), 4096))
            assertFalse(audio.isClosed)
            var refreshed = false
            transport.onKeyFrameNeeded = { refreshed = true }
            transport.resumeVideo(socket(after), newKey)
            assertTrue(refreshed)
            transport.sendVideoFrame(byteArrayOf(0,0,0,1,0x41,7), 2) // drop predictive frame
            val idr = byteArrayOf(0,0,0,1,0x65,9)
            transport.sendVideoFrame(idr, 3)
            val config = assertNotNull(after.poll(2, TimeUnit.SECONDS))
            val resumed = assertNotNull(after.poll(2, TimeUnit.SECONDS))
            assertEquals(1, ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt())
            assertContentEquals(CarPlayMediaWire.videoFrame(idr), CarPlayCrypto.open(newKey,
                CarPlayCrypto.nonce(0), resumed.copyOfRange(128, resumed.size), resumed.copyOfRange(0,128)))
            assertTrue(after.isEmpty())
            assertFalse(failed)
        } finally { transport.close(); receiver.close() }
    }
}
