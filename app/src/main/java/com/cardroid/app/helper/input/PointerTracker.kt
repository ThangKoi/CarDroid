package com.cardroid.app.helper.input

import com.cardroid.app.helper.HelperProtocol.TouchContact

/**
 * Pure (no android.*) diff of consecutive TOUCH_STATE snapshots into touch events, modelled on scrcpy PointersState/Controller
 * (Apache-2.0, Genymobile/scrcpy): local pointer ids are the lowest free 0..9, downTime is the first DOWN of a gesture, a lifted
 * pointer is still present in its UP/POINTER_UP event and is removed afterwards. Not thread-safe; the injector synchronises.
 */
class PointerTracker {
    enum class Kind { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP }
    class Ptr(val slot: Int, val pid: Int, val x: Int, val y: Int)
    /** [index] = position in [pointers] of the pointer going down/up (0 for MOVE). */
    class Event(val kind: Kind, val index: Int, val pointers: List<Ptr>, val downTime: Long)

    private var active = ArrayList<Ptr>()
    private var downTime = 0L
    val activeCount: Int get() = active.size

    fun update(contacts: List<TouchContact>, now: Long): List<Event> {
        val want = LinkedHashMap<Int, TouchContact>()
        for (c in contacts) if (c.down) want[c.id] = c
        val out = ArrayList<Event>()
        // 1. coordinate changes of pointers that stay
        var moved = false
        val next = ArrayList<Ptr>(active.size)
        for (p in active) {
            val c = want[p.slot]
            if (c == null) next.add(p) else { if (c.x != p.x || c.y != p.y) moved = true; next.add(Ptr(p.slot, p.pid, c.x, c.y)) }
        }
        if (moved) { active = next; out.add(Event(Kind.MOVE, 0, snapshot(), downTime)) }
        // 2. lifts
        for (p in ArrayList(active)) if (!want.containsKey(p.slot)) {
            val idx = active.indexOf(p)
            out.add(Event(if (active.size == 1) Kind.UP else Kind.POINTER_UP, idx, snapshot(), downTime))
            active.removeAt(idx)
        }
        // 3. new pointers
        for (c in want.values) if (active.none { it.slot == c.id } && active.size < MAX) {
            val first = active.isEmpty()
            if (first) downTime = now
            active.add(Ptr(c.id, freePid(), c.x, c.y))
            out.add(Event(if (first) Kind.DOWN else Kind.POINTER_DOWN, active.size - 1, snapshot(), downTime))
        }
        return out
    }

    /** Lifts everything (cleanup path). */
    fun releaseAll(now: Long): List<Event> = update(emptyList(), now)

    private fun snapshot(): List<Ptr> = ArrayList(active)
    private fun freePid(): Int { var i = 0; while (active.any { it.pid == i }) i++; return i }

    companion object { const val MAX = 10 }
}
