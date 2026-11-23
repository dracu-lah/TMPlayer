package com.tmplayer.ui.components

import com.tmplayer.data.HintIds
import com.tmplayer.data.HintRegistry
import com.tmplayer.data.HintState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The rules a one-time hint follows (WP-68): one per screen visit, counted, off after two tour skips. */
class OneTimeHintTest {

    @Test
    fun `two hints asked on one screen show only the first`() {
        val slot = HintSlot()
        assertTrue(slot.claim("tile_options"))
        assertFalse(slot.claim("page_keys"))
        // The owner may keep asking while it is on screen.
        assertTrue(slot.claim("tile_options"))
    }

    @Test
    fun `a new screen visit has a new slot`() {
        assertTrue(HintSlot().claim("a"))
        assertTrue(HintSlot().claim("b"))
    }

    @Test
    fun `a counted hint stops after its limit`() {
        var state = HintState()
        repeat(5) {
            assertTrue(hintAllowed(state, "tile_options", maxShows = 5))
            state = HintRegistry.bump(state, "tile_options")
        }
        assertFalse(hintAllowed(state, "tile_options", maxShows = 5))
    }

    @Test
    fun `a hint said once is not asked again`() {
        assertTrue(hintAllowed(HintState(), "a"))
        assertFalse(hintAllowed(HintRegistry.raise(HintState(), "a"), "a"))
    }

    @Test
    fun `nothing shows before the registry has loaded`() {
        assertFalse(hintAllowed(null, "a"))
    }

    @Test
    fun `no hint during playback except on the first run`() {
        assertTrue(hintAllowed(HintState(), "player", inPlayback = true))
        val later = HintRegistry.raise(HintState(), "tv_browse")
        assertFalse(hintAllowed(later, "scrub", inPlayback = true))
        assertTrue(hintAllowed(later, "scrub", inPlayback = false))
    }

    @Test
    fun `all hints are off once the tour was skipped twice`() {
        var state = HintRegistry.bump(HintState(), HintIds.TOUR_SKIPPED)
        assertTrue(hintAllowed(state, "a"))
        state = HintRegistry.bump(state, HintIds.TOUR_SKIPPED)
        assertEquals(2, state.count(HintIds.TOUR_SKIPPED))
        assertFalse(hintAllowed(state, "a"))
        assertFalse(hintAllowed(state, "b"))
    }
}
