package com.cardroid.app.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.cardroid.app.CarDroidLog
import com.cardroid.lib.airplay.RequestUi

/** Picks the phone app that answers a car `requestUI` (now playing, maps, phone, messages) and opens it on the car display. */
object RequestUiApps {
    private val MAPS_FALLBACKS = listOf("com.google.android.apps.maps", "com.waze", "vn.vietmap.live")

    fun open(context: Context, request: RequestUi.Request) {
        // The car's CarPlay button reveals our existing virtual display. Keep its
        // foreground task intact; only the explicit Home button should open the launcher.
        // The protocol handler restores screen ownership and refreshes video separately.
        if (request.kind == RequestUi.Kind.SHOW) {
            CarDroidLog.log("CarDisplay: requestUI SHOW -> preserving current car app")
            return
        }
        // Until active-player routing exists, retain the existing Now Playing fallback.
        if (request.kind == RequestUi.Kind.NOW_PLAYING) {
            val ok = CarDisplayState.goHome()
            CarDroidLog.log("CarDisplay: requestUI ${request.kind} -> launcher ${if (ok) "sent" else "failed (helper display not active)"}")
            return
        }
        if (request.kind == RequestUi.Kind.UNKNOWN) {
            CarDroidLog.log("CarDisplay: requestUI url=${request.url} has no app mapping; showing current app")
            return
        }
        val pkg = resolve(context, request.kind)
        if (pkg == null) {
            CarDroidLog.log("CarDisplay: requestUI ${request.kind}: no matching app installed")
            return
        }
        val ok = CarDisplayState.launch(pkg)
        CarDroidLog.log("CarDisplay: requestUI ${request.kind} -> launch $pkg ${if (ok) "sent" else "failed (helper display not active)"}")
    }

    private fun resolve(context: Context, kind: RequestUi.Kind): String? {
        val pm = context.packageManager
        return when (kind) {
            RequestUi.Kind.MAPS -> selector(pm, Intent.CATEGORY_APP_MAPS) ?: firstInstalled(pm, MAPS_FALLBACKS)
            RequestUi.Kind.MESSAGES -> selector(pm, Intent.CATEGORY_APP_MESSAGING)
            RequestUi.Kind.PHONE -> pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" }
            else -> null
        }
    }

    private fun selector(pm: PackageManager, category: String): String? =
        pm.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" } // "android" = the chooser, i.e. no default

    private fun firstInstalled(pm: PackageManager, packages: List<String>): String? =
        packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
}
