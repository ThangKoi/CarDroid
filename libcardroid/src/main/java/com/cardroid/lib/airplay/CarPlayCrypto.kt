package com.cardroid.lib.airplay

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.InputStream
import java.io.OutputStream

/** Wire primitives matching Receiver v0.1.0 AirPlayCrypto/ControlCipher. */
object CarPlayCrypto {
    private val random = SecureRandom()
    fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    fun hash(vararg bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
        bytes.forEach(::update); digest()
    }
    fun hkdf(secret: ByteArray, salt: String, info: String): ByteArray = ByteArray(32).also {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(secret, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8)))
        generator.generateBytes(it, 0, it.size)
    }
    fun xPublic(privateKey: ByteArray) = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    fun shared(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32).also {
        X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(publicKey, 0), it, 0)
    }
    fun edPublic(seed: ByteArray) = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    fun sign(seed: ByteArray, data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, Ed25519PrivateKeyParameters(seed, 0)); update(data, 0, data.size); generateSignature()
    }
    fun verify(key: ByteArray, data: ByteArray, signature: ByteArray): Boolean = Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(key, 0)); update(data, 0, data.size); verifySignature(signature)
    }
    fun nonce(counter: Long): ByteArray = ByteArray(12).also { n ->
        for (i in 0..7) n[i + 4] = (counter ushr (8 * i)).toByte()
    }
    fun nonce(label: String): ByteArray = ByteArray(12).also { label.toByteArray(Charsets.US_ASCII).copyInto(it, 4) }
    fun seal(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray = byteArrayOf()) = crypt(true, key, nonce, data, aad)
    fun open(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray = byteArrayOf()) = crypt(false, key, nonce, data, aad)
    private fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val n = cipher.processBytes(data, 0, data.size, out, 0)
        return out.copyOf(n + cipher.doFinal(out, n))
    }
}

/** Streaming control records; handles fragmented TCP reads and multiple records. */
class CarPlayRecordInput(private val wire: InputStream, private val key: ByteArray) : InputStream() {
    private var counter = 0L
    private var plain = byteArrayOf()
    private var offset = 0
    override fun read(): Int {
        if (offset == plain.size) {
            val lo = wire.read()
            if (lo < 0) return -1
            val hi = wire.read(); require(hi >= 0) { "Truncated record header" }
            val length = lo or (hi shl 8)
            require(length <= 0x4000) { "Oversized control record" }
            val aad = byteArrayOf(lo.toByte(), hi.toByte())
            plain = CarPlayCrypto.open(key, CarPlayCrypto.nonce(counter++), readExact(wire, length + 16), aad)
            offset = 0
            if (plain.isEmpty()) return read()
        }
        return plain[offset++].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read(); if (first < 0) return -1
        b[off] = first.toByte()
        val n = minOf(len - 1, plain.size - offset)
        plain.copyInto(b, off + 1, offset, offset + n); offset += n
        return n + 1
    }
}
class CarPlayRecordOutput(private val wire: OutputStream, private val key: ByteArray) : OutputStream() {
    private var counter = 0L
    override fun write(b: Int) = write(byteArrayOf(b.toByte()))
    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        while (offset < off + len) {
            val end = minOf(offset + 0x4000, off + len)
            val n = end - offset
            val aad = byteArrayOf(n.toByte(), (n ushr 8).toByte())
            wire.write(aad)
            wire.write(CarPlayCrypto.seal(key, CarPlayCrypto.nonce(counter++), b.copyOfRange(offset, end), aad))
            offset = end
        }
    }
    override fun flush() = wire.flush()
}
internal fun readExact(input: InputStream, length: Int): ByteArray = ByteArray(length).also {
    var pos = 0
    while (pos < length) {
        val n = input.read(it, pos, length - pos)
        check(n > 0) { "Unexpected EOF" }; pos += n
    }
}
