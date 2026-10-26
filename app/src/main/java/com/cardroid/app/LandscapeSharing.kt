package com.cardroid.app

import android.content.Context
import android.provider.Settings

/** Restore the user's rotation settings when the sharing session ends. */
object LandscapeSharing {
    fun force(context: Context): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val resolver=context.contentResolver
        val prefs=context.getSharedPreferences("sharing_rotation",Context.MODE_PRIVATE)
        if (!prefs.contains("previous_auto")) prefs.edit()
            .putInt("previous_auto",Settings.System.getInt(resolver,Settings.System.ACCELEROMETER_ROTATION,1))
            .putInt("previous_rotation",Settings.System.getInt(resolver,Settings.System.USER_ROTATION,0)).commit()
        Settings.System.putInt(resolver,Settings.System.ACCELEROMETER_ROTATION,0)
        Settings.System.putInt(resolver,Settings.System.USER_ROTATION,1)
        return true
    }
    fun restore(context: Context) {
        val prefs=context.getSharedPreferences("sharing_rotation",Context.MODE_PRIVATE)
        if (!prefs.contains("previous_auto") || !Settings.System.canWrite(context)) return
        Settings.System.putInt(context.contentResolver,Settings.System.USER_ROTATION,prefs.getInt("previous_rotation",0))
        Settings.System.putInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,prefs.getInt("previous_auto",1))
        prefs.edit().clear().commit()
    }
}
