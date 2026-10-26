package com.cardroid.app.helper.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRoutingTest {
    @Test fun mediaIndexToKeycode() {
        assertEquals(126, InputRouting.mediaKeycode(1)); assertEquals(127, InputRouting.mediaKeycode(2)); assertEquals(85, InputRouting.mediaKeycode(3))
        assertEquals(87, InputRouting.mediaKeycode(4)); assertEquals(88, InputRouting.mediaKeycode(5))
        assertNull(InputRouting.mediaKeycode(0)); assertNull(InputRouting.mediaKeycode(6)); assertNull(InputRouting.mediaKeycode(200))
    }

    @Test fun knobEdgesOnly() {
        assertEquals(listOf(3), InputRouting.knobKeys(0, 2)); assertEquals(listOf(4), InputRouting.knobKeys(0, 4))
        assertEquals(emptyList<Int>(), InputRouting.knobKeys(2, 2)) // held, repeated report
        assertEquals(emptyList<Int>(), InputRouting.knobKeys(2, 0)) // release
        assertEquals(listOf(3, 4), InputRouting.knobKeys(0, 6)); assertEquals(listOf(4), InputRouting.knobKeys(2, 6))
        assertEquals(emptyList<Int>(), InputRouting.knobKeys(0, 1)) // select/knob motion only
    }

    @Test fun mediaDedupeWindow() {
        assertTrue(InputRouting.mediaShouldFire(3, 0, 0, 1000))
        assertFalse(InputRouting.mediaShouldFire(3, 3, 1000, 1100)) // same press repeated
        assertTrue(InputRouting.mediaShouldFire(3, 3, 1000, 1600)) // press without release, pressed again later
        assertTrue(InputRouting.mediaShouldFire(4, 3, 1000, 1010)) // different button
        assertFalse(InputRouting.mediaShouldFire(0, 3, 1000, 2000)); assertFalse(InputRouting.mediaShouldFire(6, 0, 0, 2000))
    }
}
