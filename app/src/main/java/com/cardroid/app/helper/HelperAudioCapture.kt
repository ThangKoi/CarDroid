package com.cardroid.app.helper

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import com.cardroid.app.helper.display.FakeContext
import java.net.Socket
import java.nio.ByteBuffer

/** Shell-only output capture. The owning connection bounds the lifetime of audio redirection. */
object HelperAudioCapture {
    /** Socketless-protocol probe invoked explicitly from adb; always releases output capture. */
    fun selfTest(log: (String) -> Unit) {
        FakeContext.get(log)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val worker = Thread({ server.accept().use { it.soTimeout = HelperProtocol.TIMEOUT_MS; stream(it, log) } }, "audio-probe")
            worker.start()
            try {
                Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                    client.soTimeout = 5000
                    HelperProtocol.write(client.getOutputStream(), HelperProtocol.T_AUDIO_CONFIG,
                        ByteBuffer.allocate(8).putInt(48000).putInt(1024).array())
                    val ready = HelperProtocol.read(client.getInputStream())
                    check(ready.type == HelperProtocol.T_AUDIO_READY) {
                        if (ready.type == HelperProtocol.T_ERROR) HelperProtocol.parseError(ready.payload).message else "not ready"
                    }
                    var nonzero = 0
                    repeat(400) {
                        val frame = HelperProtocol.read(client.getInputStream())
                        check(frame.type == HelperProtocol.T_AUDIO_PCM && frame.payload.size == 4096)
                        if (frame.payload.any { it != 0.toByte() }) nonzero++
                    }
                    log("Audio self-test passed: 400 PCM packets (>8 seconds), nonzero=$nonzero")
                }
            } finally { worker.join(3000) }
        }
    }

    @android.annotation.SuppressLint("MissingPermission", "WrongConstant")
    fun stream(socket: Socket, log: (String) -> Unit) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        var recorder: AudioRecord? = null
        try {
            require(Build.VERSION.SDK_INT >= 31) { "Helper audio requires Android 12+" }
            val request = HelperProtocol.read(input)
            require(request.type == HelperProtocol.T_AUDIO_CONFIG && request.payload.size == 8)
            // The audio channel has no inbound heartbeat after CONFIG. Its EOF
            // watcher must block until disconnect, not stop recording at the
            // handshake timeout (six seconds).
            socket.soTimeout = 0
            val config = ByteBuffer.wrap(request.payload)
            val rate = config.int
            val samples = config.int
            require(rate in listOf(44100, 48000) && samples in 1..4096) { "Unsupported audio format" }
            val bytes = samples * 4 // PCM16 stereo
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Unsupported audio buffer" }
            // Request unity gain for the remote mix. AudioRecord owns the
            // full-volume lease and releases it on stop/release or binder death.
            val attributesBuilder = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(attributesBuilder, 8 /* REMOTE_SUBMIX */)
            AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
                .invoke(attributesBuilder, "fixedVolume")
            val attributes = attributesBuilder.build()
            val builder = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 8, bytes * 8))
            AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
                .invoke(builder, attributes)
            // Hidden API: attribute recording to the shell rather than the application UID.
            AudioRecord.Builder::class.java.getMethod("setContext", android.content.Context::class.java)
                .invoke(builder, FakeContext.get(log))
            val audio = builder.build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            HelperProtocol.write(out, HelperProtocol.T_AUDIO_READY)
            log("Audio: REMOTE_SUBMIX started ${rate}Hz stereo fixedVolume=true; phone output redirected")
            // Socket EOF must stop capture even when AudioRecord.read() is blocked.
            Thread({
                try { while (input.read() != -1) { } } catch (_: Exception) { }
                finally { runCatching { audio.stop() } }
            }, "helper-audio-stop").apply { isDaemon = true }.start()
            val pcm = ByteArray(bytes)
            while (!socket.isClosed) {
                var offset = 0
                while (offset < bytes) {
                    val n = audio.read(pcm, offset, bytes - offset, AudioRecord.READ_BLOCKING)
                    check(n > 0) { "Audio read failed: $n" }
                    offset += n
                }
                HelperProtocol.write(out, HelperProtocol.T_AUDIO_PCM, pcm)
            }
        } catch (e: Exception) {
            log("Audio: stopped/failed ${e.javaClass.simpleName}: ${e.message}")
            runCatching { HelperProtocol.write(out, HelperProtocol.T_ERROR,
                HelperProtocol.error(HelperProtocol.E_AUDIO_FAILED, "${e.javaClass.simpleName}: ${e.message}")) }
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            runCatching { socket.close() }
            log("Audio: output capture released")
        }
    }
}
