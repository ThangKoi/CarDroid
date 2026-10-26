package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayBplist as B
import com.cardroid.lib.airplay.CarDiagSummary
import kotlin.test.*

class CarDiagSummaryTest {
    private val now = 1_700_000_000_000L

    private fun info() = B.encode(B.dict(
        "features" to B.int(0x1_0000_0001L),
        "sourceVersion" to B.str("220.68"),
        "model" to B.str("TestReceiver1,1"),
        "hidDevices" to B.array(
            B.dict("name" to B.str("Touch"), "uuid" to B.str("2A2A2A2A"), "usagePage" to B.int(13), "usage" to B.int(4),
                "hidDescriptor" to B.data(ByteArray(120))),
            B.dict("name" to B.str("Knob"), "uuid" to B.str("2A2A2A2B")),
        ),
        "displays" to B.array(B.dict("type" to B.int(110), "widthPixels" to B.int(1280), "heightPixels" to B.int(672),
            "primaryInputDevice" to B.int(1), "maxFPS" to B.int(60), "features" to B.int(5))),
    ))

    @Test fun infoSummaryListsHidDevicesDisplaysAndFeatures() {
        val line = CarDiagSummary.summarizeInfo(info(), now)
        assertTrue(line.startsWith("[car-diag] info t="))
        assertTrue("hidDevices=2:" in line, line)
        assertTrue("usagePage=13 usage=4 name=Touch uuid=2A2A2A2A descriptorLen=120" in line, line)
        assertTrue("descriptorLen=none" in line, line)
        assertTrue("1280x672 type=110" in line && "primaryInputDevice=1" in line && "maxFPS=60" in line, line)
        assertTrue("features=0x100000001(4294967297)" in line, line)
        assertTrue("sourceVersion=220.68" in line && "model=TestReceiver1,1" in line, line)
        assertTrue("keys=" in line && "hidDevices" in line.substringAfter("keys="), line)
        assertFalse('\n' in line)
    }

    @Test fun infoSummaryToleratesMissingKeysAndGarbage() {
        val minimal = CarDiagSummary.summarizeInfo(B.encode(B.dict("name" to B.str("car"))), now)
        assertTrue("hidDevices=absent" in minimal && "displays=absent" in minimal && "features=null" in minimal, minimal)
        assertTrue("(not a readable bplist)" in CarDiagSummary.summarizeInfo(byteArrayOf(1, 2, 3), now))
        assertTrue("(not a readable bplist)" in CarDiagSummary.summarizeInfo(ByteArray(0), now))
    }

    @Test fun longValuesAreTruncatedAndBytesNeverDumped() {
        val line = CarDiagSummary.summarizeInfo(B.encode(B.dict("name" to B.str("x".repeat(500)), "blob" to B.data(ByteArray(5000)))), now)
        assertTrue("name=" + "x".repeat(200) + "…(500)" in line, line)
        assertFalse("x".repeat(201) in line)
        assertEquals("<5000B>", CarDiagSummary.fmt(ByteArray(5000)))
        assertTrue(CarDiagSummary.fmt("y".repeat(1000)).length <= CarDiagSummary.MAX_VALUE_CHARS + 10)
    }

    @Test fun setupResponseShowsEnabledFeaturesAndPorts() {
        val line = CarDiagSummary.summarizeSetupResponse(B.encode(B.dict(
            "eventPort" to B.int(49152), "timingPort" to B.int(0), "enabledFeatures" to B.array(B.str("iAPChannel")))), now)
        assertTrue("enabledFeatures=[iAPChannel]" in line && "eventPort=49152" in line && "timingPort=0" in line, line)
        val bare = CarDiagSummary.summarizeSetupResponse(B.encode(B.dict("eventPort" to B.int(7))), now)
        assertTrue("enabledFeatures=absent" in bare && "timingPort=null" in bare, bare)
    }

    @Test fun mediaSetupResponseListsStreams() {
        val line = CarDiagSummary.summarizeMediaSetupResponse(B.encode(B.dict("streams" to B.array(
            B.dict("type" to B.int(110), "streamID" to B.int(1), "dataPort" to B.int(50000)),
            B.dict("type" to B.int(130), "streamID" to B.int(3), "dataPort" to B.int(50002))))), now)
        assertTrue("streams=2:[type=110/streamID=1/dataPort=50000;type=130/streamID=3/dataPort=50002]" in line, line)
    }

    @Test fun commandCountsAndHidLines() {
        val c = CarDiagSummary.CommandCounts()
        assertEquals(1, c.record("iAPSendMessage")); assertEquals(2, c.record("iAPSendMessage"))
        assertEquals(1, c.record("hidSendReport")); c.record(null); c.record(42)
        assertEquals(5, c.total()); assertEquals(1, c.hidCount())
        val s = c.summary("+10s", now)
        assertTrue("total=5 hidSendReport=1" in s && "iAPSendMessage=2" in s && "unknown=2" in s && "+10s" in s, s)
        val hid = CarDiagSummary.hidLine(3, 1500, mapOf("uuid" to "2A2A2A2A", "hidReport" to byteArrayOf(1, 2, 3)), now)
        assertTrue("hid #3" in hid && "+1500ms" in hid && "uuid=2A2A2A2A len=3 first=010203" in hid, hid)
        assertTrue(CarDiagSummary.logHid(50) && !CarDiagSummary.logHid(51) && CarDiagSummary.logHid(60))
        assertTrue(Regex("\\d\\d:\\d\\d:\\d\\d\\.\\d{3}").matches(CarDiagSummary.wall(now)))
    }
}
