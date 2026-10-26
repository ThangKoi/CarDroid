package com.cardroid.lib.airplay

/**
 * Picks the encoded video canvas, virtual-display density and bitrate for a
 * receiver display.
 *
 * Standard mode keeps the long side at 1280 px (the original behaviour), so a
 * 2560x1360 car panel gets a 1280x672 stream that the car upscales 2x.  Native
 * mode streams the panel's own pixel size; density and bitrate scale with it so
 * the car UI keeps the same layout in dp while rendering at full sharpness.
 */
object VideoCanvas {
    const val STANDARD_LONG_SIDE = 1280
    /** Largest long side native mode will request (AVC level 5.x territory). */
    const val NATIVE_MAX_LONG_SIDE = 2560
    const val BASE_DPI = 160
    const val BASE_BITRATE = 4_000_000
    const val MAX_BITRATE = 12_000_000
    private const val BASE_PIXELS = 1280 * 672

    data class Size(val width: Int, val height: Int)

    /** Scales [receiverWidth]x[receiverHeight] down to [maxLongSide] (never up) and aligns both sides to 16. */
    fun size(receiverWidth: Int, receiverHeight: Int, maxLongSide: Int = STANDARD_LONG_SIDE): Size {
        val factor = minOf(1.0, maxLongSide.toDouble() / maxOf(receiverWidth, receiverHeight))
        fun aligned(value: Int) = ((value * factor / 16).toInt().coerceAtLeast(1) * 16)
        return Size(aligned(receiverWidth), aligned(receiverHeight))
    }

    /** Density that keeps the layout of a 1280-wide 160 dpi canvas at any canvas width. */
    fun dpi(canvasWidth: Int, canvasHeight: Int): Int =
        maxOf(BASE_DPI, BASE_DPI * maxOf(canvasWidth, canvasHeight) / STANDARD_LONG_SIDE)

    /** Bitrate scaled by pixel count from 4 Mbps at 1280x672, clamped to [BASE_BITRATE]..[MAX_BITRATE]. */
    fun bitrate(canvasWidth: Int, canvasHeight: Int): Int {
        val scaled = BASE_BITRATE.toLong() * canvasWidth * canvasHeight / BASE_PIXELS
        return scaled.coerceIn(BASE_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }
}
