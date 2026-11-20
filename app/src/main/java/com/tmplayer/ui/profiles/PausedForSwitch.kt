package com.tmplayer.ui.profiles

import android.content.Context

/**
 * What earlier builds kept on the device of the downloads a switch paused, to resume them by
 * themselves on the next open (HX88). Nothing resumes by itself now ([com.tmplayer.data.SwitchPause]):
 * the copy is cleared once and never read.
 */
internal object PausedForSwitch {
    private const val OLD_PREFS = "profile_switch_paused"

    fun clearOldCopy(context: Context) {
        // Cleared, not deleted: deleteSharedPreferences is Android 7.0 and the floor is 6.0.
        runCatching { context.applicationContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
