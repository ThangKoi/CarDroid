package com.cardroid.app.shizuku

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService implementation. Shizuku constructs this class in a shell/root
 * app_process, so it deliberately has no Context dependency and only exposes the narrow
 * AIDL surface needed by the app process.
 */
class HelperLauncherService : IHelperLauncher.Stub() {

    private val lock = Any()

    override fun startHelper(apkPath: String?, appUid: Int, port: Int): Int = synchronized(lock) {
        val path = apkPath.orEmpty()
        val validation = HelperLauncherCommand.validate(path, appUid, port)
        if (validation != null) {
            log("start rejected: $validation")
            return@synchronized -1
        }
        val identityValidation = installedUidValidation(appUid)
        if (identityValidation != null) {
            log("start rejected: $identityValidation")
            return@synchronized -1
        }

        val existing = helperPids()
        if (existing.isNotEmpty()) {
            val pid = existing.first()
            log("helper already running pid=$pid")
            return@synchronized pid
        }

        val command = HelperLauncherCommand.startShellCommand(path, appUid, port)
        val result = runProcess(
            listOf("/system/bin/sh", "-c", command),
            timeoutMs = START_COMMAND_TIMEOUT_MS,
        )
        if (!result.completed || result.exitCode != 0) {
            log("helper command failed completed=${result.completed} exit=${result.exitCode}")
            return@synchronized -1
        }

        val deadline = SystemClock.elapsedRealtime() + START_PID_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val pid = helperPids().firstOrNull()
            if (pid != null) {
                log("helper started pid=$pid uid=${android.os.Process.myUid()} port=$port appUid=$appUid")
                return@synchronized pid
            }
            SystemClock.sleep(PID_POLL_MS)
        }
        log("helper command exited but pidof did not find ${HelperLauncherCommand.HELPER_NAME}")
        -1
    }

    override fun stopHelper(): Boolean = synchronized(lock) {
        val pids = helperPids()
        if (pids.isEmpty()) return@synchronized true

        var signalled = false
        for (pid in pids) {
            val cmdline = readCmdline(pid)
            if (cmdline != null && !isHelperCmdline(cmdline)) {
                log("skip pid=$pid; cmdline did not identify helper")
                continue
            }
            val result = runProcess(
                listOf("/system/bin/kill", "-TERM", pid.toString()),
                timeoutMs = KILL_TIMEOUT_MS,
            )
            signalled = signalled || (result.completed && result.exitCode == 0)
            log("TERM pid=$pid completed=${result.completed} exit=${result.exitCode}")
        }

        val deadline = SystemClock.elapsedRealtime() + STOP_WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (helperPids().isEmpty()) return@synchronized true
            SystemClock.sleep(PID_POLL_MS)
        }
        val remaining = helperPids()
        log("helper still running after TERM signalled=$signalled pids=$remaining")
        false
    }

    override fun isHelperRunning(): Boolean = synchronized(lock) {
        helperPids().isNotEmpty()
    }

    /** Reserved Shizuku destroy transaction. The remove=true operation calls this callback. */
    override fun destroy() {
        log("UserService destroy")
        System.exit(0)
    }

    private fun helperPids(): List<Int> {
        val result = runProcess(
            listOf("/system/bin/pidof", HelperLauncherCommand.HELPER_NAME),
            timeoutMs = PID_COMMAND_TIMEOUT_MS,
        )
        if (!result.completed || result.exitCode != 0) return emptyList()
        val pids = HelperLauncherCommand.parsePids(result.output)
        if (result.output.isNotBlank() && pids.isEmpty()) {
            log("pidof returned non-numeric output")
        }
        return pids
    }

    /** Shell can read /proc on supported targets; an unavailable cmdline remains a bounded fallback. */
    private fun readCmdline(pid: Int): String? = runCatching {
        File("/proc/$pid/cmdline").inputStream().use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun isHelperCmdline(cmdline: String): Boolean {
        val first = cmdline.split('\u0000', ' ', '\t', '\n').firstOrNull().orEmpty()
        return first == HelperLauncherCommand.HELPER_NAME
    }

    /** Re-check the installed package UID inside the privileged process before forming a command. */
    private fun installedUidValidation(appUid: Int): String? {
        val result = runProcess(
            listOf("/system/bin/cmd", "package", "list", "packages", "-U", CARDROID_PACKAGE),
            timeoutMs = UID_COMMAND_TIMEOUT_MS,
        )
        if (!result.completed || result.exitCode != 0) return "cannot resolve installed CarDroid UID"
        val lines = result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val match = lines.singleOrNull()?.let {
            Regex("^package:${Regex.escape(CARDROID_PACKAGE)}\\s+uid:(\\d+)$").matchEntire(it)
        } ?: return "package UID output is invalid"
        val installedUid = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            ?: return "package UID output is invalid"
        return if (installedUid == appUid) null
        else "app UID $appUid does not match installed CarDroid UID $installedUid"
    }

    private data class ProcessResult(val completed: Boolean, val exitCode: Int, val output: String)

    private fun runProcess(command: List<String>, timeoutMs: Long): ProcessResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            log("exec ${command.firstOrNull()} failed: ${t.javaClass.simpleName}: ${t.message}")
            return ProcessResult(completed = true, exitCode = -1, output = "")
        }
        val completed = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!completed) {
            runCatching { process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            return ProcessResult(completed = false, exitCode = -1, output = "")
        }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        return ProcessResult(completed, exit, output)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "CarDroidHelperLauncher"
        private const val START_COMMAND_TIMEOUT_MS = 5_000L
        private const val START_PID_TIMEOUT_MS = 5_000L
        private const val PID_COMMAND_TIMEOUT_MS = 500L
        private const val UID_COMMAND_TIMEOUT_MS = 1_000L
        private const val KILL_TIMEOUT_MS = 1_000L
        private const val STOP_WAIT_TIMEOUT_MS = 1_000L
        private const val PID_POLL_MS = 50L
        private const val CARDROID_PACKAGE = "com.cardroid.app"
    }
}
