package com.cardroid.runner

import com.cardroid.lib.airplay.AudioCodec
import com.cardroid.lib.airplay.AudioNegotiation
import com.cardroid.lib.airplay.AudioProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioNegotiationTest {
    @Test
    fun carProfileChoosesAdvertisedType102Aac48k() {
        val info = mapOf<String, Any?>(
            "audioFormats" to listOf(
                mapOf<String, Any?>("type" to 102L, "audioType" to "media", "audioOutputFormats" to (1L shl 23)),
                mapOf<String, Any?>("type" to 100L, "audioType" to "media", "audioOutputFormats" to (1L shl 28)),
            ),
            "audioLatencies" to listOf(
                mapOf<String, Any?>("type" to 102L, "audioType" to "media", "sampleRate" to 48_000L, "outputLatencyMicros" to 35_000L),
            ),
        )
        val profile = AudioNegotiation.preferred(info)
        assertEquals(102, profile.streamType)
        assertEquals(AudioCodec.AAC_LC, profile.codec)
        assertEquals(48_000, profile.sampleRate)
        assertEquals(AudioProfile.FORMAT_AAC_LC_48K_STEREO, profile.formatBit)
        assertEquals(35, profile.latencyMs)
        assertEquals(1024, profile.samplesPerPacket)
    }

    @Test
    fun pcmFallbackOnlyUsesStereoMediaBits() {
        val info = mapOf<String, Any?>(
            "audioFormats" to listOf(
                mapOf<String, Any?>("type" to 100L, "audioType" to "voice", "audioOutputFormats" to (1L shl 15)),
                mapOf<String, Any?>("type" to 100L, "audioType" to "media", "audioOutputFormats" to (1L shl 11)),
            ),
        )
        val profile = AudioNegotiation.preferred(info)
        assertEquals(AudioCodec.PCM, profile.codec)
        assertEquals(44_100, profile.sampleRate)
        assertEquals("media", profile.audioType)
        assertTrue(AudioNegotiation.candidates(info).none { it.audioType == "voice" })
    }

    @Test
    fun iphoneAudioSetupMatchesRealIphoneCapture() {
        // Receiver log of iPhone18,4 / iOS 26.7.1: {audioLatencyMs=1000, streamConnectionID, spf=1024, controlPort,
        //   type=102, audioFormat=8388608, supportsHighAccuracyTimestamps=true, audioType=media}
        val aac = com.cardroid.lib.airplay.AudioProfile(
            streamType = 102, audioType = "media", formatBit = 1L shl 23, sampleRate = 48_000,
            latencyMs = com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator.IPHONE_AUDIO_LATENCY_MS,
            codec = com.cardroid.lib.airplay.AudioCodec.AAC_LC,
        )
        val map = com.cardroid.lib.airplay.ReceiverPlist.map(com.cardroid.lib.airplay.AirPlayBplist.encode(
            com.cardroid.lib.airplay.AirPlayBplist.dict("s" to
                com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator.iphoneAudioStream(aac, 5705906374803867755L, 65224))))
        val stream = map["s"] as Map<*, *>
        kotlin.test.assertEquals(
            setOf("type", "streamConnectionID", "audioType", "audioFormat", "spf", "audioLatencyMs", "controlPort", "supportsHighAccuracyTimestamps"),
            stream.keys.map { it.toString() }.toSet(),
        )
        kotlin.test.assertEquals(102L, stream["type"]); kotlin.test.assertEquals(8388608L, stream["audioFormat"])
        kotlin.test.assertEquals(1024L, stream["spf"]); kotlin.test.assertEquals(1000L, stream["audioLatencyMs"])
        kotlin.test.assertEquals(65224L, stream["controlPort"]); kotlin.test.assertEquals(true, stream["supportsHighAccuracyTimestamps"])
        kotlin.test.assertEquals("media", stream["audioType"])
    }
}
