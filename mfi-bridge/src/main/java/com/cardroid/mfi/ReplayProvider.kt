package com.cardroid.mfi

/**
 * Returns pre-captured (challenge -> signature) pairs.
 *
 * Useful for replaying a single recorded session against the SAME head unit
 * if it happens to re-issue identical challenges (rare — most head units use
 * fresh nonces, so this almost always fails the next time you connect).
 *
 * Provide [pairs] from a `btsnoop_hci.log` capture of a real iPhone + the
 * exact same head unit doing a successful CarPlay handshake.
 */
class ReplayProvider(
    private val pairs: Map<HexBytes, HexBytes>,
    private val certBytes: ByteArray?,
) : MfiAuthProvider {
    override val name = "Replay (${pairs.size} canned pairs)"

    override suspend fun sign(challenge: ByteArray): ByteArray? =
        pairs[HexBytes(challenge)]?.bytes

    override suspend fun certificate(): ByteArray? = certBytes
}

/** Wrap ByteArray with proper structural equals/hashCode for use as a Map key. */
class HexBytes(val bytes: ByteArray) {
    override fun toString(): String = bytes.joinToString("") { "%02X".format(it) }
    override fun equals(other: Any?): Boolean =
        other is HexBytes && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}
