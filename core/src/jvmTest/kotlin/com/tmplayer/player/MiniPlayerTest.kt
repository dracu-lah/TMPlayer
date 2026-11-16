package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerTest {

    private val full = MiniPlayerState().opened()

    @Test
    fun `back docks a playing video`() {
        val docked = full.leave(MiniContext())
        assertEquals(MiniPhase.Docked, docked.phase)
        assertTrue(docked.barVisible)
        assertFalse(docked.expanded)
        assertTrue(docked.holdsPlayer)
    }

    @Test
    fun `back closes when the verdict is not dock`() {
        val table = listOf(
            MiniContext(platformAllowed = false) to MiniVerdict.Disabled,
            MiniContext(ended = true) to MiniVerdict.Ended,
            MiniContext(failed = true) to MiniVerdict.Failed,
            MiniContext(asking = true) to MiniVerdict.Asking,
            MiniContext(casting = true) to MiniVerdict.Casting,
            MiniContext(locked = true) to MiniVerdict.Locked,
            MiniContext(inPictureInPicture = true) to MiniVerdict.PictureInPicture,
            MiniContext(opened = false) to MiniVerdict.NotOpened,
            MiniContext(pausedForMs = MiniPlayerState.PAUSED_CLOSES_AFTER_MS) to MiniVerdict.Paused,
            MiniContext(pausedForMs = 10 * 60_000L) to MiniVerdict.Paused,
        )
        for ((context, why) in table) {
            assertEquals(why, MiniPlayerState.verdict(context))
            val after = full.leave(context)
            assertEquals("$why", MiniPhase.Closed, after.phase)
            assertFalse("$why", after.holdsPlayer)
        }
    }

    @Test
    fun `a video paused for a few seconds closes on back, a playing or just paused one docks`() {
        val justPaused = MiniContext(pausedForMs = MiniPlayerState.PAUSED_CLOSES_AFTER_MS - 1)
        assertEquals(MiniVerdict.Dock, MiniPlayerState.verdict(justPaused))
        assertEquals(MiniVerdict.Dock, MiniPlayerState.verdict(MiniContext(pausedForMs = 0)))
        val closed = full.leave(MiniContext(pausedForMs = MiniPlayerState.PAUSED_CLOSES_AFTER_MS))
        assertEquals(MiniPhase.Closed, closed.phase)
        assertFalse(closed.holdsPlayer)
    }

    @Test
    fun `a stronger reason is named before the pause`() {
        val long = MiniPlayerState.PAUSED_CLOSES_AFTER_MS
        assertEquals(MiniVerdict.Casting, MiniPlayerState.verdict(MiniContext(casting = true, pausedForMs = long)))
        assertEquals(MiniVerdict.Locked, MiniPlayerState.verdict(MiniContext(locked = true, pausedForMs = long)))
        assertEquals(MiniVerdict.Disabled, MiniPlayerState.verdict(MiniContext(platformAllowed = false, pausedForMs = long)))
        assertEquals(MiniVerdict.PictureInPicture, MiniPlayerState.verdict(MiniContext(inPictureInPicture = true, pausedForMs = long)))
    }

    @Test
    fun `a big screen never docks`() {
        assertEquals(MiniVerdict.Disabled, MiniPlayerState.verdict(MiniContext(platformAllowed = false)))
    }

    @Test
    fun `the lock wins over a playing video`() {
        // Locked and playing: Back is the lock's to refuse, so it never docks past it.
        assertEquals(MiniVerdict.Locked, MiniPlayerState.verdict(MiniContext(locked = true)))
    }

    @Test
    fun `a tap expands, the same engine`() {
        val back = full.leave(MiniContext()).expand()
        assertEquals(MiniPhase.Full, back.phase)
        assertTrue(back.expanded)
        assertFalse(back.barVisible)
    }

    @Test
    fun `dismiss frees the player from every phase`() {
        assertFalse(full.leave(MiniContext()).dismiss().holdsPlayer)
        assertFalse(full.dismiss().holdsPlayer)
        assertFalse(MiniPlayerState().dismiss().holdsPlayer)
    }

    @Test
    fun `a new video while docked replaces it in place and is full`() {
        val next = full.leave(MiniContext()).opened()
        assertEquals(MiniPhase.Full, next.phase)
        assertFalse(next.barVisible)
    }

    @Test
    fun `a cast hides the bar and keeps the player, then the bar returns`() {
        val casting = full.leave(MiniContext()).casting(true)
        assertFalse(casting.barVisible)
        assertTrue(casting.holdsPlayer)
        assertTrue(casting.casting(false).barVisible)
        assertEquals(casting, casting.casting(true))
    }

    @Test
    fun `a cast that outlives a video does not hide the next bar`() {
        val casting = MiniPlayerState().casting(true)
        // The cast is a fact of the window, not of one video: dismissing keeps it.
        assertTrue(casting.opened().dismiss().casting)
        assertFalse(casting.opened().leave(MiniContext()).barVisible)
        assertTrue(casting.casting(false).opened().leave(MiniContext()).barVisible)
    }

    @Test
    fun `a video that stops on its own while docked leaves no bar`() {
        assertEquals(MiniPhase.Closed, full.leave(MiniContext()).stoppedOnItsOwn().phase)
        // With the full player up its own end sheet is what shows.
        assertEquals(MiniPhase.Full, full.stoppedOnItsOwn().phase)
    }

    @Test
    fun `expand, leave and stopped do nothing when closed`() {
        val closed = MiniPlayerState()
        assertEquals(closed, closed.expand())
        assertEquals(closed, closed.leave(MiniContext()))
        assertEquals(closed, closed.stoppedOnItsOwn())
        assertFalse(closed.barVisible)
    }

    @Test
    fun `leave while already docked does not change anything`() {
        val docked = full.leave(MiniContext())
        assertEquals(docked, docked.leave(MiniContext(failed = true)))
    }

    @Test
    fun `the progress line is safe for unknown, negative and overshooting values`() {
        assertEquals(0f, MiniPlayerState.progress(5_000, 0), 0f)
        assertEquals(0f, MiniPlayerState.progress(-5, 100), 0f)
        assertEquals(0f, MiniPlayerState.progress(0, 100), 0f)
        assertEquals(0.5f, MiniPlayerState.progress(50, 100), 0.0001f)
        assertEquals(1f, MiniPlayerState.progress(500, 100), 0f)
        assertEquals(0f, MiniPlayerState.progress(Long.MAX_VALUE, -1), 0f)
        assertEquals(1f, MiniPlayerState.progress(Long.MAX_VALUE, Long.MAX_VALUE), 0f)
    }
}
