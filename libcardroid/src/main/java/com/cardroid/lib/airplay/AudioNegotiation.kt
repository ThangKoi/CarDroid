package com.cardroid.lib.airplay

/**
 * Selects a media audio profile from the receiver's `/info` declaration.
 *
 * A receiver can return 200 for a stream it does not actually route to its
 * media mixer.  We therefore only generate profiles for formats the receiver
 * explicitly advertises, and prefer the dedicated MainHighAudio AAC path when
 * it is present.  The real car used by CarDroid advertises AAC-LC 48 kHz
 * stereo on type 102; its type-100 media entry has no PCM stereo bits.
 */
object AudioNegotiation {
    private const val FALLBACK_LATENCY_MS = 100L

    fun candidates(info: Map<*, *>?, log: (String) -> Unit = {}): List<AudioProfile> {
        val formats = (info?.get("audioFormats") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
        if (formats.isEmpty()) {
            log("AirPlay: /info has no audioFormats; using ${AudioProfile.DEFAULT.label}")
            return listOf(AudioProfile.DEFAULT)
        }

        val result = mutableListOf<AudioProfile>()

        // AAC is only valid when the receiver advertises the matching stream
        // type and media audioType.  Do not send AAC bytes to type 100 merely
        // because a different channel happens to expose an AAC bit.
        val aacFormats = listOf(
            Triple(AudioProfile.STREAM_TYPE_MAIN_HIGH_AUDIO, AudioProfile.FORMAT_AAC_LC_48K_STEREO, 48_000),
            Triple(AudioProfile.STREAM_TYPE_MAIN_HIGH_AUDIO, AudioProfile.FORMAT_AAC_LC_44K_STEREO, 44_100),
            Triple(AudioProfile.STREAM_TYPE_MAIN_AUDIO, AudioProfile.FORMAT_AAC_LC_48K_STEREO, 48_000),
            Triple(AudioProfile.STREAM_TYPE_MAIN_AUDIO, AudioProfile.FORMAT_AAC_LC_44K_STEREO, 44_100),
        )
        for ((streamType, bit, rate) in aacFormats) {
            for (entry in formats) {
                if ((entry["type"] as? Number)?.toInt() != streamType) continue
                if ((entry["audioType"] as? String)?.lowercase() != "media") continue
                val output = (entry["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                if ((output and bit) == 0L) continue
                result += AudioProfile(
                    streamType = streamType,
                    audioType = "media",
                    formatBit = bit,
                    sampleRate = rate,
                    latencyMs = latencyMs(info, streamType, "media", rate),
                    codec = AudioCodec.AAC_LC,
                )
            }
        }

        // PCM remains the compatibility path for receivers that advertise it.
        // Restrict this to media-like channels so voice/telephony formats do not
        // accidentally become a music stream.
        val pcmFormats = listOf(
            AudioProfile.FORMAT_PCM_48K_STEREO to 48_000,
            AudioProfile.FORMAT_PCM_44K_STEREO to 44_100,
        )
        for ((bit, rate) in pcmFormats) {
            for (entry in formats) {
                if ((entry["type"] as? Number)?.toInt() != AudioProfile.STREAM_TYPE_MAIN_AUDIO) continue
                val audioType = (entry["audioType"] as? String)?.lowercase() ?: continue
                if (audioType !in setOf("media", "compatibility", "default")) continue
                val output = (entry["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                if ((output and bit) == 0L) continue
                result += AudioProfile(
                    streamType = AudioProfile.STREAM_TYPE_MAIN_AUDIO,
                    audioType = audioType,
                    formatBit = bit,
                    sampleRate = rate,
                    latencyMs = latencyMs(info, AudioProfile.STREAM_TYPE_MAIN_AUDIO, audioType, rate),
                    codec = AudioCodec.PCM,
                )
            }
        }

        val distinct = result.distinctBy { it.key }
        if (distinct.isEmpty()) {
            log("AirPlay: /info has no supported stereo media format; using ${AudioProfile.DEFAULT.label}")
            return listOf(AudioProfile.DEFAULT)
        }
        log("AirPlay: audio candidates=${distinct.joinToString { it.label }}")
        return distinct
    }

    fun preferred(info: Map<*, *>?, log: (String) -> Unit = {}): AudioProfile =
        candidates(info, log).first()


    private fun latencyMs(info: Map<*, *>?, streamType: Int, audioType: String, sampleRate: Int): Long {
        val entries = (info?.get("audioLatencies") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
        val declaredMicros = entries.firstOrNull { entry ->
            (entry["type"] as? Number)?.toInt() == streamType &&
                (entry["audioType"] as? String)?.lowercase() == audioType &&
                declaredSampleRate(entry) in listOf(null, sampleRate)
        }?.get("outputLatencyMicros") as? Number
        return declaredMicros?.toLong()?.div(1_000L)?.takeIf { it > 0L } ?: FALLBACK_LATENCY_MS
    }

    private fun declaredSampleRate(entry: Map<*, *>): Int? =
        (entry["sampleRate"] as? Number)?.toInt()
            ?: (entry["sampleRateHz"] as? Number)?.toInt()
}
