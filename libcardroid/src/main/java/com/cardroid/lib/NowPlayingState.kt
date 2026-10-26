package com.cardroid.lib

/** Snapshot from the phone's active Android media session; no artwork transfer yet. */
data class NowPlayingState(
    val title: String = "", val artist: String = "", val album: String = "", val appName: String = "",
    val durationMs: Long = 0, val positionMs: Long = 0,
    /** iAP2: 0 stopped, 1 playing, 2 paused. */
    val status: Int = 0,
) {
    fun message(): ByteArray {
        fun text(id: Int, value: String) = Iap2ControlSession.param(id,
            (value.substringBefore('\u0000').take(512) + "\u0000").toByteArray(Charsets.UTF_8))
        fun number(id: Int, value: Long) = Iap2ControlSession.param(id,
            java.nio.ByteBuffer.allocate(4).putInt(value.coerceIn(0, 0xffffffffL).toInt()).array())
        val item = text(1, title) + number(4, durationMs) + text(12, artist) + text(6, album)
        val playback = Iap2ControlSession.param(0, byteArrayOf(status.coerceIn(0, 2).toByte())) +
            number(1, positionMs) + text(7, appName)
        return Iap2ControlSession.build(0x5001, Iap2ControlSession.param(0, item) + Iap2ControlSession.param(1, playback))
    }
}
