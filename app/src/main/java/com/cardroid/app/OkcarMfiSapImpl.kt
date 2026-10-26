package com.cardroid.app

import android.content.Context
import android.util.Log
import com.okcar.tool.encrypter.CE
import com.okcar.tool.encrypter.SR
import com.cardroid.lib.airplay.AirPlayMfiSap
import com.cardroid.lib.airplay.KotlinMfiSap

/**
 * MFi-SAP implementation backed by OkcarOS's libap-lib.so via the
 * `com.okcar.tool.encrypter.CE` JNI bridge.
 *
 * Lifecycle:
 *   1. First call to any SR.* method triggers `System.loadLibrary` chain.
 *   2. {@link CE#z(Context)} initializes the MFi machinery.
 *   3. {@link CE#f()} returns 64 bytes of MFi-SAP client material. We send
 *      the first 32 as the body of POST /auth-setup (prefixed by 0x01) and
 *      keep the rest in memory for the exchange-finish step.
 *   4. The second 32 bytes from CE.f() are the X25519 private material.
 *      We derive eiv/ekey in managed code so we do not need CE.r(), which
 *      SIGSEGVs when called from CarDroid's process on this device.
 *
 * If any of this throws (libs missing, native rejects), the methods log
 * and fall back to the pure Kotlin SAP path.
 */
class OkcarMfiSapImpl(
    context: Context,
    private val log: (String) -> Unit = {},
) : AirPlayMfiSap {

    private val initialized: Boolean
    private val fallback = KotlinMfiSap()

    init {
        var ok = false
        try {
            // Touching SR triggers System.loadLibrary chain.
            val ver = SR.z2()
            val build = SR.y7()
            log("OkcarMfiSap: libap-lib loaded (ver='$ver' build=$build)")
            val rc = CE.z(context.applicationContext)
            log("OkcarMfiSap: CE.z(context) -> $rc")
            ok = true
        } catch (t: Throwable) {
            Log.w(TAG, "OkcarMfiSap init failed", t)
            log("OkcarMfiSap: init failed (${t.javaClass.simpleName}: ${t.message}); using Kotlin SAP")
        }
        initialized = ok
    }

    private var cachedMaterial: ByteArray = ByteArray(0)

    override fun initialAuthBody(): ByteArray {
        if (!initialized) return fallback.initialAuthBody()
        val material = try {
            CE.f()
        } catch (t: Throwable) {
            log("OkcarMfiSap: CE.f() threw ${t.javaClass.simpleName}: ${t.message}; fallback to Kotlin SAP")
            return fallback.initialAuthBody()
        }
        if (material == null || material.size < 32) {
            log("OkcarMfiSap: CE.f() returned ${material?.size ?: -1} bytes; using Kotlin SAP")
            return fallback.initialAuthBody()
        }
        cachedMaterial = material
        val body = ByteArray(33)
        body[0] = 0x01
        System.arraycopy(material, 0, body, 1, 32)
        log("OkcarMfiSap: /auth-setup body built from CE.f() (${material.size} bytes total)")
        log("OkcarMfiSap: CE.f() public=${material.copyOfRange(0, minOf(32, material.size)).previewHex()} private=${material.copyOfRange(32, minOf(64, material.size)).previewHex()}")
        return body
    }

    override fun finishExchange(serverResponse: ByteArray): Pair<ByteArray, ByteArray> {
        if (CarDroidSettings.useHardcodedMfiKeys.value) {
            log("OkcarMfiSap: using HARDCODED OkcarOS constants (eiv/ekey from PcConnectService.java:488-490)")
            return CarDroidSettings.HARDCODED_EIV to CarDroidSettings.HARDCODED_EKEY
        }
        if (!initialized) return fallback.finishExchange(serverResponse)
        if (cachedMaterial.size < 64) {
            log("OkcarMfiSap: no CE.f() private material (${cachedMaterial.size}B); using Kotlin SAP")
            return fallback.finishExchange(serverResponse)
        }
        return try {
            log("OkcarMfiSap: deriving with server response first32=${serverResponse.previewHex(32)}")
            val keys = KotlinMfiSap.deriveKeysFromClientPrivate(
                cachedMaterial.copyOfRange(32, 64),
                serverResponse,
            )
            log("OkcarMfiSap: derived eiv=${keys.first.size}B key=${keys.second.size}B from CE.f() private material")
            keys
        } catch (t: Throwable) {
            log("OkcarMfiSap: managed derive threw ${t.javaClass.simpleName}: ${t.message}; fallback to Kotlin SAP")
            fallback.finishExchange(serverResponse)
        }
    }

    companion object { private const val TAG = "CarDroid/MFi-SAP" }
}

private fun ByteArray.previewHex(max: Int = 8): String =
    take(max).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }.ifEmpty { "empty" }
