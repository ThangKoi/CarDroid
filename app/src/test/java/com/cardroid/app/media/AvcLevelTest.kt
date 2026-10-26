package com.cardroid.app.media

import android.media.MediaCodecInfo.CodecProfileLevel as L
import org.junit.Assert.*
import org.junit.Test

class AvcLevelTest {
    @Test fun levels() {
        assertEquals(L.AVCLevel32, AvcLevel.minimum(1280, 720, 60)!!.constant)
        assertEquals(L.AVCLevel32, AvcLevel.minimum(1280, 672, 60)!!.constant)
        assertEquals(L.AVCLevel31, AvcLevel.minimum(1280, 720, 30)!!.constant)
        assertEquals(L.AVCLevel31, AvcLevel.minimum(1280, 672, 30)!!.constant)
        assertEquals(L.AVCLevel4, AvcLevel.minimum(1920, 1080, 30)!!.constant)
        assertEquals(L.AVCLevel42, AvcLevel.minimum(1920, 1080, 60)!!.constant)
    }
    @Test fun bitrateBumpsLevel() { assertEquals(L.AVCLevel41, AvcLevel.minimum(1920, 1080, 30, 30_000_000)!!.constant) }
    @Test fun idc() { assertEquals(32, AvcLevel.minimum(1280, 672, 60)!!.idc) }
    @Test fun planKeeps60WhenSupported() {
        val p = AvcLevel.plan(1280, 672, 60, 4_000_000, L.AVCLevel42) { true }
        assertEquals(60, p.fps); assertFalse(p.capped); assertEquals(L.AVCLevel32, p.level.constant)
    }
    @Test fun planCapsTo30WhenLevelTooLow() {
        val p = AvcLevel.plan(1280, 672, 60, 4_000_000, L.AVCLevel31) { true }
        assertEquals(30, p.fps); assertTrue(p.capped); assertEquals(L.AVCLevel31, p.level.constant)
        assertEquals("level 3.2 not supported at 60fps; capping to 30fps (level 3.1)", p.note)
    }
    @Test fun planCapsWhenSizeRateRejected() {
        val p = AvcLevel.plan(1280, 672, 60, 4_000_000, null) { it <= 30 }
        assertEquals(30, p.fps)
    }
}
