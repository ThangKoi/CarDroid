package com.cardroid.app.media

import android.media.MediaCodecInfo.CodecProfileLevel as L

/** H.264 level selection (ITU-T H.264 Table A-1) so the encoder is configured at a level that actually fits size+fps. */
object AvcLevel {
    /** [constant] = MediaCodecInfo.CodecProfileLevel.AVCLevelXX, [idc] = SPS level_idc, limits for baseline-family profiles. */
    class Entry(val name: String, val constant: Int, val idc: Int, val maxFs: Int, val maxMbps: Int, val maxBrBps: Int)

    val TABLE = listOf(
        Entry("3.1", L.AVCLevel31, 31, 3600, 108_000, 14_000_000),
        Entry("3.2", L.AVCLevel32, 32, 5120, 216_000, 20_000_000),
        Entry("4", L.AVCLevel4, 40, 8192, 245_760, 20_000_000),
        Entry("4.1", L.AVCLevel41, 41, 8192, 245_760, 50_000_000),
        Entry("4.2", L.AVCLevel42, 42, 8704, 522_240, 50_000_000),
        Entry("5", L.AVCLevel5, 50, 22080, 589_824, 135_000_000),
        Entry("5.1", L.AVCLevel51, 51, 36864, 983_040, 240_000_000),
        Entry("5.2", L.AVCLevel52, 52, 36864, 2_073_600, 240_000_000),
    )

    private fun mbs(v: Int) = (v + 15) / 16

    /** Lowest level (never below 3.1) whose frame size, macroblock rate and bitrate limits fit; null if even 5.1 cannot. */
    fun minimum(width: Int, height: Int, fps: Int, bitrateBps: Int = 0): Entry? {
        val fs = mbs(width) * mbs(height); val mbps = fs.toLong() * fps
        return TABLE.firstOrNull { fs <= it.maxFs && mbps <= it.maxMbps && bitrateBps <= it.maxBrBps }
    }

    fun byConstant(constant: Int) = TABLE.firstOrNull { it.constant == constant }

    class Plan(val fps: Int, val level: Entry, val capped: Boolean, val note: String?)

    /**
     * Picks fps+level. [maxLevelConstant] = highest baseline level the codec advertises (null = unknown, trust the table);
     * [sizeRateOk] = codec's areSizeAndRateSupported. If the requested fps does not fit, caps to 30 fps.
     */
    fun plan(width: Int, height: Int, requestedFps: Int, bitrateBps: Int, maxLevelConstant: Int?, sizeRateOk: (Int) -> Boolean): Plan {
        fun fits(fps: Int): Entry? = minimum(width, height, fps, bitrateBps)?.takeIf { (maxLevelConstant == null || it.constant <= maxLevelConstant) && sizeRateOk(fps) }
        fits(requestedFps)?.let { return Plan(requestedFps, it, false, null) }
        val need = minimum(width, height, requestedFps, bitrateBps)
        val cap = if (requestedFps > 30) 30 else requestedFps
        val l30 = fits(cap) ?: minimum(width, height, cap, bitrateBps) ?: TABLE.first()
        return Plan(cap, l30, cap != requestedFps, "level ${need?.name ?: "?"} not supported at ${requestedFps}fps; capping to ${cap}fps (level ${l30.name})")
    }

    /** Highest AVC level the codec advertises for the given baseline-family profiles (null if none listed). */
    fun maxBaselineLevel(levels: Array<L>): Int? =
        levels.filter { it.profile == L.AVCProfileBaseline || it.profile == L.AVCProfileConstrainedBaseline }.maxOfOrNull { it.level }
}
