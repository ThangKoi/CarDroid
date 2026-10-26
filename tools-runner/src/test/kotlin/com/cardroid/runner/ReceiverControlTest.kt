package com.cardroid.runner

import com.cardroid.lib.*
import com.cardroid.mfi.OkcarBypassProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ReceiverControlTest {
    private class Transport : IapTransport {
        override val incomingControl = MutableSharedFlow<Pair<Int, ByteArray>>()
        override var onLinkUp: () -> Unit = {}
        val sent = mutableListOf<ByteArray>()
        override fun start(scope: CoroutineScope) {}
        override fun stop() {}
        override fun sendControl(controlMessageBytes: ByteArray) { sent += controlMessageBytes }
        override fun sendMfiCertResponse(hash: ByteArray) {}
        override fun sendAuthStatusOk() {}
        override fun sendAuthenticationResponse(signature: ByteArray) {}
        override fun diagnostic(msg: String) {}
    }

    @Test fun wifiConfigurationAnnouncesWirelessAvailabilityOnce() = runBlocking {
        val transport = Transport()
        var ssid = ""
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) { ssid = network.ssid }
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        val params = Iap2ControlSession.param(1, "Receiver\u0000".encodeToByteArray())
        session.onControlMessage(0x5703, params)
        session.onControlMessage(0x5703, params)
        assertEquals("Receiver", ssid)
        // Matches Receiver's group 1 / parameter 0 U8 wireless availability codec.
        assertEquals("4040000f4300000900010005000001",
            transport.sent.single().joinToString("") { "%02x".format(it) })
    }

    @Test fun parsesReceiverPublishedStartSessionVector() = runBlocking {
        val transport = Transport()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {}
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        var endpoint: CarPlaySession.AirPlayEndpoint? = null
        session.onAirPlayStart = { endpoint = it }
        session.onControlMessage(0x1D01, Iap2ControlSession.param(0, "Receiver\u0000".encodeToByteArray()))
        // Receiver Iap2WirelessControlClientTest.carPlayStartSessionMatchesLiviWirelessVector.
        val hex = "40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003" +
            "3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461" +
            "61626263630000080005312e3000"
        val wire = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val (id, params) = assertNotNull(Iap2ControlSession.parse(wire))
        session.onControlMessage(id, params)
        val result = assertNotNull(endpoint)
        assertEquals("Receiver", result.receiverName)
        assertEquals("192.168.2.1", result.ipv6)
        assertEquals(49152, result.port)
        assertEquals("aabbcc", result.publicKey)
        assertEquals("1.0", result.firmware)
        assertNotEquals(result.publicKey, result.sessionUuid)
        java.util.UUID.fromString(result.sessionUuid)
        Unit
    }

    @Test fun linkUpReannouncesWirelessAvailability() = runBlocking {
        val transport = Transport()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {}
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        val params = Iap2ControlSession.param(1, "Receiver\u0000".encodeToByteArray())
        fun availabilityCount() = transport.sent.count { it.size > 5 && it[4] == 0x43.toByte() && it[5] == 0x00.toByte() }
        session.onControlMessage(0x5703, params)
        session.onControlMessage(0x5703, params)
        assertEquals(1, availabilityCount())
        session.onLinkUp()
        session.onControlMessage(0x5703, params)
        assertEquals(2, availabilityCount())
    }
}
