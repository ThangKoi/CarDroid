package com.cardroid.mfi

import android.util.Log

/**
 * Logs the challenge and returns no signature. The CarPlay handshake will fail
 * at the auth step, which is the expected outcome of a software-only solution.
 * Useful for protocol bring-up and reverse engineering.
 */
class NullProvider : MfiAuthProvider {
    override val name = "Null (no auth — handshake will fail at challenge)"

    override suspend fun sign(challenge: ByteArray): ByteArray? {
        Log.w(TAG, "MFi challenge received (${challenge.size} bytes), no signer attached")
        Log.w(TAG, "hex: " + challenge.joinToString("") { "%02X".format(it) })
        return null
    }

    override suspend fun certificate(): ByteArray? = null

    companion object { private const val TAG = "CarDroid/MFi/Null" }
}
