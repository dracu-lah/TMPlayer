package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import com.tmplayer.platform.Logger
import java.util.Locale

/**
 * Tells AWT when the window manager does not reparent, before the first window exists.
 *
 * AWT on X11 assumes the window manager wraps each window in a frame of its own (reparents it),
 * and reads the window's size and position off that frame. sway, and every other compositor whose
 * X11 support comes from wlroots, never reparents, and neither do Hyprland or niri. AWT then never
 * learns the size the compositor gave the window: the picture stays at its old size in a corner of
 * the tile, and fullscreen leaves it small in the middle of a black screen. The JDK's own switch
 * for this is the `_JAVA_AWT_WM_NONREPARENTING` environment variable, which it reads with
 * `getenv` the first time it needs to know, so setting it on this very process early enough works.
 * Reparenting window managers (GNOME, KDE, Xfce) are left alone: there the switch would put the
 * window's contents under its title bar.
 */
object NonReparentingWm {

    private const val VARIABLE = "_JAVA_AWT_WM_NONREPARENTING"

    private interface CLib : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    /** Desktop names (`XDG_CURRENT_DESKTOP`, `XDG_SESSION_DESKTOP`) of window managers that never reparent. */
    private val NON_REPARENTING = setOf("sway", "hyprland", "river", "niri", "wayfire", "labwc", "dwl")

    /** Variables only those compositors set, for a session that names no desktop. */
    private val NON_REPARENTING_SOCKETS = listOf("SWAYSOCK", "HYPRLAND_INSTANCE_SIGNATURE", "NIRI_SOCKET")

    fun applyIfNeeded(env: Map<String, String> = System.getenv()) {
        if (!OsInfo.isLinux || !needed(env)) return
        runCatching { Native.load("c", CLib::class.java).setenv(VARIABLE, "1", 0) }
            .onSuccess { Logger.i(TAG, "non reparenting window manager: $VARIABLE=1") }
            .onFailure { Logger.w(TAG, "could not set $VARIABLE: ${it.message}") }
    }

    internal fun needed(env: Map<String, String>): Boolean {
        if (env.containsKey(VARIABLE)) return false
        if (NON_REPARENTING_SOCKETS.any { !env[it].isNullOrBlank() }) return true
        return listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP").any { key ->
            env[key].orEmpty().lowercase(Locale.ROOT).split(':').any { it.trim() in NON_REPARENTING }
        }
    }

    private const val TAG = "NonReparentingWm"
}
