package com.cardroid.app.helper.display

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import com.cardroid.app.media.AvcLevel
import com.cardroid.lib.airplay.CarPlayMediaWire
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 surface encoder for the helper display. Settings ladder ported from AndroidMediaCapture.prepareEncoder
 * (baseline+computed level/tuned -> no-profile/tuned -> plain) with KEY_REPEAT_PREVIOUS_FRAME_AFTER=100 ms so a static display keeps emitting frames.
 * Encode order follows scrcpy SurfaceEncoder: encoder + input surface first, display created on that surface by the caller.
 */
class DisplayEncoder(
    private val width: Int, private val height: Int, private val requestedFps: Int, private val bitrate: Int,
    private val log: (String) -> Unit, private val sink: Sink,
) {
    interface Sink {
        fun onConfig(sps: ByteArray, pps: ByteArray)
        fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean)
        fun onFailure(reason: String)
    }

    private val closed = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile var activeFps = requestedFps; private set

    /** Creates and starts the codec; returns its input surface. Throws if no encoder accepts any setting. */
    fun prepare(): Surface {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        var last: Exception? = null
        for (info in candidates) {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
            val maxLevel = AvcLevel.maxBaselineLevel(caps.profileLevels)
            log("Encoder ${info.name} baseline levels max=${maxLevel?.let { AvcLevel.byConstant(it)?.name ?: "0x" + it.toString(16) }} advertised=${caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }.joinToString { "0x" + it.level.toString(16) }}")
            val plan = AvcLevel.plan(width, height, requestedFps, bitrate, maxLevel) { caps.videoCapabilities?.areSizeAndRateSupported(width, height, it.toDouble()) != false }
            plan.note?.let { log("Encoder: $it") }
            val fps = plan.fps; val level = plan.level
            val lowLatency = Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            for ((baseline, tuned) in listOf(true to true, false to true, false to false)) {
                var c: MediaCodec? = null
                var input: Surface? = null
                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                        if (baseline) {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                            setInteger(MediaFormat.KEY_LEVEL, level.constant)
                        }
                        if (tuned && Build.VERSION.SDK_INT >= 30) {
                            if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                        }
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
                    }
                    c = MediaCodec.createByCodecName(info.name)
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    input = c.createInputSurface()
                    c.start()
                    activeFps = fps; codec = c
                    log("Encoder ${info.name} started ${width}x$height baseline=$baseline level=${level.name} fps=$fps lowLatency=${tuned && lowLatency} tuned=$tuned repeatAfterUs=$REPEAT_AFTER_US")
                    return input
                } catch (e: Exception) {
                    log("Encoder ${info.name} rejected baseline=$baseline tuned=$tuned: ${(e as? MediaCodec.CodecException)?.diagnosticInfo ?: e.message}")
                    last = e
                    runCatching { c?.release() }; runCatching { input?.release() }
                }
            }
        }
        throw IllegalStateException("No usable H264 surface encoder", last)
    }

    /** Starts the drain thread (call after the display exists). */
    fun startDraining() {
        val c = codec ?: error("prepare() not called")
        thread = Thread({
            if (Looper.myLooper() == null) Looper.prepare() // scrcpy does this on the encode thread
            drain(c)
        }, "helper-encode").apply { isDaemon = true; start() }
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        if (closed.get()) return
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var configured = false
        var outputFrames = 0L
        var lastOutputNs = 0L
        var stallReported = false
        try {
            while (!closed.get()) {
                val index = c.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = c.outputFormat
                    val nals = mutableListOf<ByteArray>()
                    for (name in listOf("csd-0", "csd-1")) format.getByteBuffer(name)?.duplicate()?.let { b ->
                        val data = ByteArray(b.remaining()); b.get(data); nals += CarPlayMediaWire.nalUnits(data)
                    }
                    val sps = nals.firstOrNull { (it[0].toInt() and 31) == 7 } ?: error("Encoder missing SPS")
                    val pps = nals.firstOrNull { (it[0].toInt() and 31) == 8 } ?: error("Encoder missing PPS")
                    log("Encoder: SPS level_idc=${sps.getOrNull(3)?.toInt()?.and(0xFF)} profile_idc=${sps.getOrNull(1)?.toInt()?.and(0xFF)}"); sink.onConfig(sps, pps); configured = true
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Keep this diagnostic on the helper side. The app's video sender has its own
                    // queue/write metrics, so this tells us whether the codec stopped producing
                    // buffers before the frame ever reached the VIDEO socket.
                    if (configured && lastOutputNs != 0L) {
                        val ageMs = (System.nanoTime() - lastOutputNs) / 1_000_000L
                        if (ageMs >= OUTPUT_STALL_LOG_MS && !stallReported) {
                            log("Encoder: no output for ${ageMs}ms after $outputFrames frames (drain alive)")
                            stallReported = true
                        }
                    }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(configured) { "Frame before codec configuration" }
                            val b = c.getOutputBuffer(index) ?: error("No codec buffer")
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val data = ByteArray(info.size); b.get(data)
                            val now = System.nanoTime()
                            if (stallReported) {
                                log("Encoder: output resumed after ${(now - lastOutputNs) / 1_000_000L}ms; frames=$outputFrames")
                                stallReported = false
                            }
                            outputFrames++
                            lastOutputNs = now
                            sink.onFrame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    } finally { c.releaseOutputBuffer(index, false) }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) { log("Encoder failed ${e.javaClass.simpleName}: ${e.message}"); sink.onFailure("${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        val t = thread
        if (t != null && t !== Thread.currentThread()) { t.interrupt(); runCatching { t.join(1000) } }
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
    }

    companion object {
        const val REPEAT_AFTER_US = 100_000L
        private const val OUTPUT_STALL_LOG_MS = 1_000L
    }
}
