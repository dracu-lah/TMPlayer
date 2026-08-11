package com.tmplayer.data

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * One answer, for the whole process, to "is this a television?".
 *
 * The UI branches on this in several places, so it has to be the same answer everywhere: a screen
 * that decides it is on a TV while the player decides it is on a phone is worse than either.
 */
object FormFactor {

    @Volatile
    private var cached: Boolean? = null

    /**
     * True on a TV, false on a touch device.
     *
     * Two signals, because neither is reliable alone. The leanback system feature is what a real
     * Android TV declares, but some boxes ship without it, and an emulator can carry it while
     * running in a phone UI mode. `UiModeManager` catches those. Hardware cannot change under a
     * running process, so the result is worth caching; a benign race here just computes it twice.
     */
    fun isTv(context: Context): Boolean = cached ?: compute(context).also { cached = it }

    /**
     * Answers [isTv] with [value] from here on, whatever the hardware says.
     *
     * This exists for the screenshot fixture, which captures the television layout on a phone
     * panel resized to 1920x1080. Nothing in a release build calls it: the promo activity is the
     * only caller and it is not part of a release APK.
     */
    fun override(value: Boolean) {
        cached = value
    }

    private fun compute(context: Context): Boolean {
        val app = context.applicationContext
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val uiMode = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
