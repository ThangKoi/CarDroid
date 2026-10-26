package com.cardroid.app.helper

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Car-display helper wire protocol v6. Pure Kotlin (JVM-safe, no android.*). Frame = u32 BE payload length + u8 type + payload.
 *
 * Roles (HELLO): CONTROL=0 (max payload 64 KiB), VIDEO=1 (max payload 4 MiB). One slot per role; VIDEO is accepted only while a CONTROL
 * client with the same UID is connected. PING/PONG are CONTROL only; the VIDEO connection is exempt from the helper read timeout.
 *
 * Message types (all integers big-endian):
 *   1 HELLO, 2 HELLO_ACK, 3 PING, 4 PONG, 5 BYE                     (v1)
 *   6 START_DISPLAY      app->helper CONTROL {width u16, height u16, dpi u16, fps u8, bitrate u32}
 *   7 STOP_DISPLAY       app->helper CONTROL {}
 *   8 REQUEST_KEYFRAME   app->helper CONTROL {}
 *   9 DISPLAY_STARTED    helper->app CONTROL {displayId i32, width u16, height u16, fps u8}
 *  10 DISPLAY_STOPPED    helper->app CONTROL {reason u8}
 *  11 ERROR              helper->app CONTROL {code u8, msg utf8}
 *  12 VIDEO_CONFIG       helper->app VIDEO   {spsLen u16, sps, ppsLen u16, pps}   (Annex-B NAL bodies without start codes)
 *  13 VIDEO_FRAME        helper->app VIDEO   {ptsUs i64, flags u8 (bit0 keyframe), data Annex-B}
 *  14 TOUCH_STATE       app->helper CONTROL {count u8 (0..10), then per contact: id u8, down u8 (0/1), x u16, y u16}
 *                       state snapshot per HID report, coordinates in car-display pixels; count 0 releases everything
 *  15 KEY               app->helper CONTROL {keycode u16 (1..65535)}; helper injects DOWN then UP on the car display
 *  16 LAUNCH           app->helper CONTROL {flags u8 (bit0 CLEAR_TASK, others reserved 0), target utf8 (1..512 bytes): "pkg/cls" component or "pkg" (its LAUNCHER activity)}
 *                       helper startActivityAsUser(NEW_TASK + launchDisplayId = car display); E_NO_DISPLAY / E_LAUNCH_FAILED on problems
 *  17 GO_HOME          app->helper CONTROL {}   helper launches CarLauncherActivity on the car display (singleTask, brings the launcher task to the front)
 *  18 LIST_TASKS       app->helper CONTROL {max u8 (1..32)}   answered with TASKS (or ERROR E_TASKS_FAILED / E_NO_DISPLAY)
 *  19 MOVE_TASK_TO_FRONT app->helper CONTROL {taskId i32}   E_TASKS_FAILED if the move throws
 *  20 TASKS            helper->app CONTROL {count u8 (0..32), per task: taskId i32, pkgLen u8, pkg utf8, compLen u16, component utf8 ("pkg/cls" or empty)}
 *                       tasks of the car display, most recent first
 * v4 also makes the helper start CarLauncherActivity on the car display right after DISPLAY_STARTED.
 * Errors (ERROR code): 1 UNKNOWN_TYPE, 2 BAD_REQUEST, 3 DISPLAY_FAILED, 4 BUSY, 5 INPUT_FAILED, 6 NO_DISPLAY (input/launch while no display is active),
 *   7 LAUNCH_FAILED (startActivityAsUser threw or returned a negative result), 8 TASKS_FAILED (getTasks/moveTaskToFront reflection or call failed).
 * Unknown CONTROL types are answered with ERROR(E_UNKNOWN_TYPE) and do not disconnect.
 */
object HelperProtocol {
    const val PORT = 27185
    const val MAGIC = 0x50525448 // "PRTH"
    const val PROTOCOL_VERSION = 7
    const val MAX_PAYLOAD = 64 * 1024 // CONTROL role
    const val MAX_PAYLOAD_VIDEO = 4 * 1024 * 1024
    const val PING_INTERVAL_MS = 2_000L
    const val TIMEOUT_MS = 6_000

    const val T_HELLO = 1
    const val T_HELLO_ACK = 2
    const val T_PING = 3
    const val T_PONG = 4
    const val T_BYE = 5

    const val T_START_DISPLAY = 6
    const val T_STOP_DISPLAY = 7
    const val T_REQUEST_KEYFRAME = 8
    const val T_DISPLAY_STARTED = 9
    const val T_DISPLAY_STOPPED = 10
    const val T_ERROR = 11
    const val T_VIDEO_CONFIG = 12
    const val T_VIDEO_FRAME = 13
    const val T_TOUCH_STATE = 14
    const val T_KEY = 15
    const val T_LAUNCH = 16
    const val T_GO_HOME = 17
    const val T_LIST_TASKS = 18
    const val T_MOVE_TASK_TO_FRONT = 19
    const val T_TASKS = 20
    // v5 AUDIO role: CONFIG {sampleRate i32, samplesPerPacket i32}, READY {}, PCM {PCM16 LE stereo}.
    const val T_AUDIO_CONFIG = 21
    const val T_AUDIO_READY = 22
    const val T_AUDIO_PCM = 23
    const val ROLE_AUDIO = 2
    const val E_AUDIO_FAILED = 9
    const val T_MEDIA_STATE = 24 // v6: helper -> app, UTF-8 JSON active-media snapshot
    const val T_PANEL_TOUCH = 25 // v7: displayId i32 + TOUCH_STATE; restricted to app-owned panel displays
    const val T_PANEL_LAUNCH = 27 // v7: displayId i32 + LAUNCH, limited to YouTube/Maps
    const val T_PANEL_KEY = 26 // v7: displayId i32 + KEY (Back only)

    fun panelCommand(displayId: Int, payload: ByteArray): ByteArray {
        if (displayId <= 0) throw ProtocolException("panel display must not be the phone")
        return ByteBuffer.allocate(4 + payload.size).putInt(displayId).put(payload).array()
    }
    fun parsePanelCommand(payload: ByteArray): Pair<Int, ByteArray> {
        if (payload.size < 5) throw ProtocolException("truncated panel command")
        val id = ByteBuffer.wrap(payload).int
        if (id <= 0) throw ProtocolException("panel display must not be the phone")
        return id to payload.copyOfRange(4, payload.size)
    }
    const val MAX_CONTACTS = 10
    const val MAX_TASKS = 32
    const val MAX_LAUNCH_TARGET = 512
    const val LAUNCH_CLEAR_TASK = 1

    const val ROLE_CONTROL = 0
    const val ROLE_VIDEO = 1

    const val E_UNKNOWN_TYPE = 1
    const val E_BAD_REQUEST = 2
    const val E_DISPLAY_FAILED = 3
    const val E_BUSY = 4
    const val E_INPUT_FAILED = 5
    const val E_NO_DISPLAY = 6
    const val E_LAUNCH_FAILED = 7
    const val E_TASKS_FAILED = 8

    const val STOP_REQUESTED = 1
    const val STOP_CONTROL_LOST = 2
    const val STOP_VIDEO_LOST = 3
    const val STOP_ENCODER_FAILED = 4

    const val BYE_VERSION = 1
    const val BYE_UID = 2
    const val BYE_BUSY = 3
    const val BYE_PROTOCOL = 4
    const val BYE_SHUTDOWN = 5

    class ProtocolException(msg: String) : Exception(msg)

    class Frame(val type: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame && other.type == type && other.payload.contentEquals(payload)
        override fun hashCode() = 31 * type + payload.contentHashCode()
    }

    fun maxPayload(role: Int): Int = if (role == ROLE_VIDEO) MAX_PAYLOAD_VIDEO else MAX_PAYLOAD

    class StartDisplay(val width: Int, val height: Int, val dpi: Int, val fps: Int, val bitrate: Int)
    class DisplayStarted(val displayId: Int, val width: Int, val height: Int, val fps: Int)
    class VideoConfig(val sps: ByteArray, val pps: ByteArray)
    class VideoFrame(val ptsUs: Long, val keyFrame: Boolean, val data: ByteArray)
    class TouchContact(val id: Int, val down: Boolean, val x: Int, val y: Int)
    class Launch(val flags: Int, val target: String)
    class TaskEntry(val taskId: Int, val pkg: String, val component: String)
    class ErrorMsg(val code: Int, val message: String)

    class Hello(val magic: Int, val protoVersion: Int, val apkVersionCode: Int, val role: Int)
    class HelloAck(val protoVersion: Int, val helperPid: Int, val sdkInt: Int)

    fun encode(type: Int, payload: ByteArray = ByteArray(0), maxPayload: Int = MAX_PAYLOAD): ByteArray {
        if (payload.size > maxPayload) throw ProtocolException("payload too large: ${payload.size}")
        return ByteBuffer.allocate(5 + payload.size).putInt(payload.size).put(type.toByte()).put(payload).array()
    }

    fun write(out: OutputStream, type: Int, payload: ByteArray = ByteArray(0), maxPayload: Int = MAX_PAYLOAD) {
        out.write(encode(type, payload, maxPayload)); out.flush()
    }

    /** Reads one frame; EOFException on clean/truncated end of stream, ProtocolException on a bad length. */
    fun read(input: InputStream, maxPayload: Int = MAX_PAYLOAD): Frame {
        val din = DataInputStream(input)
        val len = din.readInt()
        if (len < 0 || len > maxPayload) throw ProtocolException("bad frame length: $len")
        val type = din.readUnsignedByte()
        val payload = ByteArray(len)
        try { din.readFully(payload) } catch (e: EOFException) { throw EOFException("truncated frame") }
        return Frame(type, payload)
    }

    fun hello(apkVersionCode: Int, role: Int = ROLE_CONTROL, protoVersion: Int = PROTOCOL_VERSION): ByteArray =
        ByteBuffer.allocate(11).putInt(MAGIC).putShort(protoVersion.toShort()).putInt(apkVersionCode).put(role.toByte()).array()

    fun parseHello(p: ByteArray): Hello {
        if (p.size != 11) throw ProtocolException("bad HELLO size ${p.size}")
        val b = ByteBuffer.wrap(p)
        val h = Hello(b.int, b.short.toInt() and 0xFFFF, b.int, b.get().toInt() and 0xFF)
        if (h.magic != MAGIC) throw ProtocolException("bad magic")
        return h
    }

    fun helloAck(helperPid: Int, sdkInt: Int, protoVersion: Int = PROTOCOL_VERSION): ByteArray =
        ByteBuffer.allocate(8).putShort(protoVersion.toShort()).putInt(helperPid).putShort(sdkInt.toShort()).array()

    fun parseHelloAck(p: ByteArray): HelloAck {
        if (p.size != 8) throw ProtocolException("bad HELLO_ACK size ${p.size}")
        val b = ByteBuffer.wrap(p)
        return HelloAck(b.short.toInt() and 0xFFFF, b.int, b.short.toInt() and 0xFFFF)
    }

    fun bye(reason: Int): ByteArray = byteArrayOf(reason.toByte())
    fun parseBye(p: ByteArray): Int = if (p.isEmpty()) 0 else p[0].toInt() and 0xFF

    /** Null when the HELLO is acceptable, otherwise the BYE reason to send. */
    fun checkHello(h: Hello, expectedVersion: Int = PROTOCOL_VERSION): Int? =
        if (h.protoVersion != expectedVersion) BYE_VERSION else null

    // ---- v2 display / video messages ----

    private fun u8(v: Int, name: String): Int { if (v !in 0..0xFF) throw ProtocolException("$name out of range: $v"); return v }
    private fun u16(v: Int, name: String): Int { if (v !in 0..0xFFFF) throw ProtocolException("$name out of range: $v"); return v }
    private fun exact(p: ByteArray, size: Int, what: String): ByteBuffer {
        if (p.size != size) throw ProtocolException("bad $what size ${p.size}")
        return ByteBuffer.wrap(p)
    }

    fun startDisplay(width: Int, height: Int, dpi: Int, fps: Int, bitrate: Int): ByteArray =
        ByteBuffer.allocate(11).putShort(u16(width, "width").toShort()).putShort(u16(height, "height").toShort())
            .putShort(u16(dpi, "dpi").toShort()).put(u8(fps, "fps").toByte()).putInt(bitrate).array()

    fun parseStartDisplay(p: ByteArray): StartDisplay {
        val b = exact(p, 11, "START_DISPLAY")
        val d = StartDisplay(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.get().toInt() and 0xFF, b.int)
        if (d.width <= 0 || d.height <= 0 || d.dpi <= 0 || d.fps <= 0 || d.bitrate <= 0) throw ProtocolException("invalid START_DISPLAY values")
        return d
    }

    fun displayStarted(displayId: Int, width: Int, height: Int, fps: Int): ByteArray =
        ByteBuffer.allocate(9).putInt(displayId).putShort(u16(width, "width").toShort()).putShort(u16(height, "height").toShort())
            .put(u8(fps, "fps").toByte()).array()

    fun parseDisplayStarted(p: ByteArray): DisplayStarted {
        val b = exact(p, 9, "DISPLAY_STARTED")
        return DisplayStarted(b.int, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.get().toInt() and 0xFF)
    }

    fun displayStopped(reason: Int): ByteArray = byteArrayOf(u8(reason, "reason").toByte())
    fun parseDisplayStopped(p: ByteArray): Int = exact(p, 1, "DISPLAY_STOPPED").get().toInt() and 0xFF

    /** Message text is truncated so the frame always fits CONTROL limits. */
    fun error(code: Int, msg: String): ByteArray {
        val m = msg.toByteArray(Charsets.UTF_8).let { if (it.size > 1024) it.copyOf(1024) else it }
        return ByteBuffer.allocate(1 + m.size).put(u8(code, "code").toByte()).put(m).array()
    }

    fun parseError(p: ByteArray): ErrorMsg {
        if (p.isEmpty()) throw ProtocolException("bad ERROR size 0")
        return ErrorMsg(p[0].toInt() and 0xFF, String(p, 1, p.size - 1, Charsets.UTF_8))
    }

    fun videoConfig(sps: ByteArray, pps: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + sps.size + pps.size).putShort(u16(sps.size, "sps").toShort()).put(sps)
            .putShort(u16(pps.size, "pps").toShort()).put(pps).array()

    fun parseVideoConfig(p: ByteArray): VideoConfig {
        val b = ByteBuffer.wrap(p)
        fun part(): ByteArray {
            if (b.remaining() < 2) throw ProtocolException("truncated VIDEO_CONFIG")
            val n = b.short.toInt() and 0xFFFF
            if (b.remaining() < n) throw ProtocolException("truncated VIDEO_CONFIG")
            return ByteArray(n).also { b.get(it) }
        }
        val sps = part(); val pps = part()
        if (b.hasRemaining()) throw ProtocolException("trailing bytes in VIDEO_CONFIG")
        if (sps.isEmpty() || pps.isEmpty()) throw ProtocolException("empty SPS/PPS")
        return VideoConfig(sps, pps)
    }

    fun videoFrame(ptsUs: Long, keyFrame: Boolean, data: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + data.size).putLong(ptsUs).put(if (keyFrame) 1 else 0).put(data).array()

    fun parseVideoFrame(p: ByteArray): VideoFrame {
        if (p.size < 9) throw ProtocolException("bad VIDEO_FRAME size ${p.size}")
        val b = ByteBuffer.wrap(p)
        val pts = b.long; val flags = b.get().toInt() and 0xFF
        return VideoFrame(pts, flags and 1 != 0, ByteArray(b.remaining()).also { b.get(it) })
    }

    // ---- v3 input messages ----

    fun touchState(contacts: List<TouchContact>): ByteArray {
        if (contacts.size > MAX_CONTACTS) throw ProtocolException("too many contacts: ${contacts.size}")
        val b = ByteBuffer.allocate(1 + 6 * contacts.size).put(contacts.size.toByte())
        for (c in contacts) b.put(u8(c.id, "id").toByte()).put(if (c.down) 1 else 0).putShort(u16(c.x, "x").toShort()).putShort(u16(c.y, "y").toShort())
        return b.array()
    }

    fun parseTouchState(p: ByteArray): List<TouchContact> {
        if (p.isEmpty()) throw ProtocolException("bad TOUCH_STATE size 0")
        val b = ByteBuffer.wrap(p)
        val n = b.get().toInt() and 0xFF
        if (n > MAX_CONTACTS) throw ProtocolException("too many contacts: $n")
        if (p.size != 1 + 6 * n) throw ProtocolException("bad TOUCH_STATE size ${p.size} for $n contacts")
        return List(n) { TouchContact(b.get().toInt() and 0xFF, (b.get().toInt() and 0xFF) != 0, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF) }
    }

    fun key(keycode: Int): ByteArray = ByteBuffer.allocate(2).putShort(u16(keycode, "keycode").toShort()).array()

    fun parseKey(p: ByteArray): Int {
        val k = exact(p, 2, "KEY").short.toInt() and 0xFFFF
        if (k == 0) throw ProtocolException("invalid keycode 0")
        return k
    }

    // ---- v4 launcher messages ----

    fun launch(target: String, flags: Int = 0): ByteArray {
        val t = target.toByteArray(Charsets.UTF_8)
        if (t.isEmpty() || t.size > MAX_LAUNCH_TARGET) throw ProtocolException("bad LAUNCH target length ${t.size}")
        if (flags and LAUNCH_CLEAR_TASK.inv() != 0) throw ProtocolException("unknown LAUNCH flags $flags")
        return ByteBuffer.allocate(1 + t.size).put(u8(flags, "flags").toByte()).put(t).array()
    }

    fun parseLaunch(p: ByteArray): Launch {
        if (p.size < 2 || p.size > 1 + MAX_LAUNCH_TARGET) throw ProtocolException("bad LAUNCH size ${p.size}")
        val flags = p[0].toInt() and 0xFF
        if (flags and LAUNCH_CLEAR_TASK.inv() != 0) throw ProtocolException("unknown LAUNCH flags $flags")
        val target = String(p, 1, p.size - 1, Charsets.UTF_8)
        if (target.isBlank()) throw ProtocolException("blank LAUNCH target")
        return Launch(flags, target)
    }

    fun listTasks(max: Int): ByteArray {
        if (max !in 1..MAX_TASKS) throw ProtocolException("LIST_TASKS max out of range: $max")
        return byteArrayOf(max.toByte())
    }

    fun parseListTasks(p: ByteArray): Int {
        val m = exact(p, 1, "LIST_TASKS").get().toInt() and 0xFF
        if (m !in 1..MAX_TASKS) throw ProtocolException("LIST_TASKS max out of range: $m")
        return m
    }

    fun moveTaskToFront(taskId: Int): ByteArray = ByteBuffer.allocate(4).putInt(taskId).array()
    fun parseMoveTaskToFront(p: ByteArray): Int = exact(p, 4, "MOVE_TASK_TO_FRONT").int

    fun tasks(list: List<TaskEntry>): ByteArray {
        if (list.size > MAX_TASKS) throw ProtocolException("too many tasks: ${list.size}")
        val enc = list.map { Triple(it.taskId, it.pkg.toByteArray(Charsets.UTF_8), it.component.toByteArray(Charsets.UTF_8)) }
        val b = ByteBuffer.allocate(1 + enc.sumOf { 4 + 1 + it.second.size + 2 + it.third.size }).put(list.size.toByte())
        for ((id, pkg, comp) in enc) {
            if (pkg.size > 255) throw ProtocolException("package too long: ${pkg.size}")
            if (comp.size > 1024) throw ProtocolException("component too long: ${comp.size}")
            b.putInt(id).put(pkg.size.toByte()).put(pkg).putShort(comp.size.toShort()).put(comp)
        }
        return b.array()
    }

    fun parseTasks(p: ByteArray): List<TaskEntry> {
        if (p.isEmpty()) throw ProtocolException("bad TASKS size 0")
        val b = ByteBuffer.wrap(p)
        val n = b.get().toInt() and 0xFF
        if (n > MAX_TASKS) throw ProtocolException("too many tasks: $n")
        fun str(len: Int): String {
            if (b.remaining() < len) throw ProtocolException("truncated TASKS")
            return String(ByteArray(len).also { b.get(it) }, Charsets.UTF_8)
        }
        val out = ArrayList<TaskEntry>(n)
        repeat(n) {
            if (b.remaining() < 5) throw ProtocolException("truncated TASKS")
            val id = b.int
            val pkg = str(b.get().toInt() and 0xFF)
            if (b.remaining() < 2) throw ProtocolException("truncated TASKS")
            val comp = str(b.short.toInt() and 0xFFFF)
            out.add(TaskEntry(id, pkg, comp))
        }
        if (b.hasRemaining()) throw ProtocolException("trailing bytes in TASKS")
        return out
    }
}
