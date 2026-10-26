package com.cardroid.app.car

import com.cardroid.app.helper.HelperProtocol.TaskEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLauncherLogicTest {
    private val apps = listOf("Calculator", "Chrome", "Maps", "Music", "Settings", "Spotify").map { CarApp(it, "p.${it.lowercase()}", "C") }
    private fun k(label: String) = "p.${label.lowercase()}/C"
    private fun labels(l: List<CarApp>) = l.map { it.label }

    @Test fun arrange_noPrefs_keepsOrder() = assertEquals(labels(apps), labels(CarLauncherLogic.arrange(apps, { it.target }, emptyList(), emptySet())))

    @Test fun arrange_favoritesFirstInPinnedOrder_thenRestWithoutDuplicates() {
        val out = CarLauncherLogic.arrange(apps, { it.target }, listOf(k("Spotify"), k("Maps")), emptySet())
        assertEquals(listOf("Spotify", "Maps", "Calculator", "Chrome", "Music", "Settings"), labels(out))
    }

    @Test fun arrange_hiddenExcluded_evenWhenPinned_andUnknownPinsIgnored() {
        val out = CarLauncherLogic.arrange(apps, { it.target }, listOf("gone/X", k("Chrome"), k("Maps")), setOf(k("Maps"), k("Settings")))
        assertEquals(listOf("Chrome", "Calculator", "Music", "Spotify"), labels(out))
    }

    @Test fun togglePin_addsRemovesAndCapsAtMax() {
        var p = emptyList<String>()
        p = CarLauncherLogic.togglePin(p, "a"); assertEquals(listOf("a"), p)
        p = CarLauncherLogic.togglePin(p, "a"); assertEquals(emptyList<String>(), p)
        val full = (1..CarLauncherLogic.MAX_PINNED).map { "x$it" }
        assertEquals(full, CarLauncherLogic.togglePin(full, "new"))
        assertFalse(CarLauncherLogic.canPin(full, "new"))
        assertTrue(CarLauncherLogic.canPin(full, "x3"))
        assertEquals(full - "x3", CarLauncherLogic.togglePin(full, "x3"))
    }

    @Test fun hide_unpins() {
        val (p, h) = CarLauncherLogic.hide(listOf("a", "b"), setOf("c"), "a")
        assertEquals(listOf("b"), p); assertEquals(setOf("c", "a"), h)
    }

    @Test fun prune_dropsUninstalled() {
        val (p, h) = CarLauncherLogic.prune(listOf("a", "gone", "a"), setOf("b", "gone2"), setOf("a", "b"))
        assertEquals(listOf("a"), p); assertEquals(setOf("b"), h)
    }

    @Test fun pages_chunksBy21() {
        val items = (1..45).toList()
        val pages = CarLauncherLogic.pages(items, 21)
        assertEquals(listOf(21, 21, 3), pages.map { it.size })
        assertTrue(CarLauncherLogic.pages(emptyList<Int>(), 21).isEmpty())
    }

    @Test fun search_caseInsensitiveSubstring_blankMatchesAll() {
        assertEquals(listOf("Music"), labels(CarLauncherLogic.search(apps, { it.label }, " MUS ")))
        assertEquals(listOf("Calculator", "Chrome"), labels(CarLauncherLogic.search(apps, { it.label }, "c").filter { it.label.startsWith("C") }))
        assertEquals(apps.size, CarLauncherLogic.search(apps, { it.label }, "  ").size)
    }

    @Test fun recents_filtersLauncherAndSecondaryHome() {
        val tasks = listOf(
            TaskEntry(1, "com.cardroid.app", "com.cardroid.app/com.cardroid.app.car.CarLauncherActivity"),
            TaskEntry(2, "com.android.chrome", "com.android.chrome/com.google.android.apps.chrome.Main"),
            TaskEntry(3, "com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher/com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"),
            TaskEntry(4, "", ""),
            TaskEntry(2, "com.android.chrome", "dup"),
        )
        assertEquals(listOf(2), CarLauncherLogic.recents(tasks, "com.cardroid.app").map { it.taskId })
    }

    @Test fun pushRecent_movesToFrontWithoutDuplicates_andCaps() {
        var r = CarLauncherLogic.pushRecent(listOf("a", "b", "c"), "b"); assertEquals(listOf("b", "a", "c"), r)
        r = (1..20).fold(emptyList()) { acc, i -> CarLauncherLogic.pushRecent(acc, "x$i") }
        assertEquals(CarLauncherLogic.MAX_RECENT_PANEL, r.size); assertEquals("x20", r.first())
    }

    @Test fun recentFirst_recentInOrder_skipsUninstalled_thenRestInOrder() {
        val out = CarLauncherLogic.recentFirst(apps, { it.target }, listOf(k("Spotify"), "gone/X", k("Chrome")))
        assertEquals(listOf("Spotify", "Chrome", "Calculator", "Maps", "Music", "Settings"), labels(out))
    }
}
