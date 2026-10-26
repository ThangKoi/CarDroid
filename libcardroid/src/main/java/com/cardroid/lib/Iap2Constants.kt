package com.cardroid.lib

import java.util.UUID

/**
 * Public iAP2 constants gathered from open reverse-engineering sources.
 *
 * Sources:
 *   - reference.de/misc/posts/mfi_iap.html
 *   - github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering
 *   - Infineon BTSDK iAP2 API headers (public surface only)
 *
 * Header, service UUIDs and Apple manufacturer-data prefix are public.
 * Detect / LinkSync exact payload bytes are covered by Apple's MFi NDA
     * and are NOT reproduced here; see [Iap2LinkSync] for plug points.
 */
object Iap2Constants {
    // Every iAP2 link packet begins with these two bytes.
    const val LINK_HEADER_0: Byte = 0xFF.toByte()
    const val LINK_HEADER_1: Byte = 0x5A.toByte()

    // SDP-advertised service UUIDs for Bluetooth-transport iAP2.
    val SERVICE_UUID_ACCESSORY: UUID =
        UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
    val SERVICE_UUID_IPHONE: UUID =
        UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    // Apple's assigned BT SIG company ID, little-endian in mfg-data.
    const val APPLE_COMPANY_ID: Int = 0x004C

    // Session 0 is used by Link Sync frames and, in reference's packet map, by
    // control/authentication payloads after the link is established.
    const val LINK_SESSION_ID: Byte = 0x00

    // reference's wireless CarPlay write-up identifies session 0 as the
    // control/authentication channel. Some Link Sync payloads still advertise a
    // type=0 session id; the transport accepts that inbound, but sends current
    // control/auth test traffic on session 0.
    const val CONTROL_SESSION_ID: Byte = 0x00

    // External-Accessory session ID (not advertised by all cars).
    const val EA_SESSION_ID_DEFAULT: Byte = 0x02
}
