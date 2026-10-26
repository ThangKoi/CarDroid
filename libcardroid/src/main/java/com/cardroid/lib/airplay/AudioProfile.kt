package com.cardroid.lib.airplay

/** Encoding used by an AirPlay media audio stream. */
enum class AudioCodec {
    /** Signed 16-bit PCM samples, sent big-endian on the wire. */
    PCM,

    /** AAC-LC access units, sent as raw (no ADTS) bytes. */
    AAC_LC,
}

/**
 * One negotiated audio stream profile.
 *
 * The receiver advertises formats per `(stream type, audioType)` pair.  Keeping
 * those values together prevents the capture rate, SETUP declaration, RTP
 * payload type, and packet sample clock from drifting apart.
 */
data class AudioProfile(
    val streamType: Int = STREAM_TYPE_MAIN_AUDIO,
    val audioType: String = "media",
    val formatBit: Long = FORMAT_PCM_44K_STEREO,
    val sampleRate: Int = 44_100,
    val latencyMs: Long = 100,
    val codec: AudioCodec = AudioCodec.PCM,
    val samplesPerPacket: Int = if (codec == AudioCodec.AAC_LC) AAC_FRAME_SAMPLES else PCM_SAMPLES_PER_PACKET,
) {
    val key: String get() = "$streamType|${audioType.lowercase()}|$formatBit|${codec.name}"

    val label: String
        get() = "t$streamType/$audioType/0x${formatBit.toString(16)}(${sampleRate}Hz,${codec.name},${latencyMs}ms)"

    companion object {
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102

        /** PCM 44.1 kHz, 16-bit, stereo. */
        const val FORMAT_PCM_44K_STEREO: Long = 1L shl 11

        /** PCM 48 kHz, 16-bit, stereo. */
        const val FORMAT_PCM_48K_STEREO: Long = 1L shl 15

        /** AAC-LC 44.1 kHz, stereo. */
        const val FORMAT_AAC_LC_44K_STEREO: Long = 1L shl 22

        /** AAC-LC 48 kHz, stereo. */
        const val FORMAT_AAC_LC_48K_STEREO: Long = 1L shl 23

        const val PCM_SAMPLES_PER_PACKET = 352
        const val AAC_FRAME_SAMPLES = 1024

        val DEFAULT: AudioProfile = AudioProfile()
    }
}
