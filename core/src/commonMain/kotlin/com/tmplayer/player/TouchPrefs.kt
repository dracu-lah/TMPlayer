package com.tmplayer.player

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
    val seekGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val volumeGesture: Boolean = true,
    val haptics: Boolean = true,
    /** The total on the times line reads as the time left instead. */
    val showRemaining: Boolean = false,
) {
    companion object {
        const val DOUBLE_TAP_DEFAULT_MS = 10_000L
        val DOUBLE_TAP_CHOICES_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

        const val HOLD_DEFAULT = 2f
        /** Zero is "off". */
        val HOLD_CHOICES = listOf(0f, 1.5f, 2f, 3f)

        const val TIMEOUT_DEFAULT_MS = 3_500L
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
            speed <= 0f -> "Off"
            speed % 1f == 0f -> "${speed.toInt()}x"
            else -> "${speed}x"
        }

        fun timeoutLabel(ms: Long): String = when {
            ms <= 0L -> "Never"
            ms % 1000L == 0L -> "${ms / 1000} seconds"
            else -> String.format(java.util.Locale.ROOT, "%.1f seconds", ms / 1000f)
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
