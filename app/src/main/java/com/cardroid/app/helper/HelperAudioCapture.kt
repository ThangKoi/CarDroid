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
        var policy: LoopbackMix? = null
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
            // A registered loopback mix is the only remote-submix route audio
            // policy plays at unity gain; the legacy device follows media volume.
            val mixed = registerLoopbackMix(rate, log)
            policy = mixed
            val attributesBuilder = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(attributesBuilder, 8 /* REMOTE_SUBMIX */)
            AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
                .invoke(attributesBuilder, "addr=${mixed.address}")
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
            log("Audio: loopback mix started ${rate}Hz stereo addr=${mixed.address}; phone output redirected at unity gain")
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
            policy?.let { runCatching { it.release() } }
            runCatching { socket.close() }
            log("Audio: output capture released")
        }
    }

    /** Registered audio policy; audio service also drops it if the helper process dies. */
    private class LoopbackMix(val address: String, val release: () -> Unit)

    // Usages the legacy remote submix redirected: media, games, navigation and assistant.
    private val LOOPBACK_USAGES = intArrayOf(
        AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)

    // Hidden/system API: android.media.audiopolicy loopback mix registered as the shell.
    private fun registerLoopbackMix(rate: Int, log: (String) -> Unit): LoopbackMix {
        val context = FakeContext.get(log)
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        for (usage in LOOPBACK_USAGES) {
            addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), 1 /* RULE_MATCH_ATTRIBUTE_USAGE */)
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(rule.javaClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder,
            AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, 2 /* ROUTE_FLAG_LOOP_BACK */)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(android.content.Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val audioPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val status = android.media.AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
            .invoke(manager, audioPolicy) as Int
        check(status == 0) { "Audio policy registration failed: $status" }
        val release = { android.media.AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
            .invoke(manager, audioPolicy); Unit }
        val address = runCatching { mixClass.getMethod("getRegistration").invoke(mix) as String? }.getOrNull()
        if (address.isNullOrEmpty()) { runCatching(release); error("Audio policy mix has no address") }
        return LoopbackMix(address, release)
    }
}
