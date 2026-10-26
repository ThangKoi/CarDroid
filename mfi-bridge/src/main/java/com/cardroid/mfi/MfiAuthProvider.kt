package com.cardroid.mfi

/**
 * Apple MFi (Made For iPhone) Authentication Coprocessor abstraction.
 *
 * Real CarPlay handshake step:
 *   - Head unit sends an Apple-signed nonce ("challenge")
 *   - iPhone forwards it to its on-board MFi auth chip
 *   - Chip signs with an embedded private key tied to an Apple cert
 *   - iPhone returns (signature, cert) — head unit verifies against Apple's root
 *
 * Android phones do NOT have an MFi chip. The only paths to a real signature:
 *   1. External MFi chip wired to phone (USB-OTG -> I²C bridge) — [ExternalChipProvider]
 *   2. Recorded challenge/response pairs from a real iPhone — [ReplayProvider]
 *      (works only if the head unit re-uses the same challenges; in practice
 *      it does not — challenges are nonces)
 *   3. No-op for protocol-development / research — [NullProvider]
 *
 * Without (1), Wireless CarPlay will NOT complete its handshake with a stock car.
 */
interface MfiAuthProvider {
    /** Sign [challenge] and return signature, or null if signing failed. */
    suspend fun sign(challenge: ByteArray): ByteArray?

    /** Return the Apple-issued accessory certificate as DER bytes. */
    suspend fun certificate(): ByteArray?

    /** Human-readable identifier shown in UI / logs. */
    val name: String
}
