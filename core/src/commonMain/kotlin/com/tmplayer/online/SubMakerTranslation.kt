package com.tmplayer.online

import com.tmplayer.platform.JobFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where a SubMaker translation is, for the progress line and the buttons under it. */
sealed interface TranslationState {
    /** Asked, and SubMaker has answered with its placeholder only: no line yet. */
    data object Waiting : TranslationState

    /** [lines] cues so far, and the loaded track holds them. */
    data class Translating(val lines: Int) : TranslationState

    /** Finished: [lines] cues, kept for this video. */
    data class Done(val lines: Int) : TranslationState

    /** Stopped by [notice] after [lines] cues (the track holds those, if any). */
    data class Failed(val notice: SubtitleNotice, val lines: Int) : TranslationState

    val active: Boolean get() = this is Waiting || this is Translating
}

/**
 * One translation by SubMaker, from the first fetch of a "Make <Language>" address to its end.
 *
 * SubMaker answers a translate address at once with a placeholder, and the same address fetched
 * again returns what is translated so far, growing for one to three minutes; nothing says when it
 * is complete. So this polls the same address every [pollMs], reads the placeholder as "pending",
 * hands over the text each time it holds more cues, and finishes when the count has not grown for
 * [stableMs], or the total reaches [capMs], or the last cue reaches the end of the video.
 *
 * Time here is the time spent polling in the foreground: nothing is asked, and no time counts,
 * while [foreground] is false. A 429 waits out the wait it asked for (doubling, up to a minute);
 * those waits count neither as "not grown" nor toward the cap (they have their own, [maxBusyMs]),
 * and a job that ended because of them, or at the cap, is shown but never handed over as final:
 * only the video's end or [stableMs] of calm polling without growth is the end of a translation.
 */
class SubMakerTranslator(
    private val api: SubMakerApi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val pollMs: Long = POLL_MS,
    private val stableMs: Long = STABLE_MS,
    private val capMs: Long = CAP_MS,
    private val maxBusyMs: Long = MAX_BUSY_MS,
) {
    /**
     * Runs until finished, failed or cancelled. [onState] is told each step, [onTrack] each time the
     * translation holds more cues (the text as SubRip and its cue count; [final] on the last).
     * [endMs] is the video's length when known: a last cue within [END_SLACK_MS] of it finishes early.
     */
    suspend fun run(
        url: String,
        endMs: Long?,
        foreground: StateFlow<Boolean>,
        onState: (TranslationState) -> Unit,
        onTrack: (srt: String, lines: Int, final: Boolean) -> Unit,
    ) {
        var elapsed = 0L
        var stalled = 0L
        var lines = 0
        var best: String? = null
        var bad = 0
        var throttles = 0
        var busyTotal = 0L
        onState(TranslationState.Waiting)

        /** [final] only when it reached the end or stopped growing; at the cap the text is partial. */
        fun finish(final: Boolean) {
            val text = best
            if (text != null && lines > 0) {
                onTrack(text, lines, final)
                onState(TranslationState.Done(lines))
            } else {
                onState(TranslationState.Failed(SubtitleNotice.SubMakerEmpty, 0))
            }
        }

        while (true) {
            // Nothing is asked in the background, and the time it takes does not count.
            if (!foreground.value) foreground.first { it }
            // A Cancel that landed while this ran (before the wait, or with the app already in the
            // foreground, so the wait never suspended) must not still ask the addon: a cancelled
            // job reaches no suspension point of its own before the request goes out.
            currentCoroutineContext().ensureActive()
            var wait = pollMs
            var busy = false
            when (val answer = api.fetch(url, SubtitleText.MAX_BYTES)) {
                is SubMakerApi.Answer.Failed -> when (val failure = answer.failure) {
                    is SubMakerApi.Failure.Busy -> {
                        throttles++
                        wait = maxOf(failure.retryAfterMs, pollMs shl minOf(throttles, 3)).coerceAtMost(MAX_BACKOFF_MS)
                        // A rest the addon asked for is not a sign that the translation stopped growing.
                        busy = true
                        busyTotal += wait
                    }
                    SubMakerApi.Failure.Rejected -> return onState(TranslationState.Failed(SubtitleNotice.SubMakerRejected, lines))
                    SubMakerApi.Failure.Offline, SubMakerApi.Failure.Unreadable -> {
                        // A few blips are waited out; then it says so, keeping what it has.
                        if (++bad >= MAX_BAD) {
                            val notice = if (failure == SubMakerApi.Failure.Offline) SubtitleNotice.Offline else SubtitleNotice.Unreadable
                            return onState(TranslationState.Failed(notice, lines))
                        }
                        stalled += wait
                    }
                }
                is SubMakerApi.Answer.Ok -> {
                    throttles = 0
                    val text = SubtitleText.from(answer.value, "translation.srt")?.text
                    if (text == null) {
                        if (++bad >= MAX_BAD) return onState(TranslationState.Failed(SubtitleNotice.Unreadable, lines))
                        stalled += wait
                    } else {
                        bad = 0
                        val cues = if (SubMakerApi.isPlaceholder(text)) 0 else SubMakerApi.cueCount(text)
                        if (cues > lines) {
                            lines = cues
                            best = text
                            stalled = 0
                            onState(TranslationState.Translating(lines))
                            val reachedEnd = endMs != null && endMs > MIN_END_MS &&
                                SubMakerApi.lastCueEndMs(text) >= endMs - END_SLACK_MS
                            if (reachedEnd) return finish(true)
                            onTrack(text, lines, false)
                        } else {
                            stalled += wait
                        }
                    }
                }
            }
            if (busy) {
                if (busyTotal >= maxBusyMs) {
                    // Too long asked to wait: what came is shown (partial, never final) and it says so.
                    best?.let { text -> if (lines > 0) onTrack(text, lines, false) }
                    return onState(TranslationState.Failed(SubtitleNotice.SubMakerBusy, lines))
                }
                sleep(wait)
                continue
            }
            if (lines > 0 && stalled >= stableMs) return finish(true)
            if (elapsed + wait >= capMs) return finish(false)
            sleep(wait)
            elapsed += wait
        }
    }

    companion object {
        const val POLL_MS = 10_000L
        const val STABLE_MS = 3 * 60_000L
        const val CAP_MS = 6 * 60_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** The most time one job spends waiting out 429 answers before it stops with what it has. */
        const val MAX_BUSY_MS = 10 * 60_000L
        private const val MAX_BAD = 3

        /** A video shorter than this is not judged finished by its last cue. */
        private const val MIN_END_MS = 10 * 60_000L

        /** A last cue this near the video's end counts as the end: credits carry no dialogue. */
        const val END_SLACK_MS = 90_000L
    }
}

/**
 * The translations running now, one per language and video ([keyOf]), kept apart from any screen
 * so the player carries on and a reopened picker finds the progress. Each job writes the track
 * through [write] (kept for the video, never the half translation under the finished one's name)
 * and tells the player through [onTrack], no more often than every [applyEveryMs] until the last.
 *
 * Nothing runs without a job: no job, no polling. [setForeground] false (the app in the
 * background) pauses every job's polling, [cancelVideo] ends a video's jobs when the player closes.
 */
class SubMakerJobs(
    private val translator: SubMakerTranslator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val applyEveryMs: Long = APPLY_EVERY_MS,
) {
    private val foreground = MutableStateFlow(true)
    private val running = HashMap<String, Job>()
    private val state = MutableStateFlow<Map<String, TranslationState>>(emptyMap())

    /** Every job's state by key, finished and failed ones included until the next start or [clear]. */
    val states: StateFlow<Map<String, TranslationState>> = state.asStateFlow()

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun stateOf(key: String): TranslationState? = state.value[key]

    /**
     * Starts the translation at [url] under [key] unless one is running for it (false then).
     * [write] stores a text as the track's file (null: the disk refused) and [onTrack] puts that
     * file over the video; both may be called from a background thread.
     */
    @Synchronized
    fun start(
        key: String,
        url: String,
        endMs: Long?,
        write: (srt: String, final: Boolean) -> java.io.File?,
        onTrack: (file: java.io.File, final: Boolean) -> Unit,
    ): Boolean {
        if (running[key]?.isActive == true) return false
        set(key, TranslationState.Waiting)
        var lastApplied = 0L
        // The newest text a throttled update left unapplied: laid when the job ends without a final one.
        var held: String? = null
        // The profile the translation was asked for (HX88): its track file is written only while
        // that profile is open, and a switch ends the job (the video is that profile's).
        val owner = com.tmplayer.data.Accounts.stamp
        fun mine(): Boolean = owner == com.tmplayer.data.Accounts.stamp
        running[key] = scope.launch(com.tmplayer.data.AccountOwner(owner)) {
            // This job's own handle: a state it reports after a Cancel (or after the same key was
            // started again) is dropped, so Cancel's "forget the state" is the last word.
            val self = coroutineContext[Job]
            try {
                translator.run(
                    url = url,
                    endMs = endMs,
                    foreground = foreground,
                    onState = { next ->
                        val rest = held
                        if (!next.active && rest != null && mine()) {
                            held = null
                            write(rest, false)?.let { onTrack(it, false) }
                        }
                        setBy(self, key, next)
                    },
                    onTrack = track@{ srt, _, final ->
                        if (!mine()) return@track
                        val t = now()
                        if (final || lastApplied == 0L || t - lastApplied >= applyEveryMs) {
                            val file = write(srt, final)
                            held = null
                            if (file != null) {
                                lastApplied = t
                                onTrack(file, final)
                            } else if (final) {
                                setBy(self, key, TranslationState.Failed(SubtitleNotice.Unreadable, SubMakerApi.cueCount(srt)))
                            }
                        } else {
                            held = srt
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JobFailures.handle("SubMakerTranslation", e)
                setBy(self, key, TranslationState.Failed(SubtitleNotice.Offline, 0))
            }
        }
        return true
    }

    /** Ends the job and forgets its state: the viewer's Cancel. */
    @Synchronized
    fun cancel(key: String) {
        running.remove(key)?.cancel()
        state.value = state.value - key
    }

    /** Ends every job for a video, when the player closes; [prefix] is the video's part of the key. */
    @Synchronized
    fun cancelVideo(prefix: String) {
        for (key in running.keys.filter { it.startsWith(prefix) }) cancel(key)
        state.value = state.value.filterKeys { !it.startsWith(prefix) }
    }

    /** Ends every job, when the viewer's address changes. */
    @Synchronized
    fun cancelAll() {
        running.values.forEach { it.cancel() }
        running.clear()
        state.value = emptyMap()
    }

    /** Records a translation already finished on this device, so its line reads as done. */
    fun finished(key: String, lines: Int) = set(key, TranslationState.Done(lines))

    /** Forgets a finished or failed state, so its line goes. */
    @Synchronized
    fun clear(key: String) {
        if (state.value[key]?.active == false) state.value = state.value - key
    }

    @Synchronized
    private fun set(key: String, value: TranslationState) {
        state.value = state.value + (key to value)
    }

    /**
     * [set] for a job: [cancel] ends the job first and forgets the state second, both under this
     * lock, so a state set here after that sees the job inactive and is dropped (a late report
     * used to bring the cancelled line back).
     */
    @Synchronized
    private fun setBy(job: Job?, key: String, value: TranslationState) {
        if (job == null || job.isActive) set(key, value)
    }

    companion object {
        const val APPLY_EVERY_MS = 60_000L

        /** The job's key: the video, then the language, so a prefix of it is all of a video's jobs. */
        fun keyOf(video: String, language: String): String = "$video|${language.lowercase()}"
    }
}
