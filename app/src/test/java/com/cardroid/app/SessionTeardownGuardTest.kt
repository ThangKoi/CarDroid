package com.cardroid.app

import com.cardroid.lib.airplay.AirPlayTimingSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SessionTeardownGuardTest {
    @Test fun secondEntryIsRejectedUntilRearmed() {
        val g = SessionTeardownGuard()
        assertTrue(g.tryEnter()); assertFalse(g.tryEnter())
        g.rearm()
        assertTrue(g.tryEnter())
    }

    @Test fun concurrentCallersRunOnce() {
        val g = SessionTeardownGuard(); val n = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        repeat(8) { pool.execute { go.await(); if (g.tryEnter()) n.incrementAndGet() } }
        go.countDown(); pool.shutdown(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, n.get())
    }

    @Test fun timingStopRunsOnce() {
        val n = AtomicInteger()
        val s = AirPlayTimingSession.onceOnly(1) { n.incrementAndGet() }
        s.close(); s.close()
        assertEquals(1, n.get())
    }
}
