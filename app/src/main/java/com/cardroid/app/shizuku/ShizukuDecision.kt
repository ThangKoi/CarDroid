package com.cardroid.app.shizuku

/** Pure state/latch rules used by the Android starter and covered by JVM tests. */
object ShizukuDecision {
    enum class PermissionState {
        UNAVAILABLE,
        UNSUPPORTED,
        NEEDS_PERMISSION,
        PERMISSION_DENIED,
        READY,
    }

    fun permissionState(
        binderLive: Boolean,
        preV11: Boolean,
        permissionGranted: Boolean,
        showRationale: Boolean,
    ): PermissionState = when {
        !binderLive -> PermissionState.UNAVAILABLE
        preV11 -> PermissionState.UNSUPPORTED
        permissionGranted -> PermissionState.READY
        showRationale -> PermissionState.PERMISSION_DENIED
        else -> PermissionState.NEEDS_PERMISSION
    }

    fun mayAutoStart(
        settingEnabled: Boolean,
        attemptConsumed: Boolean,
        manualSuppressed: Boolean,
        starterReady: Boolean,
        helperNotRunning: Boolean,
    ): Boolean = settingEnabled && !attemptConsumed && !manualSuppressed && starterReady && helperNotRunning

    /** Single-flight plus post-success cooldown for start requests (manual and auto alike). */
    fun mayStart(nowMs: Long, cooldownUntilMs: Long, inFlight: Boolean): Boolean =
        !inFlight && nowMs >= cooldownUntilMs

    /** Rate limit for the on-demand real-process probe (each probe binds a UserService). */
    fun mayRefreshRunning(nowMs: Long, lastRefreshMs: Long, minIntervalMs: Long, inFlight: Boolean): Boolean =
        !inFlight && (lastRefreshMs <= 0L || nowMs - lastRefreshMs >= minIntervalMs)
}
