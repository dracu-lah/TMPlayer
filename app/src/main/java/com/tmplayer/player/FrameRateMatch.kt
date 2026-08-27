package com.tmplayer.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import com.tmplayer.data.FormFactor

/**
 * Runs a 24 fps film at 24 Hz, on the televisions that allow it.
 *
 * A movie shown on a panel locked to 60 Hz repeats every fourth frame, a cadence the eye reads as
 * a faint judder on every slow pan. Media3 only ever asks the display for a switch it can make
 * without a picture interruption, because a black flash mid-binge is not its call to make. From
 * Android 12 the television settings carry that exact question, "Match content frame rate", and
 * when the viewer has answered Always, the interruption is one they asked for: the app is then
 * expected to request the switch itself, which Media3 will not do.
 *
 * So: on a television whose viewer said Always, Media3's own signalling is turned off and the
 * surface is told the film's real rate with the stronger request. Everywhere else nothing changes,
 * and Media3 keeps making the seamless switches it always made.
 */
object FrameRateMatch {

    /** Whether this device wants the app, not Media3, to speak to the display. */
    fun shouldTakeOver(context: Context): Boolean {
        if (!FormFactor.isTv(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val displays = context.getSystemService(DisplayManager::class.java) ?: return false
        return displays.matchContentFrameRateUserPreference ==
            DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS
    }

    /**
     * Asks the display for the film's own rate. A rate of zero is a format that never said, and
     * clears the request rather than pinning the panel to nothing.
     */
    fun apply(surface: Surface?, frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val target = surface?.takeIf { it.isValid } ?: return
        runCatching {
            target.setFrameRate(
                frameRate.coerceAtLeast(0f),
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS,
            )
        }
    }
}
