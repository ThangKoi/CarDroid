package com.cardroid.lib.airplay

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure-Kotlin Apple MFi-SAP client (X25519 + SHA1-derived AES key/IV).
 *
 * Implements the math that matches what `public reference`'s
 * `carplay_services.m:handle_auth_setup` validates server-side. Reference
 * (verbatim cite of reference line numbers):
 *
 *   carplay_services.m:1021-1032
 *     uint8_t aesKey[20], aesIV[20];
 *     CC_SHA1("AES-KEY" || sharedSecret, aesKey)
 *     CC_SHA1("AES-IV"  || sharedSecret, aesIV)
 *
 *   carplay_services.m:1117-1158 — M2 wire layout returned by server:
 *     [0..32)  server_curve25519_pub
 *     [32..36) leaf_len_be (u32 BE)
 *     [36..)   leaf_der
 *     [..)     enc_sig_len_be (u32 BE), enc_sig
 *     [..)     baIC_len_be (u32 BE),    OPACK({"baIC": intermediate_der})
 *
 * Our client side:
 *   1. Generate Curve25519 keypair `(sk, pk)`.
 *   2. M1 (initialAuthBody): send `0x01 || pk` (33 bytes).
 *   3. M2 (finishExchange):
 *      a. parse first 32 bytes of server response as `peer_pk`
 *      b. shared = X25519(sk, peer_pk)
 *      c. eiv = SHA1("AES-IV"  || shared)[0..16]
 *      d. ekey = SHA1("AES-KEY" || shared)[0..16]
 *      e. SETUP plist uses `eiv`/`ekey` per
 *         `AirPlayHandshakeOrchestrator.buildSetupPlist`.
 *
 * We do NOT verify the BAA cert or the encrypted signature — cardroid
 * never has the chain, and the BMW does not appear to require us to
 * prove anything on our side. Symmetric with our iAP2 OkcarBypassProvider
 * philosophy.
 *
 * Note: OkcarOS `CE.f()` was disassembled locally and only does
 * `RandomBytes(32)` + `curve25519_donna`, returning `public || private`.
 * Its exported `CE.r()` is a no-op in this build, so this class performs the
 * same client-side exchange directly.
 */
class KotlinMfiSap(
    private val random: SecureRandom = SecureRandom(),
) : AirPlayMfiSap {

    private val clientPriv: X25519PrivateKeyParameters
    private val clientPub: X25519PublicKeyParameters

    init {
        val kpg = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
        val kp = kpg.generateKeyPair()
        clientPriv = kp.private as X25519PrivateKeyParameters
        clientPub = kp.public as X25519PublicKeyParameters
    }

    /** M1: returns 33 bytes = `0x01 || clientPublicKey`. */
    override fun initialAuthBody(): ByteArray {
        val pk = clientPub.encoded  // byte[32]
        val out = ByteArray(33)
        out[0] = 0x01
        System.arraycopy(pk, 0, out, 1, 32)
        return out
    }

    /**
     * Parse server M2 (first 32 bytes are the server's X25519 pub) and derive
     * (eiv, ekey) the same way reference derives (aesIV, aesKey).
     */
    override fun finishExchange(serverResponse: ByteArray): Pair<ByteArray, ByteArray> =
        deriveKeysFromClientPrivate(clientPriv.encoded, serverResponse)

    companion object {
        fun deriveKeysFromClientPrivate(
            clientPrivateBytes: ByteArray,
            serverResponse: ByteArray,
        ): Pair<ByteArray, ByteArray> {
            require(clientPrivateBytes.size >= 32) {
                "MFi-SAP client private material too short: ${clientPrivateBytes.size} bytes"
            }
            require(serverResponse.size >= 32) {
                "MFi-SAP M2 response too short: ${serverResponse.size} bytes (need >= 32 for server pk)"
            }
            val peerPkBytes = serverResponse.copyOfRange(0, 32)
            val peerPk = X25519PublicKeyParameters(peerPkBytes, 0)
            val clientPrivate = X25519PrivateKeyParameters(clientPrivateBytes, 0)

            val agreement = X25519Agreement()
            agreement.init(clientPrivate)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(peerPk, sharedSecret, 0)

            val aesIv = sha1Truncate16(label = "AES-IV", shared = sharedSecret)
            val aesKey = sha1Truncate16(label = "AES-KEY", shared = sharedSecret)

            return aesIv to aesKey
        }

        private fun sha1Truncate16(label: String, shared: ByteArray): ByteArray {
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(label.toByteArray(Charsets.US_ASCII))
            sha1.update(shared)
            val digest = sha1.digest()  // 20 bytes
            return digest.copyOfRange(0, 16)
        }
    }

    /** Exposed for unit-style round-trip testing. */
    internal fun clientPublicKeyBytes(): ByteArray = clientPub.encoded

    /** Exposed for unit-style round-trip testing. */
    internal fun clientPrivateKeyBytes(): ByteArray = clientPriv.encoded
}
