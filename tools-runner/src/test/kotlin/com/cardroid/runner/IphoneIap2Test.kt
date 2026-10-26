package com.cardroid.runner

import com.cardroid.lib.Iap2ControlSession
import com.cardroid.lib.IphoneIap2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IphoneIap2Test {
    private fun id(bytes: ByteArray) = Iap2ControlSession.parse(bytes)!!.first

    @Test
    fun capturedConstantsAreWellFormedControlMessages() {
        // Header length field must equal the array length, or the car drops the frame.
        for ((bytes, expected) in listOf(
            IphoneIap2.NOW_PLAYING_UPDATE_IDLE to 0x5001,
            IphoneIap2.COMMUNICATIONS_UPDATE_IDLE to 0x4158,
            IphoneIap2.START_LOCATION_INFORMATION to 0xFFFA,
            IphoneIap2.GPRMC_DATA_STATUS to 0xFFF0,
        )) {
            assertEquals(bytes.size, ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
            assertEquals(expected, id(bytes))
        }
    }

    @Test
    fun subscriptionsGetEmptyStateReplies() {
        assertEquals(0x5001, id(assertNotNull(IphoneIap2.replyFor(0x5000))))
        assertEquals(0x4155, id(assertNotNull(IphoneIap2.replyFor(0x4154))))
        assertEquals(0x4158, id(assertNotNull(IphoneIap2.replyFor(0x4157))))
        assertEquals(0x4171, id(assertNotNull(IphoneIap2.replyFor(0x4170))))
        assertNull(IphoneIap2.replyFor(0x6800)) // StartHID: no reply, HID goes over the event channel
    }

    @Test
    fun postAuthBurstMatchesRealIphoneOrder() {
        val burst = IphoneIap2.postAuthBurst(1_761_379_200_000L, 420, false, sessionId = "S")
        assertEquals(listOf(0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0), burst.map(::id))
        // 0x4E0B: u64 Unix seconds BE, s16 tz minutes (UTC+7 = 420), u8 DST.
        val time = Iap2ControlSession.parseParams(Iap2ControlSession.parse(burst[3])!!.second)
        assertEquals(1_761_379_200L, time[0].value.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) })
        assertEquals(420, ((time[1].value[0].toInt()) shl 8) or (time[1].value[1].toInt() and 0xff))
        assertEquals(0, time[2].value[0].toInt())
    }
    @Test fun friendlyNameIsUnifiedAndTerminated() {
        val identity = com.cardroid.lib.IPhoneIdentity()
        assertEquals(identity.name, identity.bluetoothName)
        assertEquals(identity.name, com.cardroid.lib.airplay.AirPlayParams("host", 7000, "session").name)
        val message = IphoneIap2.postAuthBurst(0, 0, false).first { id(it) == IphoneIap2.DEVICE_INFORMATION_UPDATE }
        val value = Iap2ControlSession.parseParams(Iap2ControlSession.parse(message)!!.second).single().value
        assertEquals(identity.name + "\u0000", value.toString(Charsets.UTF_8))
    }

    @Test fun liveNowPlayingEncodesUnicodeAndPlaybackState() {
        val snapshot = com.cardroid.lib.NowPlayingState("Cơm Áo Gạo Tiền", "Tuấn Phong", "Album", "Zing MP3", 123456, 3603, 2)
        val message = Iap2ControlSession.parse(snapshot.message())!!
        assertEquals(0x5001, message.first)
        val groups = Iap2ControlSession.parseParams(message.second).associate { it.id to it.value }
        val item = Iap2ControlSession.parseParams(groups.getValue(0)).associate { it.id to it.value }
        assertEquals(snapshot.title + "\u0000", item.getValue(1).toString(Charsets.UTF_8))
        assertEquals(snapshot.artist + "\u0000", item.getValue(12).toString(Charsets.UTF_8))
        assertEquals(snapshot.album + "\u0000", item.getValue(6).toString(Charsets.UTF_8))
        assertNull(item[26]) // artwork transfer identifier must not contain album text
        assertEquals(123456, java.nio.ByteBuffer.wrap(item.getValue(4)).int)
        val state = Iap2ControlSession.parseParams(groups.getValue(1)).associate { it.id to it.value }
        assertEquals(2, state.getValue(0).single().toInt())
        assertEquals(3603, java.nio.ByteBuffer.wrap(state.getValue(1)).int)
    }
}
