package com.cardroid.app.helper.input

import com.cardroid.app.helper.HelperProtocol.TouchContact
import com.cardroid.app.helper.input.PointerTracker.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerTrackerTest {
    private fun c(id: Int, x: Int, y: Int, down: Boolean = true) = TouchContact(id, down, x, y)
    private fun kinds(e: List<PointerTracker.Event>) = e.map { it.kind }

    @Test fun singleGesture() {
        val t = PointerTracker()
        val d = t.update(listOf(c(0, 10, 20)), 100)
        assertEquals(listOf(Kind.DOWN), kinds(d)); assertEquals(100L, d[0].downTime); assertEquals(1, t.activeCount)
        assertTrue(t.update(listOf(c(0, 10, 20)), 108).isEmpty()) // unchanged snapshot -> nothing
        val m = t.update(listOf(c(0, 15, 25)), 116)
        assertEquals(listOf(Kind.MOVE), kinds(m)); assertEquals(15, m[0].pointers[0].x); assertEquals(100L, m[0].downTime)
        val u = t.update(listOf(c(0, 15, 25, false)), 124)
        assertEquals(listOf(Kind.UP), kinds(u)); assertEquals(15, u[0].pointers[0].x); assertEquals(0, t.activeCount)
    }

    @Test fun twoFingerDownAndUpOrdering() {
        val t = PointerTracker()
        t.update(listOf(c(0, 1, 1)), 0)
        val d = t.update(listOf(c(0, 1, 1), c(1, 5, 5)), 10)
        assertEquals(listOf(Kind.POINTER_DOWN), kinds(d)); assertEquals(1, d[0].index); assertEquals(2, d[0].pointers.size); assertEquals(0L, d[0].downTime)
        // first finger lifts: POINTER_UP index 0, lifted pointer still present in the event
        val up1 = t.update(listOf(c(0, 1, 1, false), c(1, 5, 5)), 20)
        assertEquals(listOf(Kind.POINTER_UP), kinds(up1)); assertEquals(0, up1[0].index); assertEquals(2, up1[0].pointers.size); assertEquals(1, t.activeCount)
        val up2 = t.update(emptyList(), 30)
        assertEquals(listOf(Kind.UP), kinds(up2)); assertEquals(1, up2[0].pointers.size); assertEquals(1, up2[0].pointers[0].slot)
    }

    @Test fun simultaneousDownAndUpInOneSnapshot() {
        val t = PointerTracker()
        val d = t.update(listOf(c(3, 1, 1), c(4, 2, 2)), 5)
        assertEquals(listOf(Kind.DOWN, Kind.POINTER_DOWN), kinds(d)); assertEquals(listOf(0, 1), d[1].pointers.map { it.pid })
        val u = t.update(emptyList(), 6)
        assertEquals(listOf(Kind.POINTER_UP, Kind.UP), kinds(u)); assertEquals(0, t.activeCount)
    }

    @Test fun idReuseAndDownTimeReset() {
        val t = PointerTracker()
        assertEquals(10L, t.update(listOf(c(0, 1, 1)), 10)[0].downTime)
        t.update(listOf(c(0, 1, 1, false)), 20)
        val d = t.update(listOf(c(0, 2, 2)), 50)
        assertEquals(50L, d[0].downTime); assertEquals(0, d[0].pointers[0].pid)
        // pid freed by a lifted pointer is reused by the next one; slot ids are arbitrary
        t.update(listOf(c(0, 2, 2), c(7, 3, 3)), 60)
        t.update(listOf(c(7, 3, 3)), 70)
        val d2 = t.update(listOf(c(7, 3, 3), c(9, 4, 4)), 80)
        assertEquals(listOf(1, 0), d2[0].pointers.map { it.pid }) // slot 7 keeps pid 1, new slot 9 takes the free pid 0
    }

    @Test fun emptySnapshotReleasesAllAndIsIdempotent() {
        val t = PointerTracker()
        t.update(listOf(c(0, 1, 1), c(1, 2, 2), c(2, 3, 3)), 0)
        val r = t.releaseAll(9)
        assertEquals(listOf(Kind.POINTER_UP, Kind.POINTER_UP, Kind.UP), kinds(r)); assertEquals(0, t.activeCount)
        assertTrue(t.releaseAll(10).isEmpty())
    }

    @Test fun moveEmittedBeforeLiftAndCapAtTen() {
        val t = PointerTracker()
        t.update(listOf(c(0, 1, 1), c(1, 2, 2)), 0)
        assertEquals(listOf(Kind.MOVE, Kind.POINTER_UP), kinds(t.update(listOf(c(0, 9, 9), c(1, 2, 2, false)), 5)))
        val t2 = PointerTracker()
        t2.update(List(12) { c(it, it, it) }, 0)
        assertEquals(PointerTracker.MAX, t2.activeCount)
    }
}
