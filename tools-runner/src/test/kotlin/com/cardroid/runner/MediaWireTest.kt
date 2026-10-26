package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class MediaWireTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun jcaOpen(nonce: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = Cipher.getInstance("ChaCha20-Poly1305").run {
        init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"ChaCha20"),IvParameterSpec(nonce)); updateAAD(aad); doFinal(body)
    }
    @Test fun screenPacketMatchesReceiverHeaderAndJcaAuthentication() {
        val nals = byteArrayOf(0,0,0,1,0x65,0x12,0x34,0,0,1,0x41,0x56)
        val body = CarPlayMediaWire.videoFrame(nals)
        assertContentEquals(byteArrayOf(0,0,0,3,0x65,0x12,0x34,0,0,0,2,0x41,0x56),body)
        val packet = CarPlayMediaWire.screenPacket(key,5,false,body,1234)
        val header = packet.copyOfRange(0,128)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(body.size+16,b.int); assertEquals(0,b.short.toInt()); b.short
        assertEquals(1234L,b.long)
        assertContentEquals(body,jcaOpen(CarPlayCrypto.nonce(5),packet.copyOfRange(128,packet.size),header))
        val broken = packet.copyOfRange(128,packet.size); broken[0] = (broken[0].toInt() xor 1).toByte()
        assertFails { jcaOpen(CarPlayCrypto.nonce(5),broken,header) }
    }
    @Test fun audioPacketHasRtpClockBigEndianSamplesAndReceiverNonceTail() {
        val pcm = byteArrayOf(0x34,0x12,0x78,0x56)
        val wire = CarPlayMediaWire.audioPacket(key,7,123,352,0x50524554,pcm)
        assertEquals(40,wire.size)
        assertEquals(123,ByteBuffer.wrap(wire,2,2).short.toInt())
        assertEquals(352,ByteBuffer.wrap(wire,4,4).int)
        val nonce = byteArrayOf(0,0,0,0) + wire.copyOfRange(wire.size-8,wire.size)
        assertContentEquals(byteArrayOf(0x12,0x34,0x56,0x78),jcaOpen(nonce,wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12)))
    }
    @Test fun aacPacketKeepsAccessUnitBytesAndUsesType102Marker() {
        val aac = byteArrayOf(0x12,0x34,0x56)
        val wire = CarPlayMediaWire.audioPacket(key,8,1,1024,0,aac,payloadType=102,pcmLittleEndian=false,marker=true)
        assertEquals(0xe6,wire[1].toInt() and 0xff)
        val nonce = byteArrayOf(0,0,0,0) + wire.copyOfRange(wire.size-8,wire.size)
        assertContentEquals(aac,jcaOpen(nonce,wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12)))
    }
    @Test fun fragmentedControlRecordsRoundTripAcrossChunkBoundary() {
        val plain = ByteArray(40000) { (it % 251).toByte() }
        val wire = ByteArrayOutputStream()
        CarPlayRecordOutput(wire,key).write(plain)
        val source = object : ByteArrayInputStream(wire.toByteArray()) {
            override fun read(b: ByteArray,off: Int,len: Int) = super.read(b,off,minOf(len,3))
        }
        assertContentEquals(plain,CarPlayRecordInput(source,key).readBytes())
    }
    @Test fun codecConfigIsAvccAndMalformedNalLengthsAreRejected() {
        val config = CarPlayMediaWire.videoConfig(byteArrayOf(0x67,0x42,0,0x1f),byteArrayOf(0x68,1))
        assertContentEquals(byteArrayOf(1,0x42,0,0x1f,0xff.toByte(),0xe1.toByte(),0,4,0x67,0x42,0,0x1f,1,0,2,0x68,1),config)
        assertFails { CarPlayMediaWire.videoFrame(byteArrayOf(0,0,0,9,0x65)) }
    }
    @Test fun receiverPlistReadsNestedSetupAndRejectsTruncation() {
        val wire = AirPlayBplist.encode(AirPlayBplist.dict("eventPort" to AirPlayBplist.int(42000),
            "streams" to AirPlayBplist.array(AirPlayBplist.dict("type" to AirPlayBplist.int(110),"dataPort" to AirPlayBplist.int(45000)))))
        val result = ReceiverPlist.map(wire)
        assertEquals(42000L,result["eventPort"])
        val stream = (result["streams"] as List<*>).single() as Map<*,*>
        assertEquals(45000L,stream["dataPort"])
        assertFails { ReceiverPlist.map(wire.copyOf(wire.size-20)) }
    }
}
