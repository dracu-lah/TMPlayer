package com.tmplayer.ui.profiles

import android.content.Context

/**
 * The downloads a profile switch paused, kept per profile on the device (HX88), so they go on
 * again when that profile is open next: after a Cancel, after the viewer comes back to it, or
 * after the process died while the switch waited. Only the ids; a row that is gone by then is
 * passed over.
 */
internal object PausedForSwitch {
    private const val PREFS = "profile_switch_paused"

    private fun key(folder: String?) = "paused_" + (folder ?: "")

    fun record(context: Context, folder: String?, ids: List<Int>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ids.isEmpty()) prefs.edit().remove(key(folder)).apply() else prefs.edit().putString(key(folder), ids.joinToString(",")).apply()
    }

    /** The ids paused for [folder]'s switch, taken: asked again, there are none. */
    fun take(context: Context, folder: String?): List<Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString(key(folder), null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        prefs.edit().remove(key(folder)).apply()
        return ids
    }
}
