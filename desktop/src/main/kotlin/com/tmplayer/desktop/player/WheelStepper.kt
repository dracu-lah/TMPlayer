package com.tmplayer.desktop.player

import kotlin.math.abs
import kotlin.math.sign

/**
 * Turns the stream of wheel events into whole steps (WP-48). A mouse notch arrives as 1.0 and is
 * one step at once; a trackpad sends dozens of small deltas for one swipe (and a tail of momentum
 * after the fingers lift), which used to be a step each, so one swipe ran the volume from 50 to 0.
 *
 * Deltas add up until they reach [stepAt] (one notch), one step per whole [stepAt], never more
 * than [maxSteps] for one event. A gap of more than [quietMs] with no event ends the gesture: what
 * was left over is dropped rather than carried into the next swipe, and a change of direction
 * starts over too. Each axis keeps its own sum.
 */
class WheelStepper(
    private val stepAt: Float = 1f,
    private val maxSteps: Int = 2,
    private val quietMs: Long = 150L,
) {
    private var sumX = 0f
    private var sumY = 0f
    private var last = Long.MIN_VALUE

    /** The whole steps this event completes, as (horizontal, vertical), each between -[maxSteps] and [maxSteps]. */
    fun feed(dx: Float, dy: Float, nowMs: Long): Pair<Int, Int> {
        if (last != Long.MIN_VALUE && nowMs - last > quietMs) {
            sumX = 0f
            sumY = 0f
        }
        last = nowMs
        val sx = take(dx, true)
        val sy = take(dy, false)
        return sx to sy
    }

    /** Forgets what has built up (a sheet opened, the player went away). */
    fun reset() {
        sumX = 0f
        sumY = 0f
        last = Long.MIN_VALUE
    }

    private fun take(delta: Float, horizontal: Boolean): Int {
        if (delta == 0f || delta.isNaN() || delta.isInfinite()) return 0
        var sum = if (horizontal) sumX else sumY
        if (sum != 0f && sign(sum) != sign(delta)) sum = 0f
        sum += delta
        val whole = (abs(sum) / stepAt).toInt().coerceAtMost(maxSteps)
        val steps = if (sum < 0) -whole else whole
        sum -= steps * stepAt
        // What is left after a capped event is no more than one step, so a burst cannot queue up.
        sum = sum.coerceIn(-stepAt, stepAt)
        if (horizontal) sumX = sum else sumY = sum
        return steps
    }
}
