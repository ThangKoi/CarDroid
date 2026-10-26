package com.cardroid.app.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * App-side monitor of the car-display helper (protocol v6). Never throws into callers; silent while the helper is absent.
 * One CONTROL connection (reader coroutine + single channel-serialised writer coroutine + PING watchdog) and, while a display is
 * requested, one VIDEO connection whose frames are delivered through the callbacks.
 */
class HelperClient(private val apkVersionCode: Int, private val log: (String) -> Unit) {
    sealed class State {
        object NotRunning : State()
        object Connecting : State()
        data class Connected(val helperPid: Int, val sdkInt: Int) : State()
        data class DisplayActive(val displayId: Int, val helperPid: Int) : State()
        object Outdated : State()
    }

    class DisplayParams(val width: Int, val height: Int, val dpi: Int, val fps: Int, val bitrate: Int)

    companion object {
        /** Process-wide status for the UI (one service instance). */
        val status: StateFlow<State> get() = shared
        private val shared = MutableStateFlow<State>(State.NotRunning)
        fun label(s: State) = when (s) {
            is State.Connected -> "connected (pid ${s.helperPid}, sdk ${s.sdkInt})"
            is State.DisplayActive -> "display active (id ${s.displayId}, pid ${s.helperPid})"
            State.Connecting -> "connecting"
            State.Outdated -> "outdated - restart helper"
            State.NotRunning -> "not running"
        }
    }

    /** Called on a client thread for every VIDEO_CONFIG / VIDEO_FRAME; must not block for long. */
    @Volatile var onVideoConfig: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    @Volatile var onVideoFrame: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    /** Display could not start, or was lost (helper stopped it, VIDEO EOF, CONTROL loss). Fired once per startDisplay(). */
    @Volatile var onDisplayLost: ((reason: String) -> Unit)? = null
    @Volatile var onDisplayStarted: ((displayId: Int) -> Unit)? = null
    /** v4: TASKS reply to listTasks (car display tasks, most recent first) / ERROR(TASKS_FAILED) for list or move. Called on the reader coroutine. */
    @Volatile var onTasks: ((List<HelperProtocol.TaskEntry>) -> Unit)? = null
    @Volatile var onTasksFailed: ((message: String) -> Unit)? = null
    @Volatile var onLaunchFailed: ((message: String) -> Unit)? = null

    @Volatile var nowPlaying = com.cardroid.lib.NowPlayingState()
        private set

    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var videoSocket: Socket? = null
    @Volatile private var outbox: Channel<ByteArray>? = null
    @Volatile private var session: CoroutineScope? = null
    @Volatile private var helperPid = 0
    @Volatile private var helperSdk = 0
    private val displayLock = Any()
    @Volatile private var displayRequested = false

    /** True while a CONTROL session is established (Connected or DisplayActive). */
    val isConnected: Boolean get() = shared.value.let { it is State.Connected || it is State.DisplayActive }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val result = runSession()
                    backoff = if (result == Result.WasConnected) 1_000L else minOf(backoff * 2, 5_000L)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) {
                    set(State.NotRunning); backoff = minOf(backoff * 2, 5_000L)
                }
                delay(backoff)
            }
        }
    }

    fun stop() {
        displayLost("client stopped", fire = false)
        job?.cancel(); job = null
        runCatching { socket?.close() }; socket = null
        set(State.NotRunning)
    }

    /** Asks the helper for a car display and opens the VIDEO connection. Returns false (and does nothing) if not connected. Never throws. */
    fun startDisplay(p: DisplayParams): Boolean {
        val box = outbox; val scope = session
        if (box == null || scope == null || !isConnected) return false
        return try {
            synchronized(displayLock) {
                if (displayRequested) return false
                displayRequested = true
            }
            box.trySend(HelperProtocol.encode(HelperProtocol.T_START_DISPLAY, HelperProtocol.startDisplay(p.width, p.height, p.dpi, p.fps, p.bitrate)))
            scope.launch(Dispatchers.IO) { runVideo() }
            true
        } catch (e: Throwable) { displayRequested = false; log("Helper: startDisplay failed ${e.javaClass.simpleName}: ${e.message}"); false }
    }

    fun stopDisplay() {
        val had = synchronized(displayLock) { displayRequested.also { displayRequested = false } }
        if (!had) return
        runCatching { outbox?.trySend(HelperProtocol.encode(HelperProtocol.T_STOP_DISPLAY)) }
        closeVideo()
        (shared.value as? State.DisplayActive)?.let { set(State.Connected(it.helperPid, helperSdk)) }
    }

    /** Sends a TOUCH_STATE snapshot (display pixels). False (no-op) unless a display is active; never throws. */
    fun sendTouchState(contacts: List<HelperProtocol.TouchContact>): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_TOUCH_STATE, HelperProtocol.touchState(contacts)) }

    /** Sends KEY (helper injects DOWN+UP on the car display). False (no-op) unless a display is active; never throws. */
    fun sendKey(keycode: Int): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_KEY, HelperProtocol.key(keycode)) }

    /** v4: launches [target] ("pkg/cls" or "pkg") on the car display through the helper. False (no-op) unless a display is active; never throws. */
    fun launch(target: String, flags: Int = 0): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_LAUNCH, HelperProtocol.launch(target, flags)) }

    /** v4: asks the helper to bring CarLauncherActivity to the front of the car display. */
    fun goHome(): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_GO_HOME) }

    /** v4: requests the car display's tasks; the answer arrives via [onTasks] (or [onTasksFailed]). */
    fun listTasks(max: Int = 12): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_LIST_TASKS, HelperProtocol.listTasks(max)) }

    /** v4: brings task [taskId] to the front. */
    fun moveTaskToFront(taskId: Int): Boolean = sendInput { HelperProtocol.encode(HelperProtocol.T_MOVE_TASK_TO_FRONT, HelperProtocol.moveTaskToFront(taskId)) }


    private inline fun sendInput(frame: () -> ByteArray): Boolean {
        val box = outbox ?: return false
        if (shared.value !is State.DisplayActive) return false
        return try { box.trySend(frame()).isSuccess } catch (e: Throwable) { false }
    }

    fun panelTouch(displayId: Int, contacts: List<HelperProtocol.TouchContact>): Boolean =
        sendPanel(HelperProtocol.T_PANEL_TOUCH, displayId, HelperProtocol.touchState(contacts))
    fun panelLaunch(displayId: Int, target: String): Boolean = sendPanel(HelperProtocol.T_PANEL_LAUNCH, displayId, HelperProtocol.launch(target, 0))
    fun panelBack(displayId: Int): Boolean = sendPanel(HelperProtocol.T_PANEL_KEY, displayId, HelperProtocol.key(4))
    private fun sendPanel(type: Int, displayId: Int, payload: ByteArray): Boolean {
        if (!isConnected) return false
        return runCatching { outbox?.trySend(HelperProtocol.encode(type, HelperProtocol.panelCommand(displayId, payload)))?.isSuccess == true }.getOrDefault(false)
    }

    fun requestKeyFrame() {
        if (!displayRequested) return
        runCatching { outbox?.trySend(HelperProtocol.encode(HelperProtocol.T_REQUEST_KEYFRAME)) }
    }

    private fun closeVideo() { runCatching { videoSocket?.close() }; videoSocket = null }

    private fun displayLost(reason: String, fire: Boolean = true) {
        val had = synchronized(displayLock) { displayRequested.also { displayRequested = false } }
        if (!had) return
        closeVideo()
        (shared.value as? State.DisplayActive)?.let { set(State.Connected(it.helperPid, helperSdk)) }
        if (fire) { log("Helper: display lost ($reason)"); runCatching { onDisplayLost?.invoke(reason) } }
    }

    private enum class Result { NeverConnected, WasConnected, Rejected }

    private fun set(s: State) {
        val old = shared.value
        if (s == State.NotRunning || s == State.Outdated) nowPlaying = com.cardroid.lib.NowPlayingState()
        if (old != s) { shared.value = s; runCatching { log("Helper: ${label(s)}") } }
    }

    private suspend fun runSession(): Result {
        val s = Socket()
        socket = s
        var connected = false
        try {
            try {
                runInterruptible { s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), HelperProtocol.PORT), 1_000) }
            } catch (e: java.io.IOException) { set(State.NotRunning); return Result.NeverConnected }
            s.tcpNoDelay = true
            s.soTimeout = HelperProtocol.TIMEOUT_MS // handshake only; afterwards liveness is the PING watchdog
            val out = s.getOutputStream(); val input = s.getInputStream()
            try {
                HelperProtocol.write(out, HelperProtocol.T_HELLO, HelperProtocol.hello(apkVersionCode, HelperProtocol.ROLE_CONTROL))
                val ack = runInterruptible { HelperProtocol.read(input) }
                when (ack.type) {
                    HelperProtocol.T_HELLO_ACK -> {
                        val a = HelperProtocol.parseHelloAck(ack.payload)
                        if (a.protoVersion != HelperProtocol.PROTOCOL_VERSION) { set(State.Outdated); return Result.Rejected }
                        helperPid = a.helperPid; helperSdk = a.sdkInt
                        set(State.Connected(a.helperPid, a.sdkInt)); connected = true
                    }
                    HelperProtocol.T_BYE -> {
                        if (HelperProtocol.parseBye(ack.payload) == HelperProtocol.BYE_VERSION) set(State.Outdated) else set(State.NotRunning)
                        return Result.Rejected
                    }
                    else -> { set(State.NotRunning); return Result.Rejected }
                }
                s.soTimeout = 0
                val box = Channel<ByteArray>(Channel.UNLIMITED)
                val lastPong = AtomicLong(System.nanoTime())
                coroutineScope {
                    outbox = box; session = this
                    launch(Dispatchers.IO) {
                        try { for (frame in box) runInterruptible { out.write(frame); out.flush() } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { runCatching { s.close() } }
                    }
                    launch {
                        while (isActive) {
                            box.trySend(HelperProtocol.encode(HelperProtocol.T_PING))
                            delay(HelperProtocol.PING_INTERVAL_MS)
                            if (System.nanoTime() - lastPong.get() > HelperProtocol.TIMEOUT_MS * 1_000_000L) {
                                log("Helper: heartbeat timeout"); runCatching { s.close() }; break
                            }
                        }
                    }
                    try {
                        while (isActive) {
                            val f = runInterruptible { HelperProtocol.read(input) }
                            when (f.type) {
                                HelperProtocol.T_PONG -> lastPong.set(System.nanoTime())
                                HelperProtocol.T_MEDIA_STATE -> runCatching {
                                    val state = org.json.JSONObject(f.payload.toString(Charsets.UTF_8))
                                    val next = com.cardroid.lib.NowPlayingState(state.optString("title"), state.optString("artist"),
                                        state.optString("album"), state.optString("appName"), state.optLong("durationMs"),
                                        state.optLong("positionMs"), state.optInt("status"))
                                    if (next.title != nowPlaying.title || next.status != nowPlaying.status) {
                                        log("MediaState: app=${next.appName} title=${next.title} status=${next.status}")
                                    }
                                    nowPlaying = next
                                }.onFailure { log("MediaState: invalid snapshot: ${it.message}") }
                                HelperProtocol.T_BYE -> return@coroutineScope
                                HelperProtocol.T_DISPLAY_STARTED -> runCatching {
                                    val d = HelperProtocol.parseDisplayStarted(f.payload)
                                    if (displayRequested) {
                                        set(State.DisplayActive(d.displayId, helperPid))
                                        log("Helper: DISPLAY_STARTED id=${d.displayId} ${d.width}x${d.height}@${d.fps}")
                                        onDisplayStarted?.invoke(d.displayId)
                                    }
                                }
                                HelperProtocol.T_TASKS -> runCatching { HelperProtocol.parseTasks(f.payload) }
                                    .onSuccess { runCatching { onTasks?.invoke(it) } }.onFailure { log("Helper: bad TASKS: ${it.message}") }
                                HelperProtocol.T_DISPLAY_STOPPED -> displayLost("helper stopped display reason=${runCatching { HelperProtocol.parseDisplayStopped(f.payload) }.getOrNull()}")
                                HelperProtocol.T_ERROR -> {
                                    val e = runCatching { HelperProtocol.parseError(f.payload) }.getOrNull()
                                    log("Helper: error code=${e?.code} ${e?.message}")
                                    if (e?.code == HelperProtocol.E_TASKS_FAILED) runCatching { onTasksFailed?.invoke(e.message) }
                                    if (e?.code == HelperProtocol.E_LAUNCH_FAILED) runCatching { onLaunchFailed?.invoke(e.message) }
                                    // input/launcher errors (bad/failed touch, no display, launch or task failures) are not display loss
                                    if (e != null && e.code != HelperProtocol.E_UNKNOWN_TYPE && e.code != HelperProtocol.E_INPUT_FAILED &&
                                        e.code != HelperProtocol.E_NO_DISPLAY && e.code != HelperProtocol.E_LAUNCH_FAILED &&
                                        e.code != HelperProtocol.E_TASKS_FAILED) displayLost("helper error ${e.code}: ${e.message}")
                                }
                                else -> {}
                            }
                        }
                    } finally {
                        runCatching { s.close() }
                        outbox = null; session = null; box.close()
                        coroutineContext[Job]?.cancelChildren()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: SocketTimeoutException) { log("Helper: heartbeat timeout")
            } catch (e: Exception) { /* EOF/reset/protocol error: helper went away */ }
            set(State.NotRunning)
            return if (connected) Result.WasConnected else Result.NeverConnected
        } finally {
            outbox = null; session = null
            displayLost("control connection ended")
            runCatching { s.close() }; socket = null
        }
    }

    /** VIDEO connection for the requested display; EOF/error while the display is requested means the display is lost. */
    private suspend fun runVideo() {
        var v: Socket? = null
        try {
            var attempt = 0
            while (displayRequested) {
                val sock = Socket()
                try {
                    runInterruptible { sock.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), HelperProtocol.PORT), 1_000) }
                    sock.tcpNoDelay = true; sock.soTimeout = HelperProtocol.TIMEOUT_MS
                    HelperProtocol.write(sock.getOutputStream(), HelperProtocol.T_HELLO, HelperProtocol.hello(apkVersionCode, HelperProtocol.ROLE_VIDEO))
                    val input = sock.getInputStream()
                    val ack = runInterruptible { HelperProtocol.read(input) }
                    if (ack.type == HelperProtocol.T_BYE && attempt++ < 3) { runCatching { sock.close() }; delay(200); continue }
                    check(ack.type == HelperProtocol.T_HELLO_ACK) { "VIDEO rejected (type ${ack.type} reason ${HelperProtocol.parseBye(ack.payload)})" }
                    sock.soTimeout = 0
                    v = sock; videoSocket = sock
                    if (!displayRequested) return
                    val max = HelperProtocol.MAX_PAYLOAD_VIDEO
                    while (displayRequested) {
                        val f = runInterruptible { HelperProtocol.read(input, max) }
                        when (f.type) {
                            HelperProtocol.T_VIDEO_CONFIG -> HelperProtocol.parseVideoConfig(f.payload).let { onVideoConfig?.invoke(it.sps, it.pps) }
                            HelperProtocol.T_VIDEO_FRAME -> HelperProtocol.parseVideoFrame(f.payload).let { onVideoFrame?.invoke(it.data, it.ptsUs) }
                            HelperProtocol.T_BYE -> throw java.io.EOFException("VIDEO bye")
                        }
                    }
                    return
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    runCatching { sock.close() }
                    if (displayRequested) displayLost("video connection ${e.javaClass.simpleName}: ${e.message}")
                    return
                }
            }
        } finally { if (videoSocket === v) videoSocket = null; runCatching { v?.close() } }
    }
}
