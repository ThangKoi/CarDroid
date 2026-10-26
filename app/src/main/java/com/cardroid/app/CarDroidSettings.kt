package com.cardroid.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime-toggleable knobs surfaced in the UI so we can A/B test wire
 * variants on the real BMW without rebuilding the APK.
 *
 * Read from anywhere; flipped from MainActivity. Values are read at each
 * AirPlay handshake attempt, so changes take effect on the next attempt.
 */
object CarDroidSettings {

    private const val PREFS = "cardroid.settings"
    private const val KEY_AUTO_START_HELPER = "autoStartHelperViaShizuku"
    private const val KEY_AUTO_START_BLUETOOTH = "autoStartBluetooth"
    private var preferences: android.content.SharedPreferences? = null

    private val _autoStartHelperViaShizuku = MutableStateFlow(true)
    /** Start the shell helper once when CarDroidService comes up and Shizuku is Ready. */
    val autoStartHelperViaShizuku: StateFlow<Boolean> = _autoStartHelperViaShizuku

    private val _autoStartBluetooth = MutableStateFlow(true)
    /** Start Bluetooth listening when the app opens, after boot and after an app update. */
    val autoStartBluetooth: StateFlow<Boolean> = _autoStartBluetooth

    /** Loads the persisted switch once; safe to call from Activity and Service. */
    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences == null) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                preferences = prefs
                _autoStartHelperViaShizuku.value = prefs.getBoolean(KEY_AUTO_START_HELPER, true)
                _autoStartBluetooth.value = prefs.getBoolean(KEY_AUTO_START_BLUETOOTH, true)
            }
        }
    }

    fun setAutoStartBluetooth(enabled: Boolean) {
        _autoStartBluetooth.value = enabled
        preferences?.edit()?.putBoolean(KEY_AUTO_START_BLUETOOTH, enabled)?.apply()
    }

    fun setAutoStartHelperViaShizuku(enabled: Boolean) {
        _autoStartHelperViaShizuku.value = enabled
        preferences?.edit()?.putBoolean(KEY_AUTO_START_HELPER, enabled)?.apply()
    }

    /**
     * If true, [OkcarMfiSapImpl] ignores the X25519/SHA1 derivation and
     * sends the hardcoded `eiv`/`ekey` constants used by OkcarOS's
     * `PcConnectService.java:488-490` -- a known-good pair against several
     * real CarPlay head units. /auth-setup still runs (the car requires the
     * roundtrip even though the result is discarded).
     */
    private val _useHardcodedMfiKeys = MutableStateFlow(false)
    val useHardcodedMfiKeys: StateFlow<Boolean> = _useHardcodedMfiKeys
    fun setUseHardcodedMfiKeys(v: Boolean) { _useHardcodedMfiKeys.value = v }

    /**
     * Constants lifted verbatim from
     * `reference-source/sources/com/okcar/autoconn/conn/PcConnectService.java:488-490`.
     * IV is a 4-byte pattern `68 EA 43 CB` repeated four times.
     */
    val HARDCODED_EIV: ByteArray = byteArrayOf(
        0x68.toByte(), 0xEA.toByte(), 0x43.toByte(), 0xCB.toByte(),
        0x68.toByte(), 0xEA.toByte(), 0x43.toByte(), 0xCB.toByte(),
        0x68.toByte(), 0xEA.toByte(), 0x43.toByte(), 0xCB.toByte(),
        0x68.toByte(), 0xEA.toByte(), 0x43.toByte(), 0xCB.toByte(),
    )
    val HARDCODED_EKEY: ByteArray = byteArrayOf(
        0x67.toByte(), 0x64.toByte(), 0x00.toByte(), 0x20.toByte(),
        0xAC.toByte(), 0x1B.toByte(), 0x1A.toByte(), 0x80.toByte(),
        0x50.toByte(), 0x05.toByte(), 0x8F.toByte(), 0xBA.toByte(),
        0x6A.toByte(), 0x0A.toByte(), 0x0F.toByte(), 0xC8.toByte(),
    )
}
