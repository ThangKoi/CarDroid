package com.cardroid.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackSignalTrackerTest {
    @Test fun sustainedSilenceAndRecoveryAreReportedOnce() {
        val tracker = PlaybackSignalTracker(8)
        assertNull(tracker.accept(false, 4))
        assertEquals(PlaybackSignalTracker.Transition.AUDIBLE, tracker.accept(true, 4))
        assertNull(tracker.accept(false, 4))
        assertEquals(PlaybackSignalTracker.Transition.SILENT, tracker.accept(false, 4))
        assertNull(tracker.accept(false, 4))
        assertEquals(PlaybackSignalTracker.Transition.AUDIBLE, tracker.accept(true, 4))
        assertEquals(24L, tracker.capturedFrames)
    }
    @Test fun briefSilenceDoesNotTriggerOrAccumulateAcrossSound() {
        val tracker = PlaybackSignalTracker(8)
        tracker.accept(true, 4)
        assertNull(tracker.accept(false, 4))
        assertNull(tracker.accept(true, 4))
        assertNull(tracker.accept(false, 4))
    }
    @Test fun sustainedStartupSilenceIsReportedOnce() {
        val tracker = PlaybackSignalTracker(8)
        assertNull(tracker.accept(false, 4))
        assertEquals(PlaybackSignalTracker.Transition.SILENT, tracker.accept(false, 4))
        assertNull(tracker.accept(false, 4))
    }

}
