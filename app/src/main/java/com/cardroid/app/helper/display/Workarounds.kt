package com.cardroid.app.helper.display

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Port of scrcpy's Workarounds (Apache-2.0, Genymobile/scrcpy): lets app_process code obtain a system Context
 * (ActivityThread bootstrap) so DisplayManager and friends work from the shell UID. Every step is best-effort.
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
object Workarounds {
    private var activityThread: Any? = null
    private var activityThreadClass: Class<*>? = null
    private var applied = false

    @Synchronized fun apply(log: (String) -> Unit) {
        if (applied) return
        applied = true
        // app_process skips the normal media-module bootstrap performed for apps.
        if (Build.VERSION.SDK_INT >= 31) step(log, "media service manager") {
            val initializer = Class.forName("android.media.MediaFrameworkPlatformInitializer")
            if (initializer.getMethod("getMediaServiceManager").invoke(null) == null) {
                val manager = Class.forName("android.media.MediaServiceManager")
                initializer.getMethod("setMediaServiceManager", manager)
                    .invoke(null, manager.getDeclaredConstructor().newInstance())
            }
        }
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
            val at = ctor.newInstance()
            cls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)
            cls.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true)
            activityThread = at; activityThreadClass = cls
        } catch (t: Throwable) { log("Workarounds: ActivityThread bootstrap failed ${t.javaClass.simpleName}: ${t.message} cause=${t.cause}"); return }
        if (Build.VERSION.SDK_INT >= 31) step(log, "configuration controller") {
            val cc = Class.forName("android.app.ConfigurationController")
            val internal = Class.forName("android.app.ActivityThreadInternal")
            val c = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(activityThread)
            activityThreadClass!!.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(activityThread, c)
        }
        if (!Build.BRAND.equals("ONYX", ignoreCase = true)) step(log, "app info") {
            val bd = Class.forName("android.app.ActivityThread\$AppBindData")
            val data = bd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val info = ApplicationInfo().apply { packageName = FakeContext.PACKAGE_NAME }
            bd.getDeclaredField("appInfo").apply { isAccessible = true }.set(data, info)
            activityThreadClass!!.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, data)
        }
        step(log, "app context") {
            val app = Instrumentation.newApplication(Application::class.java, FakeContext.get(log))
            activityThreadClass!!.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, app)
        }
    }

    private inline fun step(log: (String) -> Unit, name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("Workarounds: $name skipped (${t.javaClass.simpleName}: ${t.message})") }
    }

    fun systemContext(): Context? = try {
        activityThreadClass!!.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(activityThread) as Context
    } catch (t: Throwable) { null }
}
