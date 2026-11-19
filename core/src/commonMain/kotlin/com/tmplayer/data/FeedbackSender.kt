package com.tmplayer.data

import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the one [FeedbackDraft] lives: the settings on a device, a variable in a test. */
interface FeedbackDraftStore {
    suspend fun read(): FeedbackDraft?
    suspend fun write(draft: FeedbackDraft?)
}

/** A [FeedbackDraftStore] in memory, for tests. */
class MemoryFeedbackDraft(var draft: FeedbackDraft? = null) : FeedbackDraftStore {
    override suspend fun read(): FeedbackDraft? = draft
    override suspend fun write(draft: FeedbackDraft?) {
        this.draft = draft
    }
}

/** The log report a viewer may add to their feedback, built on the device and shown by its size before anything goes. */
class LogsPreview(val text: String) {
    val bytes: Int = text.encodeToByteArray().size
}

/**
 * The steps behind "Report a problem" and "Request a feature" (HX64), apart from the screen so a
 * test can walk them. Nothing goes until the viewer has seen the confirm step and pressed Send.
 *
 *  - [State.Editing]: the form. What is typed is kept on this device a moment after each change
 *    (one short wait, nothing left running when idle) and again on leaving, so a closed window
 *    or a killed app loses nothing.
 *  - [State.Review]: exactly what would be sent ([State.Review.report]) and, when "Add my logs" is
 *    ticked, the log report's size. Back returns to the form; Send sends.
 *  - [State.Sending], then [State.Sent] with the code, [State.Queued] when there is no connection,
 *    the inbox's limit or the inbox failing (kept, with Try again, and tried once a minute after the
 *    next start), or [State.Refused] when the inbox will never take it (the draft stays to edit).
 *
 * Send is taken once: a second press while sending does nothing, and every try of one draft
 * carries its id, so the inbox files it once. Editing a draft after a try draws a new id, so the
 * changed words are never taken for the old ones.
 */
class FeedbackSender(
    private val store: FeedbackDraftStore,
    private val upload: suspend (String) -> TitleUpload.Outcome,
    /** Sends a log report, or null where this build cannot (then "Add my logs" is not offered). */
    private val uploadLogs: (suspend (String) -> LogUpload.Outcome)? = null,
) {

    sealed interface State {
        data object Idle : State

        /** The stored draft is being read. */
        data object Opening : State

        data class Editing(val draft: FeedbackDraft, val facts: Feedback.Facts, val problem: FeedbackReport.Problem? = null) : State

        /** The log report is being put together, before the review. */
        data class PreparingLogs(val draft: FeedbackDraft, val facts: Feedback.Facts) : State

        data class Review(val draft: FeedbackDraft, val facts: Feedback.Facts, val report: FeedbackReport, val logs: LogsPreview?) : State

        data class Sending(val draft: FeedbackDraft, val facts: Feedback.Facts) : State

        /** Filed: say [code]. [logsCode] is the log report's, when one went; [logsMissed] when it was wanted and could not go. */
        data class Sent(val code: String, val logsCode: String?, val logsMissed: Boolean = false) : State

        /** Kept on this device for later; [waitSeconds] above 0 when it was the inbox's limit. */
        data class Queued(val draft: FeedbackDraft, val facts: Feedback.Facts, val logs: LogsPreview?, val waitSeconds: Int = 0) : State

        /** The inbox refused it ([status]); the draft is kept so it can be changed. */
        data class Refused(val draft: FeedbackDraft, val facts: Feedback.Facts, val status: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Whether "Add my logs" can be offered at all. */
    val logsAvailable: Boolean get() = uploadLogs != null && buildLogs != null

    private var job: Job? = null

    /** The one short wait before typing is kept; replaced by each change, done once it has written. */
    private var saveJob: Job? = null

    /** The scope the window was opened in, for keeping what is typed. */
    private var saveScope: CoroutineScope? = null

    /**
     * Opens the form for [kind], over the stored draft when there is one (its kind is changed to
     * [kind] only while nothing is typed in it). A draft waiting to go opens on its queued step.
     */
    fun open(scope: CoroutineScope, kind: FeedbackReport.Kind, facts: Feedback.Facts) {
        if (_state.value !is State.Idle) return
        _state.value = State.Opening
        saveScope = scope
        job = scope.launch {
            val stored = runCatching { store.read() }.getOrNull()
            val draft = when {
                stored == null -> FeedbackDraft(kind = kind)
                stored.message.isBlank() && stored.pending == null -> stored.copy(kind = kind)
                else -> stored
            }.let { if (!logsAvailable) it.copy(includeLogs = false) else it }
            if (_state.value !is State.Opening) return@launch
            _state.value = if (draft.pending != null) State.Queued(draft, facts, logs = null) else State.Editing(draft, facts)
        }
    }

    /**
     * The form changed. A changed draft that was tried before is a new one, with a new id. Logs
     * unticked take their code with them, so nothing of them goes. Kept on this device after
     * [SAVE_DELAY_MS] of quiet.
     */
    fun edit(change: (FeedbackDraft) -> FeedbackDraft) {
        val now = _state.value as? State.Editing ?: return
        val next = change(now.draft)
            .let { if (!logsAvailable) it.copy(includeLogs = false) else it }
            .let { if (!it.includeLogs) it.copy(logsCode = null) else it }
        val changed = next.kind != now.draft.kind || next.message != now.draft.message ||
            next.contact != now.draft.contact || next.includeLogs != now.draft.includeLogs
        val fresh = if (changed && now.draft.pending != null) next.copy(id = FeedbackReport.newId(), pending = null) else next
        _state.value = now.copy(draft = fresh, problem = now.problem?.let { FeedbackReport.problem(fresh.message, fresh.contact) })
        if (fresh != now.draft) saveSoon()
    }

    private fun saveSoon() {
        val scope = saveScope ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            val editing = _state.value as? State.Editing ?: return@launch
            runCatching { store.write(if (editing.draft.isBlank) null else editing.draft) }
        }
    }

    private fun stopSaving() {
        saveJob?.cancel()
        saveJob = null
    }

    /** From the form to the confirm step; the form says what is wrong when it cannot go. */
    fun review(scope: CoroutineScope) {
        val now = _state.value as? State.Editing ?: return
        val problem = FeedbackReport.problem(now.draft.message, now.draft.contact)
        if (problem != null) {
            _state.value = now.copy(problem = problem)
            return
        }
        stopSaving()
        persist(scope, now.draft)
        val logs = if (uploadLogs != null) buildLogs else null
        if (!now.draft.includeLogs || now.draft.logsCode != null || logs == null) {
            toReview(now.draft, now.facts, null)
            return
        }
        _state.value = State.PreparingLogs(now.draft, now.facts)
        job = scope.launch {
            val text = try {
                logs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Logs not built", e)
                null
            }
            val still = _state.value as? State.PreparingLogs ?: return@launch
            // No log could be built: the review says so, and the report goes without one.
            toReview(still.draft, still.facts, text?.let { LogsPreview(it) })
        }
    }

    /** Puts the log report together on this platform, set by [FeedbackReports.install]; null offers no logs. */
    var buildLogs: (suspend () -> String?)? = null

    private fun toReview(draft: FeedbackDraft, facts: Feedback.Facts, logs: LogsPreview?) {
        val report = FeedbackReport.of(draft.id, draft.kind, draft.message, draft.contact, draft.logsToSend, facts)
        _state.value = if (report == null) {
            State.Editing(draft, facts, FeedbackReport.problem(draft.message, draft.contact) ?: FeedbackReport.Problem.Empty)
        } else {
            State.Review(draft, facts, report, logs)
        }
    }

    /** From the confirm step (or a queued or refused one) back to the form. */
    fun back() {
        _state.value = when (val now = _state.value) {
            is State.Review -> State.Editing(now.draft, now.facts)
            is State.Queued -> State.Editing(now.draft, now.facts)
            is State.Refused -> State.Editing(now.draft, now.facts)
            is State.PreparingLogs -> {
                job?.cancel()
                State.Editing(now.draft, now.facts)
            }
            else -> return
        }
    }

    /** Sends what the confirm step showed. Taken once: a second press while it sends does nothing. */
    fun send(scope: CoroutineScope) {
        val (draft, facts, logs) = when (val now = _state.value) {
            is State.Review -> Triple(now.draft, now.facts, now.logs)
            is State.Queued -> Triple(now.draft, now.facts, now.logs)
            else -> return
        }
        // A queued draft from an earlier run that wanted logs has none built yet: back to the review, which builds and shows them.
        if (draft.includeLogs && draft.logsCode == null && logs == null && draft.pending != null && buildLogs != null && uploadLogs != null) {
            _state.value = State.Editing(draft, facts)
            review(scope)
            return
        }
        _state.value = State.Sending(draft, facts)
        job = scope.launch { run(draft, facts, logs) }
    }

    private suspend fun run(start: FeedbackDraft, facts: Feedback.Facts, logs: LogsPreview?) {
        var draft = start
        var logsMissed = false
        val sendLogs = uploadLogs
        if (draft.includeLogs && draft.logsCode == null && logs != null && sendLogs != null) {
            when (val got = sendLogs(logs.text)) {
                is LogUpload.Outcome.Sent -> {
                    // Kept the moment it exists: a cancel or a kill from here never sends the logs twice.
                    draft = draft.copy(logsCode = got.code)
                    runCatching { store.write(draft) }
                    if (_state.value is State.Sending) _state.value = State.Sending(draft, facts)
                }
                is LogUpload.Outcome.SlowDown -> return queue(draft, facts, logs, got.retryAfterSeconds)
                LogUpload.Outcome.NoConnection -> return queue(draft, facts, logs, 0)
                // The inbox will not take this log: the words still go, and the result says the logs did not.
                is LogUpload.Outcome.Failed -> if (got.status in 500..599 || got.status == 0) return queue(draft, facts, logs, 0) else logsMissed = true
            }
        } else if (draft.includeLogs && draft.logsCode == null) {
            logsMissed = true
        }
        val report = FeedbackReport.of(draft.id, draft.kind, draft.message, draft.contact, draft.logsToSend, facts)
        if (report == null) {
            _state.value = State.Editing(draft, facts, FeedbackReport.problem(draft.message, draft.contact) ?: FeedbackReport.Problem.Empty)
            return
        }
        // Kept before it goes: the app killed while sending sends it at the next start, under the same id.
        draft = draft.copy(pending = report.toJson())
        runCatching { store.write(draft) }
        // The window's step holds it too, so a Cancel now keeps it queued rather than writing it back without.
        if (_state.value is State.Sending) _state.value = State.Sending(draft, facts)
        val outcome = upload(report.toJson())
        when {
            outcome is TitleUpload.Outcome.Sent -> {
                runCatching { store.write(null) }
                _state.value = State.Sent(outcome.code, draft.logsToSend, logsMissed)
            }
            // No status at all (a redirect not followed, a captive portal): the line, not the report.
            TitleUpload.worthRetrying(outcome) || (outcome as? TitleUpload.Outcome.Failed)?.status == 0 -> queue(draft, facts, logs, (outcome as? TitleUpload.Outcome.SlowDown)?.retryAfterSeconds ?: 0)
            else -> {
                val status = (outcome as? TitleUpload.Outcome.Failed)?.status ?: 0
                val kept = draft.copy(pending = null)
                runCatching { store.write(kept) }
                _state.value = State.Refused(kept, facts, status)
            }
        }
    }

    private suspend fun queue(draft: FeedbackDraft, facts: Feedback.Facts, logs: LogsPreview?, wait: Int) {
        val kept = if (draft.pending == null) {
            draft.copy(pending = FeedbackReport.of(draft.id, draft.kind, draft.message, draft.contact, draft.logsToSend, facts)?.toJson())
        } else {
            draft
        }
        runCatching { store.write(kept) }
        _state.value = State.Queued(kept, facts, logs, wait)
    }

    /**
     * Closes the window. What is typed stays on this device. A send in flight is cancelled: once
     * its report was put together it stays queued (it opens on Kept for later, and goes at the
     * next start), and a log report already sent keeps its code. A sent report leaves nothing.
     */
    fun close(scope: CoroutineScope) {
        val now = _state.value
        job?.cancel()
        job = null
        stopSaving()
        _state.value = State.Idle
        val keep = when (now) {
            is State.Editing -> now.draft
            is State.Review -> now.draft
            is State.PreparingLogs -> now.draft
            is State.Sending -> now.draft
            is State.Queued -> now.draft
            is State.Refused -> now.draft
            else -> return
        }
        persist(scope, keep)
    }

    /** Throws the draft away: the form starts empty next time. */
    fun discard(scope: CoroutineScope) {
        job?.cancel()
        job = null
        stopSaving()
        _state.value = State.Idle
        scope.launch { runCatching { store.write(null) } }
    }

    private fun persist(scope: CoroutineScope, draft: FeedbackDraft) {
        scope.launch { runCatching { store.write(if (draft.isBlank) null else draft) } }
    }

    /**
     * Sends a report that was confirmed and is waiting ([FeedbackDraft.pending]), once, with no
     * window: the line was down when the viewer pressed Send. One with logs still to send waits
     * for the viewer, who sees them again first. Never while the window is open.
     */
    suspend fun flush() {
        if (_state.value !is State.Idle) return
        val draft = runCatching { store.read() }.getOrNull() ?: return
        val pending = draft.pending ?: return
        if (draft.includeLogs && draft.logsCode == null) return
        val outcome = upload(pending)
        if (outcome is TitleUpload.Outcome.Sent && _state.value is State.Idle) runCatching { store.write(null) }
    }

    companion object {
        private const val TAG = "FeedbackSender"

        /** How long typing rests before it is kept on the device. */
        const val SAVE_DELAY_MS = 1_500L
    }
}

/**
 * The one [FeedbackSender] a process has. A build with no inbox address (forks, CI) has none, so
 * [available] is false and "Report a problem" keeps only its GitHub and e-mail routes.
 */
object FeedbackReports {

    @Volatile
    var sender: FeedbackSender? = null
        private set

    private const val FLUSH_DELAY_MS = 60_000L

    /**
     * Starts the process's sender over [store] and the build's [address]. [buildLogs] puts the log
     * report together on this platform (null: "Add my logs" is not offered). Safe to call again.
     */
    fun install(store: FeedbackDraftStore, address: String?, buildLogs: (suspend () -> String?)? = null) {
        val url = LogUpload.feedbackUrl(address)
        val reportUrl = address?.trim()
        sender = url?.let { target ->
            FeedbackSender(
                store = store,
                upload = { text -> LogUploader.sendFeedback(target, text) },
                uploadLogs = if (buildLogs != null && reportUrl != null) ({ text -> LogUploader.send(reportUrl, text) }) else null,
            ).also { it.buildLogs = buildLogs }
        }
    }

    val available: Boolean get() = sender != null

    /** Tries a report waiting for a connection once, a minute after start, and never on its own again. */
    fun flushSoon() {
        val current = sender ?: return
        Background.scope.launch {
            delay(FLUSH_DELAY_MS)
            runCatching { current.flush() }
        }
    }
}
