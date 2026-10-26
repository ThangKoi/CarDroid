package com.cardroid.app.helper.display

import com.cardroid.app.helper.HelperProtocol.TouchContact
import com.cardroid.app.helper.activity.ActivityLauncher
import com.cardroid.app.helper.activity.TaskManager
import com.cardroid.app.helper.input.InputInjector
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Socketless diagnostic kept from the Phase 2 step-0 gate. Run as shell:
 *   CLASSPATH=<base.apk> app_process /system/bin --nice-name=cardroid-selftest com.cardroid.app.helper.HelperMain --self-test=1280x720@30:160
 * Creates the car display + encoder, writes the Annex-B stream to /data/local/tmp/cardroid-selftest.h264, logs counts every 10 s, stays alive until killed.
 */
object SelfTest {
    const val OUTPUT = "/data/local/tmp/cardroid-selftest.h264"

    fun run(spec: String, log: (String) -> Unit) {
        val m = Regex("(\\d+)x(\\d+)@(\\d+):(\\d+)").matchEntire(spec) ?: run { log("bad --self-test spec '$spec'"); exitProcess(2) }
        val (w, h, fps, dpi) = m.destructured
        val out = FileOutputStream(OUTPUT)
        var configs = 0; var frames = 0; var keys = 0; var bytes = 0L
        val sink = object : DisplayEncoder.Sink {
            override fun onConfig(sps: ByteArray, pps: ByteArray) {
                synchronized(out) { val sc = byteArrayOf(0, 0, 0, 1); out.write(sc); out.write(sps); out.write(sc); out.write(pps) }
                configs++; log("selftest: config sps=${sps.size} pps=${pps.size}")
            }
            override fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
                synchronized(out) { out.write(data) }
                frames++; bytes += data.size; if (keyFrame) keys++
                if (frames <= 3 || frames % 100 == 0) log("selftest: frame#$frames key=$keyFrame size=${data.size} pts=$ptsUs keys=$keys bytes=$bytes")
            }
            override fun onFailure(reason: String) { log("selftest: encoder failure $reason") }
        }
        try {
            val d = HelperDisplay(w.toInt(), h.toInt(), dpi.toInt(), fps.toInt(), 4_000_000, log, sink)
            val id = d.start()
            log("selftest: display id=$id fps=${d.activeFps}; running")
            while (true) { Thread.sleep(10_000); log("selftest: alive configs=$configs frames=$frames keys=$keys") }
        } catch (t: Throwable) { log("selftest: FAILED ${t.javaClass.name}: ${t.message}"); t.printStackTrace(); exitProcess(5) }
    }

    const val CMD_FILE = "/data/local/tmp/cardroid-selftest-cmd"

    /**
     * `--self-test-input=<w>x<h>@<fps>:<dpi>`: creates the car display (frames discarded) and injects input on it, driven by command files
     * so an adb operator can interleave `am start --display <id>`, scrcpy frame grabs and `dumpsys input`. Usage: write a line into
     * [CMD_FILE] (e.g. `adb shell "echo 'tap 640 360' > /data/local/tmp/cardroid-selftest-cmd"`); the helper consumes and deletes it, logs
     * `selftest-input: ...` per step. Commands: `tap x y`, `drag x1 y1 x2 y2`, `pinch`, `home`, `back`, `key <code>`, `quit`; v4 adds `launch <pkg[/cls]>`, `gohome` (CarLauncherActivity), `tasks [max]`, `move <taskId>`; an empty file
     * runs the next scripted step: tap centre, drag-scroll, HOME, BACK, tap the omnibox region (centre-x, 12% of height).
     */
    fun runInput(spec: String, log: (String) -> Unit) {
        val m = Regex("(\\d+)x(\\d+)@(\\d+):(\\d+)").matchEntire(spec) ?: run { log("bad --self-test-input spec '$spec'"); exitProcess(2) }
        val (w, h, fps, dpi) = m.destructured
        val width = w.toInt(); val height = h.toInt()
        val sink = object : DisplayEncoder.Sink {
            override fun onConfig(sps: ByteArray, pps: ByteArray) {}
            override fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {}
            override fun onFailure(reason: String) { log("selftest-input: encoder failure $reason") }
        }
        try {
            val d = HelperDisplay(width, height, dpi.toInt(), fps.toInt(), 4_000_000, log, sink)
            val id = d.start()
            val inj = InputInjector(log)
            val launcher = ActivityLauncher(log); val taskManager = TaskManager(log)
            File(CMD_FILE).delete()
            log("selftest-input: display id=$id ${width}x$height; waiting for commands in $CMD_FILE")
            val script = listOf("tap ${width / 2} ${height / 2}", "drag ${width / 2} ${height * 7 / 10} ${width / 2} ${height * 3 / 10}", "home", "back", "tap ${width / 2} ${height * 12 / 100}")
            var next = 0
            while (true) {
                val f = File(CMD_FILE)
                if (!f.exists()) { Thread.sleep(200); continue }
                Thread.sleep(100)
                val text = f.readText().trim(); f.delete()
                val line = if (text.isEmpty()) script.getOrNull(next++) else text
                if (line == null) { log("selftest-input: script finished"); continue }
                log("selftest-input: step '$line'")
                try {
                    val a = line.split(Regex("\\s+"))
                    fun n(i: Int) = a[i].toInt()
                    when (a[0]) {
                        "tap" -> tap(inj, id, n(1), n(2))
                        "drag" -> drag(inj, id, n(1), n(2), n(3), n(4))
                        "pinch" -> pinch(inj, id, width / 2, height / 2)
                        "home" -> inj.key(id, 3)
                        "back" -> inj.key(id, 4)
                        "key" -> inj.key(id, n(1))
                        "launch" -> launcher.launch(id, a[1])
                        "gohome" -> launcher.goHome(id)
                        "tasks" -> taskManager.list(id, if (a.size > 1) n(1) else 12).forEach { log("selftest-input: task ${it.taskId} ${it.pkg} ${it.component}") }
                        "move" -> taskManager.moveToFront(n(1))
                        "quit" -> { inj.releaseAll(id); d.release(); log("selftest-input: done"); exitProcess(0) }
                        else -> log("selftest-input: unknown command '${a[0]}'")
                    }
                    log("selftest-input: ok")
                } catch (t: Throwable) { log("selftest-input: step FAILED ${t.javaClass.name}: ${t.message}"); t.printStackTrace() }
            }
        } catch (t: Throwable) { log("selftest-input: FAILED ${t.javaClass.name}: ${t.message}"); t.printStackTrace(); exitProcess(5) }
    }

    private fun c(id: Int, x: Int, y: Int, down: Boolean = true) = TouchContact(id, down, x, y)

    private fun tap(inj: InputInjector, display: Int, x: Int, y: Int) {
        inj.touch(display, listOf(c(0, x, y))); Thread.sleep(80); inj.touch(display, listOf(c(0, x, y, false)))
    }

    private fun drag(inj: InputInjector, display: Int, x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 15) {
        inj.touch(display, listOf(c(0, x1, y1)))
        for (i in 1..steps) { Thread.sleep(16); inj.touch(display, listOf(c(0, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps))) }
        inj.touch(display, listOf(c(0, x2, y2, false)))
    }

    private fun pinch(inj: InputInjector, display: Int, cx: Int, cy: Int) {
        inj.touch(display, listOf(c(0, cx - 40, cy), c(1, cx + 40, cy)))
        for (i in 1..10) { Thread.sleep(16); inj.touch(display, listOf(c(0, cx - 40 - i * 20, cy), c(1, cx + 40 + i * 20, cy))) }
        inj.touch(display, listOf(c(0, 0, 0, false), c(1, 0, 0, false)))
    }
}
