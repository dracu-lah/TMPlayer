package com.tmplayer.data

import com.tmplayer.data.SetupFlow.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup after sign in as one question: what the shell shows, for every combination of inputs. */
class SetupFlowTest {

    private fun next(
        pending: Boolean = true,
        answerLoaded: Boolean = true,
        mode: AddedMode = AddedMode.Unanswered,
        replay: Boolean = false,
        kidsLocked: Boolean = false,
        waived: Boolean = false,
        chatsLoaded: Boolean = true,
        total: Int = 40,
        connected: Boolean = true,
        offlineSure: Boolean = false,
    ) = SetupFlow.next(
        SetupFlow.Inputs(pending, answerLoaded, mode, replay, kidsLocked, waived, chatsLoaded, total, connected, offlineSure),
    )

    @Test
    fun `an unarmed setup shows nothing`() {
        assertEquals(Next.Idle, next(pending = false))
        assertEquals(Next.Idle, next(pending = false, mode = AddedMode.Picked))
        assertFalse(SetupFlow.up(Next.Idle))
    }

    @Test
    fun `the question is the only screen, once the chats are in`() {
        assertEquals(Next.Choose, next())
        assertEquals(Next.Choose, next(total = 100_000))
        assertTrue(SetupFlow.up(Next.Choose))
    }

    @Test
    fun `even one chat is asked about, so Saved Messages alone gets the hint`() {
        assertEquals(Next.Choose, next(total = 1))
        assertEquals(Next.Choose, next(total = 2))
        // The chat choice after an update still waits for four.
        assertTrue(ChatsGate.MIN_CHATS > 1)
    }

    @Test
    fun `it waits for the stored answer, with a plain spinner`() {
        assertEquals(Next.Hold, next(answerLoaded = false))
        assertEquals(Next.Hold, next(answerLoaded = false, mode = AddedMode.Picked))
    }

    @Test
    fun `a sign in on a device that already had a setup asks nothing again`() {
        assertEquals(Next.Finish, next(mode = AddedMode.Picked))
        assertEquals(Next.Finish, next(mode = AddedMode.All))
        // An empty pick is an answer too.
        assertEquals(Next.Finish, next(mode = AddedMode.Picked, total = 0, chatsLoaded = false))
        // Not even a wait for the list: no flash of the spinner.
        assertEquals(Next.Finish, next(mode = AddedMode.Picked, chatsLoaded = false, connected = false))
    }

    @Test
    fun `a child's profile is not asked`() {
        assertEquals(Next.Finish, next(kidsLocked = true))
        assertEquals(Next.Finish, next(kidsLocked = true, chatsLoaded = false, total = 0))
    }

    @Test
    fun `continue without choosing finishes the setup for this visit`() {
        assertEquals(Next.Finish, next(waived = true))
        assertEquals(Next.Finish, next(waived = true, chatsLoaded = false, total = 0))
    }

    @Test
    fun `a slow chat list waits, and never ends by itself`() {
        assertEquals(Next.Wait, next(chatsLoaded = false, total = 0))
        // Connected but nothing arrives for a long time: still the spinner, with its way out.
        assertEquals(Next.Wait, next(chatsLoaded = false, total = 0, offlineSure = true))
        // The list answered with no chat at all: the same wait, the way out is Continue without choosing.
        assertEquals(Next.Wait, next(chatsLoaded = true, total = 0))
    }

    @Test
    fun `no list and no network shows the offline card, but not at the first moment`() {
        assertEquals(Next.Wait, next(chatsLoaded = false, total = 0, connected = false, offlineSure = false))
        assertEquals(Next.Offline, next(chatsLoaded = false, total = 0, connected = false, offlineSure = true))
        // The list that is already in wins over the network flag.
        assertEquals(Next.Choose, next(connected = false, offlineSure = true))
        // And the card goes by itself when the list comes.
        assertEquals(Next.Choose, next(chatsLoaded = true, total = 12, connected = true, offlineSure = true))
    }

    @Test
    fun `running it again from Settings asks even with an answer, and Back is allowed`() {
        assertEquals(Next.Choose, next(mode = AddedMode.Picked, replay = true))
        assertEquals(Next.Choose, next(mode = AddedMode.All, replay = true))
        assertEquals(Next.Wait, next(mode = AddedMode.Picked, replay = true, chatsLoaded = false, total = 0))
        assertTrue(SetupFlow.required(replay = false))
        assertFalse(SetupFlow.required(replay = true))
        // It still ends without a chat list when the way out is used.
        assertEquals(Next.Finish, next(replay = true, waived = true))
    }

    @Test
    fun `the picker's run bookkeeping sees the question as a shown gate and every wait as a wait`() {
        assertEquals(ChatsGate.Move.Show, SetupFlow.toMove(Next.Choose))
        for (wait in listOf(Next.Hold, Next.Wait, Next.Offline)) assertEquals(ChatsGate.Move.Wait, SetupFlow.toMove(wait))
        assertEquals(ChatsGate.Move.None, SetupFlow.toMove(Next.Finish))
        assertEquals(ChatsGate.Move.None, SetupFlow.toMove(Next.Idle))
    }

    @Test
    fun `every combination ends in a defined move and an armed setup is never idle`() {
        for (answer in listOf(false, true)) for (mode in AddedMode.entries) for (replay in listOf(false, true))
            for (kids in listOf(false, true)) for (waived in listOf(false, true)) for (loaded in listOf(false, true))
                for (total in listOf(0, 1, 5)) for (connected in listOf(false, true)) for (sure in listOf(false, true)) {
                    val move = next(true, answer, mode, replay, kids, waived, loaded, total, connected, sure)
                    assertTrue("armed is never Idle", move != Next.Idle)
                    // Choose is only ever offered with a chat to tick.
                    if (move == Next.Choose) assertTrue(loaded && total >= 1)
                    // Offline is only ever said with no network and no chats.
                    if (move == Next.Offline) assertTrue(!connected && !(loaded && total >= 1))
                }
    }
}
