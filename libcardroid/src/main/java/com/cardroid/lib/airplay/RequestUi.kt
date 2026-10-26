package com.cardroid.lib.airplay

/**
 * Classifies the accessory's `requestUI` command.
 *
 * Apple's AirPlayReceiverSessionRequestUI sends `{type: requestUI}` and, when
 * the head unit wants a specific app, `params.url` (kAirPlayKey_URL).  A car
 * sends it when the user taps its CarPlay icon (no URL: just show CarPlay
 * again) and from shortcut buttons such as now-playing, maps or phone.
 */
object RequestUi {
    enum class Kind { SHOW, NOW_PLAYING, MAPS, PHONE, MESSAGES, UNKNOWN }

    data class Request(val kind: Kind, val url: String?)

    fun parse(command: Map<*, *>?): Request {
        val url = ((command?.get("params") as? Map<*, *>)?.get("url") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return Request(classify(url), url)
    }

    fun classify(url: String?): Kind {
        if (url == null) return Kind.SHOW
        val scheme = url.substringBefore(':').lowercase()
        return when (scheme) {
            "nowplaying", "music", "audio", "podcasts", "audiobooks" -> Kind.NOW_PLAYING
            "maps", "map", "geo", "navigation", "google.navigation" -> Kind.MAPS
            "mobilephone", "tel", "telprompt", "phone" -> Kind.PHONE
            "sms", "messages", "message" -> Kind.MESSAGES
            else -> Kind.UNKNOWN
        }
    }

    /** Drops a repeat of the same request within [windowMs]; cars resend requestUI on rapid taps. */
    class Debouncer(private val windowMs: Long = 1_000L, private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private var last: Request? = null
        private var lastAt = Long.MIN_VALUE / 2

        @Synchronized
        fun accept(request: Request, restoringScreen: Boolean = false): Boolean {
            val now = clockMs()
            val duplicate = !restoringScreen && request == last && now - lastAt < windowMs
            if (duplicate) return false
            last = request; lastAt = now
            return true
        }
    }
}
