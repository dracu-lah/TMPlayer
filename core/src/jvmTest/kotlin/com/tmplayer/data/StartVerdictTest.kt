package com.tmplayer.data

import com.tmplayer.data.StartVerdict.State
import com.tmplayer.player.OpeningProgress
import com.tmplayer.player.StreamStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** The table of FQ1: every row is one situation of an opening that has not started. */
class StartVerdictTest {

    private val slow = OpeningProgress.SPEED_NOTE_AFTER_MS
    private val stall = OpeningStall.STALL_MS
    private val fine = StreamStats.MIN_MEANINGFUL_SPEED * 100

    private fun facts(
        waited: Long,
        quiet: Long = 0,
        local: Boolean = false,
        offline: Boolean = false,
        rate: Long = fine,
        limited: Boolean = false,
    ) = StartVerdict.Facts(waited, quiet, local, offline, rate, limited)

    private data class Row(val name: String, val facts: StartVerdict.Facts, val expected: State)

    @Test
    fun `the table`() {
        val rows = listOf(
            Row("just started", facts(waited = 500, rate = 0), State.Waiting),
            Row("exactly at the slow point", facts(waited = slow, rate = 0), State.Waiting),
            Row("past the slow point with a good rate", facts(waited = slow + 1), State.Waiting),
            Row("past the slow point, a trickle", facts(waited = slow + 1, rate = 100), State.Slow),
            Row("past the slow point, nothing measured but bytes came lately", facts(waited = 5_000, quiet = 1_000, rate = 0), State.Slow),
            Row("the meaningful edge is not slow", facts(waited = 5_000, rate = StreamStats.MIN_MEANINGFUL_SPEED), State.Waiting),
            Row("quiet for 9.9 s is still slow, not stalled", facts(waited = 20_000, quiet = stall - 1, rate = 0), State.Slow),
            Row("quiet for 10 s is stalled", facts(waited = 20_000, quiet = stall, rate = 0), State.Stalled),
            Row("stalled before a byte ever came", facts(waited = stall, quiet = stall, rate = 0), State.Stalled),
            Row("capped account with bytes flowing", facts(waited = 5_000, limited = true), State.Quota),
            Row("capped account with a trickle", facts(waited = 5_000, rate = 100, limited = true), State.Quota),
            Row("capped account but early", facts(waited = 500, limited = true), State.Waiting),
            Row("a cap never excuses nothing arriving", facts(waited = 30_000, quiet = stall, rate = 0, limited = true), State.Stalled),
            Row("device offline", facts(waited = 30_000, quiet = 30_000, rate = 0, offline = true), State.Offline),
            Row("device offline at the start", facts(waited = 100, offline = true), State.Offline),
            Row("whole file on this device", facts(waited = 60_000, quiet = 60_000, rate = 0, local = true), State.Waiting),
            Row("on this device beats offline and a cap", facts(waited = 60_000, quiet = 60_000, rate = 0, local = true, offline = true, limited = true), State.Waiting),
        )
        for (row in rows) assertEquals(row.name, row.expected, StartVerdict.of(row.facts))
    }

    @Test
    fun `a new byte moves a stalled wait back to slow`() {
        val before = facts(waited = 15_000, quiet = stall, rate = 0)
        val after = before.copy(quietMs = 200, bytesPerSec = 500)
        assertEquals(State.Stalled, StartVerdict.of(before))
        assertEquals(State.Slow, StartVerdict.of(after))
    }

    @Test
    fun `the thresholds can be moved by the caller`() {
        assertEquals(State.Stalled, StartVerdict.of(facts(waited = 8_000, quiet = 8_000, rate = 0), stallMs = 8_000))
        assertEquals(State.Slow, StartVerdict.of(facts(waited = 3_000, rate = 0, quiet = 100), slowAfterMs = 2_500))
    }
}
