package com.cardroid.app.helper.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPacingTest {
    private val min = 16_666_667L
    private val idle = 100_000_000L

    @Test fun freshFrameDrawsOnlyAfterMinInterval() {
        assertFalse(RelayPacing.shouldDraw(true, 5_000_000L, min, idle))
        assertTrue(RelayPacing.shouldDraw(true, min, min, idle))
    }

    @Test fun staticDisplayKeepsDrawingEveryIdleInterval() {
        assertFalse(RelayPacing.shouldDraw(false, 99_000_000L, min, idle))
        assertTrue(RelayPacing.shouldDraw(false, idle, min, idle))
        assertTrue(RelayPacing.shouldDraw(false, 10 * idle, min, idle)) // never gives up (unlike GraphicBufferSource repeat)
    }

    @Test fun waitIsBoundedAndPositive() {
        assertEquals(100L, RelayPacing.waitMs(false, 0L, min, idle))
        assertEquals(12L, RelayPacing.waitMs(true, 5_000_000L, min, idle))
        assertEquals(1L, RelayPacing.waitMs(true, 500_000_000L, min, idle))
    }
}
