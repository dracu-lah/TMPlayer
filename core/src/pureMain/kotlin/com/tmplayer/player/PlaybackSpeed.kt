package com.tmplayer.player

import com.tmplayer.i18n.L
import kotlin.math.roundToInt

/**
 * The speeds the transport row offers, and the arithmetic for stepping between them.
 *
 * The Android player's speed button opens every stop to pick from at once, with a fine control
 * under them that moves [FINE_STEP] at a time between [MIN] and [MAX] ([fine]); the desktop's speed
 * pill still steps through the stops with [next], and its ] and [ keys step finely. The choice is
 * remembered for the show, the course, the chat or the topic either way: see [SpeedMemory].
 *
 * Pure, so the stepping is tested rather than discovered on a sofa.
 */
object PlaybackSpeed {

    /** The tiles of the speed page; 2.5 and 3 are there (WP-30) since the page and the memory both reach 4. */
    val CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    const val DEFAULT = 1f

    /** The slowest and the fastest speed anything here plays at: the desktop's range, and mpv's sane one. */
    const val MIN = 0.25f
    const val MAX = 4f

    /** One press of the Android player's fine control. */
    const val FINE_STEP = 0.05f

    /** [FINE_STEP] in hundredths, so the stepping is done in whole numbers and 1.1 never becomes 1.0999999. */
    private const val STEP_HUNDREDTHS = 5
    private const val MIN_HUNDREDTHS = 25
    private const val MAX_HUNDREDTHS = 400

    /** The next speed up, wrapping back to the slowest past the top of the list. */
    fun next(current: Float): Float {
        val at = nearestIndex(current)
        return CHOICES[(at + 1) % CHOICES.size]
    }

    /**
     * One fine step from [current], [up] or down: onto the next 0.05 mark in that direction, so a
     * speed the desktop left at 1.33x goes to 1.35x or 1.3x rather than to 1.38x. Held inside
     * [MIN] and [MAX], where a further press stays put.
     */
    fun fine(current: Float, up: Boolean): Float {
        val now = if (current.isNaN() || current <= 0f) 100 else (current * 100).roundToInt()
        val below = now.floorDiv(STEP_HUNDREDTHS) * STEP_HUNDREDTHS
        val next = when {
            up -> below + STEP_HUNDREDTHS
            below == now -> now - STEP_HUNDREDTHS
            else -> below
        }
        return next.coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS) / 100f
    }

    /** Whether a fine step [up] or down from [current] would change anything, for the buttons' enabled state. */
    fun canStep(current: Float, up: Boolean): Boolean = fine(current, up) != sanitise(current)

    /**
     * Whatever was stored on disk, as a speed this player plays: onto the nearest 0.05 mark and
     * inside [MIN] and [MAX]. Nonsense (zero, negative, not a number) is 1x.
     */
    fun sanitise(value: Float): Float {
        if (value.isNaN() || value <= 0f) return DEFAULT
        val hundredths = ((value * 100) / STEP_HUNDREDTHS).roundToInt() * STEP_HUNDREDTHS
        return hundredths.coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS) / 100f
    }

    /** Whether [value] is one of the [CHOICES], give or take a float's rounding. */
    fun isPreset(value: Float): Boolean = CHOICES.any { kotlin.math.abs(it - value) < 0.001f }

    /** "1x", "1.25x": trailing zeroes dropped, because "1.00x" reads as a measurement. */
    fun label(value: Float): String {
        // The locale's digits, at most two decimals, trailing zeroes dropped: "1x", "1.25x", "1,25x".
        // Rounded to the nearest 0.05 but not held to the range, so the step itself reads "0.05x".
        val shown = if (value.isNaN() || value <= 0f) DEFAULT else ((value * 100) / STEP_HUNDREDTHS).roundToInt() * STEP_HUNDREDTHS / 100f
        return L.formatSpeedFactor(L.messages.formatter.number(shown))
    }

    private fun nearestIndex(value: Float): Int {
        var best = 0
        for (i in CHOICES.indices) {
            if (kotlin.math.abs(CHOICES[i] - value) < kotlin.math.abs(CHOICES[best] - value)) best = i
        }
        return best
    }
}
