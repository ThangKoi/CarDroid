package com.cardroid.lib

/**
 * Connects to the accessory WiFi network used for the AirPlay-over-WiFi phase
 * of Wireless CarPlay.
 *
 * In the reference wireless flow, the phone requests WiFi configuration over iAP2
 * and the accessory responds with its AP credentials. CarDroid poses as the
 * phone, so it consumes those credentials instead of starting an Android AP.
 *
 * The :app module supplies an implementation that wraps Android APIs.
 */
interface WifiHandoff {
    data class NetworkInfo(
        val ssid: String,
        val passphrase: String,
        /** Optional 6-byte BSSID. Empty when the accessory does not provide one. */
        val bssid: ByteArray = ByteArray(0),
        /** Operating channel (2.4 GHz = 1..13, 5 GHz = 36..165). */
        val channel: Int = 0,
    )

    fun connectToAccessory(network: NetworkInfo)
    fun tearDown()

    /**
     * Optional hook: implementations that bind to a specific Android Network
     * can expose it so the AirPlay client routes its sockets through that
     * network (necessary for link-local IPv6 on a peer AP with no internet).
     */
    fun onNetworkReady(callback: (Any?) -> Unit) {}
}
