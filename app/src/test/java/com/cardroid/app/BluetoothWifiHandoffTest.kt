package com.cardroid.app

import com.cardroid.lib.CarDroidPipeline
import com.cardroid.lib.WifiHandoff
import com.cardroid.mfi.NullProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothWifiHandoffTest {
    private fun pipeline() = CarDroidPipeline.tcp("127.0.0.1", 1, NullProvider(), object : WifiHandoff {
        override fun connectToAccessory(network: WifiHandoff.NetworkInfo) = Unit
        override fun tearDown() = Unit
    })

    @Test fun bluetoothClosureDuringWifiStartupDoesNotEndSession() {
        val pipeline = pipeline()
        val ended = mutableListOf<String>()
        pipeline.onSessionEnded = { ended += it }
        var wifiEstablished = true
        pipeline.retainWifiOnBluetoothClose = { wifiEstablished }
        pipeline.endSession("RFCOMM closed")
        assertEquals(emptyList<String>(), ended)
        // A subsequent failed handshake must still release the retained session.
        wifiEstablished = false
        pipeline.endSession("handshake failed: SocketTimeoutException")
        assertEquals(listOf("handshake failed: SocketTimeoutException"), ended)
    }

    @Test fun eventLossAndUnestablishedBluetoothClosureStillEndSession() {
        val pipeline = pipeline()
        val ended = mutableListOf<String>()
        pipeline.onSessionEnded = { ended += it }
        pipeline.retainWifiOnBluetoothClose = { true }
        pipeline.endSession("event channel ended: Event EOF")
        pipeline.retainWifiOnBluetoothClose = { false }
        pipeline.endSession("RFCOMM closed")
        assertEquals(listOf("event channel ended: Event EOF", "RFCOMM closed"), ended)
    }
}
