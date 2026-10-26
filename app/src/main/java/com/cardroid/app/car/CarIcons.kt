package com.cardroid.app.car

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory app icon cache (no Coil): Drawable -> Bitmap at the exact target pixel size (adaptive icons are drawn through their own mask),
 * loaded off the main thread, keyed by "pkg/cls@px" or "pkg@px". Labels for package-only lookups (Recents) are cached alongside.
 */
object CarIcons {
    private val cache = LruCache<String, ImageBitmap>(256)
    private val labels = LruCache<String, String>(128)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val loader = Dispatchers.IO.limitedParallelism(4)

    fun cached(key: String, px: Int): ImageBitmap? = cache.get("$key@$px")

    /** [key] is "pkg/cls" (activity icon) or "pkg" (application icon). */
    suspend fun load(context: Context, key: String, px: Int): ImageBitmap? {
        cached(key, px)?.let { return it }
        return withContext(loader) {
            val pm = context.packageManager
            val d: Drawable? = runCatching {
                val cn = if (key.contains('/')) ComponentName.unflattenFromString(key) else null
                if (cn != null) runCatching { pm.getActivityIcon(cn) }.getOrElse { pm.getApplicationIcon(cn.packageName) }
                else pm.getApplicationIcon(key)
            }.getOrNull()
            d?.let { toBitmap(it, px).asImageBitmap() }?.also { cache.put("$key@$px", it) }
        }
    }

    fun label(context: Context, pkg: String): String {
        labels.get(pkg)?.let { return it }
        val pm = context.packageManager
        val l = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        labels.put(pkg, l)
        return l
    }

    private fun toBitmap(d: Drawable, px: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width == px && d.bitmap.height == px) return d.bitmap
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val dr = d.mutate()
        dr.setBounds(0, 0, px, px)
        dr.draw(Canvas(b))
        return b
    }
}

/** Icon for [key] at [px], null while loading (callers draw a placeholder). State is keyed by [key] so recycled grid cells never show a stale icon. */
@Composable
fun rememberAppIcon(key: String, px: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    val state = remember(key, px) { mutableStateOf(CarIcons.cached(key, px)) }
    LaunchedEffect(key, px) { if (state.value == null) state.value = CarIcons.load(context, key, px) }
    return state
}
