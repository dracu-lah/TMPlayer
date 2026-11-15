package com.tmplayer.player

import kotlin.math.abs

/**
 * The last two speeds the viewer used, so one tap on the speed button swaps between them (1x and
 * 1.5x, say) while the full list stays a long press away. Nothing to swap to yet (a video at 1x
 * that never left it) answers null, and the button opens the list instead.
 */
class SpeedSwap {
    private var previous: Float? = null

    /** A video opened at [current]: from a remembered 1.5x the other speed is 1x, from 1x there is none yet. */
    fun opened(current: Float) {
        previous = if (same(current, PlaybackSpeed.DEFAULT)) null else PlaybackSpeed.DEFAULT
    }

    /** The speed moved from [from] to [to]: [from] is the one a tap goes back to. */
    fun chose(from: Float, to: Float) {
        if (!same(from, to)) previous = from
    }

    /** What a tap goes to from [current], or null when there is no other speed to go to. */
    fun tap(current: Float): Float? = previous?.takeIf { !same(it, current) }

    private fun same(a: Float, b: Float) = abs(a - b) < 0.001f
}
