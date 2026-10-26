package com.cardroid.app.helper

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.cardroid.app.helper.display.FakeContext
import org.json.JSONObject

/** Runs as shell, which can read media sessions without adding notification-access permission to the app. */
object HelperMediaState {
    fun manager(): MediaSessionManager = MediaSessionManager::class.java
        .getDeclaredConstructor(Context::class.java).newInstance(FakeContext.get())

    fun snapshot(): ByteArray {
        val context = FakeContext.get()
        val manager = manager()
        val sessions = manager.getActiveSessions(null)
        val selected = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
        val metadata = selected?.metadata
        val state = selected?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        var position = state?.position?.coerceAtLeast(0) ?: 0
        if (playing && state != null && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong()
        }
        if (duration > 0) position = position.coerceAtMost(duration)
        val pkg = selected?.packageName.orEmpty()
        val app = if (pkg.isEmpty()) "" else runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        return JSONObject().put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            .put("appName", app).put("package", pkg).put("durationMs", duration).put("positionMs", position)
            .put("status", if (playing) 1 else if (state?.state == PlaybackState.STATE_PAUSED) 2 else 0)
            .toString().toByteArray(Charsets.UTF_8)
    }
}
