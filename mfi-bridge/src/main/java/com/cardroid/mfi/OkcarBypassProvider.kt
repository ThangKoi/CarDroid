package com.cardroid.mfi

import android.util.Log
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * MFi auth bypass extracted from OkcarOS (com.okcar.autoconn).
 *
 * How OkcarOS handles the iAP2 MFi auth handshake:
 *   1. Car sends its MFi certificate (msg 0xAA01). OkcarOS saves it as X.509,
 *      reads the signature algorithm name, but does NOT verify the cert chain.
 *   2. OkcarOS sends msg 0xAA02 with a hardcoded hash that depends on the
 *      certificate signature algorithm, matching j2.j.t() in the extracted app.
 *   3. When msg 0xAA03 arrives, OkcarOS sends AuthStatus=OK (msg 0xAA05) without waiting for
 *      the car to validate the response.
 *
 * This works because in the CarPlay protocol the *phone* is the verifier of the
 * car's identity - the car does not verify the phone's MFi signature at the iAP2
 * layer. The real gating happens later at the AirPlay layer (MFi-SAP / pair-setup),
 * which OkcarOS handles in libap-lib.so with embedded Apple cert/key material.
 *
 * The hardcoded hashes below are from OkcarOS j2/j.java method j.t():
 *   - SHA256withECDSA -> 32-byte hash
 *   - SHA1 fallback   -> 20-byte hash
 */
class OkcarBypassProvider : MfiAuthProvider {
    override val name = "OkcarOS Bypass (accepts any cert, hardcoded response)"

    var carCertAlgorithm: String? = null
        private set

    /**
     * Called when the car's MFi certificate arrives (iAP2 message 0xAA01).
     * We parse it as X.509 to read the signature algorithm, then discard it.
     * Returns true if the cert was parsed successfully.
     */
    fun acceptCarCertificate(certBytes: ByteArray): Boolean {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(certBytes.inputStream()) as X509Certificate
            carCertAlgorithm = cert.sigAlgName
            Log.i(TAG, "Car MFi cert accepted: issuer=${cert.issuerDN}, sigAlg=$carCertAlgorithm")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse car cert (${certBytes.size} bytes), using SHA256 fallback", e)
            carCertAlgorithm = "SHA256withECDSA"
            true // Still accept; OkcarOS doesn't fail on parse errors either
        }
    }

    /**
     * Sign the challenge. OkcarOS ignores the actual challenge bytes and returns
     * a hardcoded hash based on the signature algorithm from the car's certificate.
     */
    override suspend fun sign(challenge: ByteArray): ByteArray {
        val alg = carCertAlgorithm ?: "SHA256withECDSA"
        Log.i(TAG, "MFi challenge (${challenge.size} bytes) - returning hardcoded $alg hash")

        return if (alg.equals("SHA256withECDSA", ignoreCase = true)) {
            HASH_SHA256_ECDSA
        } else {
            HASH_SHA1
        }
    }

    /**
     * Certificate is not sent by the phone in OkcarOS's flow; the car is the one
     * presenting its cert. We return null; the transport layer handles this by
     * not sending a cert message proactively.
     */
    override suspend fun certificate(): ByteArray? = null

    companion object {
        private const val TAG = "CarDroid/MFi/Bypass"

        // From OkcarOS j2/j.java j.t() - SHA256withECDSA variant (32 bytes)
        private val HASH_SHA256_ECDSA = byteArrayOf(
            0xd4.toByte(), 0x7a.toByte(), 0xa3.toByte(), 0xde.toByte(),
            0x14.toByte(), 0x7a.toByte(), 0xd4.toByte(), 0x7a.toByte(),
            0xa3.toByte(), 0xde.toByte(), 0x14.toByte(), 0x7a.toByte(),
            0xd4.toByte(), 0x7a.toByte(), 0xa3.toByte(), 0xde.toByte(),
            0x14.toByte(), 0x7a.toByte(), 0xd8.toByte(), 0xa1.toByte(),
            0x0b.toByte(), 0xf8.toByte(), 0x5a.toByte(), 0x71.toByte(),
            0x4d.toByte(), 0x72.toByte(), 0xa5.toByte(), 0x5a.toByte(),
            0xf4.toByte(), 0x61.toByte(), 0x56.toByte(), 0x05.toByte(),
        )

        // From OkcarOS j2/j.java j.t() - SHA1 variant (20 bytes)
        private val HASH_SHA1 = byteArrayOf(
            0xd4.toByte(), 0x7a.toByte(), 0xa3.toByte(), 0xde.toByte(),
            0x14.toByte(), 0x7a.toByte(), 0xd8.toByte(), 0xa1.toByte(),
            0x0b.toByte(), 0xf8.toByte(), 0x5a.toByte(), 0x71.toByte(),
            0x4d.toByte(), 0x72.toByte(), 0xa5.toByte(), 0x5a.toByte(),
            0xf4.toByte(), 0x61.toByte(), 0x56.toByte(), 0x05.toByte(),
        )
    }
}
