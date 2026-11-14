package com.tmplayer.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The steps of "Playback test" (L6), apart from the screen and the player so a test can walk them:
 * refuse while another video plays, find a video, plan the fetch of its first 30 MB when it is not
 * on the disk (space needed and free, asked first), fetch it, play 30 seconds from the disk, say what
 * it found, and offer to add that to Send logs.
 *
 * One run at a time. Cancel at any step leaves nothing behind: a fetch that was ours is deleted
 * again ([Env.discardFetched]) after a cancel, a failure and a finished run alike.
 */
class PlaybackTestSession(private val env: Env) {

    /** The video a test would use. */
    data class Candidate(
        val id: String,
        /** Shown on this screen only; it is never written to a log or a report. */
        val title: String,
        val sizeBytes: Long,
        /** Whole on the disk (a download): played as it is. */
        val downloaded: Boolean,
    )

    /** What the app can do for the session; real on Android, fake in the tests. */
    interface Env {
        /** True while a video plays in the player, in a picture-in-picture window or in the background. */
        fun anotherVideoPlaying(): Boolean

        /** The video to test: a download first, else the one last watched. Null when there is none. */
        suspend fun pick(): Candidate?

        suspend fun freeBytes(): Long

        /** How much of the start of [c] is on the disk now. */
        suspend fun onDiskPrefix(c: Candidate): Long

        /** Brings [PlaybackTest.windowBytes] onto the disk, calling [progress] with the bytes so far. False when it could not. */
        suspend fun fetch(c: Candidate, progress: (Long) -> Unit): Boolean

        /** Deletes what [fetch] brought, if it was only this test's (nothing that was already there). */
        suspend fun discardFetched(c: Candidate)

        /** Plays the window and reports the elapsed time through [progress]; returns the result. Cancelling stops it. */
        suspend fun play(c: Candidate, progress: (Long) -> Unit): PlaybackTest.Result

        /** Keeps [lines] for the next Send logs. False when they could not be kept. */
        suspend fun keepForReport(lines: List<String>): Boolean
    }

    sealed interface State {
        data object Idle : State
        data object Looking : State

        /** Another video is playing: refused, with a way out. */
        data object Refused : State

        /** No download and nothing watched to test. */
        data object NoVideo : State

        /** Ready to start. [fetch] is null when the first window is already on the disk. */
        data class Ready(val video: Candidate, val fetch: PlaybackTest.Fetch.Needed?) : State

        /** The fetch would not leave room: the numbers are on the screen. */
        data class NoSpace(val video: Candidate, val needed: Long, val free: Long) : State

        data class Fetching(val video: Candidate, val bytes: Long, val total: Long) : State
        data class Testing(val video: Candidate, val playedMs: Long) : State
        data class Done(val result: PlaybackTest.Result, val fetched: Boolean, val added: Boolean = false, val addFailed: Boolean = false) : State

        /** [reason] is for a log, not the screen: the screen says one thing for every failure. */
        data class Failed(val fetching: Boolean, val reason: String) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = mutable

    private var job: Job? = null
    private var fetchedByUs = false

    /** Looks for a video and plans the fetch. Ignored unless idle. */
    fun begin(scope: CoroutineScope) {
        if (mutable.value !is State.Idle) return
        mutable.value = State.Looking
        job = scope.launch {
            try {
                if (env.anotherVideoPlaying()) {
                    mutable.value = State.Refused
                    return@launch
                }
                val video = env.pick()
                if (video == null) {
                    mutable.value = State.NoVideo
                    return@launch
                }
                if (video.downloaded) {
                    mutable.value = State.Ready(video, null)
                    return@launch
                }
                val plan = PlaybackTest.fetchPlan(video.sizeBytes, env.onDiskPrefix(video), env.freeBytes())
                mutable.value = when (plan) {
                    PlaybackTest.Fetch.NotNeeded -> State.Ready(video, null)
                    is PlaybackTest.Fetch.Needed -> State.Ready(video, plan)
                    is PlaybackTest.Fetch.NoSpace -> State.NoSpace(video, plan.bytes, plan.freeBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = State.Failed(fetching = false, reason = e.message.orEmpty())
            }
        }
    }

    /** Starts the fetch (if needed) and the test. Only from [State.Ready]: a second press does nothing. */
    fun start(scope: CoroutineScope) {
        val ready = mutable.value as? State.Ready ?: return
        if (!mutable.compareAndSet(ready, State.Testing(ready.video, 0L))) return
        job = scope.launch {
            var fetching = false
            try {
                // Checked again: a video may have started since the screen came up.
                if (env.anotherVideoPlaying()) {
                    mutable.value = State.Refused
                    return@launch
                }
                if (ready.fetch != null) {
                    fetching = true
                    mutable.value = State.Fetching(ready.video, 0L, ready.fetch.bytes)
                    fetchedByUs = true
                    val ok = env.fetch(ready.video) { done ->
                        mutable.value = State.Fetching(ready.video, done.coerceIn(0L, ready.fetch.bytes), ready.fetch.bytes)
                    }
                    if (!ok) {
                        discard(ready.video)
                        mutable.value = State.Failed(fetching = true, reason = "fetch")
                        return@launch
                    }
                    fetching = false
                    mutable.value = State.Testing(ready.video, 0L)
                }
                val result = env.play(ready.video) { ms ->
                    mutable.value = State.Testing(ready.video, ms.coerceIn(0L, PlaybackTest.WINDOW_MS))
                }
                val wasFetched = fetchedByUs
                discard(ready.video)
                mutable.value = State.Done(result, fetched = wasFetched)
            } catch (e: CancellationException) {
                // Cancelled: whatever this test brought to the disk goes again.
                discard(ready.video)
                throw e
            } catch (e: Exception) {
                discard(ready.video)
                mutable.value = State.Failed(fetching = fetching, reason = e.message.orEmpty())
            }
        }
    }

    /** Stops a fetch or a test in flight and closes the screen's steps; nothing is left on the disk. */
    fun cancel() {
        val s = mutable.value
        if (s is State.Fetching || s is State.Testing) {
            job?.cancel()
            mutable.value = State.Idle
        }
    }

    /** Offers the result to Send logs. Only from [State.Done], once. */
    fun addToReport(scope: CoroutineScope) {
        val done = mutable.value as? State.Done ?: return
        if (done.added) return
        scope.launch {
            val ok = try {
                env.keepForReport(PlaybackTest.reportLines(done.result, done.fetched))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            mutable.compareAndSet(done, done.copy(added = ok, addFailed = !ok))
        }
    }

    /** Closes whatever is showing, cancelling a run in flight. */
    fun close() {
        job?.cancel()
        job = null
        mutable.value = State.Idle
    }

    private suspend fun discard(video: Candidate) {
        if (!fetchedByUs) return
        // Even a cancelled coroutine finishes deleting what it brought.
        withContext(NonCancellable) { runCatching { env.discardFetched(video) } }
        fetchedByUs = false
    }
}
