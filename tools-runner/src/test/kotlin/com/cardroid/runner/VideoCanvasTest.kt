package com.cardroid.runner

import com.cardroid.lib.airplay.VideoCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCanvasTest {
    @Test
    fun standardKeepsOriginalCarCanvas() {
        // LYNK 2560x1360 panel: the pre-existing 1280x672 stream, 160 dpi, 4 Mbps.
        val size = VideoCanvas.size(2560, 1360)
        assertEquals(VideoCanvas.Size(1280, 672), size)
        assertEquals(160, VideoCanvas.dpi(size.width, size.height))
        assertEquals(4_000_000, VideoCanvas.bitrate(size.width, size.height))
    }

    @Test
    fun nativeStreamsPanelSizeWithMatchingDensity() {
        val size = VideoCanvas.size(2560, 1360, VideoCanvas.NATIVE_MAX_LONG_SIDE)
        assertEquals(VideoCanvas.Size(2560, 1360), size)
        // Same layout in dp as 1280 @ 160 dpi, at twice the pixel density.
        assertEquals(320, VideoCanvas.dpi(size.width, size.height))
        assertEquals(VideoCanvas.MAX_BITRATE, VideoCanvas.bitrate(size.width, size.height))
    }

    @Test
    fun nativeNeverUpscalesSmallPanels() {
        val size = VideoCanvas.size(800, 480, VideoCanvas.NATIVE_MAX_LONG_SIDE)
        assertEquals(VideoCanvas.Size(800, 480), size)
        assertEquals(160, VideoCanvas.dpi(size.width, size.height))
        assertEquals(VideoCanvas.BASE_BITRATE, VideoCanvas.bitrate(size.width, size.height))
    }

    @Test
    fun nativeCapsOversizedPanels() {
        val size = VideoCanvas.size(3840, 1080, VideoCanvas.NATIVE_MAX_LONG_SIDE)
        assertEquals(2560, size.width)
        assertEquals(0, size.height % 16)
    }
}
