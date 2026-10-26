package com.cardroid.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Starts Bluetooth listening without a tap: after reboot, after an app update
 * (each `adb install -r`), and when the app opens.  Both broadcasts are
 * exempt from the background foreground-service start restriction, and
 * CarDroidService calls startForeground at once in ACTION_START_BT.
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> startBluetoothIfAllowed(context, "boot")
            Intent.ACTION_MY_PACKAGE_REPLACED -> startBluetoothIfAllowed(context, "app updated")
        }
    }

    companion object {
        fun startBluetoothIfAllowed(context: Context, reason: String) {
            CarDroidSettings.initialize(context.applicationContext)
            CarDroidLog.attachFile(context)
            if (!CarDroidSettings.autoStartBluetooth.value) return
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                CarDroidLog.log("AutoStart: skipped ($reason) -- Bluetooth permission not granted yet")
                return
            }
            CarDroidLog.log("AutoStart: starting Bluetooth ($reason)")
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CarDroidService::class.java).setAction(CarDroidService.ACTION_START_BT),
                )
            }.onFailure { CarDroidLog.log("AutoStart: failed ($reason): ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
