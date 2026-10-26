package com.cardroid.runner

import com.cardroid.lib.airplay.RequestUi
import com.cardroid.lib.airplay.RequestUi.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestUiTest {
    @Test
    fun noUrlMeansShowCarPlay() {
        // Car's CarPlay icon: {type: requestUI} with no params.
        assertEquals(RequestUi.Request(Kind.SHOW, null), RequestUi.parse(mapOf("type" to "requestUI")))
        assertEquals(Kind.SHOW, RequestUi.parse(mapOf("type" to "requestUI", "params" to mapOf("url" to " "))).kind)
    }

    @Test
    fun urlSchemesMapToApps() {
        assertEquals(Kind.NOW_PLAYING, RequestUi.classify("nowplaying:"))
        assertEquals(Kind.NOW_PLAYING, RequestUi.classify("music:"))
        assertEquals(Kind.MAPS, RequestUi.classify("maps:"))
        assertEquals(Kind.PHONE, RequestUi.classify("mobilephone:"))
        assertEquals(Kind.PHONE, RequestUi.classify("tel:+84123"))
        assertEquals(Kind.MESSAGES, RequestUi.classify("sms:"))
        assertEquals(Kind.UNKNOWN, RequestUi.classify("vendorapp:settings"))
        val parsed = RequestUi.parse(mapOf("type" to "requestUI", "params" to mapOf("url" to "nowplaying:")))
        assertEquals(RequestUi.Request(Kind.NOW_PLAYING, "nowplaying:"), parsed)
    }

    @Test
    fun repeatedRequestWithinWindowIsDropped() {
        var now = 0L
        val debouncer = RequestUi.Debouncer(windowMs = 1_000L) { now }
        val show = RequestUi.Request(Kind.SHOW, null)
        assertTrue(debouncer.accept(show))
        now = 300; assertFalse(debouncer.accept(show))
        now = 400; assertTrue(debouncer.accept(RequestUi.Request(Kind.NOW_PLAYING, "nowplaying:")))
        now = 2_000; assertTrue(debouncer.accept(show))
    }
    @Test
    fun continuousRetriesDoNotPreventRestoringCarPlay() {
        var now = 0L
        val debouncer = RequestUi.Debouncer(windowMs = 1_000L) { now }
        val show = RequestUi.Request(Kind.SHOW, null)
        assertTrue(debouncer.accept(show))
        now = 400; assertFalse(debouncer.accept(show))
        now = 800; assertFalse(debouncer.accept(show))
        now = 1_000; assertTrue(debouncer.accept(show))
        now = 1_400; assertFalse(debouncer.accept(show))
        now = 2_000; assertTrue(debouncer.accept(show))
    }
    @Test fun realReturnAfterNativeHomeBypassesDuplicateWindow() {
        var now = 0L
        val arbiter = com.cardroid.lib.airplay.CarPlayResourceArbiter()
        val debouncer = RequestUi.Debouncer { now }
        val show = RequestUi.Request(Kind.SHOW, null)
        assertTrue(debouncer.accept(show, arbiter.accessoryOwnsScreen()))
        now = 100
        assertFalse(debouncer.accept(show, arbiter.accessoryOwnsScreen()))
        arbiter.applyChangeModes(mapOf("params" to mapOf("resources" to listOf(
            mapOf("resourceID" to 1, "transferType" to 1)))))
        now = 200
        assertTrue(debouncer.accept(show, arbiter.accessoryOwnsScreen()))
        arbiter.controllerTake(1, "requestUI SHOW")
        now = 300
        assertFalse(debouncer.accept(show, arbiter.accessoryOwnsScreen()))
    }
}
