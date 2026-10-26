package com.cardroid.app.car

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.cardroid.app.CarDroidLog

/** A launchable app entry: LAUNCHER activity [component] of [packageName]. */
data class CarApp(val label: String, val packageName: String, val className: String) {
    val component: ComponentName get() = ComponentName(packageName, className)
    /** Helper LAUNCH target ("pkg/cls"). */
    val target: String get() = "$packageName/$className"
}

object CarApps {
    /** All MAIN/LAUNCHER activities except CarDroid itself, sorted by label (needs the manifest <queries> entry on targetSdk 34). */
    fun load(context: Context): List<CarApp> {
        val pm = context.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { CarApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Launches [app] on the car display [displayId]: direct startActivity with ActivityOptions.setLaunchDisplayId + NEW_TASK from the launcher
     * (visible window, so background-launch rules pass); on SecurityException / any failure falls back to the helper LAUNCH via [CarDisplayState].
     * Returns true when a launch was issued (directly or through the helper).
     */
    fun launch(activity: Activity, app: CarApp, displayId: Int): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            CarDroidLog.log("CarLauncher: direct launch ${app.target} on display $displayId")
            true
        } catch (e: Exception) {
            CarDroidLog.log("CarLauncher: direct launch ${app.target} failed (${e.javaClass.simpleName}: ${e.message}); helper fallback")
            CarDisplayState.launch(app.target)
        }
    }
}
