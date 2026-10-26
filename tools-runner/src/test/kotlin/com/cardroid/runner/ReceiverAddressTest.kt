package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.*

class ReceiverAddressTest {
    @Test fun retriesNewVerifiedCandidateAfterFirstConnectionFails() {
        val server = ServerSocket(0)
        val closedPort = ServerSocket(0).use { it.localPort }
        var lookups = 0
        val client = AirPlayRtspClient(AirPlayParams("127.0.0.1",closedPort,"test"), addressCandidates = {
            lookups++
            if (lookups == 1) listOf(InetSocketAddress("127.0.0.1",closedPort))
            else listOf(InetSocketAddress("127.0.0.1",closedPort),InetSocketAddress("127.0.0.1",server.localPort))
        })
        try {
            client.connect()
            server.accept().use { assertEquals("127.0.0.1",client.connectedAddress?.hostAddress) }
            assertEquals(2,lookups)
        } finally { client.close(); server.close() }
    }

    @Test fun retriesAfterNetworkUnreachableThenConnects() {
        val server = ServerSocket(0)
        var creates = 0
        val client = AirPlayRtspClient(AirPlayParams("127.0.0.1",server.localPort,"test"), socketFactory = {
            creates++
            if (creates <= 2) object : java.net.Socket() {
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
                    throw java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)")
            } else java.net.Socket()
        }, addressCandidates = { listOf(InetSocketAddress("127.0.0.1",server.localPort)) })
        try {
            client.connect()
            server.accept().close()
            assertEquals(3,creates)
        } finally { client.close(); server.close() }
    }

    @Test fun retriesWhileLinkLocalAddressIsTentative() {
        // Car run 11:05:17: connect right after joining the car Wi-Fi failed with EADDRNOTAVAIL
        // (our IPv6 link-local not ready yet) and the whole session was torn down.
        val server = ServerSocket(0)
        var creates = 0
        val client = AirPlayRtspClient(AirPlayParams("127.0.0.1",server.localPort,"test"), socketFactory = {
            creates++
            if (creates <= 2) object : java.net.Socket() {
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
                    throw java.net.NoRouteToHostException("Address not available")
            } else java.net.Socket()
        }, addressCandidates = { listOf(InetSocketAddress("127.0.0.1",server.localPort)) })
        try {
            client.connect()
            server.accept().close()
            assertEquals(3,creates)
        } finally { client.close(); server.close() }
    }
}
