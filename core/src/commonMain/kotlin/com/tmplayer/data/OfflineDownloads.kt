package com.tmplayer.data

import com.tmplayer.platform.Logger
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The videos the viewer asked to keep: what is arriving, what is waiting its turn, what is held.
 *
 * Unlike a cached copy, a download has to survive the app being put away, so it is fetched by a
 * foreground service rather than by whichever screen started it. Several ticked videos are fetched
 * one at a time: bandwidth divided three ways finishes all three late rather than the first early.
 * That queue is the state here, and the Downloads screen and the notification both draw it.
 *
 * The fetching itself belongs to the platform, behind a [DownloadRunner]: on Android a foreground
 * service, on the desktop a coroutine in the app process. The runner mirrors the unfinished part to
 * the preference store as it changes, so a crash comes back to the same list. [restore] reads it
 * back paused, so a killed process does not start pulling a gigabyte again with nobody near the
 * phone.
 */
object OfflineDownloads {

    /** Where a video has got to, which is the only thing a row needs to know to draw itself. */
    enum class Stage {
        /** Waiting for the one ahead of it to finish. */
        Queued,

        /** Being fetched right now. */
        Running,

        /** Stopped by the viewer, or by the process dying. The bytes that landed are kept. */
        Paused,

        /**
         * Held because there is no connection, and waiting for one to come back.
         *
         * Kept apart from [Paused] because the two have opposite futures. A viewer who pressed
         * Pause has said "not now", and nothing should start it again behind their back; a phone
         * that walked into a tunnel has said nothing at all, and should pick itself up when the
         * signal does.
         */
        Offline,

        /**
         * Held because the only connection is metered and the viewer asked for Wi-Fi only. Waits
         * for Wi-Fi in exactly the way [Offline] waits for a signal.
         */
        NoWifi,

        /** Stopped by something that went wrong. Resumable in exactly the same way as paused. */
        Failed,

        /**
         * Fetched in full, and now being moved out of TDLib's cache into the Downloads folder.
         *
         * A rename on one drive, over as soon as it starts; a copy across drives, with its own
         * progress in [Progress.movedBytes]. When a player has the file open the move waits for it
         * to close, and [Progress.heldByPlayer] says so, because moving a file out from under a
         * player loses the video on screen and on Windows is refused outright.
         */
        Moving,
    }

    /** How far a file has come, and everything needed to ask for the rest of it again. */
    data class Progress(
        val request: DownloadRequest,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val stage: Stage,
        /** Set only when [stage] is [Stage.Failed], and then it is what the row says. */
        val failure: String? = null,
        /** Smoothed, not the last tick's delta. See [sample] for why that matters. */
        val bytesPerSecond: Long = 0,
        val sampledAtMs: Long = 0,
        /** When it was asked for, counting from the start of the queue: the order rows are drawn in. */
        val order: Long = 0,
        /** While [stage] is [Stage.Moving], how much of the file is in the Downloads folder so far. */
        val movedBytes: Long = 0,
        /** While [stage] is [Stage.Moving], whether the move is waiting for a player to let go. */
        val heldByPlayer: Boolean = false,
    ) {
        val fileId: Int get() = request.fileId
        val title: String get() = request.title

        val fraction: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

        /** How far the move into Downloads has come, or null outside [Stage.Moving] or with no size. */
        val moveFraction: Float?
            get() = if (stage == Stage.Moving && totalBytes > 0) {
                (movedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }

        /** Seconds still to wait, or `null` while the rate is too slow or too new to mean anything. */
        val remainingSeconds: Long?
            get() {
                if (totalBytes <= 0) return null
                return StreamStats.secondsForBytes(totalBytes - downloadedBytes, bytesPerSecond)
            }

        /**
         * Whether this will get there on its own, and therefore whether pressing download again
         * would mean anything. True of a video waiting for a signal as well as one being fetched:
         * both are going to happen without the viewer doing another thing.
         */
        val busy: Boolean
            get() = stage == Stage.Running || stage == Stage.Queued ||
                stage == Stage.Offline || stage == Stage.NoWifi || stage == Stage.Moving
    }

    private val _active = MutableStateFlow<Map<Int, Progress>>(emptyMap())

    /** Keyed by TDLib file id, which is what a media row already has to hand. */
    val active: StateFlow<Map<Int, Progress>> = _active.asStateFlow()

    /** Oldest request first, so a queue reads top to bottom and does not reshuffle as it moves. */
    val ordered: List<Progress> get() = _active.value.values.sortedBy { it.order }

    /**
     * Whether this file is spoken for by a download rather than by a player.
     *
     * True for a paused or failed one as well, which is deliberate in both places that ask. The
     * player uses it to avoid starting a second fetch of bytes something else owns, and the browse
     * grid uses it to avoid queueing a video that is already on the list.
     */
    fun isDownloading(fileId: Int): Boolean = _active.value.containsKey(fileId)

    /**
     * Asks for a video to be kept.
     *
     * Safe to call twice: the service checks its own queue, so a second press on a row that is
     * already coming down does nothing rather than starting a second fetch of the same bytes.
     */
    fun start(runner: DownloadRunner, item: MediaItem, chatTitle: String) {
        start(runner, DownloadRequest.from(item, chatTitle))
    }

    fun start(runner: DownloadRunner, request: DownloadRequest) {
        // Guarded, because from Android 12 a foreground service may not be started while the app
        // is in the background, and this is reachable from one: a screen that was left open while
        // the phone went to sleep still has a Resume button on it. The throw is the system saying
        // no, not the app being broken, and taking the process down over it would lose the queue.
        runCatching {
            runner.download(request)
        }.onFailure {
            Logger.w(TAG, "Could not start the download service", it)
            refused(request, runner.refusal)
        }
    }

    /**
     * Writes down a download Android would not let this app start.
     *
     * A failed row is not a fix for the refusal, but it is the difference between a button that
     * appears to do nothing and a video that says what happened and offers Try again.
     */
    private fun refused(request: DownloadRequest, refusal: String) {
        val existing = _active.value[request.fileId]
        val row = existing ?: Progress(
            request = request,
            downloadedBytes = 0,
            totalBytes = request.sizeBytes,
            stage = Stage.Failed,
            order = nextOrder(),
        )
        note(row.copy(stage = Stage.Failed, failure = refusal, bytesPerSecond = 0))
    }

    /** The place a new row takes at the end of the list, when the service is not there to say. */
    fun nextOrder(): Long = (_active.value.values.maxOfOrNull { it.order } ?: -1L) + 1L

    /** Stops one download, or every one of them, and keeps the bytes that already landed. */
    fun cancel(runner: DownloadRunner, fileId: Int) = send(runner, Action.Cancel, fileId)

    /** Holds a download where it is. The partial file stays, so resuming is not starting again. */
    fun pause(runner: DownloadRunner, fileId: Int) = send(runner, Action.Pause, fileId)

    /** Puts a paused or failed download back on the end of the queue. */
    fun resume(runner: DownloadRunner, fileId: Int) {
        val request = _active.value[fileId]?.request ?: return
        start(runner, request)
    }

    /**
     * Everything that is not moving, back on the queue at once.
     *
     * The notification has room for one button, and with several videos held the honest thing for
     * it to do is the same as its "Cancel all": act on all of them rather than pick one silently.
     */
    fun pauseAll(runner: DownloadRunner) = send(runner, Action.Pause, EVERYTHING)

    fun resumeAll(runner: DownloadRunner) {
        val held = _active.value.values.filter { !it.busy }.sortedBy { it.order }
        if (held.isEmpty()) return
        held.forEach { start(runner, it.request) }
    }

    private enum class Action { Cancel, Pause }

    private fun send(runner: DownloadRunner, action: Action, fileId: Int) {
        runCatching {
            when (action) {
                Action.Cancel -> runner.cancel(fileId)
                Action.Pause -> runner.pause(fileId)
            }
        }.onFailure {
            Logger.w(TAG, "Could not reach the download service for $action", it)
            // Android would not start the service, so nobody is going to carry the press out. Both
            // of these are the viewer taking something away, and a Cancel or a Pause that visibly
            // does nothing is worse than one carried out here: the fetching has stopped either way,
            // because the service that was doing it is not running.
            val touched =
                if (fileId == EVERYTHING) _active.value.keys.toList() else listOf(fileId)
            when (action) {
                Action.Cancel -> touched.forEach(::forget)
                Action.Pause -> touched.forEach { stage(it, Stage.Paused) }
            }
        }
    }

    /**
     * Brings back what an earlier run of the app was in the middle of.
     *
     * Called once at launch. Nothing is fetched: every restored video is [Stage.Paused], with
     * whatever bytes TDLib still has on disk, and it is the Downloads screen that offers to carry
     * on. Restoring them as running would mean a process the system killed for using too much
     * silently starting to use it again, with the viewer nowhere near the phone.
     */
    suspend fun restore(settings: SettingsStore) {
        if (_active.value.isNotEmpty()) return
        val stored = runCatching { settings.downloadQueueNow() }.getOrDefault(emptyList())
        if (stored.isEmpty()) return
        var order = 0L
        val restored = stored.mapNotNull { request ->
            // A file the last run finished and recorded is not a download to offer resuming: the
            // queue is written before the work, so it outlives its own entries. One that finished
            // without a record is different: it was fetched and never moved into Downloads, and
            // Resume is what moves it.
            val availability = runCatching { Td.localFileAvailability(request.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            val record = runCatching { settings.downloadRecord(request.chatId, request.messageId) }.getOrNull()
            if (record?.localPath != null) return@mapNotNull null
            if (availability == LocalFileAvailability.Complete && record != null) return@mapNotNull null
            val done = runCatching { Td.localDownloadedBytes(request.fileId) }.getOrDefault(0L)
            request.fileId to Progress(
                request = request,
                downloadedBytes = done,
                totalBytes = request.sizeBytes,
                stage = Stage.Paused,
                order = order++,
            )
        }.toMap()
        _active.value = restored
        runCatching { settings.saveDownloadQueue(restored.values.map { it.request }) }
    }

    // ---- called by the runner ---------------------------------------------------------------

    /**
     * All of these go through [MutableStateFlow.update] rather than assigning `.value`.
     *
     * `value = value + x` is a read and then a write, and the writers here genuinely overlap: the
     * per-second progress ticker on the service's scope, the block that finishes a download, the
     * network watcher, and `enqueue`/`cancel`/`pause` arriving from `onStartCommand` on the main
     * thread. A [forget] losing to a ticker's [note] built from an older snapshot leaves a row for
     * a video that is finished or cancelled, and since `stopWhenIdle` asks this map whether
     * anything is still busy, that ghost keeps the foreground service alive for the session.
     *
     * [stage] and [sample] read an entry and write a copy of it, so they take the whole
     * read-modify-write inside one `update` block rather than reading first and calling [note].
     */
    fun note(progress: Progress) {
        _active.update { it + (progress.fileId to progress) }
    }

    /** Moves one entry to another stage, leaving its figures alone. Absent ids are ignored. */
    fun stage(fileId: Int, stage: Stage, failure: String? = null) {
        _active.update { map ->
            val previous = map[fileId] ?: return@update map
            map + (
                fileId to previous.copy(
                    stage = stage,
                    failure = failure,
                    // A row that is not running is not arriving at any speed, and a stale figure
                    // on a paused row reads as though it were still coming down.
                    bytesPerSecond = if (stage == Stage.Running) previous.bytesPerSecond else 0,
                    sampledAtMs = if (stage == Stage.Running) previous.sampledAtMs else 0,
                    heldByPlayer = stage == Stage.Moving && previous.heldByPlayer,
                )
                )
        }
    }

    /**
     * Records how far a file has come and works out how fast it is arriving.
     *
     * A rate taken straight from one tick to the next swings between nothing and double the truth,
     * because TDLib writes in bursts and a second is a short window to measure a burst in. The
     * figure kept here is exponentially smoothed towards each new sample instead, which settles
     * within a few seconds and then reads as a speed rather than as a fault. A tick that saw no
     * bytes at all is still fed in, so a stalled download falls to zero rather than freezing at
     * whatever it last managed.
     */
    fun sample(fileId: Int, downloadedBytes: Long, nowMs: Long = System.currentTimeMillis()) {
        _active.update { map ->
        val previous = map[fileId] ?: return@update map
        // A tick that arrives after the viewer pressed pause must not put the row back to running,
        // and must not report a speed for something that has stopped.
        if (previous.stage != Stage.Running) return@update map
        val elapsedMs = nowMs - previous.sampledAtMs
        val ready = previous.sampledAtMs > 0 && elapsedMs >= MIN_SAMPLE_MS
        val rate = when {
            !ready -> previous.bytesPerSecond.toDouble()
            else -> {
                val instant = ((downloadedBytes - previous.downloadedBytes) * 1000.0 / elapsedMs)
                    .coerceAtLeast(0.0)
                if (previous.bytesPerSecond <= 0L) {
                    instant
                } else {
                    previous.bytesPerSecond * (1 - SMOOTHING) + instant * SMOOTHING
                }
            }
        }
        map + (
            fileId to previous.copy(
                downloadedBytes = downloadedBytes,
                bytesPerSecond = rate.toLong().coerceAtLeast(0L),
                sampledAtMs = if (ready || previous.sampledAtMs == 0L) nowMs else previous.sampledAtMs,
            )
            )
        }
    }

    /**
     * Puts a fully fetched file into [Stage.Moving], or reports how far its move has come.
     *
     * Called by the runner from the moment TDLib says the file is complete until it is in the
     * Downloads folder: once with [heldByPlayer] while a player has it open, then as the bytes
     * land. [downloadedBytes][Progress.downloadedBytes] is set to the whole size, since the
     * fetching part is over whatever the move does. Absent ids are ignored, as in [stage].
     */
    fun moving(fileId: Int, movedBytes: Long = 0, heldByPlayer: Boolean = false) {
        _active.update { map ->
            val previous = map[fileId] ?: return@update map
            val total = previous.totalBytes.takeIf { it > 0 } ?: previous.downloadedBytes
            map + (
                fileId to previous.copy(
                    stage = Stage.Moving,
                    failure = null,
                    downloadedBytes = maxOf(previous.downloadedBytes, total),
                    movedBytes = movedBytes.coerceAtLeast(0),
                    heldByPlayer = heldByPlayer,
                    bytesPerSecond = 0,
                    sampledAtMs = 0,
                )
                )
        }
    }

    fun forget(fileId: Int) {
        _active.update { it - fileId }
    }

    /** The file id that means "all of them", since no real file has it. */
    const val EVERYTHING = 0

    private const val TAG = "OfflineDownloads"

    /** Shorter than this and the clock, not the network, is what is being measured. */
    private const val MIN_SAMPLE_MS = 500L

    /** How much of each new reading to believe: low enough that one stalled second does not show. */
    private const val SMOOTHING = 0.3
}

/**
 * Whatever actually fetches the videos [OfflineDownloads] keeps track of.
 *
 * Each call may throw when the platform refuses to carry it out (Android will not start a
 * foreground service from the background, for one); [OfflineDownloads] catches that and shows the
 * row as failed or does the cancel or pause itself, so a press is never silently lost.
 */
interface DownloadRunner {
    /**
     * What a row says when this runner could not be started at all.
     *
     * The platform's to word, since only it knows why that happens and what helps: on Android it
     * is the system refusing a foreground service, and the answer is opening the app again.
     */
    val refusal: String get() = REFUSED_ANYWHERE

    /** Queues [request]. Asking for a file that is already queued or running does nothing. */
    fun download(request: DownloadRequest)

    /** Stops [fileId], or everything when it is [OfflineDownloads.EVERYTHING], keeping the bytes. */
    fun cancel(fileId: Int)

    /** Holds [fileId], or everything when it is [OfflineDownloads.EVERYTHING], where it is. */
    fun pause(fileId: Int)

    companion object {
        /** The fallback for a runner that has nothing more particular to say. */
        const val REFUSED_ANYWHERE = "This download could not start. Press Try again."
    }
}
