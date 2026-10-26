package com.cardroid.app.helper.input

/**
 * Pure receiver-HID -> helper-key mapping used by CarDroidService in helper mode (no android.* so it is JVM-testable).
 * Layouts from TestReceiver native/Engine/protocol/hid.ts: knob report byte0 bit0 select, bit1 AC Home, bit2 AC Back (then X/Y/wheel int8);
 * media report is one byte holding a usage INDEX (0 none, 1 Play, 2 Pause, 3 Play/Pause, 4 Scan Next, 5 Scan Previous, 6 AC Navigation Guidance).
 */
object InputRouting {
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_PLAY = 126
    const val KEYCODE_MEDIA_PAUSE = 127
    const val MEDIA_REPEAT_MS = 500L

    /** Media usage index -> Android keycode; null for 0 (release) and 6 (navigation guidance has no Android equivalent) or unknown. */
    fun mediaKeycode(index: Int): Int? = when (index) {
        1 -> KEYCODE_MEDIA_PLAY
        2 -> KEYCODE_MEDIA_PAUSE
        3 -> KEYCODE_MEDIA_PLAY_PAUSE
        4 -> KEYCODE_MEDIA_NEXT
        5 -> KEYCODE_MEDIA_PREVIOUS
        else -> null
    }

    /** Keycodes for knob bits that rose since [lastBits] (HOME first, then BACK); repeated reports with the bit still set yield nothing. */
    fun knobKeys(lastBits: Int, bits: Int): List<Int> {
        val rose = bits and lastBits.inv()
        return buildList { if (rose and 2 != 0) add(KEYCODE_HOME); if (rose and 4 != 0) add(KEYCODE_BACK) }
    }

    /** Whether a media report should produce a key: a non-zero index that differs from the previous one, or repeats after [MEDIA_REPEAT_MS]. */
    fun mediaShouldFire(index: Int, lastIndex: Int, lastFiredMs: Long, nowMs: Long): Boolean =
        mediaKeycode(index) != null && (index != lastIndex || nowMs - lastFiredMs >= MEDIA_REPEAT_MS)
}
