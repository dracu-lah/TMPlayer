package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Where the [TitleLedger] lives: the settings on a device, a variable in a test. A change is read and written as one step. */
interface TitleLedgerStore {
    suspend fun read(): TitleLedger
    suspend fun update(change: (TitleLedger) -> TitleLedger)
}

/** A [TitleLedgerStore] that keeps the ledger in memory, for tests. */
class MemoryTitleLedger(start: TitleLedger = TitleLedger()) : TitleLedgerStore {
    private val mutex = Mutex()
    var ledger: TitleLedger = start
        private set

    override suspend fun read(): TitleLedger = ledger

    override suspend fun update(change: (TitleLedger) -> TitleLedger) {
        mutex.lock()
        try {
            ledger = change(ledger)
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * The steps behind "Wrong title?" (N1), apart from the screen so a test can walk them: the pair is
 * built and scrubbed, shown exactly as it would leave, and only after the viewer ticked the box and
 * pressed Send does it go. One at a time, never on its own.
 *
 *  - [State.Review] the pair as it would be sent. The chat's name goes only when [setIncludeChat]
 *    is on; Send works only once [setAgreed] is on. [decline] ("No thanks") ends it for good for
 *    this pair; [close] ends it too.
 *  - [State.Sending], then [State.Sent] with the short code, or [State.Queued] when there is no
 *    connection, the inbox's limit or the inbox failing (kept for [flush], at most
 *    [TitleLedger.MAX_QUEUE]), or [State.Refused] when the inbox will never take it.
 *  - [State.AlreadySent]: the same pair went before, so nothing is sent twice.
 *
 * After a rename, [open] asks only once per pair: the ledger remembers that it asked.
 */
class TitleSender(
    private val store: TitleLedgerStore,
    private val upload: suspend (String) -> TitleUpload.Outcome,
) {

    sealed interface State {
        data object Idle : State

        /** The ledger is being read; nothing is drawn yet, so a prompt that turns out not to be due never flashes. */
        data object Preparing : State

        /**
         * What would be sent, from the raw [fileName] and [title]: [report] is that after the
         * scrub, without the chat unless [includeChat], and null when nothing is left to send.
         */
        data class Review(
            val fileName: String,
            val title: String,
            val chatName: String?,
            val includeChat: Boolean = false,
            val agreed: Boolean = false,
            val afterRename: Boolean = false,
        ) : State {
            val report: TitleReport? get() = TitleReport.of(fileName, title, chatName, includeChat)

            /** The chat's name as it would be sent if ticked, or null when there is none worth offering. */
            val chatOffer: String? get() = TitleReport.of(fileName, title, chatName, includeChat = true)?.chatName

            val canSend: Boolean get() = agreed && report != null
        }

        data class Sending(val report: TitleReport) : State
        data class Sent(val report: TitleReport, val code: String, val duplicate: Boolean) : State

        /** Kept on this device and sent when the line allows; [waitSeconds] is the inbox's own wait, 0 when none. */
        data class Queued(val report: TitleReport, val waitSeconds: Int = 0) : State
        data class AlreadySent(val report: TitleReport) : State
        data class Refused(val report: TitleReport) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = mutable

    private var job: Job? = null
    private val flushing = Mutex()

    /**
     * Opens the review of [fileName] renamed to [title]. Nothing opens when there is nothing to
     * send (no name or title left after the scrub), while another review is open, or, with
     * [afterRename], for a pair the viewer was asked about already or that went or waits.
     * [chatName] is only offered, never sent unless ticked.
     */
    fun open(scope: CoroutineScope, fileName: String, title: String, chatName: String? = null, afterRename: Boolean = false) {
        if (mutable.value !is State.Idle) return
        val review = State.Review(fileName, title, chatName, afterRename = afterRename)
        val report = review.report ?: return
        // Claimed before the ledger is read, so a second call while it reads opens nothing.
        if (!mutable.compareAndSet(State.Idle, State.Preparing)) return
        job = scope.launch {
            try {
                val ledger = store.read()
                when {
                    ledger.hasSent(report) -> mutable.value = State.AlreadySent(report)
                    ledger.isQueued(report) -> mutable.value = State.Queued(report)
                    afterRename && !ledger.shouldAsk(report) -> mutable.value = State.Idle
                    else -> {
                        if (afterRename) store.update { it.withAsked(report) }
                        mutable.value = review
                    }
                }
            } catch (e: CancellationException) {
                mutable.value = State.Idle
                throw e
            } catch (e: Exception) {
                mutable.value = State.Idle
            }
        }
    }

    fun setIncludeChat(value: Boolean) = editReview { it.copy(includeChat = value) }

    fun setAgreed(value: Boolean) = editReview { it.copy(agreed = value) }

    private fun editReview(change: (State.Review) -> State.Review) {
        while (true) {
            val now = mutable.value as? State.Review ?: return
            if (mutable.compareAndSet(now, change(now))) return
        }
    }

    /** Sends the pair shown. Only from [State.Review] with the box ticked: a double press, or a press before ticking, does nothing. */
    fun send(scope: CoroutineScope) {
        val shown = mutable.value as? State.Review ?: return
        val report = shown.report?.takeIf { shown.agreed } ?: return
        if (!mutable.compareAndSet(shown, State.Sending(report))) return
        job = scope.launch {
            try {
                val outcome = upload(report.toJson())
                when (outcome) {
                    is TitleUpload.Outcome.Sent -> {
                        store.update { it.withSent(report) }
                        mutable.value = State.Sent(report, outcome.code, outcome.duplicate)
                        // The line works: the pairs that waited for one go now.
                        try {
                            flush()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Kept in the queue for the next time.
                        }
                    }
                    is TitleUpload.Outcome.SlowDown -> {
                        store.update { it.withQueued(report) }
                        mutable.value = State.Queued(report, outcome.retryAfterSeconds)
                    }
                    TitleUpload.Outcome.NoConnection -> {
                        store.update { it.withQueued(report) }
                        mutable.value = State.Queued(report)
                    }
                    is TitleUpload.Outcome.Failed ->
                        if (TitleUpload.worthRetrying(outcome)) {
                            store.update { it.withQueued(report) }
                            mutable.value = State.Queued(report)
                        } else {
                            mutable.value = State.Refused(report)
                        }
                }
            } catch (e: CancellationException) {
                // Closed by the viewer while it went: nothing is kept, nothing more is said.
                mutable.value = State.Idle
                throw e
            } catch (e: Exception) {
                runCatching { store.update { it.withQueued(report) } }
                mutable.value = State.Queued(report)
            }
        }
    }

    /** "No thanks": this pair is not asked about again. */
    fun decline(scope: CoroutineScope) {
        val shown = mutable.value as? State.Review
        mutable.value = State.Idle
        val report = shown?.let { TitleReport.of(it.fileName, it.title) } ?: return
        scope.launch { runCatching { store.update { it.withAsked(report) } } }
    }

    /** Closes whatever is showing, cancelling a send in flight. */
    fun close() {
        job?.cancel()
        job = null
        mutable.value = State.Idle
    }

    /**
     * Sends the pairs waiting for a connection, oldest first, one at a time. Stops at the first
     * answer that says to wait (no connection, the limit, the inbox failing); a pair the inbox
     * refuses for good is dropped. Safe to call often: a second call while one runs does nothing.
     * Returns how many went.
     */
    suspend fun flush(): Int {
        if (!flushing.tryLock()) return 0
        try {
            var sent = 0
            for (report in store.read().queue) {
                val outcome = try {
                    upload(report.toJson())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return sent
                }
                if (outcome is TitleUpload.Outcome.Sent) {
                    store.update { it.withSent(report) }
                    sent++
                } else if (TitleUpload.worthRetrying(outcome)) {
                    return sent
                } else {
                    store.update { it.withoutQueued(report) }
                }
            }
            return sent
        } finally {
            flushing.unlock()
        }
    }
}
