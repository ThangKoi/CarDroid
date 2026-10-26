package com.cardroid.runner

import com.cardroid.lib.CarPlaySession
import com.cardroid.lib.IPhoneIdentity
import com.cardroid.lib.LinkSyncParameters
import com.cardroid.lib.TcpClientTransport
import com.cardroid.lib.WifiHandoff
import com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator
import com.cardroid.lib.airplay.AirPlayMfiSap
import com.cardroid.lib.airplay.AirPlayParams
import com.cardroid.lib.airplay.KotlinMfiSap
import com.cardroid.mfi.OkcarBypassProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Runs cardroid's real Kotlin protocol code (the same TcpClientTransport,
 * CarPlaySession, Iap2 framing, OkcarBypassProvider) against a Python sim over
 * TCP. No Android. No phone needed.
 *
 *   ./gradlew :tools-runner:run --args="--host 127.0.0.1 --port 5555"
 *
 * When the sim sends iAP2 0x4301 (CarPlay AirPlay-start trigger), the runner
 * spins up the AirPlay handshake orchestrator and drives RTSP against the
 * host/port the sim advertised (the test receiver listens there too).
 */
fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5555
    var timeoutMs = 30_000L
    var airPlayMfiMode = "kotlin"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--timeout-ms" -> { timeoutMs = args[++i].toLong() }
            "--mfi" -> { airPlayMfiMode = args[++i] }
            "-h", "--help" -> {
                println("Usage: tools-runner [--host H] [--port P] [--timeout-ms N] [--mfi kotlin|stub]")
                return
            }
        }
        i++
    }

    val airPlayMfiSap: AirPlayMfiSap = when (airPlayMfiMode.lowercase(Locale.US)) {
        "kotlin" -> KotlinMfiSap()
        "stub" -> AirPlayMfiSap.Stub
        else -> {
            System.err.println("Unknown --mfi '$airPlayMfiMode' (expected kotlin or stub)")
            exitProcess(64)
        }
    }

    val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val log: (String) -> Unit = { line ->
        println("[${tsFmt.format(Date())}] $line")
        System.out.flush()
    }

    val mfi = OkcarBypassProvider()
    val identity = IPhoneIdentity()
    val wifi = object : WifiHandoff {
        override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {
            log("WIFI HANDOFF RECEIVED (sim mode -- not actually joining)")
            log("  ssid:       ${network.ssid}")
            log("  passphrase: ${network.passphrase}")
            log("  channel:    ${network.channel}")
        }
        override fun tearDown() { log("WIFI: tearDown") }
    }

    val transport = TcpClientTransport(
        host = host,
        port = port,
        linkSyncParams = LinkSyncParameters(),
        onLog = log,
    )
    val carPlay = CarPlaySession(transport, mfi, wifi)

    log("=== cardroid JVM runner ===")
    log("connecting to $host:$port (timeout ${timeoutMs}ms)")
    log("identity: name='${identity.name}', model='${identity.model}'")
    log("mfi:      ${mfi.name}")
    log("airplay mfi: $airPlayMfiMode")

    var exitCode = 0
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val airplayDone = CompletableDeferred<Boolean>()

        carPlay.onAirPlayStart = { ep ->
            log("AirPlay: connecting (host=${ep.ipv6} port=${ep.port} uuid=${ep.sessionUuid})")
            val params = AirPlayParams(
                host = ep.ipv6,
                airPlayPort = ep.port,
                sessionUuid = ep.sessionUuid,
            )
            val orch = AirPlayHandshakeOrchestrator(
                params = params,
                mfi = airPlayMfiSap,
                log = log,
            )
            scope.launch {
                try {
                    orch.run(scope)
                    log("AirPlay handshake complete (entering feedback loop)")
                    // Let a couple of feedback cycles run so the log shows them.
                    delay(2_500)
                    orch.stop()
                    airplayDone.complete(true)
                } catch (e: Throwable) {
                    log("AirPlay orchestrator failed: ${e.javaClass.simpleName}: ${e.message}")
                    airplayDone.complete(false)
                }
            }
        }

        transport.onLinkUp = {
            carPlay.onLinkUp()
            carPlay.startControlFlow()
        }
        scope.launch {
            transport.incomingControl.collect { (id, params) ->
                carPlay.onControlMessage(id, params)
            }
        }
        scope.launch {
            carPlay.state.collect { log("state -> $it") }
        }

        transport.start(scope)

        // Wait for WiFi handoff first (Phase 0+1 success), then AirPlay-start,
        // then orchestrator completion.
        val wifiState = withTimeoutOrNull(timeoutMs) {
            carPlay.state.first { st ->
                st is CarPlaySession.State.WifiHandoffReceived ||
                    st is CarPlaySession.State.Failed
            }
        }
        when (wifiState) {
            is CarPlaySession.State.Failed -> {
                log("FAILED in Phase 0+1: ${wifiState.reason}")
                exitCode = 1
            }
            null -> {
                log("TIMEOUT in Phase 0+1 after ${timeoutMs}ms")
                exitCode = 2
            }
            is CarPlaySession.State.WifiHandoffReceived -> {
                log("Phase 0+1 OK: ssid=${wifiState.ssid}; waiting for 0x4301 AirPlay handoff")
                val airplayOk = withTimeoutOrNull(timeoutMs) { airplayDone.await() }
                exitCode = when (airplayOk) {
                    true -> { log("DONE: AirPlay handshake succeeded"); 0 }
                    false -> { log("FAILED: AirPlay handshake errored"); 1 }
                    null -> { log("TIMEOUT waiting for AirPlay handshake"); 2 }
                }
            }
            else -> Unit
        }

        transport.stop()
        scope.cancel()
    }
    exitProcess(exitCode)
}
