package com.cardroid.app

/** Logs capture transitions only after sustained silence, rather than sampling isolated packets. */
class PlaybackSignalTracker(private val silenceFrames: Long) {
    enum class Transition { AUDIBLE, SILENT }
    private var audible: Boolean? = null
    private var quietFrames = 0L
    var capturedFrames = 0L
        private set

    fun accept(nonSilent: Boolean, frames: Int): Transition? {
        require(frames > 0)
        capturedFrames += frames
        if (nonSilent) {
            quietFrames = 0
            if (audible != true) { audible = true; return Transition.AUDIBLE }
        } else {
            quietFrames += frames
            if (audible != false && quietFrames >= silenceFrames) { audible = false; return Transition.SILENT }
        }
        return null
    }
}
