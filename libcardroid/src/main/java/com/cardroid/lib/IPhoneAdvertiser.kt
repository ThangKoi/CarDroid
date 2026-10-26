package com.cardroid.lib

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid

/**
 * Drives the Bluetooth-discovery side of impersonating an iPhone.
 *
 *   Classic BR/EDR:
 *     - Renames the local adapter to [IPhoneIdentity.bluetoothName]
 *     - Becomes discoverable
 *     - SDP record is implicit in [Iap2Transport.start] which
 *       calls listenUsingRfcommWithServiceRecord
 *
 *   BLE:
 *     - Advertises Apple manufacturer data
 *     - Advertises the iPhone-side iAP2 service UUID so head units
 *       scanning for CarPlay-eligible peers see us
 */
class IPhoneAdvertiser(
    private val adapter: BluetoothAdapter,
    private val identity: IPhoneIdentity,
    private val onLog: (String) -> Unit = {},
) {
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val advCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onLog("BLE advertisement started")
        }
        override fun onStartFailure(errorCode: Int) {
            val name = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN"
            }
            onLog("BLE advertisement failed: $errorCode ($name)")
            val plan = fallbackPlan
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && plan != null) {
                onLog("Trying fallback: ${plan.first}")
                fallbackPlan = null
                try {
                    bleAdvertiser?.startAdvertising(plan.second, plan.third, advCallback)
                } catch (e: Throwable) {
                    onLog("Fallback advertise threw: ${e.message}")
                }
            }
        }
    }

    /** Pre-built fallback (label, settings, data) used if the primary attempt fails. */
    private var fallbackPlan: Triple<String, AdvertiseSettings, AdvertiseData>? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter.name != identity.bluetoothName) {
            adapter.name = identity.bluetoothName
            onLog("Bluetooth name set to '${identity.bluetoothName}'")
        }
        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            onLog("BLE advertiser unavailable on this adapter (not supported?)")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // BLE advert hard-capped at 31 bytes. Truncate Apple mfg data to keep it short.
        val mfgPayload = if (identity.manufacturerData.size > 8) {
            identity.manufacturerData.copyOfRange(0, 8)
        } else identity.manufacturerData

        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Iap2Constants.APPLE_COMPANY_ID, mfgPayload)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(Iap2Constants.SERVICE_UUID_IPHONE))
            .build()

        // Fallback: bare mfg-data only, no scan response (some chipsets reject
        // 4-arg startAdvertising even when each blob fits 31 bytes individually).
        val minimal = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Iap2Constants.APPLE_COMPANY_ID, mfgPayload)
            .build()
        fallbackPlan = Triple("mfg-only, no scan response", settings, minimal)

        onLog("BLE advert: mfg=${mfgPayload.size}B + scan-resp(name+UUID)")
        bleAdvertiser?.startAdvertising(settings, primary, scanResponse, advCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        bleAdvertiser?.stopAdvertising(advCallback)
        bleAdvertiser = null
    }
}
