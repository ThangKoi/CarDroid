package com.cardroid.app.car

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.cardroid.app.CarDroidLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Always-on-top Home button in the bottom-left corner of the car display, shown while a non-launcher app is in front
 * (TestReceiver has no knob HOME). Tap = GO_HOME, long-press = GO_HOME + open the launcher's Recents panel.
 * Lives in the accessibility service: TYPE_ACCESSIBILITY_OVERLAY on a display context created from the service (the service
 * override binds the per-display overlay token). All methods run on the main thread. Never touches the phone display (id 0).
 */
class CornerHomeOverlay(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var view: View? = null
    private var wm: WindowManager? = null
    private var shownId = -1

    @OptIn(FlowPreview::class)
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob()); scope = s
        s.launch {
            combine(CarDisplayState.displayId, CarDisplayState.launcherOnTop) { id, top -> if (id > 0 && !top) id else -1 }
                .distinctUntilChanged().debounce(150).collect { id -> if (id < 0) hide() else show(id) }
        }
    }

    fun stop() { scope?.cancel(); scope = null; hide() }

    private fun show(displayId: Int) {
        if (shownId == displayId && view != null) return
        hide()
        if (displayId == 0) return
        try {
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            if (display == null || !CarDisplayState.isCarDisplay(display)) { CarDroidLog.log("CornerHome: add failed: display $displayId missing or not the car display"); return }
            val ctx = service.createDisplayContext(display)
            val manager = ctx.getSystemService(WindowManager::class.java)
            val d = ctx.resources.displayMetrics.density
            val size = (64 * d).toInt(); val margin = (16 * d).toInt()
            val v = HomeButtonView(ctx)
            val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START; x = margin; y = margin; title = "CarDroidCornerHome"
            }
            manager.addView(v, lp)
            view = v; wm = manager; shownId = displayId
            CarDroidLog.log("CornerHome: shown on display $displayId")
        } catch (e: Throwable) {
            view = null; wm = null; shownId = -1
            CarDroidLog.log("CornerHome: add failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hide() {
        val v = view; val m = wm
        view = null; wm = null; shownId = -1
        if (v != null && m != null) {
            runCatching { m.removeViewImmediate(v) }.onFailure { CarDroidLog.log("CornerHome: remove failed: ${it.javaClass.simpleName}: ${it.message}") }
            CarDroidLog.log("CornerHome: hidden")
        }
    }

    private class HomeButtonView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(com.cardroid.app.CarDroidPalette.SurfaceArgb); setStroke(maxOf(1, (1 * d).toInt()), Color.argb(60, 255, 255, 255)) }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val longPress = Runnable { fired = true; CarDroidLog.log("CornerHome: long-press -> recents"); CarDisplayState.requestRecents(); CarDisplayState.goHome() }
        private var fired = false
        private var down = false
        init { contentDescription = "Home"; isClickable = true; setBackgroundDrawable(bg) }

        override fun onDraw(c: Canvas) {
            glyph.color = if (down) com.cardroid.app.CarDroidPalette.MintArgb else Color.WHITE
            val w = width.toFloat(); val h = height.toFloat(); val u = w / 64f
            val p = Path().apply {
                moveTo(w / 2, h / 2 - 13 * u); lineTo(w / 2 - 14 * u, h / 2 - 1 * u); lineTo(w / 2 - 10 * u, h / 2 - 1 * u); lineTo(w / 2 - 10 * u, h / 2 + 12 * u)
                lineTo(w / 2 - 3 * u, h / 2 + 12 * u); lineTo(w / 2 - 3 * u, h / 2 + 5 * u); lineTo(w / 2 + 3 * u, h / 2 + 5 * u); lineTo(w / 2 + 3 * u, h / 2 + 12 * u)
                lineTo(w / 2 + 10 * u, h / 2 + 12 * u); lineTo(w / 2 + 10 * u, h / 2 - 1 * u); lineTo(w / 2 + 14 * u, h / 2 - 1 * u); close()
            }
            c.drawPath(p, glyph)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; fired = false; invalidate(); postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress); down = false; invalidate()
                    val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                    if (!fired && inside) { CarDroidLog.log("CornerHome: tap -> goHome"); CarDisplayState.goHome() }
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); down = false; invalidate() }
            }
            return true
        }
        override fun onDetachedFromWindow() { removeCallbacks(longPress); super.onDetachedFromWindow() }
    }
}
