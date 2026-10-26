package com.cardroid.app.helper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class HelperProtocolTest {
    private fun roundTrip(type: Int, payload: ByteArray) =
        HelperProtocol.read(ByteArrayInputStream(HelperProtocol.encode(type, payload)))

    @Test fun frameRoundTrip() {
        val f = roundTrip(HelperProtocol.T_PING, byteArrayOf(1, 2, 3))
        assertEquals(HelperProtocol.Frame(HelperProtocol.T_PING, byteArrayOf(1, 2, 3)), f)
        assertEquals(0, roundTrip(HelperProtocol.T_PONG, ByteArray(0)).payload.size)
    }

    @Test fun maxPayloadAcceptedOversizeRejected() {
        assertEquals(HelperProtocol.MAX_PAYLOAD, roundTrip(1, ByteArray(HelperProtocol.MAX_PAYLOAD)).payload.size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.encode(1, ByteArray(HelperProtocol.MAX_PAYLOAD + 1)) }
        val bad = byteArrayOf(0, 1, 0, 1, 1) // length 65537
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.read(ByteArrayInputStream(bad)) }
        val neg = byteArrayOf(-1, -1, -1, -1, 1)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.read(ByteArrayInputStream(neg)) }
    }

    @Test fun truncatedFramesRejected() {
        val full = HelperProtocol.encode(HelperProtocol.T_HELLO, HelperProtocol.hello(1))
        for (n in listOf(0, 2, 4, 5, full.size - 1))
            assertThrows(EOFException::class.java) { HelperProtocol.read(ByteArrayInputStream(full.copyOf(n))) }
    }

    @Test fun helloAndAckRoundTrip() {
        val h = HelperProtocol.parseHello(HelperProtocol.hello(7, HelperProtocol.ROLE_CONTROL))
        assertEquals(HelperProtocol.MAGIC, h.magic); assertEquals(HelperProtocol.PROTOCOL_VERSION, h.protoVersion)
        assertEquals(7, h.apkVersionCode); assertEquals(HelperProtocol.ROLE_CONTROL, h.role)
        val a = HelperProtocol.parseHelloAck(HelperProtocol.helloAck(1234, 35))
        assertEquals(1234, a.helperPid); assertEquals(35, a.sdkInt); assertEquals(HelperProtocol.PROTOCOL_VERSION, a.protoVersion)
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.parseBye(HelperProtocol.bye(HelperProtocol.BYE_VERSION)))
    }

    @Test fun badHelloRejected() {
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseHello(ByteArray(3)) }
        val wrongMagic = HelperProtocol.hello(1).also { it[0] = 0 }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseHello(wrongMagic) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseHelloAck(ByteArray(2)) }
    }

    @Test fun versionMismatch() {
        val ok = HelperProtocol.parseHello(HelperProtocol.hello(1))
        assertNull(HelperProtocol.checkHello(ok))
        val newer = HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = HelperProtocol.PROTOCOL_VERSION + 1))
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.checkHello(newer))
    }

    @Test fun peerUidParsing() {
        val tcp6 = """  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0000000000000000FFFF00000100007F:6A31 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 1 1 0 100 0 0 10 0
   1: 0000000000000000FFFF00000100007F:A4F6 0000000000000000FFFF00000100007F:6A31 01 00000000:00000000 00:00000000 00000000 10645        0 2 1 0 21 4 30 10 -1
   2: 0000000000000000FFFF00000100007F:6A31 0000000000000000FFFF00000100007F:A4F6 01 00000000:00000000 00:00000000 00000000  2000        0 3 1 0 21 4 30 10 -1
"""
        assertEquals(10645, PeerUid.parse(ByteArrayInputStream(tcp6.toByteArray()), 0xA4F6, 0x6A31))
        assertNull(PeerUid.parse(ByteArrayInputStream(tcp6.toByteArray()), 0x1234, 0x6A31))
    }

    @Test fun protocolIsV7WithDistinctTypes() {
        assertEquals(7, HelperProtocol.PROTOCOL_VERSION)
        val types = listOf(HelperProtocol.T_HELLO, HelperProtocol.T_HELLO_ACK, HelperProtocol.T_PING, HelperProtocol.T_PONG, HelperProtocol.T_BYE,
            HelperProtocol.T_START_DISPLAY, HelperProtocol.T_STOP_DISPLAY, HelperProtocol.T_REQUEST_KEYFRAME, HelperProtocol.T_DISPLAY_STARTED,
            HelperProtocol.T_DISPLAY_STOPPED, HelperProtocol.T_ERROR, HelperProtocol.T_VIDEO_CONFIG, HelperProtocol.T_VIDEO_FRAME,
            HelperProtocol.T_TOUCH_STATE, HelperProtocol.T_KEY, HelperProtocol.T_LAUNCH, HelperProtocol.T_GO_HOME, HelperProtocol.T_LIST_TASKS,
            HelperProtocol.T_MOVE_TASK_TO_FRONT, HelperProtocol.T_TASKS, HelperProtocol.T_AUDIO_CONFIG, HelperProtocol.T_AUDIO_READY, HelperProtocol.T_AUDIO_PCM, HelperProtocol.T_MEDIA_STATE, HelperProtocol.T_PANEL_TOUCH, HelperProtocol.T_PANEL_KEY, HelperProtocol.T_PANEL_LAUNCH)
        assertEquals(types.size, types.toSet().size)
        assertEquals((1..27).toList(), types)
        assertEquals((1..9).toList(), listOf(HelperProtocol.E_UNKNOWN_TYPE, HelperProtocol.E_BAD_REQUEST, HelperProtocol.E_DISPLAY_FAILED, HelperProtocol.E_BUSY, HelperProtocol.E_INPUT_FAILED, HelperProtocol.E_NO_DISPLAY,
            HelperProtocol.E_LAUNCH_FAILED, HelperProtocol.E_TASKS_FAILED, HelperProtocol.E_AUDIO_FAILED))
    }

    @Test fun panelCommandsRejectPhoneDisplayAndMalformedPayloads() {
        for (id in listOf(0, -1)) {
            assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.panelCommand(id, byteArrayOf(0)) }
            assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parsePanelCommand(java.nio.ByteBuffer.allocate(5).putInt(id).put(0).array()) }
        }
        for (size in 0..4) assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parsePanelCommand(ByteArray(size)) }
        val touch = HelperProtocol.touchState(listOf(HelperProtocol.TouchContact(0, true, 20, 30)))
        val (id, body) = HelperProtocol.parsePanelCommand(HelperProtocol.panelCommand(7, touch))
        assertEquals(7, id)
        assertEquals(20, HelperProtocol.parseTouchState(body).single().x)
    }

    @Test fun startDisplayRoundTrip() {
        val d = HelperProtocol.parseStartDisplay(roundTrip(HelperProtocol.T_START_DISPLAY, HelperProtocol.startDisplay(1280, 720, 160, 60, 4_000_000)).payload)
        assertEquals(1280, d.width); assertEquals(720, d.height); assertEquals(160, d.dpi); assertEquals(60, d.fps); assertEquals(4_000_000, d.bitrate)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseStartDisplay(ByteArray(10)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseStartDisplay(HelperProtocol.startDisplay(0, 720, 160, 30, 1)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.startDisplay(70_000, 720, 160, 30, 1) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.startDisplay(1280, 720, 160, 300, 1) }
    }

    @Test fun displayStartedStoppedErrorRoundTrip() {
        val s = HelperProtocol.parseDisplayStarted(roundTrip(HelperProtocol.T_DISPLAY_STARTED, HelperProtocol.displayStarted(25, 1280, 720, 30)).payload)
        assertEquals(25, s.displayId); assertEquals(1280, s.width); assertEquals(720, s.height); assertEquals(30, s.fps)
        assertEquals(-1, HelperProtocol.parseDisplayStarted(HelperProtocol.displayStarted(-1, 1, 1, 1)).displayId)
        assertEquals(HelperProtocol.STOP_VIDEO_LOST, HelperProtocol.parseDisplayStopped(roundTrip(HelperProtocol.T_DISPLAY_STOPPED, HelperProtocol.displayStopped(HelperProtocol.STOP_VIDEO_LOST)).payload))
        val e = HelperProtocol.parseError(roundTrip(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_DISPLAY_FAILED, "boom \u00e9")).payload)
        assertEquals(HelperProtocol.E_DISPLAY_FAILED, e.code); assertEquals("boom \u00e9", e.message)
        assertEquals(1 + 1024, HelperProtocol.error(1, "x".repeat(5000)).size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseError(ByteArray(0)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseDisplayStarted(ByteArray(8)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseDisplayStopped(ByteArray(0)) }
    }

    @Test fun emptyControlMessagesRoundTrip() {
        for (t in listOf(HelperProtocol.T_STOP_DISPLAY, HelperProtocol.T_REQUEST_KEYFRAME)) assertEquals(HelperProtocol.Frame(t, ByteArray(0)), roundTrip(t, ByteArray(0)))
    }

    @Test fun videoConfigRoundTrip() {
        val sps = byteArrayOf(0x67, 1, 2, 3); val pps = byteArrayOf(0x68, 9)
        val c = HelperProtocol.parseVideoConfig(roundTrip(HelperProtocol.T_VIDEO_CONFIG, HelperProtocol.videoConfig(sps, pps)).payload)
        assertArrayEquals(sps, c.sps); assertArrayEquals(pps, c.pps)
        val good = HelperProtocol.videoConfig(sps, pps)
        for (n in listOf(0, 1, 3, 5, good.size - 1)) assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseVideoConfig(good.copyOf(n)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseVideoConfig(good + byteArrayOf(0)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseVideoConfig(HelperProtocol.videoConfig(ByteArray(0), pps)) }
    }

    @Test fun videoFrameRoundTrip() {
        val data = ByteArray(1000) { it.toByte() }
        val f = HelperProtocol.parseVideoFrame(roundTrip(HelperProtocol.T_VIDEO_FRAME, HelperProtocol.videoFrame(123_456_789_012L, true, data)).payload)
        assertEquals(123_456_789_012L, f.ptsUs); assertEquals(true, f.keyFrame); assertArrayEquals(data, f.data)
        assertEquals(false, HelperProtocol.parseVideoFrame(HelperProtocol.videoFrame(-5L, false, ByteArray(0))).keyFrame)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseVideoFrame(ByteArray(8)) }
    }

    @Test fun oversizeRejectedPerRole() {
        assertEquals(64 * 1024, HelperProtocol.maxPayload(HelperProtocol.ROLE_CONTROL))
        assertEquals(4 * 1024 * 1024, HelperProtocol.maxPayload(HelperProtocol.ROLE_VIDEO))
        val big = ByteArray(1024 * 1024) // 1 MiB frame: fine on VIDEO, rejected on CONTROL
        val videoMax = HelperProtocol.maxPayload(HelperProtocol.ROLE_VIDEO)
        val wire = HelperProtocol.encode(HelperProtocol.T_VIDEO_FRAME, big, videoMax)
        assertEquals(big.size, HelperProtocol.read(ByteArrayInputStream(wire), videoMax).payload.size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.read(ByteArrayInputStream(wire), HelperProtocol.maxPayload(HelperProtocol.ROLE_CONTROL)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.read(ByteArrayInputStream(wire)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.encode(HelperProtocol.T_VIDEO_FRAME, big) }
        // exactly 4 MiB accepted on VIDEO, 4 MiB + 1 rejected
        assertEquals(videoMax, HelperProtocol.read(ByteArrayInputStream(HelperProtocol.encode(1, ByteArray(videoMax), videoMax)), videoMax).payload.size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.encode(1, ByteArray(videoMax + 1), videoMax) }
        val badLen = byteArrayOf(0x00, 0x40, 0x00, 0x01, 1) // 4 MiB + 1
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.read(ByteArrayInputStream(badLen), videoMax) }
    }

    @Test fun truncatedVideoFramesRejected() {
        val full = HelperProtocol.encode(HelperProtocol.T_VIDEO_FRAME, HelperProtocol.videoFrame(1, true, ByteArray(100)), HelperProtocol.MAX_PAYLOAD_VIDEO)
        for (n in listOf(0, 3, 5, 50, full.size - 1))
            assertThrows(EOFException::class.java) { HelperProtocol.read(ByteArrayInputStream(full.copyOf(n)), HelperProtocol.MAX_PAYLOAD_VIDEO) }
    }

    @Test fun v1PeerIsOutdated() {
        val v1Hello = HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = 1))
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.checkHello(v1Hello))
        val videoHello = HelperProtocol.parseHello(HelperProtocol.hello(1, HelperProtocol.ROLE_VIDEO))
        assertEquals(HelperProtocol.ROLE_VIDEO, videoHello.role); assertNull(HelperProtocol.checkHello(videoHello))
        // a v1 helper ack seen by a v2 client carries a different version
        assertEquals(1, HelperProtocol.parseHelloAck(HelperProtocol.helloAck(1, 35, protoVersion = 1)).protoVersion)
    }

    @Test fun v2PeerIsOutdated() {
        val v2Hello = HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = 2))
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.checkHello(v2Hello))
        assertEquals(2, HelperProtocol.parseHelloAck(HelperProtocol.helloAck(1, 35, protoVersion = 2)).protoVersion)
    }

    @Test fun touchStateRoundTrip() {
        val cs = listOf(HelperProtocol.TouchContact(0, true, 1279, 719), HelperProtocol.TouchContact(1, false, 0, 65535))
        val back = HelperProtocol.parseTouchState(roundTrip(HelperProtocol.T_TOUCH_STATE, HelperProtocol.touchState(cs)).payload)
        assertEquals(2, back.size)
        assertEquals(0, back[0].id); assertEquals(true, back[0].down); assertEquals(1279, back[0].x); assertEquals(719, back[0].y)
        assertEquals(1, back[1].id); assertEquals(false, back[1].down); assertEquals(0, back[1].x); assertEquals(65535, back[1].y)
        assertEquals(0, HelperProtocol.parseTouchState(HelperProtocol.touchState(emptyList())).size)
    }

    @Test fun touchStateLimits() {
        val ten = List(10) { HelperProtocol.TouchContact(it, true, it, it) }
        assertEquals(10, HelperProtocol.parseTouchState(HelperProtocol.touchState(ten)).size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.touchState(ten + HelperProtocol.TouchContact(10, true, 0, 0)) }
        val tooMany = ByteArray(1 + 6 * 11).also { it[0] = 11 }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTouchState(tooMany) }
        // count/size mismatch both directions, empty payload
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTouchState(HelperProtocol.touchState(ten.take(2)).copyOf(12)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTouchState(HelperProtocol.touchState(ten.take(2)).copyOf(14)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTouchState(ByteArray(0)) }
        // u16 / u8 range
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.touchState(listOf(HelperProtocol.TouchContact(0, true, 65536, 0))) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.touchState(listOf(HelperProtocol.TouchContact(0, true, 0, -1))) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.touchState(listOf(HelperProtocol.TouchContact(256, true, 0, 0))) }
    }

    @Test fun keyRoundTripAndLimits() {
        assertEquals(126, HelperProtocol.parseKey(roundTrip(HelperProtocol.T_KEY, HelperProtocol.key(126)).payload))
        assertEquals(65535, HelperProtocol.parseKey(HelperProtocol.key(65535)))
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.key(65536) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.key(-1) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseKey(ByteArray(1)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseKey(ByteArray(3)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseKey(ByteArray(2)) }
    }

    @Test fun audioRoleAndPcmRoundTrip() {
        val hello = HelperProtocol.parseHello(HelperProtocol.hello(1, HelperProtocol.ROLE_AUDIO))
        assertEquals(HelperProtocol.ROLE_AUDIO, hello.role)
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.checkHello(
            HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = 4))))
        val pcm = ByteArray(4096) { it.toByte() }
        assertArrayEquals(pcm, roundTrip(HelperProtocol.T_AUDIO_PCM, pcm).payload)
    }

    @Test fun v3PeerIsOutdated() {
        val v3 = HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = 3))
        assertEquals(HelperProtocol.BYE_VERSION, HelperProtocol.checkHello(v3))
        assertNull(HelperProtocol.checkHello(HelperProtocol.parseHello(HelperProtocol.hello(1, protoVersion = HelperProtocol.PROTOCOL_VERSION))))
    }

    @Test fun launchRoundTripAndLimits() {
        val l = HelperProtocol.parseLaunch(roundTrip(HelperProtocol.T_LAUNCH, HelperProtocol.launch("com.android.chrome/com.google.android.apps.chrome.Main", HelperProtocol.LAUNCH_CLEAR_TASK)).payload)
        assertEquals(HelperProtocol.LAUNCH_CLEAR_TASK, l.flags); assertEquals("com.android.chrome/com.google.android.apps.chrome.Main", l.target)
        assertEquals("pkg.x", HelperProtocol.parseLaunch(HelperProtocol.launch("pkg.x")).target)
        assertEquals("\u00e9\u4e2d/x", HelperProtocol.parseLaunch(HelperProtocol.launch("\u00e9\u4e2d/x")).target)
        val max = "a".repeat(HelperProtocol.MAX_LAUNCH_TARGET)
        assertEquals(max, HelperProtocol.parseLaunch(HelperProtocol.launch(max)).target)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.launch(max + "a") }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.launch("") }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.launch("a", 2) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseLaunch(ByteArray(0)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseLaunch(ByteArray(1)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseLaunch(byteArrayOf(0x80.toByte(), 'a'.code.toByte())) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseLaunch(byteArrayOf(0, ' '.code.toByte())) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseLaunch(ByteArray(2 + HelperProtocol.MAX_LAUNCH_TARGET) { 'a'.code.toByte() }) }
    }

    @Test fun listTasksAndMoveRoundTrip() {
        assertEquals(12, HelperProtocol.parseListTasks(roundTrip(HelperProtocol.T_LIST_TASKS, HelperProtocol.listTasks(12)).payload))
        assertEquals(1, HelperProtocol.parseListTasks(HelperProtocol.listTasks(1)))
        assertEquals(32, HelperProtocol.parseListTasks(HelperProtocol.listTasks(32)))
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.listTasks(0) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.listTasks(33) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseListTasks(byteArrayOf(0)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseListTasks(ByteArray(0)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseListTasks(ByteArray(2) { 1 }) }
        assertEquals(384, HelperProtocol.parseMoveTaskToFront(roundTrip(HelperProtocol.T_MOVE_TASK_TO_FRONT, HelperProtocol.moveTaskToFront(384)).payload))
        assertEquals(-1, HelperProtocol.parseMoveTaskToFront(HelperProtocol.moveTaskToFront(-1)))
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseMoveTaskToFront(ByteArray(3)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseMoveTaskToFront(ByteArray(5)) }
    }

    @Test fun tasksRoundTripAndLimits() {
        val list = listOf(HelperProtocol.TaskEntry(384, "com.cardroid.app", "com.cardroid.app/com.cardroid.app.car.CarLauncherActivity"),
            HelperProtocol.TaskEntry(368, "com.android.chrome", ""), HelperProtocol.TaskEntry(-5, "", ""), HelperProtocol.TaskEntry(7, "\u00e9\u4e2d", "\u00e9\u4e2d/\u00e9"))
        val back = HelperProtocol.parseTasks(roundTrip(HelperProtocol.T_TASKS, HelperProtocol.tasks(list)).payload)
        assertEquals(list.size, back.size)
        for (i in list.indices) { assertEquals(list[i].taskId, back[i].taskId); assertEquals(list[i].pkg, back[i].pkg); assertEquals(list[i].component, back[i].component) }
        assertEquals(0, HelperProtocol.parseTasks(HelperProtocol.tasks(emptyList())).size)
        val max = List(HelperProtocol.MAX_TASKS) { HelperProtocol.TaskEntry(it, "p".repeat(255), "c".repeat(1024)) }
        val enc = HelperProtocol.tasks(max)
        assertEquals(true, enc.size <= HelperProtocol.MAX_PAYLOAD)
        assertEquals(HelperProtocol.MAX_TASKS, HelperProtocol.parseTasks(enc).size)
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.tasks(max + HelperProtocol.TaskEntry(99, "p", "c")) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.tasks(listOf(HelperProtocol.TaskEntry(1, "p".repeat(256), ""))) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.tasks(listOf(HelperProtocol.TaskEntry(1, "", "c".repeat(1025)))) }
        // count/size mismatch both ways, empty, truncated, trailing, count above limit
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(ByteArray(0)) }
        val two = HelperProtocol.tasks(list.take(2))
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(two.copyOf(two.size - 1)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(two.copyOf(two.size + 1)) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(two.copyOf().also { it[0] = 3 }) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(two.copyOf().also { it[0] = 1 }) }
        assertThrows(HelperProtocol.ProtocolException::class.java) { HelperProtocol.parseTasks(ByteArray(1) { 33 }) }
    }

    @Test fun launcherErrorCodes() {
        val e = HelperProtocol.parseError(HelperProtocol.error(HelperProtocol.E_LAUNCH_FAILED, "launch x: SecurityException"))
        assertEquals(7, e.code); assertEquals("launch x: SecurityException", e.message)
        assertEquals(8, HelperProtocol.parseError(HelperProtocol.error(HelperProtocol.E_TASKS_FAILED, "list")).code)
    }
}
