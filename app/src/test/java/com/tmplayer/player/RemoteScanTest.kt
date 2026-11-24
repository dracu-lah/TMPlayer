package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan option's arithmetic (WP-79): a held arrow moves a virtual target with the player's own
 * steps and pace, and nothing is sought until the one [RemoteScan.commit].
 */
class RemoteScanTest {

    private val hour = 3_600_000L
    private val guard = 1_000L

    /** [repeats] repeats of a held arrow, 200 ms apart, from [from], each reported as arriving on time. */
    private fun hold(scan: RemoteScan, from: Long, repeats: IntRange, forward: Boolean = true, duration: Long = hour): List<Long?> =
        repeats.map { repeat ->
            val now = 10_000L + repeat * 200L
            scan.step(from, forward, repeat, eventMs = now, nowMs = now, durationMs = duration, endGuardMs = guard)
        }

    @Test
    fun `a hold moves the target further with each repeat and seeks nothing until commit`() {
        val scan = RemoteScan()
        val steps = hold(scan, from = 600_000L, repeats = 3..20).filterNotNull()
        assertTrue("the target moved", steps.isNotEmpty())
        assertEquals("only ever forward", steps.sorted(), steps)
        assertTrue("past where it started", steps.first() > 600_000L)
        // One landing, at the last target, and the scan is over.
        val last = scan.target
        assertNotNull(last)
        assertEquals(last, scan.commit())
        assertNull(scan.target)
        assertNull("a second commit is nothing", scan.commit())
    }

    @Test
    fun `the pace grows with the hold like the live ramp, held to a share of the length`() {
        val scan = RemoteScan()
        val short = 5 * 60_000L
        val moves = hold(scan, from = 0L, repeats = 3..40, duration = short).filterNotNull()
        val steps = moves.zipWithNext { a, b -> b - a }
        assertTrue("the later steps are no smaller", steps.last() >= steps.first())
        assertTrue("never past the end less the guard", moves.all { it <= short - guard })
    }

    @Test
    fun `back from the start stops at zero and forward stops before the end`() {
        val back = RemoteScan()
        hold(back, from = 20_000L, repeats = 3..60, forward = false)
        assertEquals(0L, back.target)
        val forward = RemoteScan()
        hold(forward, from = hour - 30_000L, repeats = 3..60, forward = true)
        assertEquals(hour - guard, forward.target)
    }

    @Test
    fun `no length means no scan and a stale repeat moves nothing`() {
        val live = RemoteScan()
        assertNull(live.step(5_000L, true, 5, eventMs = 1_000L, nowMs = 1_000L, durationMs = 0L, endGuardMs = guard))
        assertNull(live.target)
        val slow = RemoteScan()
        // A repeat that waited a second behind a slow main thread is dropped: the scan stops when the key does.
        assertNull(slow.step(5_000L, true, 5, eventMs = 1_000L, nowMs = 2_500L, durationMs = hour, endGuardMs = guard))
        assertNull(slow.target)
    }

    @Test
    fun `the last key wins from the same target and a cancel leaves nothing to land`() {
        val scan = RemoteScan()
        hold(scan, from = 600_000L, repeats = 3..12)
        val before = scan.target!!
        // The other arrow, held on: the steps now go back from where the scan was, not from the video.
        val back = (13..40).mapNotNull { repeat ->
            val now = 10_000L + repeat * 200L
            scan.step(600_000L, false, repeat, eventMs = now, nowMs = now, durationMs = hour, endGuardMs = guard)
        }
        assertTrue("it moved back", back.isNotEmpty())
        assertTrue("from the target, not the video: $before then $back", back.first() < before && before - back.first() < 120_000L)
        scan.cancel()
        assertNull(scan.target)
        assertNull(scan.commit())
    }
}
