package com.cardroid.app

import java.util.concurrent.atomic.AtomicBoolean

/** Single-flight guard: [tryEnter] returns true for exactly one caller per armed session; [rearm] starts a new session. */
class SessionTeardownGuard {
    private val done = AtomicBoolean(false)
    fun tryEnter(): Boolean = done.compareAndSet(false, true)
    fun rearm() = done.set(false)
}
