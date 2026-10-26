package com.cardroid.runner

import com.cardroid.lib.airplay.*
import kotlin.test.*

class ScreenStreamLifecycleTest {
    private fun screen(entity: Int, borrowed: Boolean = false) =
        CarPlayResourceArbiter.ResourceState(1, entity, if (borrowed) 1 else entity, borrowed)

    @Test fun nativeHomeTearsDownOnlyScreenAndReturnRecreatesOnce() {
        val calls = mutableListOf<String>()
        val lifecycle = ScreenStreamLifecycle({ calls += "pause" }, { calls += "teardown" }, { calls += "setup" })
        lifecycle.reconcile(screen(2))
        lifecycle.reconcile(screen(2))
        assertTrue(lifecycle.suspended)
        assertEquals(listOf("pause", "teardown"), calls)
        lifecycle.reconcile(screen(1))
        lifecycle.reconcile(screen(1))
        assertFalse(lifecycle.suspended)
        assertEquals(listOf("pause", "teardown", "setup"), calls)
        val body = ReceiverPlist.map(ScreenStreamLifecycle.teardownBody())
        val streams = body["streams"] as List<*>
        assertEquals(listOf(mapOf("type" to 110L)), streams)
    }

    @Test fun backupCameraBorrowDoesNotDestroyScreenStream() {
        val lifecycle = ScreenStreamLifecycle({ error("pause") }, { error("teardown") }, { error("setup") })
        lifecycle.reconcile(screen(2, borrowed = true))
        lifecycle.reconcile(screen(1))
        assertFalse(lifecycle.suspended)
    }

    @Test fun rejectedScreenSetupDoesNotMarkTheScreenRunning() {
        val lifecycle = ScreenStreamLifecycle({}, {}, { error("SETUP rejected") })
        lifecycle.reconcile(screen(2))
        assertFailsWith<IllegalStateException> { lifecycle.reconcile(screen(1)) }
        assertTrue(lifecycle.suspended)
    }
}
