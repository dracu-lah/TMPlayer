package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedSwapTest {

    @Test
    fun `a video at 1x has nothing to swap to until a speed is chosen`() {
        val swap = SpeedSwap()
        swap.opened(1f)
        assertNull(swap.tap(1f))
    }

    @Test
    fun `a tap swaps between the last two speeds, both ways`() {
        val swap = SpeedSwap()
        swap.opened(1f)
        swap.chose(1f, 1.5f)
        assertEquals(1f, swap.tap(1.5f)!!, 0f)
        swap.chose(1.5f, 1f)
        assertEquals(1.5f, swap.tap(1f)!!, 0f)
    }

    @Test
    fun `a video opened at a remembered speed swaps back to 1x`() {
        val swap = SpeedSwap()
        swap.opened(1.25f)
        assertEquals(1f, swap.tap(1.25f)!!, 0f)
    }

    @Test
    fun `choosing the speed already in force changes nothing`() {
        val swap = SpeedSwap()
        swap.opened(1f)
        swap.chose(1f, 1f)
        assertNull(swap.tap(1f))
        swap.chose(1f, 2f)
        swap.chose(2f, 2f)
        assertEquals(1f, swap.tap(2f)!!, 0f)
    }

    @Test
    fun `three speeds in a row keep the most recent other one`() {
        val swap = SpeedSwap()
        swap.opened(1f)
        swap.chose(1f, 1.25f)
        swap.chose(1.25f, 1.5f)
        assertEquals(1.25f, swap.tap(1.5f)!!, 0f)
    }
}
