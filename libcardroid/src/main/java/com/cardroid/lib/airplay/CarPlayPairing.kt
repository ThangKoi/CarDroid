package com.cardroid.lib.airplay

import java.math.BigInteger
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups

/**
 * Controller-side pairing: pair-setup M1-M6 then pair-verify M1-M4. A null [receiverKey] learns the
 * receiver's Ed25519 key from M6 (real iPhone behaviour); otherwise M6 must match the pinned key.
 */
class CarPlayPairing(
    private val controllerId: String,
    private val seed: ByteArray,
    private val receiverKey: ByteArray?,
    private val headers: Map<String, String> = emptyMap(),
    private val verifyContentType: String = "application/pairing+tlv8",
    private val log: (String) -> Unit = {},
) {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun response(step: String, resp: AirPlayRtspClient.Response, state: Int): Map<Int, ByteArray> {
        log("AirPlay: pairing $step -> ${resp.statusCode} (${runCatching { Tlv8.describe(resp.body) }.getOrDefault("undecodable ${resp.body.size}B")})")
        check(resp.statusCode in 200..299) { "Pairing $step HTTP ${resp.statusCode}" }
        val tlv = Tlv8.decode(resp.body)
        val err = tlv[Tlv8.ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
        check(err == null) {
            "Pairing rejected at $step (error=$err${when (err) { 2 -> ", authentication"; 6 -> ", unavailable/busy"; 3 -> ", backoff"; else -> "" }})"
        }
        check(tlv[Tlv8.STATE]?.contentEquals(byteArrayOf(state.toByte())) == true) { "Pairing $step: expected State=$state" }
        return tlv
    }
    fun run(rtsp: AirPlayRtspClient): ByteArray {
        require(receiverKey == null || receiverKey.size == 32) { "Receiver public key missing from iAP2" }
        val m2 = response("M2", rtsp.pairSetup(Tlv8.encode(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(0)), headers), 2)
        val srp = SrpController(m2.getValue(Tlv8.SALT), m2.getValue(Tlv8.PUBLIC_KEY))
        val m4 = response("M4", rtsp.pairSetup(Tlv8.encode(Tlv8.STATE to byteArrayOf(3),
            Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof), headers), 4)
        check(srp.verify(m4.getValue(Tlv8.PROOF))) { "Invalid SRP server proof" }
        val key = CarPlayCrypto.hkdf(srp.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val id = bytes(controllerId)
        val publicKey = CarPlayCrypto.edPublic(seed)
        val signKey = CarPlayCrypto.hkdf(srp.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sub = Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.PUBLIC_KEY to publicKey,
            Tlv8.SIGNATURE to CarPlayCrypto.sign(seed, signKey + id + publicKey))
        val m6 = response("M6", rtsp.pairSetup(Tlv8.encode(Tlv8.STATE to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to
            CarPlayCrypto.seal(key, CarPlayCrypto.nonce("PS-Msg05"), sub)), headers), 6)
        val accessory = Tlv8.decode(CarPlayCrypto.open(key, CarPlayCrypto.nonce("PS-Msg06"), m6.getValue(Tlv8.ENCRYPTED_DATA)))
        val accessoryKey = accessory.getValue(Tlv8.PUBLIC_KEY)
        val receiverId = accessory.getValue(Tlv8.IDENTIFIER)
        val peerKey: ByteArray
        if (receiverKey != null) {
            check(accessoryKey.contentEquals(receiverKey)) { "Receiver pairing identity changed" }
            peerKey = receiverKey
        } else {
            check(accessoryKey.size == 32) { "Receiver M6 public key is ${accessoryKey.size}B, expected 32" }
            peerKey = accessoryKey
            log("AirPlay: pair-setup M6 receiver id=${String(receiverId, Charsets.UTF_8)} ltpk=${hex(peerKey.copyOf(8))}\u2026")
        }
        val accessorySign = CarPlayCrypto.hkdf(srp.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        check(CarPlayCrypto.verify(peerKey, accessorySign + receiverId + peerKey, accessory.getValue(Tlv8.SIGNATURE))) {
            "Invalid receiver pairing signature"
        }
        val ephemeral = CarPlayCrypto.random(32)
        val ownPublic = CarPlayCrypto.xPublic(ephemeral)
        val v2 = response("V2", rtsp.pairVerify(Tlv8.encode(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to ownPublic), headers, verifyContentType), 2)
        val peerPublic = v2.getValue(Tlv8.PUBLIC_KEY)
        val shared = CarPlayCrypto.shared(ephemeral, peerPublic)
        val verifyKey = CarPlayCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val peer = Tlv8.decode(CarPlayCrypto.open(verifyKey, CarPlayCrypto.nonce("PV-Msg02"), v2.getValue(Tlv8.ENCRYPTED_DATA)))
        check(peer.getValue(Tlv8.IDENTIFIER).contentEquals(receiverId)) { "Receiver verify identity changed" }
        check(CarPlayCrypto.verify(peerKey, peerPublic + receiverId + ownPublic, peer.getValue(Tlv8.SIGNATURE))) {
            "Invalid receiver verify signature"
        }
        val verifySub = Tlv8.encode(Tlv8.IDENTIFIER to id,
            Tlv8.SIGNATURE to CarPlayCrypto.sign(seed, ownPublic + id + peerPublic))
        response("V4", rtsp.pairVerify(Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to
            CarPlayCrypto.seal(verifyKey, CarPlayCrypto.nonce("PV-Msg03"), verifySub)), headers, verifyContentType), 4)
        rtsp.enableEncryption(
            CarPlayCrypto.hkdf(shared, "Control-Salt", "Control-Read-Encryption-Key"),
            CarPlayCrypto.hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key"),
        )
        return shared
    }
}

/** AirPlay 2 transient pair-setup (M1-M4, PIN 3939, X-Apple-HKP: 4). Returns the SRP session key. */
object TransientPairing {
    private val HEADERS = mapOf("X-Apple-HKP" to "4")

    /** M1: State=1, Method=0, Flags=0x10 (transient). */
    fun m1(): ByteArray = Tlv8.encode(Tlv8.STATE to Tlv8.u8(1), Tlv8.METHOD to Tlv8.u8(0), Tlv8.FLAGS to Tlv8.u8(0x10))

    private fun reply(resp: AirPlayRtspClient.Response, state: Int): Map<Int, ByteArray> {
        check(resp.statusCode in 200..299) { "Transient pair-setup M${state - 1} HTTP ${resp.statusCode}" }
        val tlv = Tlv8.decode(resp.body)
        tlv[Tlv8.ERROR]?.let { e ->
            val code = e.firstOrNull()?.toInt()?.and(0xFF)
            error("Transient pair-setup rejected at M$state (error=$code${if (code == 2) ", wrong PIN/authentication" else ""})")
        }
        check(tlv[Tlv8.STATE]?.contentEquals(Tlv8.u8(state)) == true) { "Transient pair-setup: expected State=$state" }
        return tlv
    }

    /**
     * [enableEncryption] false keeps the RTSP control channel plaintext (diagnostic).
     * [swapKeys] true uses Control-Write-Encryption-Key for reading and Control-Read-Encryption-Key for writing (diagnostic).
     */
    fun run(
        rtsp: AirPlayRtspClient,
        log: (String) -> Unit,
        enableEncryption: Boolean = true,
        swapKeys: Boolean = false,
    ): ByteArray {
        val m2Resp = rtsp.pairSetup(m1(), HEADERS)
        log("AirPlay: transient M2 -> ${m2Resp.statusCode} (${Tlv8.describe(m2Resp.body)})")
        val m2 = reply(m2Resp, 2)
        val salt = checkNotNull(m2[Tlv8.SALT]) { "Transient M2 missing Salt" }
        val pk = checkNotNull(m2[Tlv8.PUBLIC_KEY]) { "Transient M2 missing PublicKey" }
        val srp = SrpController(salt, pk)
        val m3 = Tlv8.encode(Tlv8.STATE to Tlv8.u8(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)
        val m4Resp = rtsp.pairSetup(m3, HEADERS)
        log("AirPlay: transient M4 -> ${m4Resp.statusCode} (${Tlv8.describe(m4Resp.body)})")
        val m4 = reply(m4Resp, 4)
        check(srp.verify(checkNotNull(m4[Tlv8.PROOF]) { "Transient M4 missing Proof" })) { "Invalid SRP server proof" }
        if (enableEncryption) {
            val readLabel = if (swapKeys) "Control-Write-Encryption-Key" else "Control-Read-Encryption-Key"
            val writeLabel = if (swapKeys) "Control-Read-Encryption-Key" else "Control-Write-Encryption-Key"
            rtsp.enableEncryption(
                CarPlayCrypto.hkdf(srp.key, "Control-Salt", readLabel),
                CarPlayCrypto.hkdf(srp.key, "Control-Salt", writeLabel),
            )
        }
        return srp.key
    }
}

/** SRP-6a SHA-512 client, including HomeKit's proof and unpadded S hashing. */
internal class SrpController(salt: ByteArray, serverPublic: ByteArray) {
    private val n = SRP6StandardGroups.rfc5054_3072.n
    private val g = BigInteger.valueOf(5)
    private fun raw(v: BigInteger) = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad(v: BigInteger) = ByteArray(384).also { val b = raw(v); b.copyInto(it, it.size - b.size) }
    private fun number(b: ByteArray) = BigInteger(1, b)
    private val a = number(CarPlayCrypto.random(32))
    private val A = g.modPow(a, n)
    val publicKey = pad(A)
    val key: ByteArray
    val proof: ByteArray
    init {
        val B = number(serverPublic)
        require(B > BigInteger.ZERO && B < n) { "Invalid SRP public key" }
        val k = number(CarPlayCrypto.hash(raw(n), pad(g)))
        val u = number(CarPlayCrypto.hash(publicKey, pad(B)))
        require(u != BigInteger.ZERO)
        val x = number(CarPlayCrypto.hash(salt, CarPlayCrypto.hash("Pair-Setup:3939".toByteArray())))
        val S = (B - k * g.modPow(x, n)).mod(n).modPow(a + u * x, n)
        key = CarPlayCrypto.hash(raw(S))
        val hn = CarPlayCrypto.hash(raw(n)); val hg = CarPlayCrypto.hash(raw(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        proof = CarPlayCrypto.hash(xor, CarPlayCrypto.hash("Pair-Setup".toByteArray()), salt, publicKey, pad(B), key)
    }
    fun verify(serverProof: ByteArray) = java.security.MessageDigest.isEqual(CarPlayCrypto.hash(publicKey, proof, key), serverProof)
}
