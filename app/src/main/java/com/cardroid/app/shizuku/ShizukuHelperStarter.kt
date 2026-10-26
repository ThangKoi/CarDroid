package com.cardroid.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Looper
import androidx.annotation.MainThread
import com.cardroid.app.CarDroidLog
import com.cardroid.app.CarDroidSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** App-side Shizuku state, permission, and one-shot UserService orchestration. */
class ShizukuHelperStarter private constructor(context: Context) {

    sealed class State {
        data object Unavailable : State()
        data object Unsupported : State()
        data object NeedsPermission : State()
        data object PermissionDenied : State()
        data object Ready : State()
        data object Starting : State()
        data class Started(val pid: Int) : State()
        data class Error(val message: String) : State()
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state

    /** Manual Start/Stop actions consume the current CarDroidService auto-start opportunity. */
    private val _manualActions = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val manualActions: SharedFlow<Unit> = _manualActions

    /** Real helper process state via Shizuku; null = unknown (Shizuku unavailable or not probed). */
    private val _helperRunning = MutableStateFlow<Boolean?>(null)
    val helperRunning: StateFlow<Boolean?> = _helperRunning

    @Volatile private var startCooldownUntil = 0L
    @Volatile private var lastRefreshAt = 0L
    private val refreshInFlight = AtomicBoolean(false)
    private val operationInFlight = AtomicBoolean(false)
    private val manualActionCounter = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var initialized = false

    /** Monotonic generation used by CarDroidService to suppress a pending auto-start race. */
    val manualActionGeneration: Long get() = manualActionCounter.get()

    private val permissionListener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == REQUEST_CODE) recomputeState()
        }
    }

    init {
        CarDroidSettings.initialize(appContext)
    }

    /** Safe to call from every Activity/Service instance; listeners are installed once. */
    @MainThread
    fun initialize() {
        if (initialized) {
            recomputeState()
            return
        }
        initialized = true
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListener { recomputeState() }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                _state.value = State.Unavailable
                CarDroidLog.log("Shizuku: binder died; helper status remains independent")
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure {
            _state.value = State.Error("Shizuku listener setup failed: ${it.message ?: it.javaClass.simpleName}")
        }
        recomputeState()
    }

    /** Request only from the explicit NeedsPermission UI state; never loops on permanent denial. */
    fun requestPermission() {
        initializeIfNeeded()
        if (_state.value !is State.NeedsPermission) return
        runCatching { rikka.shizuku.Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { _state.value = State.Error("Shizuku permission request failed: ${message(it)}") }
    }

    /** Starts the detached helper. [auto] is used only by CarDroidService's one-shot hook. */
    fun start(auto: Boolean = false) {
        initializeIfNeeded()
        if (!auto) {
            manualActionCounter.incrementAndGet()
            _manualActions.tryEmit(Unit)
        }
        if (!ShizukuDecision.mayStart(android.os.SystemClock.elapsedRealtime(), startCooldownUntil, operationInFlight.get())) {
            CarDroidLog.log("Shizuku: start ignored (in flight or cooldown)")
            return
        }
        if (!beginOperation()) return
        _state.value = State.Starting
        scope.launchOperation {
            val identity = packageIdentity()
            CarDroidLog.log(
                "Shizuku: start package=${appContext.packageName} apk=${identity.apkPath} " +
                    "appUid=${identity.appUid} version=${identity.versionCode} shizukuUid=${shizukuUid()}"
            )
            val pid = withLauncher { it.startHelper(identity.apkPath, identity.appUid, HelperLauncherCommand.DEFAULT_PORT) }
            if (pid <= 0) error("UserService did not return a helper PID")
            startCooldownUntil = android.os.SystemClock.elapsedRealtime() + START_COOLDOWN_MS
            _helperRunning.value = true
            _state.value = State.Started(pid)
            CarDroidLog.log("Shizuku: helper start requested pid=$pid")
        }
    }

    /** Stops only the verified cardroid-helper PID(s) discovered by the UserService. */
    fun stop(auto: Boolean = false) {
        initializeIfNeeded()
        if (!auto) {
            manualActionCounter.incrementAndGet()
            _manualActions.tryEmit(Unit)
        }
        if (!beginOperation()) return
        startCooldownUntil = 0L
        _state.value = State.Starting
        scope.launchOperation {
            val stopped = withLauncher { it.stopHelper() }
            if (!stopped) error("helper PID remained after TERM")
            _helperRunning.value = false
            _state.value = State.Ready
            CarDroidLog.log("Shizuku: helper stopped")
        }
    }

    /** Stop then start under one in-flight latch for Outdated recovery. */
    fun restart() {
        initializeIfNeeded()
        manualActionCounter.incrementAndGet()
        _manualActions.tryEmit(Unit)
        if (!beginOperation()) return
        startCooldownUntil = 0L
        _state.value = State.Starting
        scope.launchOperation {
            if (!withLauncher { it.stopHelper() }) error("helper PID remained after TERM")
            val identity = packageIdentity()
            val pid = withLauncher { it.startHelper(identity.apkPath, identity.appUid, HelperLauncherCommand.DEFAULT_PORT) }
            if (pid <= 0) error("UserService did not return a helper PID")
            startCooldownUntil = android.os.SystemClock.elapsedRealtime() + START_COOLDOWN_MS
            _helperRunning.value = true
            _state.value = State.Started(pid)
            CarDroidLog.log("Shizuku: helper restarted pid=$pid")
        }
    }

    /**
     * On-demand probe of the real cardroid-helper process (independent of HelperClient).
     * Rate-limited and single-flight because each probe binds a short-lived UserService.
     */
    fun refreshHelperRunning() {
        initializeIfNeeded()
        val now = android.os.SystemClock.elapsedRealtime()
        if (_state.value !is State.Ready && _state.value !is State.Started) return
        if (operationInFlight.get()) return
        if (!ShizukuDecision.mayRefreshRunning(now, lastRefreshAt, REFRESH_MIN_INTERVAL_MS, refreshInFlight.get())) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        lastRefreshAt = now
        scope.launch(Dispatchers.IO) {
            try {
                _helperRunning.value = withLauncher { it.isHelperRunning() }
            } catch (c: CancellationException) {
                if (c !is TimeoutCancellationException) throw c
            } catch (t: Throwable) {
                CarDroidLog.log("Shizuku: helper probe failed ${message(t)}")
            } finally {
                lastRefreshAt = android.os.SystemClock.elapsedRealtime()
                refreshInFlight.set(false)
            }
        }
    }

    /** Launch the installed Shizuku app, or its release page when it is not installed. */
    fun openShizuku(context: Context = appContext) {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        }.getOrNull()
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASE_URL))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { CarDroidLog.log("Shizuku: open action failed ${message(it)}") }
    }

    private fun initializeIfNeeded() {
        if (Looper.myLooper() == Looper.getMainLooper()) initialize()
        else scope.launch { initialize() }
    }

    private fun recomputeState() {
        val next = runCatching {
            // The permission helpers call through Shizuku's binder and throw when
            // the service is absent. Probe availability first so the UI reports
            // Unavailable instead of turning a normal binder absence into Error.
            val binderLive = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
            if (!binderLive) return@runCatching State.Unavailable
            val preV11 = rikka.shizuku.Shizuku.isPreV11()
            if (preV11) return@runCatching State.Unsupported
            val decision = ShizukuDecision.permissionState(
                binderLive = true,
                preV11 = false,
                permissionGranted = rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED,
                showRationale = rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale(),
            )
            when (decision) {
                ShizukuDecision.PermissionState.UNAVAILABLE -> State.Unavailable
                ShizukuDecision.PermissionState.UNSUPPORTED -> State.Unsupported
                ShizukuDecision.PermissionState.PERMISSION_DENIED -> State.PermissionDenied
                ShizukuDecision.PermissionState.NEEDS_PERMISSION -> State.NeedsPermission
                ShizukuDecision.PermissionState.READY -> {
                // Keep operation progress visible until the remote call finishes.
                if (_state.value is State.Starting || _state.value is State.Started) _state.value
                else State.Ready
                }
            }
        }.getOrElse { State.Error("Shizuku state unavailable: ${message(it)}") }
        _state.value = next
    }

    private fun beginOperation(): Boolean = operationInFlight.compareAndSet(false, true)

    private data class PackageIdentity(val apkPath: String, val appUid: Int, val versionCode: Int)

    @Suppress("DEPRECATION")
    private fun packageIdentity(): PackageIdentity {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val apk = info.applicationInfo?.sourceDir ?: error("installed base APK path is unavailable")
        val uid = info.applicationInfo?.uid ?: -1
        val version = info.versionCode
        val validation = HelperLauncherCommand.validate(apk, uid, HelperLauncherCommand.DEFAULT_PORT)
        if (validation != null) error(validation)
        return PackageIdentity(apk, uid, version)
    }

    private fun shizukuUid(): Int = runCatching { rikka.shizuku.Shizuku.getUid() }.getOrDefault(-1)

    private suspend fun <T> withLauncher(block: suspend (IHelperLauncher) -> T): T {
        if (!runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)) {
            error("Shizuku binder is not running")
        }
        val args = userServiceArgs()
        var connection: ServiceConnection? = null
        var remote: IHelperLauncher? = null
        try {
            val launcher = withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IHelperLauncher> { continuation ->
                    val callbackConsumed = AtomicBoolean(false)
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (!continuation.isActive || !callbackConsumed.compareAndSet(false, true)) return
                            runCatching { IHelperLauncher.Stub.asInterface(service) }
                                .onSuccess { continuation.resume(it) }
                                .onFailure { continuation.resumeWithException(it) }
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive && callbackConsumed.compareAndSet(false, true)) continuation.resumeWithException(
                                IllegalStateException("Shizuku UserService disconnected")
                            )
                        }
                    }
                    connection = c
                    scope.launch(Dispatchers.Main.immediate) {
                        runCatching { rikka.shizuku.Shizuku.bindUserService(args, c) }
                            .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                    }
                    continuation.invokeOnCancellation {
                        // The finally block below performs the remove=true cleanup when possible.
                    }
                }
            }
            remote = launcher
            return block(launcher)
        } finally {
            val c = connection
            if (c != null) {
                // Cancellation (including a bind timeout) must not skip the remove=true
                // teardown. Keep the cleanup non-cancellable while returning to Shizuku's
                // required main-thread API surface.
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main.immediate) {
                        val removed = runCatching { rikka.shizuku.Shizuku.unbindUserService(args, c, true) }
                        removed.onFailure {
                            CarDroidLog.log("Shizuku: UserService remove failed ${message(it)}; requesting direct destroy")
                            // The documented remove=true path should invoke destroy(). If the
                            // server rejects that path, make one best-effort direct destroy call
                            // so a launcher process cannot linger indefinitely.
                            runCatching { remote?.destroy() }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun userServiceArgs(): rikka.shizuku.Shizuku.UserServiceArgs {
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode
        }.getOrDefault(1)
        return rikka.shizuku.Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, HelperLauncherService::class.java.name)
        ).processNameSuffix("helper-launcher")
            .tag("cardroid-helper-launcher")
            .version(version)
            .daemon(false)
    }

    private fun message(t: Throwable): String = t.message ?: t.javaClass.simpleName

    private fun CoroutineScope.launchOperation(block: suspend () -> Unit) = launch(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            // withTimeout uses cancellation internally; expose a bounded bind timeout
            // to the UI instead of leaving the operation stuck in Starting forever.
            if (cancelled is TimeoutCancellationException) {
                _state.value = State.Error("Shizuku UserService bind timed out")
                CarDroidLog.log("Shizuku: operation timed out")
            } else {
                throw cancelled
            }
        } catch (t: Throwable) {
            _state.value = State.Error(message(t))
            CarDroidLog.log("Shizuku: operation failed ${message(t)}")
        } finally {
            operationInFlight.set(false)
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_RELEASE_URL = "https://github.com/RikkaApps/Shizuku/releases"
        const val REQUEST_CODE = 27185
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val START_COOLDOWN_MS = 10_000L
        private const val REFRESH_MIN_INTERVAL_MS = 3_000L

        @Volatile private var instance: ShizukuHelperStarter? = null

        fun get(context: Context): ShizukuHelperStarter =
            instance ?: synchronized(this) {
                instance ?: ShizukuHelperStarter(context.applicationContext).also { instance = it }
            }
    }
}
