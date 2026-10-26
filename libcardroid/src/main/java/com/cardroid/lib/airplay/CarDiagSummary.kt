package com.cardroid.lib.airplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only diagnostics for the car's /info, SETUP replies and event-channel commands
 * (car-touch-iap2-tunnel Stage 0). Pure and exception-free: every entry point returns a
 * single compact log line, never dumps large bodies, never changes behaviour.
 */
object CarDiagSummary {
    const val MAX_VALUE_CHARS = 200

    /** Wall-clock HH:mm:ss.SSS (device local time) so noted tap times can be matched to log lines. */
    fun wall(ms: Long = System.currentTimeMillis()): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun cut(s: String): String = if (s.length <= MAX_VALUE_CHARS) s else s.take(MAX_VALUE_CHARS) + "…(${s.length})"

    /** Scalar/compound value to short text; bytes become "<nB>", numbers decimal. Truncated to [MAX_VALUE_CHARS]. */
    fun fmt(v: Any?, depth: Int = 0): String = cut(when (v) {
        null -> "null"
        is ByteArray -> "<${v.size}B>"
        is Map<*, *> -> if (depth >= 3) "{…}" else v.entries.joinToString(",", "{", "}") { "${it.key}=${fmt(it.value, depth + 1)}" }
        is List<*> -> if (depth >= 3) "[…]" else v.joinToString(",", "[", "]") { fmt(it, depth + 1) }
        else -> v.toString()
    })

    private fun hex(v: Any?): String = when (v) {
        is Long -> "0x" + java.lang.Long.toHexString(v) + "($v)"
        is Number -> "0x" + java.lang.Long.toHexString(v.toLong()) + "($v)"
        else -> fmt(v)
    }

    private fun decode(body: ByteArray): Map<String, Any?>? = runCatching { ReceiverPlist.map(body) }.getOrNull()

    /** `[car-diag] info ...` for the car's /info reply. */
    fun summarizeInfo(body: ByteArray, nowMs: Long = System.currentTimeMillis()): String {
        val m = decode(body) ?: return "[car-diag] info t=${wall(nowMs)} body=${body.size}B (not a readable bplist)"
        val hid = m["hidDevices"] as? List<*>
        val hidText = if (hid == null) "absent" else "${hid.size}:" + hid.joinToString(";", "[", "]") { d ->
            val dm = d as? Map<*, *> ?: return@joinToString fmt(d)
            val desc = (dm["hidDescriptor"] ?: dm["descriptor"]) as? ByteArray
            "usagePage=${fmt(dm["usagePage"])} usage=${fmt(dm["usage"])} name=${fmt(dm["name"])} uuid=${fmt(dm["uuid"])} " +
                "descriptorLen=${desc?.size ?: "none"} keys=${dm.keys.joinToString("/")}"
        }
        val displays = m["displays"] as? List<*>
        val dispText = if (displays == null) "absent" else "${displays.size}:" + displays.joinToString(";", "[", "]") { d ->
            val dm = d as? Map<*, *> ?: return@joinToString fmt(d)
            "${fmt(dm["widthPixels"])}x${fmt(dm["heightPixels"])} type=${fmt(dm["type"])} uuid=${fmt(dm["uuid"])} " +
                "primaryInputDevice=${fmt(dm["primaryInputDevice"])} features=${hex(dm["features"])} maxFPS=${fmt(dm["maxFPS"])}"
        }
        return "[car-diag] info t=${wall(nowMs)} body=${body.size}B keys=${cut(m.keys.joinToString(","))} hidDevices=$hidText " +
            "displays=$dispText features=${hex(m["features"])} sourceVersion=${fmt(m["sourceVersion"])} " +
            "model=${fmt(m["model"])} name=${fmt(m["name"])}"
    }

    /** `[car-diag] setup-resp ...` for the session-SETUP reply (eventPort/timingPort/enabledFeatures). */
    fun summarizeSetupResponse(body: ByteArray, nowMs: Long = System.currentTimeMillis()): String {
        val m = decode(body) ?: return "[car-diag] setup-resp t=${wall(nowMs)} body=${body.size}B (not a readable bplist)"
        val extra = m.entries.filter { it.key !in setOf("eventPort", "timingPort", "enabledFeatures") }
            .joinToString(" ") { "${it.key}=${fmt(it.value)}" }
        return "[car-diag] setup-resp t=${wall(nowMs)} body=${body.size}B keys=${cut(m.keys.joinToString(","))} " +
            "enabledFeatures=${if ("enabledFeatures" in m) fmt(m["enabledFeatures"]) else "absent"} " +
            "eventPort=${fmt(m["eventPort"])} timingPort=${fmt(m["timingPort"])}" + if (extra.isNotEmpty()) " $extra" else ""
    }

    /** `[car-diag] media-setup-resp ...` stream list as type/streamID/dataPort. */
    fun summarizeMediaSetupResponse(body: ByteArray, nowMs: Long = System.currentTimeMillis()): String {
        val m = decode(body) ?: return "[car-diag] media-setup-resp t=${wall(nowMs)} body=${body.size}B (not a readable bplist)"
        val streams = (m["streams"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        val list = streams.joinToString(";", "[", "]") { "type=${fmt(it["type"])}/streamID=${fmt(it["streamID"])}/dataPort=${fmt(it["dataPort"])}" }
        return "[car-diag] media-setup-resp t=${wall(nowMs)} body=${body.size}B keys=${cut(m.keys.joinToString(","))} streams=${streams.size}:$list"
    }

    /** Thread-safe event command-type counter. */
    class CommandCounts {
        private val counts = java.util.TreeMap<String, Int>()
        private var hid = 0
        @Synchronized fun record(type: Any?): Int {
            val k = (type as? String) ?: "unknown"
            counts.merge(k, 1, Int::plus)
            if (k == "hidSendReport") hid++
            return counts.getValue(k)
        }
        @Synchronized fun total(): Int = counts.values.sum()
        @Synchronized fun hidCount(): Int = hid
        @Synchronized fun summary(label: String, nowMs: Long = System.currentTimeMillis()): String =
            "[car-diag] cmd-counts $label t=${wall(nowMs)} total=${counts.values.sum()} hidSendReport=$hid ${counts}"
    }

    /** `[car-diag] hid ...` line text for one hidSendReport. */
    fun hidLine(n: Int, sinceStartMs: Long, command: Map<*, *>, nowMs: Long = System.currentTimeMillis()): String {
        val report = command["hidReport"] as? ByteArray
        return "[car-diag] hid #$n t=${wall(nowMs)} +${sinceStartMs}ms uuid=${fmt(command["uuid"])} len=${report?.size ?: -1}" +
            " first=${report?.take(8)?.joinToString("") { "%02x".format(it) } ?: "none"}"
    }

    /** True when hid report #[n] should be logged (first 50, then every 20th). */
    fun logHid(n: Int): Boolean = n <= 50 || n % 20 == 0

    val IAP_MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
}
