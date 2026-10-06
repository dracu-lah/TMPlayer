package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * When the player stops to ask "Still watching?", on every platform.
 *
 * Two ways for a session to run on with nobody there: autoplay walking a series on its own, and a
 * long film nobody has touched the remote for. Either way the card pauses playback and stops the
 * downloads, so a viewer who fell asleep does not wake to a finished season and a spent data plan.
 * Any input at all, a key, a touch, a click, counts as somebody being there and starts both
 * counts again.
 */
object StillWatching {

    /** Episodes autoplay may start in a row before the next one waits to be asked for. */
    const val AUTOPLAY_LIMIT = 3

    /** Two hours of playback with no input. */
    const val IDLE_LIMIT_MS = 2 * 60 * 60 * 1000L

    /**
     * Whether autoplay asks before starting another episode, [autoplayedInARow] being how many it
     * has started since anybody last pressed anything.
     */
    fun askBeforeAutoplay(autoplayedInARow: Int): Boolean = autoplayedInARow >= AUTOPLAY_LIMIT

    /** Whether playback has gone on for long enough without input to ask. */
    fun askAfterIdle(idleMs: Long): Boolean = idleMs >= IDLE_LIMIT_MS
}

/**
 * The player menu's sleep timer: a few fixed lengths, or the end of the video playing.
 *
 * Only in the menu, never on the picture (the 1.16.0 decision that took the countdown off the
 * screen stands). When it runs out playback pauses the same way "Still watching?" pauses it.
 */
object SleepTimer {

    /** Stops at the end of the video playing, rather than after a length of time. */
    const val END_OF_VIDEO = 0

    /** What the menu offers, in minutes, with [END_OF_VIDEO] last. */
    val CHOICES: List<Int> = listOf(15, 30, 45, 60, 90, END_OF_VIDEO)

    fun label(minutes: Int): String = when {
        minutes == END_OF_VIDEO -> L.sleepEndOfVideo
        minutes < 60 -> L.unitMinutes(minutes)
        minutes % 60 == 0 -> L.unitHours(minutes / 60)
        else -> L.sleepHoursMinutes(minutes / 60, minutes % 60)
    }

    /**
     * The menu line's detail while a timer runs: what is left, rounded up to the minute so it never
     * reads zero while there is still time on it.
     */
    fun remaining(leftMs: Long): String {
        val minutes = ((leftMs.coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)
        return L.sleepMinutesLeft(minutes)
    }
}
