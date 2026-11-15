package com.tmplayer.ui.browse

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The one rule for how long a search outlives the app being away. */
class SearchLifetimeTest {
    private val me = "process-a"
    private val t0 = 1_000_000L

    private fun keeps(saved: String? = me, leftAt: Long? = t0, now: Long) =
        SearchLifetime.keeps(saved, leftAt, now, currentProcessId = me)

    @Test
    fun `119 seconds away keeps the search`() = assertTrue(keeps(now = t0 + 119_000))

    @Test
    fun `exactly two minutes still keeps it`() = assertTrue(keeps(now = t0 + 120_000))

    @Test
    fun `121 seconds away clears it`() = assertFalse(keeps(now = t0 + 121_000))

    @Test
    fun `a state restored into a new process clears it`() =
        assertFalse(SearchLifetime.keeps("process-dead", leftAtMs = null, nowMs = t0, currentProcessId = me))

    @Test
    fun `nothing stored clears it`() = assertFalse(keeps(saved = null, leftAt = null, now = t0))

    @Test
    fun `a rotation never left the app, so it keeps`() = assertTrue(keeps(leftAt = null, now = t0 + 10_000_000))

    @Test
    fun `a clock set back clears it`() = assertFalse(keeps(now = t0 - 5_000))
}
