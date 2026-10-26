package com.cardroid.app.helper.input

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.cardroid.app.helper.HelperProtocol.TouchContact
import com.cardroid.app.helper.display.FakeContext

/**
 * Injects touch and key events onto one display as the shell UID. Port of the relevant parts of scrcpy (Apache-2.0, Genymobile/scrcpy):
 * wrappers/InputManager (reflective injectInputEvent ASYNC + InputEvent.setDisplayId), Device.injectEvent/injectKeyEvent and
 * Controller.injectTouch/injectPointers. Every failure throws; the caller reports ERROR and keeps the helper alive.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class InputInjector(private val log: (String) -> Unit) {
    private val tracker = PointerTracker()
    private val lock = Any()
    private val inputManager: Any by lazy { FakeContext.get(log).getSystemService(Context.INPUT_SERVICE) ?: error("no InputManager") }
    private val injectMethod by lazy { inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    val activePointers: Int get() = synchronized(lock) { tracker.activeCount }

    /** Applies a TOUCH_STATE snapshot to [displayId]; returns the number of events injected. */
    fun touch(displayId: Int, contacts: List<TouchContact>): Int = synchronized(lock) { inject(displayId, tracker.update(contacts, SystemClock.uptimeMillis())) }

    /** Lifts every active pointer (display stop, control/video loss, helper exit). Never throws. */
    fun releaseAll(displayId: Int) {
        synchronized(lock) {
            if (tracker.activeCount == 0) return
            val n = tracker.activeCount
            try { inject(displayId, tracker.releaseAll(SystemClock.uptimeMillis())); log("Input: released $n active pointer(s) on display $displayId") }
            catch (t: Throwable) { log("Input: release failed ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    /** KEY DOWN then UP on [displayId]. */
    fun key(displayId: Int, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val media = keyCode in intArrayOf(85, 86, 87, 88, 89, 90, 126, 127)
        val mediaManager = if (media) com.cardroid.app.helper.HelperMediaState.manager() else null
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            if (mediaManager != null) {
                mediaManager.javaClass.getMethod("dispatchMediaKeyEvent", KeyEvent::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(mediaManager, event, false)
            } else send(event, displayId)
        }
        if (media) log("MediaControl: dispatched key=$keyCode through Android media session")
    }

    private fun inject(displayId: Int, events: List<PointerTracker.Event>): Int {
        for (e in events) send(motion(e), displayId)
        return events.size
    }

    private fun motion(e: PointerTracker.Event): MotionEvent {
        val n = e.pointers.size
        val props = Array(n) { MotionEvent.PointerProperties().apply { id = e.pointers[it].pid; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = e.pointers[i].x.toFloat(); y = e.pointers[i].y.toFloat()
                // a lifting pointer reports zero pressure, like scrcpy
                pressure = if ((e.kind == PointerTracker.Kind.UP || e.kind == PointerTracker.Kind.POINTER_UP) && i == e.index) 0f else 1f
                size = 1f
            }
        }
        val action = when (e.kind) {
            PointerTracker.Kind.DOWN -> MotionEvent.ACTION_DOWN
            PointerTracker.Kind.UP -> MotionEvent.ACTION_UP
            PointerTracker.Kind.MOVE -> MotionEvent.ACTION_MOVE
            PointerTracker.Kind.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (e.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PointerTracker.Kind.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (e.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        return MotionEvent.obtain(e.downTime, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(event: InputEvent, displayId: Int) {
        try {
            if (displayId != 0) setDisplayId.invoke(event, displayId)
            val ok = injectMethod.invoke(inputManager, event, INJECT_MODE_ASYNC)
            if (ok == false) throw IllegalStateException("injectInputEvent returned false")
        } finally { (event as? MotionEvent)?.recycle() }
    }

    companion object { const val INJECT_MODE_ASYNC = 0 }
}
