package com.cardroid.lib.airplay

/** Runs on the serialized RTSP worker. Temporary camera borrows keep the stream alive. */
class ScreenStreamLifecycle(
    private val suspendVideo: () -> Unit,
    private val teardownScreen: () -> Unit,
    private val resumeScreen: () -> Unit,
) {
    var suspended: Boolean = false
        private set

    fun reconcile(screen: CarPlayResourceArbiter.ResourceState) {
        require(screen.id == CarPlayResourceArbiter.RESOURCE_SCREEN)
        if (screen.entity == CarPlayResourceArbiter.ENTITY_ACCESSORY && !screen.borrowed && !suspended) {
            suspended = true
            suspendVideo()
            teardownScreen()
        } else if (screen.entity == CarPlayResourceArbiter.ENTITY_PHONE && suspended) {
            resumeScreen()
            suspended = false
        }
    }

    companion object {
        /** Never send an empty TEARDOWN: that would end audio, events and the whole session. */
        fun teardownBody(): ByteArray = AirPlayBplist.encode(AirPlayBplist.dict(
            "streams" to AirPlayBplist.array(AirPlayBplist.dict("type" to AirPlayBplist.int(110))),
        ))
    }
}
