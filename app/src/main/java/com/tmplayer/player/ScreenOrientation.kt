package com.tmplayer.player

import android.content.pm.ActivityInfo

/**
 * Which way up the picture is held, and the order the button steps through.
 *
 * Three states, the same three VLC offers: leave it to the phone, hold it sideways, hold it
 * upright. The locks let a viewer override the system's auto rotate setting for the length of one
 * video, which is the whole reason the button is worth having.
 *
 * [Follow] is the system's own setting rather than the raw sensor, so a phone with auto rotate off
 * is respected. The two locks are the sensor kind on purpose, so a phone turned end for end keeps
 * the picture the right way up instead of upside down.
 *
 * Meaningless on a television, which reports one orientation and ignores every request made of it,
 * so nothing here is ever reached there.
 */
enum class ScreenOrientation(val requested: Int, val label: String) {
    /** Whatever the phone's own rotation setting says. */
    Follow(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "Rotation: follow the phone"),

    /** Sideways, either way round. What the player opens in, because almost every video is wide. */
    Landscape(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "Rotation: locked sideways"),

    /** Upright, either way up. */
    Portrait(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, "Rotation: locked upright"),
    ;

    fun next(): ScreenOrientation = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * What a player opens in.
         *
         * Landscape rather than [Follow], so the window is already the right way round before the
         * decoder reports a picture and playback does not begin by throwing the screen around.
         * A portrait clip still turns, but that is the rare case.
         */
        val DEFAULT = Landscape
    }
}
