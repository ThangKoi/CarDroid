package com.cardroid.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuDecisionTest {
    @Test fun permissionTableDistinguishesBinderSupportAndDenial() {
        assertEquals(ShizukuDecision.PermissionState.UNAVAILABLE, ShizukuDecision.permissionState(false, false, false, false))
        assertEquals(ShizukuDecision.PermissionState.UNSUPPORTED, ShizukuDecision.permissionState(true, true, false, false))
        assertEquals(ShizukuDecision.PermissionState.NEEDS_PERMISSION, ShizukuDecision.permissionState(true, false, false, false))
        assertEquals(ShizukuDecision.PermissionState.PERMISSION_DENIED, ShizukuDecision.permissionState(true, false, false, true))
        assertEquals(ShizukuDecision.PermissionState.READY, ShizukuDecision.permissionState(true, false, true, false))
    }

    @Test fun autoStartGateIsOneShotAndManualStopWins() {
        assertTrue(ShizukuDecision.mayAutoStart(true, false, false, true, true))
        assertFalse(ShizukuDecision.mayAutoStart(false, false, false, true, true))
        assertFalse(ShizukuDecision.mayAutoStart(true, true, false, true, true))
        assertFalse(ShizukuDecision.mayAutoStart(true, false, true, true, true))
        assertFalse(ShizukuDecision.mayAutoStart(true, false, false, false, true))
        assertFalse(ShizukuDecision.mayAutoStart(true, false, false, true, false))
    }

    @Test fun startIsSingleFlightWithCooldown() {
        assertTrue(ShizukuDecision.mayStart(1_000, 0, false))
        assertFalse(ShizukuDecision.mayStart(1_000, 0, true))
        assertFalse(ShizukuDecision.mayStart(5_000, 11_000, false))
        assertTrue(ShizukuDecision.mayStart(11_000, 11_000, false))
    }

    @Test fun helperProbeIsRateLimitedAndSingleFlight() {
        assertTrue(ShizukuDecision.mayRefreshRunning(100, 0, 3_000, false))
        assertFalse(ShizukuDecision.mayRefreshRunning(2_000, 1_000, 3_000, false))
        assertTrue(ShizukuDecision.mayRefreshRunning(4_000, 1_000, 3_000, false))
        assertFalse(ShizukuDecision.mayRefreshRunning(9_000, 1_000, 3_000, true))
    }
}
