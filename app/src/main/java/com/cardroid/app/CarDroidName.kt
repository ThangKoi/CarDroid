package com.cardroid.app

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Read the configured phone name, rather than the Bluetooth alias we temporarily advertise. */
object CarDroidName {
    fun forPhone(context: Context): String {
        val phone = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL.trim().ifEmpty { "Android" }
        return if (phone.endsWith(" - CarDroid")) phone else "$phone - CarDroid"
    }
}
