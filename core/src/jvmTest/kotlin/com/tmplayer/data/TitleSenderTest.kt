package com.tmplayer.data

import com.tmplayer.data.TitleSender.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** "Wrong title?" (N1) walked step by step: what is shown, what is sent, what is kept, and what never repeats. */
class TitleSenderTest {

    private val sentTexts = mutableListOf<String>()
    private var answer: TitleUpload.Outcome = TitleUpload.Outcome.Sent("NAME-2PGE")
    private var gate: CompletableDeferred<Unit>? = null

    private fun sender(store: TitleLedgerStore = MemoryTitleLedger()) = TitleSender(store) { text ->
        sentTexts += text
        gate?.await()
        answer
    }

    private suspend fun TestScope.open(
        sender: TitleSender,
        name: String = "Night.Train.2024.mkv",
        title: String = "Night Train",
        chat: String? = "Home videos",
        afterRename: Boolean = false,
    ) {
        sender.open(this, name, title, chat, afterRename)
        advanceUntilIdle()
    }

    private fun review(sender: TitleSender) = assertIs<State.Review>(sender.state.value)

    @Test
    fun `the review shows the scrubbed pair and nothing is sent until the box is ticked and Send pressed`() = runTest {
        val s = sender()
        open(s, name = "Night Train +91 98765 43210 @someone.mkv")
        val shown = review(s)
        assertEquals("Night Train @user.mkv", shown.report?.fileName)
        assertEquals("Night Train", shown.report?.title)
        // Send before ticking does nothing.
        s.send(this)
        advanceUntilIdle()
        assertTrue(sentTexts.isEmpty())
        assertIs<State.Review>(s.state.value)
        s.setAgreed(true)
        s.send(this)
        advanceUntilIdle()
        assertEquals(1, sentTexts.size)
        val sent = assertIs<State.Sent>(s.state.value)
        assertEquals("NAME-2PGE", sent.code)
    }

    @Test
    fun `the box and the chat tick are off by default`() = runTest {
        val s = sender()
        open(s)
        val shown = review(s)
        assertFalse(shown.agreed)
        assertFalse(shown.includeChat)
        assertFalse(shown.canSend)
        assertNull(shown.report?.chatName)
        assertEquals("Home videos", shown.chatOffer)
    }

    @Test
    fun `the chat name leaves only when ticked, and what is sent is what the review showed`() = runTest {
        val s = sender()
        open(s)
        s.setAgreed(true)
        s.send(this)
        advanceUntilIdle()
        assertFalse(sentTexts.single().contains("Home videos"))
        sentTexts.clear()
        val ticked = sender()
        open(ticked)
        ticked.setAgreed(true)
        ticked.setIncludeChat(true)
        val shownText = assertNotNull(review(ticked).report).toJson()
        ticked.send(this)
        advanceUntilIdle()
        assertEquals(shownText, sentTexts.single())
        assertTrue(sentTexts.single().contains("Home videos"))
    }

    @Test
    fun `only the pair is in the text, nothing else`() = runTest {
        val s = sender()
        open(s)
        s.setAgreed(true)
        s.setIncludeChat(true)
        s.send(this)
        advanceUntilIdle()
        val keys = com.tmplayer.json.JSONObject(sentTexts.single()).keySet()
        assertEquals(setOf("v", "name", "title", "chat", "hash"), keys)
    }

    @Test
    fun `a pair with nothing left after the scrub opens nothing`() = runTest {
        val s = sender()
        open(s, name = "+91 98765 43210")
        assertIs<State.Idle>(s.state.value)
        open(s, title = "   ")
        assertIs<State.Idle>(s.state.value)
        open(s, title = "a@b.example")
        assertIs<State.Idle>(s.state.value)
    }

    @Test
    fun `a send that went is never sent twice`() = runTest {
        val store = MemoryTitleLedger()
        val s = sender(store)
        open(s)
        s.setAgreed(true)
        s.send(this)
        advanceUntilIdle()
        s.close()
        // The same pair again (other case and spacing), by the menu: told, not asked again.
        open(s, name = " NIGHT.TRAIN.2024.MKV ", title = "night   train")
        assertIs<State.AlreadySent>(s.state.value)
        assertEquals(1, sentTexts.size)
        s.close()
        // And after a rename: no prompt at all.
        open(s, afterRename = true)
        assertIs<State.AlreadySent>(s.state.value)
        assertEquals(1, sentTexts.size)
    }

    @Test
    fun `a double press on Send sends once`() = runTest {
        gate = CompletableDeferred()
        val s = sender()
        open(s)
        s.setAgreed(true)
        s.send(this)
        s.send(this)
        runCurrent()
        assertIs<State.Sending>(s.state.value)
        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sentTexts.size)
    }

    @Test
    fun `no connection keeps the pair for later, and the limit and a failing inbox do too`() = runTest {
        for (outcome in listOf(TitleUpload.Outcome.NoConnection, TitleUpload.Outcome.SlowDown(30), TitleUpload.Outcome.Failed(503))) {
            val store = MemoryTitleLedger()
            answer = outcome
            val s = sender(store)
            open(s)
            s.setAgreed(true)
            s.send(this)
            advanceUntilIdle()
            val queued = assertIs<State.Queued>(s.state.value, outcome.toString())
            assertEquals(if (outcome is TitleUpload.Outcome.SlowDown) 30 else 0, queued.waitSeconds)
            assertEquals(1, store.ledger.queue.size)
        }
    }

    @Test
    fun `a refusal for good is not queued`() = runTest {
        val store = MemoryTitleLedger()
        answer = TitleUpload.Outcome.Failed(400)
        val s = sender(store)
        open(s)
        s.setAgreed(true)
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Refused>(s.state.value)
        assertTrue(store.ledger.queue.isEmpty())
        assertTrue(store.ledger.sent.isEmpty())
    }

    @Test
    fun `an upload that throws is kept for later, not a crash`() = runTest {
        val store = MemoryTitleLedger()
        val s = TitleSender(store) { error("socket exploded") }
        open(s)
        s.setAgreed(true)
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
        assertEquals(1, store.ledger.queue.size)
    }

    @Test
    fun `closing while it goes cancels the send and keeps nothing`() = runTest {
        gate = CompletableDeferred()
        val store = MemoryTitleLedger()
        val s = sender(store)
        open(s)
        s.setAgreed(true)
        s.send(this)
        runCurrent()
        s.close()
        advanceUntilIdle()
        assertIs<State.Idle>(s.state.value)
        assertTrue(store.ledger.queue.isEmpty())
        assertTrue(store.ledger.sent.isEmpty())
    }

    @Test
    fun `the queue holds at most twenty and flush sends them oldest first`() = runTest {
        val store = MemoryTitleLedger()
        answer = TitleUpload.Outcome.NoConnection
        val s = sender(store)
        for (n in 1..23) {
            open(s, name = "Show.$n.mkv", title = "Show $n")
            s.setAgreed(true)
            s.send(this)
            advanceUntilIdle()
            s.close()
        }
        assertEquals(TitleLedger.MAX_QUEUE, store.ledger.queue.size)
        sentTexts.clear()
        answer = TitleUpload.Outcome.Sent("NAME-2222")
        assertEquals(20, s.flush())
        assertEquals(20, sentTexts.size)
        assertTrue(sentTexts.first().contains("Show.4.mkv"))
        assertTrue(store.ledger.queue.isEmpty())
        assertEquals(20, store.ledger.sent.size)
    }

    @Test
    fun `flush stops at the first answer that says to wait and keeps the rest`() = runTest {
        val store = MemoryTitleLedger()
        answer = TitleUpload.Outcome.NoConnection
        val s = sender(store)
        for (n in 1..3) {
            open(s, name = "Show.$n.mkv", title = "Show $n")
            s.setAgreed(true)
            s.send(this)
            advanceUntilIdle()
            s.close()
        }
        sentTexts.clear()
        assertEquals(0, s.flush())
        assertEquals(1, sentTexts.size)
        assertEquals(3, store.ledger.queue.size)
    }

    @Test
    fun `flush drops a pair the inbox refuses for good and goes on`() = runTest {
        val store = MemoryTitleLedger()
        answer = TitleUpload.Outcome.NoConnection
        val s = sender(store)
        for (n in 1..2) {
            open(s, name = "Show.$n.mkv", title = "Show $n")
            s.setAgreed(true)
            s.send(this)
            advanceUntilIdle()
            s.close()
        }
        val results = ArrayDeque(listOf<TitleUpload.Outcome>(TitleUpload.Outcome.Failed(400), TitleUpload.Outcome.Sent("NAME-3333")))
        val flushing = TitleSender(store) { results.removeFirst() }
        assertEquals(1, flushing.flush())
        assertTrue(store.ledger.queue.isEmpty())
        assertEquals(1, store.ledger.sent.size)
    }

    @Test
    fun `two flushes at once send each pair once`() = runTest {
        val store = MemoryTitleLedger(TitleLedger().withQueued(assertNotNull(TitleReport.of("A.mkv", "A"))))
        gate = CompletableDeferred()
        val s = sender(store)
        val first = launchFlush(s)
        runCurrent()
        assertEquals(0, s.flush())
        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, first.await())
        assertEquals(1, sentTexts.size)
    }

    private fun TestScope.launchFlush(s: TitleSender) = async { s.flush() }

    @Test
    fun `a rename asks once per pair, so after No thanks it does not ask again`() = runTest {
        val store = MemoryTitleLedger()
        val s = sender(store)
        open(s, afterRename = true)
        assertTrue(review(s).afterRename)
        s.decline(this)
        advanceUntilIdle()
        assertIs<State.Idle>(s.state.value)
        // The same rename again: no prompt.
        open(s, afterRename = true)
        assertIs<State.Idle>(s.state.value)
        // The menu still opens it, for a viewer who changed their mind.
        open(s)
        assertIs<State.Review>(s.state.value)
        // Another title is another question.
        s.close()
        open(s, title = "Night Train 2", afterRename = true)
        assertIs<State.Review>(s.state.value)
    }

    @Test
    fun `closing the prompt without an answer does not ask again either`() = runTest {
        val s = sender()
        open(s, afterRename = true)
        s.close()
        open(s, afterRename = true)
        assertIs<State.Idle>(s.state.value)
    }

    @Test
    fun `a pair already waiting says so and is not queued twice`() = runTest {
        val store = MemoryTitleLedger(TitleLedger().withQueued(assertNotNull(TitleReport.of("Night.Train.2024.mkv", "Night Train"))))
        val s = sender(store)
        open(s)
        assertIs<State.Queued>(s.state.value)
        assertEquals(1, store.ledger.queue.size)
    }

    @Test
    fun `one review at a time`() = runTest {
        val s = sender()
        open(s)
        open(s, name = "Other.mkv", title = "Other")
        assertEquals("Night.Train.2024.mkv", review(s).fileName)
    }

    @Test
    fun `a ledger that cannot be read opens nothing and does not crash`() = runTest {
        val broken = object : TitleLedgerStore {
            override suspend fun read(): TitleLedger = error("disk gone")
            override suspend fun update(change: (TitleLedger) -> TitleLedger) = error("disk gone")
        }
        val s = sender(broken)
        open(s)
        assertIs<State.Idle>(s.state.value)
    }

}
