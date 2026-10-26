package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayBplist as B
import com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator
import com.cardroid.lib.airplay.CarPlayRecordInput
import com.cardroid.lib.airplay.EventCommandWriter
import com.cardroid.lib.airplay.HidDeviceRegistry
import com.cardroid.lib.airplay.HidDescriptorParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class HidCompletionTest {
    private val lynkDescriptor = byteArrayOf(
        0x05, 0x0D, 0x09, 0x04, 0xA1.toByte(), 0x01, 0x05, 0x0D, 0x09, 0x22,
        0xA1.toByte(), 0x02, 0x05, 0x0D, 0x09, 0x33, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x01, 0x81.toByte(), 0x02, 0x75, 0x07,
        0x95.toByte(), 0x01, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30,
        0x15, 0x00, 0x26, 0x00, 0x0A, 0x75, 0x10, 0x95.toByte(), 0x01,
        0x81.toByte(), 0x02, 0x09, 0x31, 0x15, 0x00, 0x26, 0x50, 0x05,
        0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x02, 0xC0.toByte(), 0xC0.toByte(),
    )

    private fun registry(): HidDeviceRegistry = HidDeviceRegistry.fromInfo(B.encode(B.dict(
        "hidDevices" to B.array(
            B.dict("uuid" to B.str("0"), "name" to B.str("TouchScreen"), "hidDescriptor" to B.data(lynkDescriptor)),
            B.dict("uuid" to B.str("1"), "name" to B.str("ButtonInput"), "hidDescriptor" to B.data(ByteArray(31))),
            B.dict("uuid" to B.str("2"), "name" to B.str("PhoneKeyInput"), "hidDescriptor" to B.data(ByteArray(29))),
        ),
    )))

    @Test fun realDescriptorIsFiveByteSingleContact() {
        val r = registry()
        val descriptor = r.devices.first { (it.uuid as? com.cardroid.lib.airplay.HidUuid.Text)?.value == "0" }.touch
        assertEquals(5, descriptor?.reportBytes)
        assertEquals(2560, descriptor?.xMax)
        assertEquals(1360, descriptor?.yMax)
        val down = r.decodeDirect("0", byteArrayOf(1, 0, 0, 0, 0), 2560, 1360, 1280, 680)
        assertIs<HidDeviceRegistry.DecodeResult.Touch>(down)
        assertEquals(0f, down.contacts.single().x)
        assertEquals(0f, down.contacts.single().y)
        assertTrue(down.contacts.single().down)

        // Both coordinates intentionally exceed the logical range: the
        // descriptor-derived decoder clamps them and still emits the release.
        val up = r.decodeDirect("0", byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 2560, 1360, 1280, 680)
        assertIs<HidDeviceRegistry.DecodeResult.Touch>(up)
        assertEquals(1279f, up.contacts.single().x)
        assertEquals(679f, up.contacts.single().y)
        assertFalse(up.contacts.single().down)
    }

    @Test fun invalidUuidAndReportDoNotGuessTouch() {
        val r = registry()
        assertIs<HidDeviceRegistry.DecodeResult.UnknownUuid>(r.decodeDirect("99", ByteArray(5), 1, 1, 1, 1))
        assertIs<HidDeviceRegistry.DecodeResult.InvalidReport>(r.decodeDirect("0", ByteArray(4), 1, 1, 1, 1))
        val unsupported = B.encode(B.dict("hidDevices" to B.array(
            B.dict("uuid" to B.str("bad"), "name" to B.str("TouchScreen"), "hidDescriptor" to B.data(byteArrayOf(0x05, 0x0D, 0x09, 0x04, 0xA1.toByte(), 0x01))),
        )))
        assertIs<HidDeviceRegistry.DecodeResult.UnsupportedDescriptor>(HidDeviceRegistry.fromInfo(unsupported).decodeDirect("bad", ByteArray(5), 1, 1, 1, 1))
    }

    @Test fun descriptorRejectsReportIdRelativeSwappedAndMultiContactLayouts() {
        val swapped = lynkDescriptor.copyOf().also { it[35] = 0x31.toByte(); it[48] = 0x30.toByte() }
        assertNull(HidDescriptorParser.parse(swapped).touch)

        val relative = lynkDescriptor.copyOf().also { it[46] = 0x06.toByte() }
        assertNull(HidDescriptorParser.parse(relative).touch)

        val multi = lynkDescriptor.copyOf().also { it[44] = 0x02.toByte() }
        assertNull(HidDescriptorParser.parse(multi).touch)

        val reportId = lynkDescriptor.copyOf().toMutableList().apply {
            add(12, 0x85.toByte()); add(13, 0x01.toByte())
        }.toByteArray()
        assertNull(HidDescriptorParser.parse(reportId).touch)
    }

    @Test fun existingTestReceiverTwelveByteTouchIsRetained() {
        val r = HidDeviceRegistry.empty()
        val report = byteArrayOf(0, 1, 100, 0, 50, 0, 1, 0, 0, 0, 0, 0)
        val decoded = r.decodeDirect("2A2A2A2A", report, 1280, 720, 1280, 720)
        assertIs<HidDeviceRegistry.DecodeResult.Touch>(decoded)
        assertTrue(decoded.contacts.first().down)
    }

    @Test fun declaredLegacyTestReceiverStillGetsTwelveByteLayout() {
        val body = B.encode(B.dict(
            "hidDevices" to B.array(B.dict(
                "uuid" to B.str("2a2a2a2a"),
                "name" to B.str("TouchScreen"),
                "hidDescriptor" to B.data(byteArrayOf()),
            )),
        ))
        val report = byteArrayOf(0, 1, 100, 0, 50, 0, 1, 0, 0, 0, 0, 0)
        val decoded = HidDeviceRegistry.fromInfo(body).decodeDirect("2a2a2a2a", report, 1280, 720, 1280, 720)
        assertIs<HidDeviceRegistry.DecodeResult.Touch>(decoded)
    }

    @Test fun numericLegacyTestReceiverUuidRoutesAndArmsWithHexString() {
        val body = B.encode(B.dict(
            "hidDevices" to B.array(B.dict(
                "uuid" to B.int(0x2A2A2A2A),
                "name" to B.str("TouchScreen"),
                "hidDescriptor" to B.data(byteArrayOf()),
            )),
        ))
        val report = byteArrayOf(0, 1, 100, 0, 50, 0, 1, 0, 0, 0, 0, 0)
        val registry = HidDeviceRegistry.fromInfo(body)
        assertIs<HidDeviceRegistry.DecodeResult.Touch>(
            registry.decodeDirect(0x2A2A2A2A, report, 1280, 720, 1280, 720),
        )
        val params = com.cardroid.lib.airplay.ReceiverPlist.map(B.encode(registry.commandValues().single()))["params"] as Map<*, *>
        assertEquals("2a2a2a2a", params["uuid"])
    }

    @Test fun realButtonAndPhoneKeyNamesRemainUnsupportedWithoutDescriptorDecoder() {
        val r = registry()
        assertIs<HidDeviceRegistry.DecodeResult.UnsupportedDevice>(r.decodeDirect("1", ByteArray(1), 1, 1, 1, 1))
        assertIs<HidDeviceRegistry.DecodeResult.UnsupportedDevice>(r.decodeDirect("2", ByteArray(2), 1, 1, 1, 1))
    }

    @Test fun differentDisplayIsNotRoutedButRemainsArmed() {
        val body = B.encode(B.dict(
            "displays" to B.array(B.dict("type" to B.int(110), "uuid" to B.str("display-a"))),
            "hidDevices" to B.array(B.dict(
                "uuid" to B.str("0"), "name" to B.str("TouchScreen"),
                "displayUUID" to B.str("display-b"), "hidDescriptor" to B.data(lynkDescriptor),
            )),
        ))
        val r = HidDeviceRegistry.fromInfo(body)
        assertEquals(1, r.commandValues().size)
        assertIs<HidDeviceRegistry.DecodeResult.UnsupportedDevice>(r.decodeDirect("0", ByteArray(5), 1, 1, 1, 1))
    }

    @Test fun hidArmCommandPreservesStringUuidAndModeZero() {
        val command = registry().commandValues().first()
        val map = com.cardroid.lib.airplay.ReceiverPlist.map(B.encode(command))
        assertEquals("hidSetInputMode", map["type"])
        val params = map["params"] as Map<*, *>
        assertEquals(0L, params["hidInputMode"])
        assertEquals("0", params["uuid"])
    }

    @Test fun fullModesCommandHasThreeStatesAndTwoResources() {
        val map = com.cardroid.lib.airplay.ReceiverPlist.map(B.encode(AirPlayHandshakeOrchestrator.fullModesChangedCommand()))
        assertEquals("modesChanged", map["type"])
        val params = map["params"] as Map<*, *>
        val states = params["appStates"] as List<*>
        assertEquals(listOf(2L, 3L, 1L), states.map { (it as Map<*, *>) ["appStateID"] })
        assertEquals(0L, (states[0] as Map<*, *>) ["entity"])
        assertEquals(-1L, (states[2] as Map<*, *>) ["speechMode"])
        val resources = params["resources"] as List<*>
        assertEquals(listOf(1L, 2L), resources.map { (it as Map<*, *>) ["resourceID"] })
        resources.forEach {
            val m = it as Map<*, *>
            assertEquals(1L, m["entity"])
            assertEquals(1L, m["permanentEntity"])
        }
    }

    @Test fun eventWriterSerializesAckAndCommandWithOneRecordCounter() {
        val wire = ByteArrayOutputStream()
        val key = ByteArray(32) { it.toByte() }
        val writer = EventCommandWriter(wire, key)
        assertTrue(writer.enqueueAck("7"))
        assertTrue(writer.enqueueCommand(B.dict("type" to B.str("hidSetInputMode")), "hid"))
        assertTrue(writer.awaitIdle(2000))
        writer.close()
        val plain = CarPlayRecordInput(ByteArrayInputStream(wire.toByteArray()), key).readBytes().toString(Charsets.US_ASCII)
        assertTrue(plain.indexOf("HTTP/1.1 200 OK") >= 0)
        assertTrue(plain.indexOf("POST /command RTSP/1.0") > plain.indexOf("HTTP/1.1 200 OK"))
        assertTrue(plain.contains("CSeq: 7"))
        assertTrue(plain.contains("hidSetInputMode"))
    }
}
