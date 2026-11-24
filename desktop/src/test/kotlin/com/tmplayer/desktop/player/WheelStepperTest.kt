package com.tmplayer.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trackpad swipe is dozens of small deltas and must not be a step each (WP-48). */
class WheelStepperTest {

    private fun volumeSteps(stepper: WheelStepper, deltas: List<Float>, gapMs: Long = 8L, start: Long = 1_000L): Int {
        var at = start
        var total = 0
        for (d in deltas) {
            total += SeekMath.wheelActions(stepper, 0f, d, shift = false, wheelSeeks = false, nowMs = at).size
            at += gapMs
        }
        return total
    }

    @Test
    fun `sixty tiny events are two volume steps at most, not sixty`() {
        // 60 events of 4 px are 0.04 of a notch each: 2.4 notches in all.
        assertEquals(2, volumeSteps(WheelStepper(), List(60) { 0.04f }))
    }

    @Test
    fun `a mouse notch is one step at once, and three quick notches are three`() {
        assertEquals(1, volumeSteps(WheelStepper(), listOf(1f)))
        assertEquals(3, volumeSteps(WheelStepper(), listOf(1f, 1f, 1f), gapMs = 40))
    }

    @Test
    fun `one event is never more than two steps`() {
        val (_, steps) = WheelStepper().feed(0f, 9f, 0L)
        assertEquals(2, steps)
        val (_, back) = WheelStepper().feed(0f, -9f, 0L)
        assertEquals(-2, back)
    }

    @Test
    fun `a pause ends the gesture and drops what was left, so momentum does not run on`() {
        val stepper = WheelStepper()
        // 0.9 of a notch, then 200 ms of quiet, then 0.2: the second swipe starts from nothing.
        assertEquals(0, stepper.feed(0f, 0.9f, 0L).second)
        assertEquals(0, stepper.feed(0f, 0.2f, 200L).second)
        // Without the pause the same two would have made a step.
        val joined = WheelStepper()
        joined.feed(0f, 0.9f, 0L)
        assertEquals(1, joined.feed(0f, 0.2f, 100L).second)
    }

    @Test
    fun `a change of direction starts over`() {
        val stepper = WheelStepper()
        stepper.feed(0f, 0.9f, 0L)
        assertEquals(0, stepper.feed(0f, -0.3f, 20L).second)
        assertEquals(-1, stepper.feed(0f, -0.8f, 40L).second)
    }

    @Test
    fun `the horizontal axis counts on its own and Shift with the wheel still swaps seek and volume`() {
        val stepper = WheelStepper()
        val sideways = SeekMath.wheelActions(stepper, 1f, 0f, shift = false, wheelSeeks = false, nowMs = 0L)
        assertEquals(listOf<PlayerAction>(PlayerAction.SeekBy(10_000)), sideways)
        val shifted = SeekMath.wheelActions(WheelStepper(), 0f, 1f, shift = true, wheelSeeks = false, nowMs = 0L)
        assertEquals(listOf<PlayerAction>(PlayerAction.SeekBy(-10_000)), shifted)
    }

    @Test
    fun `garbage deltas change nothing`() {
        val stepper = WheelStepper()
        assertEquals(0 to 0, stepper.feed(Float.NaN, Float.POSITIVE_INFINITY, 0L))
        assertEquals(0 to 0, stepper.feed(0f, 0f, 10L))
        assertTrue(SeekMath.wheelActions(stepper, 0f, 0f, shift = false, wheelSeeks = false, nowMs = 20L).isEmpty())
    }
}
