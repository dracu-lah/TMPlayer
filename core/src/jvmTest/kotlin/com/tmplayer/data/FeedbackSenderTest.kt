package com.tmplayer.data

import com.tmplayer.data.FeedbackSender.State
import com.tmplayer.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** "Report a problem" and "Request a feature" (HX64) walked step by step: what is shown, what is sent, what is kept. */
class FeedbackSenderTest {

    private val facts = Feedback.Facts("1.40.0", Feedback.Platform.Phone, "POCO X5, Android 14", "en")
    private val sent = mutableListOf<String>()
    private val logsSent = mutableListOf<String>()
    private var answer: TitleUpload.Outcome = TitleUpload.Outcome.Sent("FB-7K3Q-M4XP")
    private var logsAnswer: LogUpload.Outcome = LogUpload.Outcome.Sent("TMP-7K3Q")
    private var gate: CompletableDeferred<Unit>? = null

    private fun sender(store: FeedbackDraftStore = MemoryFeedbackDraft(), logs: String? = "TMPlayer logs\nline") = FeedbackSender(
        store = store,
        upload = { text ->
            sent += text
            gate?.await()
            answer
        },
        uploadLogs = { text ->
            logsSent += text
            logsAnswer
        },
    ).also { it.buildLogs = { logs } }

    private fun TestScope.write(sender: FeedbackSender, message: String = "The video stops after ten seconds.", kind: FeedbackReport.Kind = FeedbackReport.Kind.Problem) {
        sender.open(this, kind, facts)
        advanceUntilIdle()
        sender.edit { it.copy(message = message) }
    }

    @Test
    fun `nothing is sent until the review was shown and Send pressed`() = runTest {
        val s = sender()
        write(s)
        assertIs<State.Editing>(s.state.value)
        s.review(this)
        advanceUntilIdle()
        val review = assertIs<State.Review>(s.state.value)
        assertEquals("The video stops after ten seconds.", review.report.message)
        assertTrue(sent.isEmpty())
        s.send(this)
        advanceUntilIdle()
        assertEquals("FB-7K3Q-M4XP", assertIs<State.Sent>(s.state.value).code)
        // What went is exactly what the review showed.
        assertEquals(listOf(review.report.toJson()), sent)
    }

    @Test
    fun `an empty or too long message stays on the form and says why`() = runTest {
        val s = sender()
        write(s, message = "   ")
        s.review(this)
        assertEquals(FeedbackReport.Problem.Empty, assertIs<State.Editing>(s.state.value).problem)
        s.edit { it.copy(message = "x".repeat(FeedbackReport.MAX_MESSAGE + 1)) }
        s.review(this)
        assertEquals(FeedbackReport.Problem.TooLong, assertIs<State.Editing>(s.state.value).problem)
        // Fixing it clears the problem as the viewer types.
        s.edit { it.copy(message = "short now") }
        assertNull(assertIs<State.Editing>(s.state.value).problem)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a double press on Send sends once`() = runTest {
        val s = sender()
        gate = CompletableDeferred()
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        s.send(this)
        advanceUntilIdle()
        s.send(this)
        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertIs<State.Sent>(s.state.value)
    }

    @Test
    fun `offline keeps the report, Try again sends it under the same id`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        answer = TitleUpload.Outcome.NoConnection
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        val queued = assertIs<State.Queued>(s.state.value)
        assertNotNull(store.draft?.pending)
        answer = TitleUpload.Outcome.Sent("FB-7K3Q-M4XP")
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Sent>(s.state.value)
        assertEquals(2, sent.size)
        assertEquals(JSONObject(sent[0]).getString("id"), JSONObject(sent[1]).getString("id"))
        assertEquals(queued.draft.id, JSONObject(sent[1]).getString("id"))
        // Sent: nothing is left on the device.
        assertNull(store.draft)
    }

    @Test
    fun `the inbox's limit and its failures queue, a refusal keeps the words to change`() = runTest {
        val s = sender()
        answer = TitleUpload.Outcome.SlowDown(60)
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertEquals(60, assertIs<State.Queued>(s.state.value).waitSeconds)
        answer = TitleUpload.Outcome.Failed(503)
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
        answer = TitleUpload.Outcome.Failed(400)
        s.send(this)
        advanceUntilIdle()
        val refused = assertIs<State.Refused>(s.state.value)
        assertEquals(400, refused.status)
        s.back()
        assertEquals("The video stops after ten seconds.", assertIs<State.Editing>(s.state.value).draft.message)
    }

    @Test
    fun `a draft closed half way is there when the form opens again, and the app killed while sending sends at the next start`() = runTest {
        val store = MemoryFeedbackDraft()
        val first = sender(store)
        write(first, message = "Half a thought")
        first.close(this)
        advanceUntilIdle()
        assertEquals("Half a thought", store.draft?.message)
        val second = sender(store)
        second.open(this, FeedbackReport.Kind.Problem, facts)
        advanceUntilIdle()
        assertEquals("Half a thought", assertIs<State.Editing>(second.state.value).draft.message)

        // Sending, then the process dies: the draft holds the confirmed report.
        val held = CompletableDeferred<Unit>()
        gate = held
        second.review(this)
        advanceUntilIdle()
        second.send(this)
        advanceUntilIdle()
        val pending = assertNotNull(store.draft?.pending)
        val third = sender(store)
        gate = null
        third.flush()
        assertEquals(pending, sent.last())
        assertNull(store.draft)
        held.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `editing after a try is a new draft with a new id, so changed words are never taken for the old`() = runTest {
        val s = sender()
        answer = TitleUpload.Outcome.NoConnection
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        val old = assertIs<State.Queued>(s.state.value).draft.id
        s.back()
        s.edit { it.copy(message = "Something else") }
        val editing = assertIs<State.Editing>(s.state.value)
        assertNotEquals(old, editing.draft.id)
        assertNull(editing.draft.pending)
    }

    @Test
    fun `logs go only when ticked, first, and their code goes with the words`() = runTest {
        val s = sender()
        write(s)
        s.review(this)
        advanceUntilIdle()
        assertNull(assertIs<State.Review>(s.state.value).logs)
        s.back()
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        val review = assertIs<State.Review>(s.state.value)
        assertEquals("TMPlayer logs\nline".encodeToByteArray().size, review.logs?.bytes)
        assertTrue(logsSent.isEmpty())
        s.send(this)
        advanceUntilIdle()
        assertEquals(listOf("TMPlayer logs\nline"), logsSent)
        assertEquals("TMP-7K3Q", JSONObject(sent.single()).getString("logs"))
        assertEquals("TMP-7K3Q", assertIs<State.Sent>(s.state.value).logsCode)
    }

    @Test
    fun `logs the inbox will not take leave the words to go alone, and the result says so`() = runTest {
        val s = sender()
        logsAnswer = LogUpload.Outcome.Failed(413)
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        val done = assertIs<State.Sent>(s.state.value)
        assertTrue(done.logsMissed)
        assertFalse(JSONObject(sent.single()).has("logs"))
    }

    @Test
    fun `logs that cannot be built are said in the review, and nothing is offered without a builder`() = runTest {
        val s = sender(logs = null)
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        assertNull(assertIs<State.Review>(s.state.value).logs)
        val none = FeedbackSender(MemoryFeedbackDraft(), upload = { answer })
        assertFalse(none.logsAvailable)
        none.open(this, FeedbackReport.Kind.Feature, facts)
        advanceUntilIdle()
        none.edit { it.copy(includeLogs = true) }
        assertFalse(assertIs<State.Editing>(none.state.value).draft.includeLogs)
    }

    @Test
    fun `offline with logs, the logs are not sent twice on Try again`() = runTest {
        val s = sender()
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        answer = TitleUpload.Outcome.NoConnection
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
        answer = TitleUpload.Outcome.Sent("FB-7K3Q-M4XP")
        s.send(this)
        advanceUntilIdle()
        assertEquals(1, logsSent.size)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a waiting report that still needs its logs is not flushed without the viewer`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        logsAnswer = LogUpload.Outcome.NoConnection
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
        s.close(this)
        advanceUntilIdle()
        sender(store).flush()
        assertTrue(sent.isEmpty())
        assertNotNull(store.draft)
    }

    @Test
    fun `the form opens on the kind asked for, unless words are typed already`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        s.open(this, FeedbackReport.Kind.Feature, facts)
        advanceUntilIdle()
        assertEquals(FeedbackReport.Kind.Feature, assertIs<State.Editing>(s.state.value).draft.kind)
        s.edit { it.copy(message = "A wish") }
        s.close(this)
        advanceUntilIdle()
        s.open(this, FeedbackReport.Kind.Problem, facts)
        advanceUntilIdle()
        assertEquals(FeedbackReport.Kind.Feature, assertIs<State.Editing>(s.state.value).draft.kind)
    }

    @Test
    fun `cancel while sending keeps it queued, discard leaves nothing`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        gate = CompletableDeferred()
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        s.close(this)
        advanceUntilIdle()
        assertIs<State.Idle>(s.state.value)
        assertNotNull(store.draft?.pending)
        // It opens again on Kept for later, the report it was sending still waiting.
        s.open(this, FeedbackReport.Kind.Problem, facts)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
        s.discard(this)
        advanceUntilIdle()
        assertNull(store.draft)
    }

    @Test
    fun `unticked logs take their code with them, so the report says no logs and carries none`() = runTest {
        val s = sender()
        answer = TitleUpload.Outcome.NoConnection
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertEquals("TMP-7K3Q", assertIs<State.Queued>(s.state.value).draft.logsCode)
        s.back()
        s.edit { it.copy(includeLogs = false) }
        assertNull(assertIs<State.Editing>(s.state.value).draft.logsCode)
        s.review(this)
        advanceUntilIdle()
        assertNull(assertIs<State.Review>(s.state.value).report.logs)
        answer = TitleUpload.Outcome.Sent("FB-7K3Q-M4XP")
        s.send(this)
        advanceUntilIdle()
        assertFalse(JSONObject(sent.last()).has("logs"))
        // And the report itself never takes a code while unticked.
        assertNull(FeedbackDraft(includeLogs = false, logsCode = "TMP-7K3Q").logsToSend)
    }

    @Test
    fun `typing is kept on the device after a short rest, with nothing left running`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        s.open(this, FeedbackReport.Kind.Problem, facts)
        advanceUntilIdle()
        s.edit { it.copy(message = "Half") }
        s.edit { it.copy(message = "Half a thought") }
        testScheduler.advanceTimeBy(FeedbackSender.SAVE_DELAY_MS / 2)
        testScheduler.runCurrent()
        assertNull(store.draft)
        testScheduler.advanceTimeBy(FeedbackSender.SAVE_DELAY_MS)
        testScheduler.runCurrent()
        // Killed now, the words are there for the next start.
        assertEquals("Half a thought", store.draft?.message)
        assertEquals("Half a thought", assertIs<State.Editing>(sender(store).also { it.open(this, FeedbackReport.Kind.Problem, facts); advanceUntilIdle() }.state.value).draft.message)
    }

    @Test
    fun `logs sent before a cancel keep their code, and Try again does not send them twice`() = runTest {
        val store = MemoryFeedbackDraft()
        val s = sender(store)
        val held = CompletableDeferred<Unit>()
        gate = held
        write(s)
        s.edit { it.copy(includeLogs = true) }
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        s.close(this)
        advanceUntilIdle()
        assertEquals("TMP-7K3Q", store.draft?.logsCode)
        gate = null
        s.open(this, FeedbackReport.Kind.Problem, facts)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertEquals(1, logsSent.size)
        assertIs<State.Sent>(s.state.value)
        held.complete(Unit)
    }

    @Test
    fun `no status at all (a redirect, a captive portal) is kept for later, not refused`() = runTest {
        val s = sender()
        answer = TitleUpload.Outcome.Failed(0)
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Queued>(s.state.value)
    }

    @Test
    fun `a store that throws never takes the form down`() = runTest {
        val broken = object : FeedbackDraftStore {
            override suspend fun read(): FeedbackDraft? = error("disk gone")
            override suspend fun write(draft: FeedbackDraft?) = error("disk full")
        }
        val s = sender(broken)
        write(s)
        s.review(this)
        advanceUntilIdle()
        s.send(this)
        advanceUntilIdle()
        assertIs<State.Sent>(s.state.value)
    }
}
