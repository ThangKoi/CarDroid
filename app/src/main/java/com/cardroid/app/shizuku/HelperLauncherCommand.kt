package com.cardroid.app.shizuku

/**
 * Pure validation and command helpers shared by the shell UserService and JVM tests.
 * Keep this class free of Android APIs so malformed command inputs can be tested on the host.
 */
object HelperLauncherCommand {
    const val HELPER_NAME = "cardroid-helper"
    const val LOG_PATH = "/data/local/tmp/cardroid-helper.log"
    const val DEFAULT_PORT = 27185
    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535
    private val APK_PATH = Regex("[A-Za-z0-9/_=.~+\\-]+")

    fun validateApkPath(path: String): String? = when {
        path.isBlank() -> "APK path is empty"
        !path.endsWith("/base.apk") -> "APK path must end with /base.apk"
        !APK_PATH.matches(path) -> "APK path contains unsupported characters"
        else -> null
    }

    fun validateAppUid(appUid: Int): String? =
        if (appUid > 0) null else "CarDroid application UID must be positive"

    fun validatePort(port: Int): String? =
        if (port in MIN_PORT..MAX_PORT) null else "port must be in $MIN_PORT..$MAX_PORT"

    fun validate(apkPath: String, appUid: Int, port: Int): String? =
        validateApkPath(apkPath) ?: validateAppUid(appUid) ?: validatePort(port)

    /**
     * Build the exact command used by the adb fallback. Every interpolated value has
     * already passed the strict validators above, so shell metacharacters cannot enter it.
     */
    fun startShellCommand(apkPath: String, appUid: Int, port: Int): String {
        require(validate(apkPath, appUid, port) == null) { validate(apkPath, appUid, port)!! }
        return "CLASSPATH=$apkPath setsid nohup app_process /system/bin " +
            "--nice-name=$HELPER_NAME com.cardroid.app.helper.HelperMain " +
            "--port=$port --app-uid=$appUid >$LOG_PATH 2>&1 </dev/null &"
    }

    /** pidof may separate PIDs with spaces or newlines; reject the whole result if any token is malformed. */
    fun parsePids(output: String): List<Int> {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return emptyList()
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.any { it.isEmpty() || !it.all(Char::isDigit) }) return emptyList()
        val parsed = tokens.mapNotNull { it.toIntOrNull() }
        if (parsed.size != tokens.size || parsed.any { it <= 0 }) return emptyList()
        return parsed.distinct()
    }
}
