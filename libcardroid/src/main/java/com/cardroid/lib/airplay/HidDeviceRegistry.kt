package com.cardroid.lib.airplay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The receiver's HID declarations are session data.  Keep the UUID's plist
 * type and exact string spelling so commands can be sent back in the form the
 * receiver advertised (a String "0" is different from integer 0 on some
 * head units).
 */
sealed class HidUuid {
    data class Text(val value: String) : HidUuid()
    data class Number(val value: Long) : HidUuid()

    fun label(): String = when (this) {
        is Text -> "string:${value}"
        is Number -> "number:${value}"
    }

    fun plist(): AirPlayBplist.Value = when (this) {
        is Text -> AirPlayBplist.str(value)
        is Number -> AirPlayBplist.int(value)
    }

    /**
     * TestReceiver's legacy HID IDs are the one observed exception to preserving
     * the /info plist type: some receivers advertise the IDs as integers but
     * expect the phone command's UUID field as the lower-case hexadecimal
     * string used by hidSendReport. Unknown numeric UUIDs remain numeric.
     */
    fun commandPlist(): AirPlayBplist.Value = when (this) {
        is Text -> AirPlayBplist.str(value)
        is Number -> legacyKind(this)?.let { AirPlayBplist.str(value.toString(16)) } ?: AirPlayBplist.int(value)
    }

    companion object {
        fun from(value: Any?): HidUuid? = when (value) {
            is String -> Text(value)
            is Byte -> Number(value.toLong())
            is Short -> Number(value.toLong())
            is Int -> Number(value.toLong())
            is Long -> Number(value)
            else -> null
        }
    }
}

private fun legacyKind(uuid: HidUuid): HidDeviceRegistry.Kind? = when (uuid) {
    is HidUuid.Text -> when {
        uuid.value.equals("2a2a2a2a", ignoreCase = true) -> HidDeviceRegistry.Kind.TOUCH
        uuid.value.equals("2a2a2a2b", ignoreCase = true) -> HidDeviceRegistry.Kind.BUTTON
        uuid.value.equals("2a2a2a2c", ignoreCase = true) -> HidDeviceRegistry.Kind.MEDIA
        else -> null
    }
    is HidUuid.Number -> when (uuid.value) {
        0x2A2A2A2AL -> HidDeviceRegistry.Kind.TOUCH
        0x2A2A2A2BL -> HidDeviceRegistry.Kind.BUTTON
        0x2A2A2A2CL -> HidDeviceRegistry.Kind.MEDIA
        else -> null
    }
}

/** Descriptor-derived one-contact report layout used by the captured LYNK unit. */
data class TouchDescriptor(
    val reportBytes: Int,
    val xMax: Int,
    val yMax: Int,
)

/** A bounded HID descriptor parser; unsupported layouts remain explicit. */
object HidDescriptorParser {
    data class Result(val touch: TouchDescriptor?, val touchCandidate: Boolean)

    private data class InputField(
        val bitOffset: Int,
        val size: Int,
        val count: Int,
        val usagePage: Int,
        val usage: Int?,
        val logicalMin: Long,
        val logicalMax: Long,
        val flags: Int,
    )

    fun parse(bytes: ByteArray?): Result {
        if (bytes == null || bytes.isEmpty()) return Result(null, false)
        var usagePage = -1
        val usages = mutableListOf<Int>()
        var touchCandidate = false
        var reportSize = 0
        var reportCount = 0
        var logicalMin = 0L
        var logicalMax = 0L
        var sawReportId = false
        var reportBits = 0
        val inputs = mutableListOf<InputField>()
        var i = 0
        var malformed = false
        while (i < bytes.size) {
            val prefix = bytes[i++].toInt() and 0xFF
            if (prefix == 0xFE) {
                // Long items are valid HID, but without a report parser they
                // are intentionally treated as an unsupported descriptor.
                malformed = true
                break
            }
            if (prefix == 0) { malformed = true; break }
            val sizeCode = prefix and 0x03
            val size = when (sizeCode) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            if (i + size > bytes.size) { malformed = true; break }
            val type = (prefix ushr 2) and 0x03
            val tag = (prefix ushr 4) and 0x0F
            val value = readUnsigned(bytes, i, size)
            val signed = readSigned(bytes, i, size)
            when (type) {
                0 -> { // Main
                    if (tag == 8) { // Input
                        if (reportSize <= 0 || reportCount <= 0 || reportBits + reportSize * reportCount > 65535) {
                            malformed = true
                        } else {
                            inputs += InputField(
                                bitOffset = reportBits,
                                size = reportSize,
                                count = reportCount,
                                usagePage = usagePage,
                                usage = usages.singleOrNull(),
                                logicalMin = logicalMin,
                                logicalMax = logicalMax,
                                flags = value,
                            )
                            reportBits += reportSize * reportCount
                        }
                    }
                }
                1 -> { // Global
                    when (tag) {
                        0 -> usagePage = value
                        1 -> logicalMin = signed
                        2 -> logicalMax = signed
                        7 -> reportSize = value
                        8 -> sawReportId = true
                        9 -> reportCount = value
                    }
                }
                2 -> { // Local
                    if (tag == 0) {
                        usages += value
                        if (usagePage == 0x0D && value in setOf(0x04, 0x22)) touchCandidate = true
                    }
                }
            }
            // Local usages apply to one Main item only.  Clearing here also
            // prevents a prior X/Y usage from being attached to the next
            // constant or unrelated input field.
            if (type == 0) usages.clear()
            i += size
        }
        val candidate = touchCandidate
        // The captured LYNK report is intentionally matched structurally:
        // tip/valid (1 bit), constant padding (7 bits), absolute X (16 bits),
        // then absolute Y (16 bits), with no ReportID or extra contacts.
        val tip = inputs.getOrNull(0)
        val padding = inputs.getOrNull(1)
        val x = inputs.getOrNull(2)
        val y = inputs.getOrNull(3)
        val exactLayout = inputs.size == 4 &&
            tip?.let { it.bitOffset == 0 && it.size == 1 && it.count == 1 &&
                it.usagePage == 0x0D && it.usage == 0x33 &&
                it.logicalMin == 0L && it.logicalMax == 1L && it.flags == 0x02 } == true &&
            padding?.let { it.bitOffset == 1 && it.size == 7 && it.count == 1 &&
                it.flags == 0x01 } == true &&
            x?.let { it.bitOffset == 8 && it.size == 16 && it.count == 1 &&
                it.usagePage == 0x01 && it.usage == 0x30 &&
                it.logicalMin == 0L && it.logicalMax in 1L..65535L &&
                it.flags == 0x02 } == true &&
            y?.let { it.bitOffset == 24 && it.size == 16 && it.count == 1 &&
                it.usagePage == 0x01 && it.usage == 0x31 &&
                it.logicalMin == 0L && it.logicalMax in 1L..65535L &&
                it.flags == 0x02 } == true &&
            reportBits == 40 && !sawReportId
        return if (!malformed && candidate && exactLayout) {
            Result(TouchDescriptor(5, x!!.logicalMax.toInt(), y!!.logicalMax.toInt()), candidate)
        } else Result(null, candidate)
    }

    private fun readUnsigned(bytes: ByteArray, offset: Int, size: Int): Int {
        var out = 0
        // HID short-item payloads are little-endian (e.g. 26 00 0A = 2560).
        for (j in size - 1 downTo 0) out = (out shl 8) or (bytes[offset + j].toInt() and 0xFF)
        return out
    }

    private fun readSigned(bytes: ByteArray, offset: Int, size: Int): Long {
        if (size == 0) return 0L
        val raw = readUnsigned(bytes, offset, size).toLong() and 0xFFFFFFFFL
        val bits = size * 8
        if (bits == 32) return if (raw and 0x80000000L != 0L) raw - 0x1_0000_0000L else raw
        val sign = 1L shl (bits - 1)
        return if (raw and sign != 0L) raw - (1L shl bits) else raw
    }
}

/** Receiver-declared HID devices and bounded diagnostics for one media session. */
class HidDeviceRegistry private constructor(
    val devices: List<Device>,
    private val log: (String) -> Unit,
) {
    enum class Kind { TOUCH, BUTTON, MEDIA, OTHER }

    data class Device(
        val uuid: HidUuid,
        val name: String?,
        val descriptor: ByteArray?,
        val touch: TouchDescriptor?,
        val touchCandidate: Boolean,
        val kind: Kind,
        val displayUuid: String?,
        /** False when /info associates this device with a different display. */
        val displayMatches: Boolean = true,
    )

    sealed class DecodeResult {
        data class Touch(val device: Device, val contacts: List<RemoteTouch.Contact>) : DecodeResult()
        data class Button(val device: Device, val bits: Int) : DecodeResult()
        data class Media(val device: Device, val index: Int) : DecodeResult()
        data class UnknownUuid(val raw: Any?) : DecodeResult()
        data class InvalidReport(val device: Device, val reason: String) : DecodeResult()
        data class UnsupportedDescriptor(val device: Device) : DecodeResult()
        data class UnsupportedDevice(val device: Device) : DecodeResult()
    }

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** Exact-type lookup first; compatibility aliases are narrowly scoped. */
    fun find(raw: Any?): Device? {
        val uuid = HidUuid.from(raw) ?: return null
        devices.firstOrNull { it.uuid == uuid }?.let { return it }
        // Legacy TestReceiver IDs have historically appeared with either case.
        // This compatibility exception does not lowercase receiver UUIDs.
        val legacy = legacyKind(uuid)
        if (legacy != null) return Device(uuid, "legacy-${legacy.name.lowercase()}", null,
            if (legacy == Kind.TOUCH) TouchDescriptor(12, 65535, 65535) else null,
            legacy == Kind.TOUCH, legacy, null)
        return null
    }

    fun commandValues(): List<AirPlayBplist.Value> = devices.map { device ->
        AirPlayBplist.dict(
            "type" to AirPlayBplist.str("hidSetInputMode"),
            "params" to AirPlayBplist.dict(
                "hidInputMode" to AirPlayBplist.int(0),
                "uuid" to device.uuid.commandPlist(),
            ),
        )
    }

    fun decodeDirect(raw: Any?, report: ByteArray, receiverWidth: Int, receiverHeight: Int,
                     displayWidth: Int, displayHeight: Int): DecodeResult {
        val device = find(raw) ?: return DecodeResult.UnknownUuid(raw)
        if (!device.displayMatches) return DecodeResult.UnsupportedDevice(device)
        return when (device.kind) {
            Kind.TOUCH -> {
                val descriptor = device.touch ?: return DecodeResult.UnsupportedDescriptor(device)
                if (report.size == 12 && descriptor.reportBytes == 12) {
                    DecodeResult.Touch(device, RemoteTouch.decodeDirect(report, receiverWidth, receiverHeight, displayWidth, displayHeight))
                } else if (report.size == descriptor.reportBytes) {
                    DecodeResult.Touch(device, RemoteTouch.decodeSingleDirect(report, descriptor, displayWidth, displayHeight))
                } else DecodeResult.InvalidReport(device, "length=${report.size} expected=${descriptor.reportBytes}")
            }
            Kind.BUTTON -> if (report.size >= 4) DecodeResult.Button(device, report[0].toInt() and 0xFF)
            else DecodeResult.InvalidReport(device, "length=${report.size} expected>=4")
            Kind.MEDIA -> if (report.isNotEmpty()) DecodeResult.Media(device, report[0].toInt() and 0xFF)
            else DecodeResult.InvalidReport(device, "length=0 expected>=1")
            Kind.OTHER -> if (device.touchCandidate) DecodeResult.UnsupportedDescriptor(device)
            else DecodeResult.UnsupportedDevice(device)
        }
    }

    fun decodeMirror(raw: Any?, report: ByteArray, receiverWidth: Int, receiverHeight: Int,
                     canvasWidth: Int, canvasHeight: Int, sourceWidth: Int, sourceHeight: Int): DecodeResult {
        val device = find(raw) ?: return DecodeResult.UnknownUuid(raw)
        if (!device.displayMatches) return DecodeResult.UnsupportedDevice(device)
        return when (device.kind) {
            Kind.TOUCH -> {
                val descriptor = device.touch ?: return DecodeResult.UnsupportedDescriptor(device)
                if (report.size == 12 && descriptor.reportBytes == 12) {
                    DecodeResult.Touch(device, RemoteTouch.decode(report, receiverWidth, receiverHeight, canvasWidth, canvasHeight, sourceWidth, sourceHeight))
                } else if (report.size == descriptor.reportBytes) {
                    DecodeResult.Touch(device, RemoteTouch.decodeSingle(report, descriptor, receiverWidth, receiverHeight, canvasWidth, canvasHeight, sourceWidth, sourceHeight))
                } else DecodeResult.InvalidReport(device, "length=${report.size} expected=${descriptor.reportBytes}")
            }
            Kind.BUTTON -> if (report.size >= 4) DecodeResult.Button(device, report[0].toInt() and 0xFF)
            else DecodeResult.InvalidReport(device, "length=${report.size} expected>=4")
            Kind.MEDIA -> if (report.isNotEmpty()) DecodeResult.Media(device, report[0].toInt() and 0xFF)
            else DecodeResult.InvalidReport(device, "length=0 expected>=1")
            Kind.OTHER -> if (device.touchCandidate) DecodeResult.UnsupportedDescriptor(device)
            else DecodeResult.UnsupportedDevice(device)
        }
    }

    /** Bounded diagnostics: first 20 of each category, then every 100th. */
    fun note(stage: String, result: DecodeResult, reportSize: Int? = null) {
        val key = when (result) {
            is DecodeResult.Touch -> "$stage:decoded"
            is DecodeResult.Button -> "$stage:decoded-button"
            is DecodeResult.Media -> "$stage:decoded-media"
            is DecodeResult.UnknownUuid -> "$stage:unknownUUID"
            is DecodeResult.InvalidReport -> "$stage:invalid"
            is DecodeResult.UnsupportedDescriptor -> "$stage:unsupporteddesc"
            is DecodeResult.UnsupportedDevice -> "$stage:unsupported"
        }
        val n = counters.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        if (n <= 20 || n % 100 == 0) {
            val suffix = when (result) {
                is DecodeResult.Touch -> "uuid=${result.device.uuid.label()} contacts=${result.contacts.size}"
                is DecodeResult.Button -> "uuid=${result.device.uuid.label()} bits=0x${result.bits.toString(16)}"
                is DecodeResult.Media -> "uuid=${result.device.uuid.label()} index=${result.index}"
                is DecodeResult.UnknownUuid -> "uuid=${result.raw} reportLen=${reportSize ?: -1}"
                is DecodeResult.InvalidReport -> "uuid=${result.device.uuid.label()} ${result.reason}"
                is DecodeResult.UnsupportedDescriptor -> "uuid=${result.device.uuid.label()} name=${result.device.name}"
                is DecodeResult.UnsupportedDevice -> "uuid=${result.device.uuid.label()} name=${result.device.name}"
            }
            log("[hid] $stage #$n $suffix")
        }
    }

    companion object {
        fun fromInfo(body: ByteArray, log: (String) -> Unit = {}): HidDeviceRegistry {
            val root = ReceiverPlist.map(body)
            val selectedDisplay = (root["displays"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.firstOrNull { (it["type"] as? Number)?.toInt() == 110 }
                ?: (root["displays"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull()
            val selectedDisplayUuid = selectedDisplay?.let {
                (it["uuid"] ?: it["displayUUID"] ?: it["displayUuid"])?.toString()
            }
            val devices = (root["hidDevices"] as? List<*>).orEmpty().mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val uuid = HidUuid.from(map["uuid"]) ?: return@mapNotNull null
                val descriptor = (map["hidDescriptor"] ?: map["descriptor"]) as? ByteArray
                val parsed = HidDescriptorParser.parse(descriptor)
                val name = (map["name"] as? String)
                val display = (map["displayUUID"] ?: map["displayUuid"])?.toString()
                val legacyKind = legacyKind(uuid)
                val kind = when {
                    parsed.touch != null || legacyKind == Kind.TOUCH -> Kind.TOUCH
                    // Device names are descriptive only.  Do not decode a
                    // real receiver's ButtonInput/PhoneKeyInput as a guessed
                    // TestReceiver bitmap/index report.
                    legacyKind == Kind.BUTTON -> Kind.BUTTON
                    legacyKind == Kind.MEDIA -> Kind.MEDIA
                    else -> Kind.OTHER
                }
                val touch = when {
                    parsed.touch != null -> parsed.touch
                    legacyKind == Kind.TOUCH -> TouchDescriptor(12, 65535, 65535)
                    else -> null
                }
                val displayMatches = selectedDisplayUuid == null || display == null ||
                    display.equals(selectedDisplayUuid, ignoreCase = true)
                if (!displayMatches) log("[hid] ignoring route for uuid=${uuid.label()} display=$display selected=$selectedDisplayUuid")
                Device(uuid, name, descriptor, touch, parsed.touchCandidate || legacyKind == Kind.TOUCH,
                    kind, display, displayMatches)
            }
            val registry = HidDeviceRegistry(devices, log)
            log("[hid] registry devices=${devices.size} " + devices.joinToString(";") {
                "${it.uuid.label()}:${it.kind}:desc=${it.descriptor?.size ?: 0}:report=${it.touch?.reportBytes ?: 0}"
            })
            return registry
        }

        fun empty(log: (String) -> Unit = {}): HidDeviceRegistry = HidDeviceRegistry(emptyList(), log)
    }
}
