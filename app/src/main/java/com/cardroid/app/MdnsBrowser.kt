package com.cardroid.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet6Address

/**
 * Browses mDNS on the currently-bound network for CarPlay-relevant services.
 *
 * Per OkcarOS reference (`sources/i2/b.java`): the iPhone publishes
 * `_airplay._tcp.local.` once it's online on the accessory's AP. We resolve
 * the SRV record to get the actual TCP port (do NOT trust the port from
 * iAP2 `0x4301` -- on the BMW test the car's AirPlay listener wasn't on
 * the declared port 7000).
 *
 * Use [start] after `bindProcessToNetwork(network)` so NsdManager queries
 * over the WiFi interface instead of cellular/main.
 */
class MdnsBrowser(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val activeBrowsers = mutableMapOf<String, NsdManager.DiscoveryListener>()

    /** Called once per resolved service. */
    var onServiceResolved: (Service) -> Unit = {}

    data class Service(
        val type: String,           // e.g. "_airplay._tcp."
        val name: String,
        val host: String,           // IPv6 string preferred
        val port: Int,
        val attributes: Map<String, ByteArray>,
    )

    fun start(types: List<String> = DEFAULT_TYPES) {
        for (type in types) {
            browse(type)
        }
    }

    fun stop() {
        for ((type, listener) in activeBrowsers) {
            runCatching { nsd.stopServiceDiscovery(listener) }
                .onSuccess { log("mDNS: stopped browse $type") }
        }
        activeBrowsers.clear()
    }

    @SuppressLint("MissingPermission")
    private fun browse(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS: discovery start FAILED for $serviceType err=$errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS: discovery stop FAILED for $serviceType err=$errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                log("mDNS: browsing $serviceType ...")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                log("mDNS: discovery stopped for $serviceType")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("mDNS: found ${serviceInfo.serviceType} name='${serviceInfo.serviceName}' -- resolving")
                resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("mDNS: lost ${serviceInfo.serviceType} name='${serviceInfo.serviceName}'")
            }
        }
        activeBrowsers[serviceType] = listener
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @SuppressLint("MissingPermission")
    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(
                info,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(err: Int) {
                        log("mDNS: resolve register FAILED err=$err for ${info.serviceName}")
                    }
                    override fun onServiceUpdated(updated: NsdServiceInfo) = report(updated)
                    override fun onServiceLost() {
                        log("mDNS: service lost: ${info.serviceName}")
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                }
            )
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log("mDNS: resolve FAILED err=$errorCode for ${serviceInfo.serviceName}")
                }
                override fun onServiceResolved(resolved: NsdServiceInfo) = report(resolved)
            })
        }
    }

    private fun report(s: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= 34) {
            s.hostAddresses?.firstOrNull { it is Inet6Address }
                ?: s.hostAddresses?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") s.host
        }
        val hostStr = host?.hostAddress.orEmpty()
        val port = s.port
        val attrs = if (Build.VERSION.SDK_INT >= 21) {
            s.attributes?.mapValues { it.value ?: ByteArray(0) } ?: emptyMap()
        } else emptyMap()
        log("mDNS: RESOLVED ${s.serviceType} name='${s.serviceName}' host=$hostStr port=$port attrs=${attrs.keys}")
        if (hostStr.isNotEmpty()) {
            onServiceResolved(
                Service(
                    type = s.serviceType.orEmpty(),
                    name = s.serviceName.orEmpty(),
                    host = hostStr,
                    port = port,
                    attributes = attrs,
                )
            )
        }
    }

    companion object {
        val DEFAULT_TYPES = listOf(
            "_airplay._tcp.",
            "_raop._tcp.",
        )
    }
}
