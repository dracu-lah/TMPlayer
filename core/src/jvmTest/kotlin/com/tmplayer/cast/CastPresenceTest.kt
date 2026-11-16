package com.tmplayer.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FQ8: the Cast button exists only once a TV is found or a cast is under way; a TV seen a moment
 * ago shows it at once and is forgotten when a search finds nothing.
 */
class CastPresenceTest {

    private var now = 1_000_000L
    private val clock = { now }

    @Test
    fun `no TV found and nothing casting draws no button`() {
        val p = CastPresence(clock, CastPresence.Memory())
        p.scanStarted()
        p.seen(false)
        assertFalse(p.visible(true, false))
        p.scanEnded()
        assertFalse(p.visible(true, false))
    }

    @Test
    fun `a TV found draws it`() {
        val p = CastPresence(clock, CastPresence.Memory())
        p.scanStarted()
        p.seen(true)
        assertTrue(p.visible(true, false))
    }

    @Test
    fun `a build without casting never draws it`() {
        val p = CastPresence(clock, CastPresence.Memory())
        p.seen(true)
        assertFalse(p.visible(false, castUnderWay = true))
    }

    @Test
    fun `a cast under way always has the button, a found TV keeps it for the visit`() {
        val p = CastPresence(clock, CastPresence.Memory())
        assertTrue(p.visible(true, castUnderWay = true))
        p.scanStarted()
        p.seen(true)
        p.scanEnded()
        // The picker's own search starts its list empty: the button stays.
        p.scanStarted()
        p.seen(false)
        p.scanEnded()
        assertTrue(p.visible(true, false))
    }

    @Test
    fun `a TV seen a moment ago shows at once, and is forgotten when the search finds nothing`() {
        val memory = CastPresence.Memory()
        CastPresence(clock, memory).apply {
            scanStarted()
            seen(true)
        }
        now += 60_000
        val next = CastPresence(clock, memory)
        assertFalse("not before the search starts", next.visible(true, false))
        next.scanStarted()
        assertTrue(next.visible(true, false))
        next.scanEnded()
        assertFalse(next.visible(true, false))
        val third = CastPresence(clock, memory)
        third.scanStarted()
        assertFalse("forgotten", third.visible(true, false))
    }

    @Test
    fun `a TV seen long ago is not shown before it answers again`() {
        val memory = CastPresence.Memory()
        CastPresence(clock, memory).seen(true)
        now += CastPresence.REMEMBER_MS + 1
        val p = CastPresence(clock, memory)
        p.scanStarted()
        assertFalse(p.visible(true, false))
    }
}
