package com.cardroid.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** Small MediaCodec wrapper for the raw AAC-LC access units used by AirPlay type 102. */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int = 2,
    private val log: (String) -> Unit,
) : java.io.Closeable {
    companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val FRAME_SAMPLES = 1024
        const val BIT_RATE = 128_000
        private const val INPUT_TIMEOUT_US = 10_000L

        fun pcmBytes(channels: Int = 2): Int = FRAME_SAMPLES * channels * 2
    }

    val inputFrameBytes: Int = pcmBytes(channels)
    private var codec: MediaCodec? = null
    private var presentationUs = 0L
    private var started = false
    private var adtsLogged = false

    fun start(): Boolean {
        if (started) return true
        return try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
            }
            codec = MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            started = true
            log("Capture: AAC-LC encoder started ${sampleRate}Hz ${channels}ch frame=$inputFrameBytes B")
            true
        } catch (e: Exception) {
            log("Capture: AAC-LC encoder unavailable: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    /** Queues one complete PCM frame and returns any raw AAC access units produced. */
    fun encode(pcm: ByteArray, size: Int = pcm.size): List<ByteArray> {
        val c = codec ?: return emptyList()
        require(size in 1..pcm.size)
        val output = mutableListOf<ByteArray>()
        try {
            val input = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (input >= 0) {
                val buffer = c.getInputBuffer(input) ?: error("AAC input buffer unavailable")
                val count = size.coerceAtMost(buffer.capacity())
                buffer.clear(); buffer.put(pcm, 0, count)
                c.queueInputBuffer(input, 0, count, presentationUs, 0)
                presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
            }
            drain(c, output)
        } catch (e: Exception) {
            log("Capture: AAC encode failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return output
    }

    private fun drain(c: MediaCodec, output: MutableList<ByteArray>) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = c.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> if (index >= 0) {
                    try {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val frame = ByteArray(info.size)
                            buffer.get(frame)
                            output += stripAdts(frame)
                        }
                    } finally {
                        c.releaseOutputBuffer(index, false)
                    }
                } else return
            }
        }
    }

    /** AirPlay carries an AAC access unit, not an ADTS transport frame. */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7 || (frame[0].toInt() and 0xff) != 0xff || (frame[1].toInt() and 0xf6) != 0xf0) return frame
        val protectionAbsent = frame[1].toInt() and 1
        val header = if (protectionAbsent != 0) 7 else 9
        if (frame.size <= header) return frame
        if (!adtsLogged) { adtsLogged = true; log("Capture: stripped ADTS header from AAC output") }
        return frame.copyOfRange(header, frame.size)
    }

    override fun close() {
        started = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

}
