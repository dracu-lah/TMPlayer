package com.tmplayer.data

import android.content.Context
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The videos the viewer asked to keep: what is arriving, what is waiting its turn, what is held.
 *
 * This is the difference between the cache and a download. Playing something already fills the
 * cache with it, but that copy belongs to the player: closing the video cancels what was still
 * coming, and the next video may evict it. A download asked for by name is for the train, and has
 * to survive the app being put away, so it is fetched by a foreground service with a notification
 * of its own rather than by whichever screen happened to start it.
 *
 * Three videos ticked at once are three downloads, not one. They are fetched one at a time, because
 * a phone's bandwidth divided three ways finishes all three late rather than the first one early,
 * and the two that are not being fetched are visible and waiting rather than silently discarded.
 * That queue is the state here, and it is what the Downloads screen and the notification both draw.
 *
 * What is in memory is the live picture; [DownloadService] mirrors the unfinished part of it to the
 * preference store as it changes, so a crash or a kill comes back to the same list of videos rather
 * than to an empty screen and a half-written file nobody remembers asking for. [restore] is what
 * reads it back, and it comes back paused: a process that died deserves a viewer's press before it
 * starts pulling a gigabyte again.
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
         * that walked into a tunnel has said nothing at all, and the download it interrupted
         * should pick itself up when the signal does.
         */
        Offline,

        /**
         * Held because the only connection is one the viewer pays for by the byte, and they have
         * asked for Wi-Fi only.
         *
         * The setting existed and the player obeyed it; this did not, so somebody who had turned
         * on "Only download over Wi-Fi" and ticked three films got three films over cellular. It
         * waits for Wi-Fi in exactly the way the one above waits for a signal.
         */
        NoWifi,

        /** Stopped by something that went wrong. Resumable in exactly the same way as paused. */
        Failed,
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
    ) {
        val fileId: Int get() = request.fileId
        val title: String get() = request.title

        val fraction: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

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
                stage == Stage.Offline || stage == Stage.NoWifi
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
    fun start(context: Context, item: MediaItem, chatTitle: String) {
        start(context, DownloadRequest.from(item, chatTitle))
    }

    fun start(context: Context, request: DownloadRequest) {
        // A foreground service, so it keeps going with the app closed. Android requires the
        // notification within a few seconds of this call, which the service posts first thing.
        //
        // Guarded, because from Android 12 a foreground service may not be started while the app
        // is in the background, and this is reachable from one: a screen that was left open while
        // the phone went to sleep still has a Resume button on it. The throw is the system saying
        // no, not the app being broken, and taking the process down over it would lose the queue.
        runCatching {
            context.startForegroundService(request.intent(context, DownloadService.ACTION_DOWNLOAD))
        }.onFailure {
            android.util.Log.w(TAG, "Could not start the download service", it)
            refused(request)
        }
    }

    /**
     * Writes down a download Android would not let this app start.
     *
     * This used to be a line in logcat and nothing else, which is the worst answer this screen can
     * give: the viewer pressed Download, no row appeared, no notification appeared, and there was
     * nothing anywhere to press again. "I was not able to download it" is exactly what that looks
     * like from the outside. A failed row is not a fix for the refusal, but it is the difference
     * between a button that did nothing and a video that says what happened and offers Try again.
     */
    private fun refused(request: DownloadRequest) {
        val existing = _active.value[request.fileId]
        val row = existing ?: Progress(
            request = request,
            downloadedBytes = 0,
            totalBytes = request.sizeBytes,
            stage = Stage.Failed,
            order = nextOrder(),
        )
        note(row.copy(stage = Stage.Failed, failure = REFUSED, bytesPerSecond = 0))
    }

    /** The place a new row takes at the end of the list, when the service is not there to say. */
    internal fun nextOrder(): Long = (_active.value.values.maxOfOrNull { it.order } ?: -1L) + 1L

    /** Stops one download, or every one of them, and keeps the bytes that already landed. */
    fun cancel(context: Context, fileId: Int) = send(context, DownloadService.ACTION_CANCEL, fileId)

    /** Holds a download where it is. The partial file stays, so resuming is not starting again. */
    fun pause(context: Context, fileId: Int) = send(context, DownloadService.ACTION_PAUSE, fileId)

    /** Puts a paused or failed download back on the end of the queue. */
    fun resume(context: Context, fileId: Int) {
        val request = _active.value[fileId]?.request ?: return
        start(context, request)
    }

    /**
     * Everything that is not moving, back on the queue at once.
     *
     * The notification has room for one button, and with several videos held the honest thing for
     * it to do is the same as its "Cancel all": act on all of them rather than pick one silently.
     */
    fun pauseAll(context: Context) = send(context, DownloadService.ACTION_PAUSE, EVERYTHING)

    fun resumeAll(context: Context) {
        val held = _active.value.values.filter { !it.busy }.sortedBy { it.order }
        if (held.isEmpty()) return
        held.forEach { start(context, it.request) }
    }

    private fun send(context: Context, action: String, fileId: Int) {
        val intent = android.content.Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_FILE_ID, fileId)
        }
        // Started in the foreground form as well. Pausing the only running download leaves the
        // service alive holding paused rows, and a plain startService onto a process that Android
        // had already stopped throws rather than starting it.
        runCatching { context.startForegroundService(intent) }.onFailure {
            android.util.Log.w(TAG, "Could not reach the download service for $action", it)
            // Android would not start the service, so nobody is going to carry the press out. Both
            // of these are the viewer taking something away, and a Cancel or a Pause that visibly
            // does nothing is worse than one carried out here: the fetching has stopped either way,
            // because the service that was doing it is not running.
            val touched =
                if (fileId == EVERYTHING) _active.value.keys.toList() else listOf(fileId)
            when (action) {
                DownloadService.ACTION_CANCEL -> touched.forEach(::forget)
                DownloadService.ACTION_PAUSE -> touched.forEach { stage(it, Stage.Paused) }
                else -> Unit
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
    suspend fun restore(context: Context) {
        if (_active.value.isNotEmpty()) return
        val stored = runCatching { SettingsStore(context).downloadQueueNow() }.getOrDefault(emptyList())
        if (stored.isEmpty()) return
        var order = 0L
        val restored = stored.mapNotNull { request ->
            // A file the last run finished, or that has since been deleted, is not a download to
            // offer resuming: the queue is written before the work, so it outlives its own entries.
            val availability = runCatching { Td.localFileAvailability(request.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            if (availability == LocalFileAvailability.Complete) return@mapNotNull null
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
        runCatching { SettingsStore(context).saveDownloadQueue(restored.values.map { it.request }) }
    }

    // ---- called by the service --------------------------------------------------------------

    internal fun note(progress: Progress) {
        _active.value = _active.value + (progress.fileId to progress)
    }

    /** Moves one entry to another stage, leaving its figures alone. Absent ids are ignored. */
    internal fun stage(fileId: Int, stage: Stage, failure: String? = null) {
        val previous = _active.value[fileId] ?: return
        note(
            previous.copy(
                stage = stage,
                failure = failure,
                // A row that is not running is not arriving at any speed, and a stale figure on a
                // paused row reads as though it were still coming down.
                bytesPerSecond = if (stage == Stage.Running) previous.bytesPerSecond else 0,
                sampledAtMs = if (stage == Stage.Running) previous.sampledAtMs else 0,
            ),
        )
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
    internal fun sample(fileId: Int, downloadedBytes: Long, nowMs: Long = System.currentTimeMillis()) {
        val previous = _active.value[fileId] ?: return
        // A tick that arrives after the viewer pressed pause must not put the row back to running,
        // and must not report a speed for something that has stopped.
        if (previous.stage != Stage.Running) return
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
        note(
            previous.copy(
                downloadedBytes = downloadedBytes,
                bytesPerSecond = rate.toLong().coerceAtLeast(0L),
                sampledAtMs = if (ready || previous.sampledAtMs == 0L) nowMs else previous.sampledAtMs,
            ),
        )
    }

    internal fun forget(fileId: Int) {
        _active.value = _active.value - fileId
    }

    internal fun forgetAll() {
        _active.value = emptyMap()
    }

    /** The file id that means "all of them", since no real file has it. */
    private const val EVERYTHING = 0

    private const val TAG = "OfflineDownloads"

    /**
     * What a row says when Android refused to start the service that would have fetched it.
     *
     * Android 14 refuses a foreground service started from the background, and Android 15 refuses
     * a data sync one whose day's budget has been spent. Neither is something the app can argue
     * with, and both are answered by opening the app and asking again, so that is what it says.
     */
    const val REFUSED = "Android would not start this download. Open TMPlayer and press Try again."

    /** Shorter than this and the clock, not the network, is what is being measured. */
    private const val MIN_SAMPLE_MS = 500L

    /** How much of each new reading to believe: low enough that one stalled second does not show. */
    private const val SMOOTHING = 0.3
}
