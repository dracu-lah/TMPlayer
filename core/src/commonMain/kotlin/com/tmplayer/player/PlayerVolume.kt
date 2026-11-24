package com.tmplayer.player

/**
 * Player volume (#33): the player's own gain, in 1 per cent steps from [MIN] (silent) to [FULL]
 * (the file as it is), under the device's volume rather than mapped onto its steps. A television
 * whose lowest volume step is still too loud at night is turned down here, below that step.
 *
 * Louder than [FULL] is not this setting's job: Volume boost lifts the sound above the file's
 * own level, and the two multiply. Remembered between videos, like the boost.
 *
 * The figure is turned into a gain on a cubic curve, as mpv's volume on the desktop is, so the
 * same figure sounds alike on both and every step is heard: 50 per cent is about -18 dB and 10
 * per cent about -60 dB, where a straight line would spend most of its steps on changes too
 * small to hear and jump 6 dB a press at the bottom.
 */
object PlayerVolume {
    const val MIN = 0
    const val FULL = 100
    const val STEP = 1

    /**
     * A stored figure as one the player can use: anything outside [MIN] to [FULL] (a damaged
     * setting, a value from a newer version) is [FULL], so a bad setting never silences a video.
     */
    fun sanitise(stored: Long?): Int = stored?.takeIf { it in MIN.toLong()..FULL.toLong() }?.toInt() ?: FULL

    /** [percent] one [STEP] up or down, held at the ends of the range. */
    fun step(percent: Int, up: Boolean): Int = (percent.coerceIn(MIN, FULL) + if (up) STEP else -STEP).coerceIn(MIN, FULL)

    /** Whether a step that way still changes anything. */
    fun canStep(percent: Int, up: Boolean): Boolean = if (up) percent < FULL else percent > MIN

    /** The gain [percent] plays at, 0 to 1: cubic, as mpv's volume is. */
    fun gain(percent: Int): Float {
        val share = percent.coerceIn(MIN, FULL) / FULL.toFloat()
        return share * share * share
    }

    /** The gain an engine is given: 0 while the keyboard's M has the sound off, else [gain]. */
    fun gain(percent: Int, muted: Boolean): Float = if (muted) 0f else gain(percent)
}
