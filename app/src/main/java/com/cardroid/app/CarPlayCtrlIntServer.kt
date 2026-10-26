package com.cardroid.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * CarDroid's "ready signal" half of the wireless CarPlay handoff.
 *
 * Per public reference carplay_bt.m:597 the expected flow after
 * receiver's 0x4301 / 0x5703:
 *
 *   sender (iPhone) publishes _carplay-ctrl._tcp
 *   receiver (car)  browses, finds us, opens HTTP /ctrl-int/1/connect
 *   sender (iPhone) responds 200 OK
 *   receiver then opens its TCP 7000 AirPlay listener
 *   sender connects out to TCP 7000 to start RTSP
 *
 * Without us advertising _carplay-ctrl._tcp and answering /ctrl-int/1/connect
 * the car never starts its 7000 listener and we get ECONNREFUSED.
 *
 * This class:
 *   - binds a TCP server socket on an ephemeral port
 *   - registers `_carplay-ctrl._tcp.` mDNS service for that port
 *   - logs every connection accepted and HTTP request line received
 *   - fires [onConnectRequest] when a `/ctrl-int/1/connect` (or any GET/POST)
 *     arrives so the orchestrator can attempt TCP 7000 RIGHT after
 *
 * Returns 200 OK to anything for now; the real iPhone returns a binary plist
 * but BMW seems to be satisfied with just receiving SOME response.
 */
class CarPlayCtrlIntServer(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val ctx = context.applicationContext
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var acceptJob: Job? = null
    private var localPort: Int = 0

    /** Fired the first time a client opens an HTTP request against our port. */
    @Volatile var onConnectRequest: (clientIp: String) -> Unit = {}

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        serviceName: String = "CarDroid",
        preferredNetwork: Network? = null,
    ) {
        stop()
        // Android's bindProcessToNetwork() makes ServerSocket bind() return
        // ECONNREFUSED on some devices. The getter can return null even when
        // the handoff callback just bound the process, so always clear before
        // opening the listener and restore the known handoff network after.
        val savedNetwork: Network? = connectivity.boundNetworkForProcess
        val restoreNetwork = savedNetwork ?: preferredNetwork
        log("CtrlInt: clearing process network binding for ServerSocket()")
        runCatching { connectivity.bindProcessToNetwork(null) }
            .onFailure { log("CtrlInt: process unbind failed ${it.javaClass.simpleName}: ${it.message}") }

        val server = try {
            try {
                java.net.ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
                }
            } catch (e: Throwable) {
                log("CtrlInt: IPv6 bind failed (${e.message}); trying IPv4")
                try {
                    java.net.ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), 0))
                    }
                } catch (e2: Throwable) {
                    log("CtrlInt: IPv4 bind also failed (${e2.message}); ctrl-int disabled")
                    return
                }
            }
        } finally {
            if (restoreNetwork != null) {
                runCatching { connectivity.bindProcessToNetwork(restoreNetwork) }
                    .onSuccess { log("CtrlInt: restored process network binding to $restoreNetwork") }
                    .onFailure { log("CtrlInt: restore network binding failed ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        serverSocket = server
        localPort = server.localPort
        log("CtrlInt: TCP server bound on port $localPort")

        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_carplay-ctrl._tcp."
            this.port = localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(svc: NsdServiceInfo, err: Int) {
                log("CtrlInt: mDNS register FAILED err=$err")
            }
            override fun onUnregistrationFailed(svc: NsdServiceInfo, err: Int) {
                log("CtrlInt: mDNS unregister FAILED err=$err")
            }
            override fun onServiceRegistered(svc: NsdServiceInfo) {
                log("CtrlInt: mDNS registered name='${svc.serviceName}' type='${svc.serviceType}' port=$localPort")
            }
            override fun onServiceUnregistered(svc: NsdServiceInfo) {
                log("CtrlInt: mDNS unregistered '${svc.serviceName}'")
            }
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Throwable) {
                log("CtrlInt: accept failed: ${e.message}")
                break
            }
            handleClient(client)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5_000
            val peer = client.inetAddress.hostAddress.orEmpty()
            log("CtrlInt: car connected from $peer")
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val out = client.getOutputStream()

            val requestLine = reader.readLine().orEmpty()
            log("CtrlInt: <- $requestLine")
            // Drain headers until blank line
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                log("CtrlInt: <- $h")
            }
            // Respond 200 OK with empty body
            val body = "OK".toByteArray(Charsets.US_ASCII)
            val resp = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Content-Type: text/plain\r\n" +
                "Server: AirPlay/420.5\r\n" +
                "\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            log("CtrlInt: -> 200 OK to $peer (${body.size}B)")
            onConnectRequest(peer)
        } catch (e: Throwable) {
            log("CtrlInt: client error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}
