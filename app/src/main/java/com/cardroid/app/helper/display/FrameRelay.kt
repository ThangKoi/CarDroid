package com.cardroid.app.helper.display

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Pure pacing decisions for [FrameRelay] (unit-tested). */
object RelayPacing {
    /** Draw when a fresh display frame is waiting and the max-rate interval elapsed, or when idle for [idleNs] (keep-alive). */
    fun shouldDraw(dirty: Boolean, sinceLastDrawNs: Long, minIntervalNs: Long, idleNs: Long): Boolean =
        (dirty && sinceLastDrawNs >= minIntervalNs) || sinceLastDrawNs >= idleNs

    /** How long the render loop may sleep before the next decision (ms, >= 1). */
    fun waitMs(dirty: Boolean, sinceLastDrawNs: Long, minIntervalNs: Long, idleNs: Long): Long {
        val dueNs = if (dirty) minOf(minIntervalNs, idleNs) - sinceLastDrawNs else idleNs - sinceLastDrawNs
        return maxOf(1L, (dueNs + 999_999L) / 1_000_000L)
    }
}

/**
 * Display -> SurfaceTexture -> GL blit -> encoder input surface.
 *
 * Why: the OMX GraphicBufferSource behind KEY_REPEAT_PREVIOUS_FRAME_AFTER repeats the last frame only a bounded number of times
 * (kRepeatLastFrameCount, ~10 x 100 ms) and then stays silent until SurfaceFlinger delivers a NEW frame. A static car display therefore
 * stops producing video after ~1 s (observed: stall until the next minute-clock redraw). This relay owns the producer side: it re-draws the
 * latest frame at least every [IDLE_MS] forever, so the encoder always sees fresh input. Modelled on scrcpy's OpenGLRunner.
 */
class FrameRelay(
    private val width: Int, private val height: Int, private val fps: Int,
    private val encoderSurface: Surface, private val log: (String) -> Unit, private val onError: (String) -> Unit,
) {
    private val lock = Object()
    private var dirty = false
    @Volatile private var closed = false
    private var thread: Thread? = null
    private var callbackThread: HandlerThread? = null
    private var texture: SurfaceTexture? = null
    private var displaySurface: Surface? = null
    @Volatile private var startError: Throwable? = null
    private val ready = java.util.concurrent.CountDownLatch(1)

    /** Starts the GL thread and returns the surface the virtual display must render to. Throws if GL setup fails. */
    fun start(): Surface {
        val t = Thread({ run() }, "helper-relay").apply { isDaemon = true }
        thread = t; t.start()
        if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) { release(); error("FrameRelay GL setup timed out") }
        startError?.let { release(); throw IllegalStateException("FrameRelay GL setup failed: ${it.message}", it) }
        return displaySurface ?: error("FrameRelay surface missing")
    }

    fun release() {
        if (closed) return
        closed = true
        synchronized(lock) { lock.notifyAll() }
        val t = thread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(1500) }
        thread = null
    }

    private fun run() {
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program = 0; var texId = 0
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
            val ver = IntArray(2); check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
            eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

            program = buildProgram()
            val aPos = GLES20.glGetAttribLocation(program, "aPos"); val aTex = GLES20.glGetAttribLocation(program, "aTex")
            val uMat = GLES20.glGetUniformLocation(program, "uMat"); val uSampler = GLES20.glGetUniformLocation(program, "uTex")
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val cb = HandlerThread("helper-relay-cb").apply { start() }; callbackThread = cb
            val st = SurfaceTexture(texId); texture = st
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ synchronized(lock) { dirty = true; lock.notifyAll() } }, Handler(cb.looper))
            displaySurface = Surface(st)
            ready.countDown()

            val quad = floatBuffer(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            val mat = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
            val minNs = 1_000_000_000L / fps.coerceIn(1, 60); val idleNs = IDLE_MS * 1_000_000L
            var lastDraw = System.nanoTime() - idleNs // first loop iteration draws immediately (black until the first display frame)
            var frames = 0L; var fresh = 0L; var lastLog = System.nanoTime()
            while (!closed) {
                var wasDirty: Boolean
                val since = System.nanoTime() - lastDraw
                synchronized(lock) {
                    if (!RelayPacing.shouldDraw(dirty, since, minNs, idleNs)) lock.wait(RelayPacing.waitMs(dirty, since, minNs, idleNs))
                    wasDirty = dirty
                }
                if (closed) break
                val now = System.nanoTime()
                if (!RelayPacing.shouldDraw(wasDirty, now - lastDraw, minNs, idleNs)) continue
                if (wasDirty) { synchronized(lock) { dirty = false }; st.updateTexImage(); st.getTransformMatrix(mat); fresh++ }
                GLES20.glViewport(0, 0, width, height)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                GLES20.glUniform1i(uSampler, 0); GLES20.glUniformMatrix4fv(uMat, 1, false, mat, 0)
                quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos)
                quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, now)
                check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers err=0x${Integer.toHexString(EGL14.eglGetError())}" }
                lastDraw = now; frames++
                if (now - lastLog > RELAY_LOG_MS * 1_000_000L) {
                    lastLog = now
                    log("relay: drawn=$frames fresh=$fresh idle=${frames - fresh}")
                }
            }
        } catch (t: Throwable) {
            if (ready.count > 0L) { startError = t; ready.countDown() }
            else if (!closed) { log("relay failed ${t.javaClass.simpleName}: ${t.message}"); onError("relay ${t.javaClass.simpleName}: ${t.message}") }
        } finally {
            runCatching { displaySurface?.release() }; runCatching { texture?.release() }
            runCatching { callbackThread?.quitSafely() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                runCatching { EGL14.eglTerminate(eglDisplay) }
            }
            ready.countDown()
        }
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    companion object {
        /** Re-draw the last frame at least this often while the display is static (matches the old 100 ms repeat). */
        const val IDLE_MS = 100L
        private const val RELAY_LOG_MS = 1_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uMat; varying vec2 vTex;\n" +
            "void main() { gl_Position = aPos; vTex = (uMat * aTex).xy; }"
        private const val FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vTex); }"
    }
}
