package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The steps behind "Send to developer" (L5), apart from the screen so a test can walk them: the
 * report is built, shown with what it holds and its size, and only after a press on Send does it
 * leave. One send at a time, never on its own.
 *
 *  - [Idle] nothing open. [open] builds the report: [Preparing], then [Confirm].
 *  - [Confirm] what is in it and how big; [send] uploads (a second press while it runs does nothing).
 *  - [Sending] [cancel] stops the upload and goes back to [Confirm]; nothing was kept.
 *  - [Sent] the code. Or [Kept]: no connection, the inbox's limit or an error, and the report saved
 *    to a file whose place is in the state (null when even that failed, say so).
 *
 * [close] ends any step except a send in flight, which it cancels first.
 */
class ReportSender(
    private val build: suspend () -> LogBundle.Result,
    private val upload: suspend (String) -> LogUpload.Outcome,
    /** Saves the text as a file and returns where, or null when it could not be saved. */
    private val keep: suspend (String) -> String?,
) {

    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Confirm(val report: LogBundle.Result) : State
        data class Sending(val report: LogBundle.Result) : State
        data class Sent(val code: String, val keepDays: Int = KEEP_DAYS) : State
        data class Kept(val why: Why, val place: String?, val waitSeconds: Int = 0) : State
        data class Failed(val message: String) : State
    }

    enum class Why { NO_CONNECTION, SLOW_DOWN, REFUSED }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = mutable

    private var job: Job? = null

    /** Builds the report and shows it. Ignored while a report is already open. */
    fun open(scope: CoroutineScope) {
        if (mutable.value !is State.Idle) return
        mutable.value = State.Preparing
        job = scope.launch {
            try {
                val report = build()
                mutable.value = if (report.text.isBlank()) State.Failed("empty") else State.Confirm(report)
            } catch (e: CancellationException) {
                mutable.value = State.Idle
                throw e
            } catch (e: Exception) {
                mutable.value = State.Failed(e.message.orEmpty())
            }
        }
    }

    /** Uploads the shown report. Only from [State.Confirm]: a double press, or a press after it finished, does nothing. */
    fun send(scope: CoroutineScope) {
        val shown = mutable.value as? State.Confirm ?: return
        // The check and the change are one step, so two presses cannot both start an upload.
        if (!mutable.compareAndSet(shown, State.Sending(shown.report))) return
        job = scope.launch {
            try {
                val outcome = upload(shown.report.text)
                mutable.value = when (outcome) {
                    is LogUpload.Outcome.Sent -> State.Sent(outcome.code)
                    is LogUpload.Outcome.SlowDown -> State.Kept(Why.SLOW_DOWN, keepSafely(shown.report.text), outcome.retryAfterSeconds)
                    LogUpload.Outcome.NoConnection -> State.Kept(Why.NO_CONNECTION, keepSafely(shown.report.text))
                    is LogUpload.Outcome.Failed -> State.Kept(Why.REFUSED, keepSafely(shown.report.text))
                }
            } catch (e: CancellationException) {
                // Cancelled by the viewer: nothing was kept, the report is still there to send or close.
                if (mutable.value is State.Sending) mutable.value = shown
                throw e
            } catch (e: Exception) {
                mutable.value = State.Kept(Why.NO_CONNECTION, keepSafely(shown.report.text))
            }
        }
    }

    /** Stops a send in flight and returns to the report; anything else is left as it is. */
    fun cancel() {
        val sending = mutable.value as? State.Sending ?: return
        job?.cancel()
        mutable.compareAndSet(sending, State.Confirm(sending.report))
    }

    /** Closes whatever is showing, cancelling a send in flight. */
    fun close() {
        job?.cancel()
        job = null
        mutable.value = State.Idle
    }

    private suspend fun keepSafely(text: String): String? = try {
        keep(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val KEEP_DAYS = 30
    }
}
