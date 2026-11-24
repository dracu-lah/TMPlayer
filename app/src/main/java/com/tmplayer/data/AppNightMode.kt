package com.tmplayer.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.tmplayer.platform.Logger

/**
 * The theme the viewer chose, handed to Android as the app's night mode (WP-44, Android 12 and later).
 *
 * Compose already draws the chosen theme, but the resources Android picks before Compose runs (the
 * launch window, `values-night` colours, the system dialogs the app opens, a View in the player) follow
 * the system's night mode alone, so a dark choice on a light phone, or the reverse, showed the wrong
 * ground until the first frame and in every View. [UiModeManager.setApplicationNightMode] makes the
 * resources follow the choice too, with no AppCompat (the app has none, so `setDefaultNightMode` would
 * do nothing). Android keeps it per app across restarts, so a call with the mode already in force
 * changes nothing; a real change recreates the activity once, as any night mode change does.
 *
 * Below Android 12 there is no such call: the window ground mirrored in `MainActivity` is what covers
 * the first frame there. A television is always dark and has no theme choice, so it is left alone.
 */
object AppNightMode {

    private const val TAG = "AppNightMode"

    /** The [UiModeManager] mode for [choice]: Light and Dark as chosen, System follows the phone. */
    fun modeFor(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.Light -> UiModeManager.MODE_NIGHT_NO
        ThemeChoice.Dark -> UiModeManager.MODE_NIGHT_YES
        ThemeChoice.System -> UiModeManager.MODE_NIGHT_AUTO
    }

    fun apply(context: Context, choice: ThemeChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (FormFactor.isTv(context)) return
        runCatching {
            val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
            manager.setApplicationNightMode(modeFor(choice))
        }.onFailure { Logger.w(TAG, "The night mode was not set: ${it.message}") }
    }
}
