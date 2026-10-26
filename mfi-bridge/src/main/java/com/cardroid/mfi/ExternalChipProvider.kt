package com.cardroid.mfi

import android.hardware.usb.UsbDeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives a physical Apple MFi Authentication Coprocessor (2.0C or 3.0)
 * connected via a USB-OTG -> I²C bridge (e.g. CP2112 HID-to-I²C).
 *
 * The MFi chip exposes an I²C register interface:
 *
 *   Register layout (public, from Apple's hardware integration guide):
 *     0x00: Device Version (read-only, ID)
 *     0x01: Firmware Version (read-only)
 *     0x02: Authentication Protocol Major (read-only)
 *     0x03: Authentication Protocol Minor (read-only)
 *     0x04: Device ID (read-only)
 *     0x10: Error Code
 *     0x11: Authentication Control & Status
 *     0x20: Challenge Response Data Length (16-bit, big-endian)
 *     0x21: Challenge Response Data (up to 128 bytes)
 *     0x30: Challenge Data Length
 *     0x31: Challenge Data (write challenge here)
 *     0x40: Accessory Certificate Data Length
 *     0x41..0x4A: Accessory Certificate Data (paged, up to 1280 bytes)
 *
 *   Flow:
 *     1. Write challenge length to 0x30, challenge bytes to 0x31
 *     2. Write 0x01 to 0x11 ("start new auth")
 *     3. Poll 0x11 until bit-status == ready (or read 0x10 for error)
 *     4. Read 0x20 for response length
 *     5. Read response bytes from 0x21
 *
 * Wire this via the host-app's USB layer.
 */
class ExternalChipProvider(
    private val i2c: I2cBridge,
    private val chipAddress: Int = 0x10, // 7-bit I²C address; some boards strap 0x11
) : MfiAuthProvider {
    override val name = "External MFi chip @ 0x${chipAddress.toString(16)}"

    override suspend fun sign(challenge: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        // 1. Write challenge length register 0x30 (u16 big-endian).
        i2c.write(chipAddress, byteArrayOf(0x30, ((challenge.size ushr 8) and 0xFF).toByte(),
            (challenge.size and 0xFF).toByte()))
        // 2. Write challenge data register 0x31.
        i2c.write(chipAddress, byteArrayOf(0x31) + challenge)
        // 3. Trigger auth: write 0x01 to register 0x11.
        i2c.write(chipAddress, byteArrayOf(0x11, 0x01))
        // 4. Poll status register 0x11 until ready or timeout.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            i2c.write(chipAddress, byteArrayOf(0x11))
            val status = i2c.read(chipAddress, 1)
            if (status != null && (status[0].toInt() and 0x80) != 0) break
            kotlinx.coroutines.delay(5)
        }
        // 5. Read response length from 0x20 (u16).
        i2c.write(chipAddress, byteArrayOf(0x20))
        val lenBytes = i2c.read(chipAddress, 2) ?: return@withContext null
        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (len <= 0 || len > 256) return@withContext null
        // 6. Read response bytes from 0x21.
        i2c.write(chipAddress, byteArrayOf(0x21))
        i2c.read(chipAddress, len)
    }

    override suspend fun certificate(): ByteArray? = withContext(Dispatchers.IO) {
        i2c.write(chipAddress, byteArrayOf(0x40))
        val lenBytes = i2c.read(chipAddress, 2) ?: return@withContext null
        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (len <= 0 || len > 1500) return@withContext null
        // Paged read: 128 bytes per page across registers 0x41..0x4A.
        val out = ByteArray(len)
        var copied = 0
        var page = 0x41
        while (copied < len && page <= 0x4A) {
            i2c.write(chipAddress, byteArrayOf(page.toByte()))
            val take = minOf(128, len - copied)
            val chunk = i2c.read(chipAddress, take) ?: return@withContext null
            System.arraycopy(chunk, 0, out, copied, chunk.size)
            copied += chunk.size
            page += 1
        }
        out
    }
}

/**
 * Minimal I²C bridge interface. Implement in the host app against your USB
 * dongle SDK (CP2112, MCP2221, FT232H, etc.) or a kernel i2c-dev node.
 */
interface I2cBridge {
    /** Write [bytes] to the slave at [address7bit]. First byte is the register pointer. */
    fun write(address7bit: Int, bytes: ByteArray): Boolean
    /** Read [count] bytes from the current register at [address7bit]. */
    fun read(address7bit: Int, count: Int): ByteArray?
    fun close()
}

/** Convenience: build an [ExternalChipProvider] from a raw USB connection + bridge factory. */
fun buildExternalChipProvider(
    usb: UsbDeviceConnection,
    bridgeFactory: (UsbDeviceConnection) -> I2cBridge,
): ExternalChipProvider = ExternalChipProvider(bridgeFactory(usb))
