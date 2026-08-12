package com.tmplayer.player

import android.content.pm.ActivityInfo

/**
 * Which way up the picture is held, and the order the button steps through.
 *
 * Three states, the same three VLC offers, because they are the only three anybody asks for: leave
 * it to the phone, hold it sideways, hold it upright. A viewer lying down with auto rotate on wants
 * the second, a viewer watching a phone-shot clip on a bus wants the third, and a viewer who has
 * turned auto rotate off at the system level wants the player to override that for the length of
 * one film, which is the whole reason the button is worth having at all.
 *
 * [Follow] is the system's own setting rather than the raw sensor: a phone with auto rotate off is
 * a phone whose owner has said which way up things should be, and a video player that ignores that
 * is the same rudeness in the other direction. The two locks are the sensor kind on purpose, so a
 * phone turned end for end keeps the picture the right way up instead of upside down.
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
         * Landscape rather than [Follow], and that is the fix for the lurch: the loading screen
         * used to be drawn in whatever the phone happened to be holding and the window turned only
         * once the decoder reported a wide picture, so every play began by throwing the screen
         * around. A portrait clip still turns, but that is the rare case rather than the usual one.
         */
        val DEFAULT = Landscape
    }
}
