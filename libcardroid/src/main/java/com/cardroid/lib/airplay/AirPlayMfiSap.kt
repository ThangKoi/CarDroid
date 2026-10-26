package com.cardroid.lib.airplay

/**
 * MFi-SAP client interface.
 *
 * Real implementation calls into OkcarOS's libap-lib.so via JNI
 * (`CE.f()` for the initial blob, `CE.r(serverBytes)` for the exchange
 * finish). See `tools/jvm-runner/...` or `app/.../OkcarMfiSapImpl.kt`.
 *
 * The default no-op implementation returns dummy bytes -- useful only
 * for wiring tests that don't talk to a real car.
 */
interface AirPlayMfiSap {
    /**
     * Build the body for POST /auth-setup. Real format:
     *   0x01 | <32 bytes MFi-SAP client material>
     */
    fun initialAuthBody(): ByteArray

    /**
     * Process the car's /auth-setup response. Returns (eiv, ekey) to use
     * in subsequent SETUP/streaming.
     */
    fun finishExchange(serverResponse: ByteArray): Pair<ByteArray, ByteArray>

    companion object {
        /** Junk-bytes stub for testing without OkcarOS native libs. */
        val Stub: AirPlayMfiSap = object : AirPlayMfiSap {
            override fun initialAuthBody(): ByteArray {
                val body = ByteArray(33)
                body[0] = 0x01
                val publicBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                System.arraycopy(publicBytes, 0, body, 1, publicBytes.size)
                return body
            }
            override fun finishExchange(serverResponse: ByteArray): Pair<ByteArray, ByteArray> {
                val iv = ByteArray(16)
                val key = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                return iv to key
            }
        }
    }
}
