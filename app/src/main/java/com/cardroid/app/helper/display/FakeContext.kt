package com.cardroid.app.helper.display

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/** Port of scrcpy's FakeContext (Apache-2.0, Genymobile/scrcpy): a Context posing as com.android.shell. */
class FakeContext private constructor(base: Context) : ContextWrapper(base) {
    companion object {
        const val PACKAGE_NAME = "com.android.shell"
        @Volatile private var instance: FakeContext? = null
        @Synchronized fun get(log: (String) -> Unit = {}): FakeContext {
            instance?.let { return it }
            Workarounds.apply(log)
            val base = Workarounds.systemContext() ?: error("no system context (Workarounds failed)")
            return FakeContext(base).also { instance = it }
        }
    }

    override fun getPackageName(): String = PACKAGE_NAME
    override fun getOpPackageName(): String = PACKAGE_NAME
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build()
    override fun getDeviceId(): Int = 0
    override fun getApplicationContext(): Context = this
    override fun createPackageContext(packageName: String?, flags: Int): Context = this

    @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        if (name == Context.CLIPBOARD_SERVICE || name == "semclipboard" || name == Context.ACTIVITY_SERVICE) {
            try { service.javaClass.getDeclaredField("mContext").apply { isAccessible = true }.set(service, this) } catch (_: Throwable) {}
        }
        return service
    }
}
