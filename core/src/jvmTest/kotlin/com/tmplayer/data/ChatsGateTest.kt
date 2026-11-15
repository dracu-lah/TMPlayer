package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mandatory chat choice: who is asked, when it waits, and when it lets the app through. */
class ChatsGateTest {

    private fun decide(
        mode: AddedMode = AddedMode.Unanswered,
        setupPending: Boolean = false,
        kidsLocked: Boolean = false,
        chatsLoaded: Boolean = true,
        listComplete: Boolean = true,
        total: Int = 40,
        waitedOut: Boolean = false,
        waived: Boolean = false,
    ) = ChatsGate.decide(mode, setupPending, kidsLocked, chatsLoaded, listComplete, total, waitedOut, waived)

    @Test
    fun `an unanswered account with enough chats is asked`() {
        assertEquals(ChatsGate.Move.Show, decide())
        assertEquals(ChatsGate.Move.Show, decide(total = ChatsGate.MIN_CHATS))
        assertEquals(ChatsGate.Move.Show, decide(total = 100_000))
    }

    @Test
    fun `any answer is never asked again, every chat kept before included`() {
        assertEquals(ChatsGate.Move.None, decide(mode = AddedMode.All))
        assertEquals(ChatsGate.Move.None, decide(mode = AddedMode.Picked))
        // An empty pick is an answer too: Home shows its none chosen card, not the picker.
        assertEquals(ChatsGate.Move.None, decide(mode = AddedMode.Picked, total = 0))
        assertEquals(ChatsGate.Move.None, decide(mode = AddedMode.All, chatsLoaded = false))
    }

    @Test
    fun `the setup after sign in runs first, so the two never stack`() {
        assertEquals(ChatsGate.Move.None, decide(setupPending = true))
        assertEquals(ChatsGate.Move.None, decide(setupPending = true, chatsLoaded = false))
    }

    @Test
    fun `a child's profile is not asked until the kids lock is off`() {
        assertEquals(ChatsGate.Move.None, decide(kidsLocked = true))
        assertEquals(ChatsGate.Move.None, decide(kidsLocked = true, chatsLoaded = false))
        assertEquals(ChatsGate.Move.Show, decide(kidsLocked = false))
    }

    @Test
    fun `it waits for the chat list, but only until the wait runs out`() {
        assertEquals(ChatsGate.Move.Wait, decide(chatsLoaded = false, total = 0))
        assertEquals(ChatsGate.Move.None, decide(chatsLoaded = false, total = 0, waitedOut = true))
        // Once the list arrives after the wait, it is asked then: nothing has to retry.
        assertEquals(ChatsGate.Move.Show, decide(chatsLoaded = true, waitedOut = true))
    }

    @Test
    fun `going on without choosing lets the app through, waiting or not`() {
        assertEquals(ChatsGate.Move.None, decide(waived = true))
        assertEquals(ChatsGate.Move.None, decide(waived = true, chatsLoaded = false))
        // The waiver is in memory only and a sign out clears it.
        ChatsGate.waive()
        assertTrue(ChatsGate.waived.value)
        ChatsGate.clearWaiver()
        assertFalse(ChatsGate.waived.value)
    }

    @Test
    fun `too few chats are not asked about and nothing is written for them`() {
        assertEquals(ChatsGate.Move.None, decide(total = 0))
        assertEquals(ChatsGate.Move.None, decide(total = ChatsGate.MIN_CHATS - 1))
        // A short list still arriving is not waited on either: offline, Home stays usable.
        assertEquals(ChatsGate.Move.None, decide(total = ChatsGate.MIN_CHATS - 1, listComplete = false))
        assertEquals(ChatsGate.Move.None, decide(total = 2, listComplete = false, waitedOut = true))
    }

    @Test
    fun `enough chats already in is enough to ask, whole list or not`() {
        assertEquals(ChatsGate.Move.Show, decide(listComplete = false))
    }

    @Test
    fun `only waiting and showing take the app's place`() {
        assertFalse(ChatsGate.up(ChatsGate.Move.None))
        assertTrue(ChatsGate.up(ChatsGate.Move.Wait))
        assertTrue(ChatsGate.up(ChatsGate.Move.Show))
    }

    @Test
    fun `a gate that was answered gives the next sign in a new picker run`() {
        var run = ChatsGate.Run(-1, shown = false)
        run = ChatsGate.advance(run, ChatsGate.Move.None)
        assertEquals("nothing shown yet, nothing moves", ChatsGate.Run(-1, false), run)
        run = ChatsGate.advance(run, ChatsGate.Move.Wait)
        run = ChatsGate.advance(run, ChatsGate.Move.Show)
        assertEquals("shown keeps the run, so a rotation keeps the ticks", ChatsGate.Run(-1, true), run)
        run = ChatsGate.advance(run, ChatsGate.Move.Show)
        assertEquals(ChatsGate.Run(-1, true), run)
        run = ChatsGate.advance(run, ChatsGate.Move.None)
        assertEquals("answered: the next gate is another model", ChatsGate.Run(-2, false), run)
        run = ChatsGate.advance(run, ChatsGate.Move.None)
        assertEquals(ChatsGate.Run(-2, false), run)
        run = ChatsGate.advance(ChatsGate.advance(run, ChatsGate.Move.Show), ChatsGate.Move.None)
        assertEquals("a second sign in and answer moves on again", ChatsGate.Run(-3, false), run)
    }

    @Test
    fun `a short wait while the gate is up keeps its run`() {
        val shown = ChatsGate.Run(-1, shown = true)
        assertEquals(shown, ChatsGate.advance(shown, ChatsGate.Move.Wait))
    }
}
