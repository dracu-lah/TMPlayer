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
    ) = ChatsGate.decide(mode, setupPending, kidsLocked, chatsLoaded, listComplete, total, waitedOut)

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
}
