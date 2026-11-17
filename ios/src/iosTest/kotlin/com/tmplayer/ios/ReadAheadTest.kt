package com.tmplayer.ios

import androidx.media3.exoplayer.DefaultLoadControl
import com.tmplayer.ios.media3.ReadAhead
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android's buffering policy reaches the engine as set, and the byte budget, which Media3 keeps for
 * what is ahead and what already played together, is shared out by the time each holds.
 */
class ReadAheadTest {
    @Test
    fun thePlayersFiguresAreKeptAsSet() {
        val plan = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 4_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setBackBuffer(10_000, true)
            .build()
            .plan
        assertEquals(ReadAhead(15_000, 50_000, 1_000, 4_000, 64 * 1024 * 1024, 10_000), plan)
    }

    @Test
    fun theBudgetIsSharedByTheTimeEachSideHolds() {
        val plan = ReadAhead(15_000, 50_000, 1_000, 4_000, 60 * 1024 * 1024, 10_000)
        // Ten seconds behind against fifty ahead: a sixth of 60 MB behind, the rest ahead.
        assertEquals(10L * 1024 * 1024, plan.behindBytes)
        assertEquals(50L * 1024 * 1024, plan.aheadBytes)
        assertEquals(plan.targetBufferBytes.toLong(), plan.aheadBytes + plan.behindBytes)
    }

    @Test
    fun noBudgetLeavesTheBytesToTheEngine() {
        val unset = DefaultLoadControl.Builder().build().plan
        assertEquals(0L, unset.aheadBytes)
        assertEquals(0L, unset.behindBytes)
        assertEquals(50_000, unset.maxBufferMs)
        val noBack = ReadAhead(15_000, 50_000, 1_000, 4_000, 20 * 1024 * 1024, 0)
        assertEquals(0L, noBack.behindBytes)
        assertEquals(20L * 1024 * 1024, noBack.aheadBytes)
    }
}
