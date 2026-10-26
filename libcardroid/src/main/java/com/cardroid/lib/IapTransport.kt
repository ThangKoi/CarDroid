package com.cardroid.lib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Surface that [CarPlaySession] and [CarDroidPipeline] use to talk to whichever
 * iAP2 transport is active. Both the production [Iap2Transport] (Bluetooth
 * RFCOMM) and the test-only [TcpClientTransport] (TCP socket against a Python
 * head-unit simulator) implement this so the rest of the stack stays unchanged.
 */
interface IapTransport {
    /** Stream of incoming control-session messages decoded as (messageId, raw params). */
    val incomingControl: SharedFlow<Pair<Int, ByteArray>>

    /** Hook fired when the iAP2 link reaches NORMAL state. */
    var onLinkUp: () -> Unit

    /** Fired when the peer session ends (RFCOMM/TCP closed, handshake aborted) with a short reason. Default: ignored. */
    var onSessionEnded: (String) -> Unit
        get() = {}
        set(_) {}

    fun start(scope: CoroutineScope)
    fun stop()

    /** Send a control-session message (already-encoded CSM bytes). */
    fun sendControl(controlMessageBytes: ByteArray)

    /** OkCar bypass: send hardcoded MFi hash on 0xAA02 after receiving 0xAA01. */
    fun sendMfiCertResponse(hash: ByteArray)

    /** Send AuthenticationSucceeded (0xAA05) with no params. */
    fun sendAuthStatusOk()

    /** Legacy: send signed MFi response (e.g. from ExternalChipProvider) on 0xAA02. */
    fun sendAuthenticationResponse(signature: ByteArray)

    /** Append a diagnostic line to the transport's log sink. */
    fun diagnostic(msg: String)
}
