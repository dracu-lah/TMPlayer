package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * How the phone's player answers a thumb, as the viewer has set it in Settings.
 *
 * Read once when the player opens. Two of these apply to a television as well: how long the
 * controls stay up, and whether the time readout shows what is left. The rest do not; its remote
 * has buttons for them, and its key model ([Skip]) is settled separately.
 */
data class TouchPrefs(
    /** A single tap on the picture plays or pauses, instead of raising the controls. */
    val tapPlaysPauses: Boolean = false,
    /** How far each double tap on a side of the picture jumps. */
    val doubleTapMs: Long = DOUBLE_TAP_DEFAULT_MS,
    /** The speed a held finger plays at; zero turns the hold off. */
    val holdSpeed: Float = HOLD_DEFAULT,
    /** How long the controls stay up while playing; zero keeps them up until tapped away. */
    val controlsTimeoutMs: Long = TIMEOUT_DEFAULT_MS,
    /**
     * The viewer has chosen [controlsTimeoutMs] (it is in the store). Until then a television uses
     * [TIMEOUT_TV_DEFAULT_MS]; a stored choice, even 3.5 s, is never changed. See [timeoutFor].
     */
    val controlsTimeoutStored: Boolean = true,
    val seekGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val volumeGesture: Boolean = true,
    val haptics: Boolean = true,
    /** The total on the times line reads as the time left instead. */
    val showRemaining: Boolean = false,
    /** The next video starts at the brightness the last one was left at, not at the system's (WP-27). Off until chosen. */
    val rememberBrightness: Boolean = false,
) {
    /** How long the controls stay up on this device: a television's own default until the viewer chooses. */
    fun timeoutFor(tv: Boolean): Long =
        if (tv && !controlsTimeoutStored) TIMEOUT_TV_DEFAULT_MS else controlsTimeoutMs

    companion object {
        const val DOUBLE_TAP_DEFAULT_MS = 10_000L
        val DOUBLE_TAP_CHOICES_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

        const val HOLD_DEFAULT = 2f
        /** Zero is "off". */
        val HOLD_CHOICES = listOf(0f, 1.5f, 2f, 3f)

        const val TIMEOUT_DEFAULT_MS = 3_500L

        /** Brightness steps of the player's page and the share of the system's own, in per cent (WP-27). */
        const val BRIGHTNESS_STEP = 10
        const val BRIGHTNESS_MIN = 2

        /** The stored level meaning "follow the system". */
        const val BRIGHTNESS_SYSTEM = -1

        /** A stored level off the range reads as the system's: a bad write never leaves the screen black. */
        fun sanitiseBrightness(percent: Long?): Int =
            // Checked as stored, before narrowing: 4294967346 would wrap to 50 (PB2-18).
            percent?.takeIf { it in BRIGHTNESS_MIN.toLong()..100L }?.toInt() ?: BRIGHTNESS_SYSTEM

        /** The next page level up or down from [percent] on the 10 per cent steps, held between 10 and 100; from the system's start at 50. */
        fun stepBrightness(percent: Int, up: Boolean): Int {
            val from = if (percent < BRIGHTNESS_MIN) 50 else percent
            // To the next multiple of ten in the direction asked: 37 goes to 40 up and 30 down, 50 to 60 and 40.
            val steps = if (up) (from / BRIGHTNESS_STEP + 1) * BRIGHTNESS_STEP else ((from - 1) / BRIGHTNESS_STEP) * BRIGHTNESS_STEP
            return steps.coerceIn(BRIGHTNESS_STEP, 100)
        }

        /** A remote needs longer than a thumb to reach the row and a button on it. */
        const val TIMEOUT_TV_DEFAULT_MS = 5_000L
        /** Zero is "never". */
        val TIMEOUT_CHOICES_MS = listOf(2_000L, 3_500L, 5_000L, 8_000L, 0L)

        /** A stored value off the list falls back to the default, so a bad write cannot stick. */
        fun sanitiseDoubleTap(ms: Long?): Long =
            ms?.takeIf { it in DOUBLE_TAP_CHOICES_MS } ?: DOUBLE_TAP_DEFAULT_MS

        fun sanitiseHold(speed: Float?): Float =
            speed?.takeIf { it in HOLD_CHOICES } ?: HOLD_DEFAULT

        fun sanitiseTimeout(ms: Long?): Long =
            ms?.takeIf { it in TIMEOUT_CHOICES_MS } ?: TIMEOUT_DEFAULT_MS

        fun holdLabel(speed: Float): String = when {
            speed <= 0f -> L.commonOff
            else -> L.formatSpeedFactor(L.messages.formatter.number(speed))
        }

        fun timeoutLabel(ms: Long): String = when {
            ms <= 0L -> L.commonNever
            ms % 1000L == 0L -> L.unitSeconds(ms / 1000)
            else -> L.unitSecondsDecimal(L.messages.formatter.decimal(ms / 1000.0, 1))
        }

        /** The next choice up or down a list, clamped at the ends. */
        fun <T> step(choices: List<T>, current: T, direction: Int): T {
            val at = choices.indexOf(current).coerceAtLeast(0)
            return choices[(at + direction).coerceIn(0, choices.lastIndex)]
        }
    }
}

/**
 * Which part of the picture a double tap landed in.
 *
 * Three zones, 35 / 30 / 35, the split Next Player and Just Player use: the sides are wide enough
 * for a thumb that is not looking, and the middle is wide enough to aim at for play and pause.
 */
enum class TapZone {
    Left, Centre, Right;

    companion object {
        const val SIDE_FRACTION = 0.35f

        fun of(x: Float, width: Int): TapZone {
            if (width <= 0) return Centre
            return when {
                x < width * SIDE_FRACTION -> Left
                x > width * (1f - SIDE_FRACTION) -> Right
                else -> Centre
            }
        }
    }
}

/**
 * Adds up consecutive double taps on one side, the way YouTube's "+20 seconds" does.
 *
 * Each tap on the same side inside [WINDOW_MS] of the last adds one step; a tap on the other side,
 * or a pause longer than the window, starts again. Pure so the arithmetic can be tested without a
 * touchscreen.
 */
class SeekCounter {
    var zone: TapZone? = null
        private set
    var steps = 0
        private set
    private var lastAt = Long.MIN_VALUE

    /** True while a run is live, during which a lone single tap should be ignored. */
    fun isLive(now: Long): Boolean = zone != null && now - lastAt <= WINDOW_MS

    /** Records one tap on [side] at [now] and returns how many steps the run has reached. */
    fun tap(side: TapZone, now: Long): Int {
        if (side != zone || now - lastAt > WINDOW_MS) {
            zone = side
            steps = 0
        }
        steps++
        lastAt = now
        return steps
    }

    fun reset() {
        zone = null
        steps = 0
        lastAt = Long.MIN_VALUE
    }

    companion object {
        /** How long after a tap the next one still counts as the same run. */
        const val WINDOW_MS = 750L
    }
}
