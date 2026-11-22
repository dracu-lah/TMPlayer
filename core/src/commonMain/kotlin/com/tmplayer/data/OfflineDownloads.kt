package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    init {
        // Another profile opened (HX88): file ids are its own database's, so the last one's rows
        // mean nothing there. Its downloads were paused before the switch; the next profile's
        // queue comes back through [restore], paused.
        Accounts.onSwitch { dropRows() }
    }

    /**
     * The profile the rows in memory belong to (HX88): set by [restore], which reads that
     * profile's stored queue first; null after they were dropped and before it ran. A persist
     * writes the rows for this profile only, so a late one never writes them into the next
     * profile's queue, and none writes before the stored queue was read.
     */
    @Volatile var rowsOwner: AccountStamp? = null
        private set

    /** Counts every drop of the rows ([dropRows]): an id taken before a drop is another file after it. */
    @Volatile var rowsGeneration: Long = 0L
        private set

    private val _databaseChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Each time the open profile's database became a new one: the download runner stops what it has. */
    val databaseChanges: kotlinx.coroutines.flow.SharedFlow<Unit> = _databaseChanges

    /** For tests: the rows of a process that just started (none, no owner), without telling the runner. */
    internal fun resetForTest() = dropRows()

    /**
     * The profile and set of rows [restore] has read the stored queue into, or null: it runs once
     * for each (HX88, review LOW). The screen that asks for it composes again with every
     * recreation of the activity, and a second run reads the stored queue as it still is, which
     * has the video the viewer just cancelled until that cancel's write lands: it came back as a
     * paused row.
     */
    private var restoredFor: Pair<AccountStamp, Long>? = null

    /** For tests: runs in [restore] after the stored queue was read and before it is merged, where a switch or a new database can land. */
    internal var afterStoredRead: (suspend () -> Unit)? = null

    /** For tests: runs in [restore] after its merge and before its write, where a switch and the next profile's restore can land. */
    internal var afterMerge: (suspend () -> Unit)? = null

    /** The rows dropped again without telling the runner, which [newDatabase] already did ([Td.renewDatabaseMarker]). */
    internal fun dropRowsQuietly() = dropRows()

    private fun dropRows() {
        synchronized(this) {
            _active.value = emptyMap()
            rowsOwner = null
            rowsGeneration++
            restoredFor = null
        }
    }

    /**
     * The open profile's TDLib database is a new one (a sign out, a session Telegram ended): the
     * rows in memory are the old database's file ids, so they go, and the runner stops their jobs.
     * The stored queue goes on restore (its marker is the old database's).
     */
    fun newDatabase() {
        dropRows()
        _databaseChanges.tryEmit(Unit)
    }

    /** The set of rows a runner job was queued in ([rowsGeneration]); its row updates carry it. */
    class Rows(val generation: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Rows>
    }

    /** Runs [update] only while the rows are still the set [generation] (null: no set, always runs). */
    fun <T> ifRows(generation: Long?, update: () -> T): T? = synchronized(this) {
        if (generation != null && generation != rowsGeneration) null else update()
    }

    /**
     * The rows to persist, the profile they are for and the set of rows they are ([rowsGeneration]),
     * read together. A write that straddles a session end hands [generation] to
     * [SettingsStore.saveDownloadQueue], which drops it when the rows went meanwhile (HX88).
     */
    data class PersistSnapshot(val owner: AccountStamp?, val requests: List<DownloadRequest>, val generation: Long)

    /**
     * Removes the row of [fileId] only while the rows are still the set [rows] it was queued in
     * ([ifRows]): after a switch or a new database the same id is another video, the next
     * database's own, which a skipped start must not remove.
     */
    fun forgetQueuedIn(fileId: Int, rows: Long) {
        ifRows(rows) { forget(fileId) }
    }

    /** The rows to persist and the profile they are for, read together. */
    fun persistSnapshot(): PersistSnapshot = synchronized(this) { PersistSnapshot(rowsOwner, ordered.map { it.request }, rowsGeneration) }

    private val persistLock = Mutex()

    /**
     * Writes the queue, now, and never alongside another write of it: the rows and the profile and
     * set of rows they are, read together ([persistSnapshot]) and written under one lock (HX88).
     * - A write that lands after a switch is dropped for its owner, never written into the next
     *   profile's queue.
     * - No owner: the rows were dropped and nothing is in them yet, and an empty write then would
     *   wipe the next profile's queue before its restore.
     * - A write that straddles a session end (the rows dropped, the marker renewed) is dropped
     *   rather than tagged with the next database's marker ([SettingsStore.saveDownloadQueue]).
     *
     * The service's writer and its completion paths call this, and so does the queue model test:
     * [afterSnapshot] is its hook for what happens between the snapshot and the write. [database] is
     * the marker to tag the queue with (the store's own profile's by default); [restore] hands the
     * one it merged under, so its write is as guarded as every other. [onlyFor] is the profile and
     * set of rows the caller merged and marked: a snapshot taken under the lock that is another's
     * (a switch landed and the next profile's restore merged first) is not written, so its rows
     * are never tagged with this caller's marker.
     */
    suspend fun persistQueue(
        settings: SettingsStore,
        database: String? = DATABASE_OF_STORE,
        onlyFor: Pair<AccountStamp, Long>? = null,
        afterSnapshot: suspend () -> Unit = {},
    ) {
        persistLock.withLock {
            val (owner, queued, generation) = persistSnapshot()
            afterSnapshot()
            if (owner == null) return@withLock
            if (onlyFor != null && (owner != onlyFor.first || generation != onlyFor.second)) return@withLock
            try {
                settings.saveDownloadQueue(queued, owner = owner, database = database, rowsGeneration = generation)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                Logger.w(TAG, "could not write the download queue", failed)
            }
        }
    }

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

        /**
         * Held because a video is playing from the network and the line is the stream's first
         * ([DownloadYield]). Waits for the playback to end, or to stay paused long, in the way
         * [Offline] waits for a signal, and is never written to disk: a crash brings the row back
         * as [Paused] like every other, so nothing here can be left waiting for a player that is gone.
         */
        Yielding,

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
                stage == Stage.Offline || stage == Stage.NoWifi || stage == Stage.Yielding ||
                stage == Stage.Moving
    }

    private val _active = MutableStateFlow<Map<Int, Progress>>(emptyMap())

    /** Keyed by TDLib file id, which is what a media row already has to hand. */
    val active: StateFlow<Map<Int, Progress>> = _active.asStateFlow()

    /**
     * What the Downloads badge on the phone, TV and desktop bars shows: the downloads being fetched
     * right now. Waiting, held (no Wi-Fi, no connection), paused and failed ones are on the page but
     * are not "downloading", so they are not counted.
     */
    fun runningCount(rows: Map<Int, Progress>): Int = rows.values.count { it.stage == Stage.Running }

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
        // The screens hide Download on these, so this is the backstop for a path that forgot.
        if (!item.canBeSaved) {
            Logger.i(TAG, "Not downloading ${item.title}: its chat does not allow saving")
            return
        }
        // A video cut into byte pieces is kept as its pieces, every one, which the player joins
        // again from Downloads as it does from Telegram.
        if (item.isJoined) {
            item.pieces.filter { !isDownloading(it.fileId) }.forEach { piece ->
                start(runner, DownloadRequest.from(piece.copy(canBeSaved = item.canBeSaved), chatTitle))
            }
            return
        }
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
     *
     * Called again after each switch and sign in (HX88), and safe to call twice: it runs once for
     * each profile and set of rows ([rowsGeneration]), so a second call (the screen composing again)
     * changes nothing. Rows already in memory (a download asked before this ran) stay as they are
     * and the stored ones join them, never written over. Until it has run for the open profile
     * ([rowsOwner] null) nothing writes the queue, so a download asked early never replaces the
     * stored queue it has not read.
     */
    suspend fun restore(settings: SettingsStore, database: String? = DATABASE_OF_STORE, beforeMarkers: Boolean? = null) {
        val owner = Accounts.stamp
        val generation = rowsGeneration
        // Already read into these rows: the memory is newer than the store (a Cancel may not have
        // been written yet), so a second run would bring back what the viewer removed.
        if (synchronized(this) { restoredFor == owner to generation }) return
        // The database of the profile the rows will be, from its own folder (HX88).
        val marker = if (database == DATABASE_OF_STORE) Td.databaseMarkerFor(owner.folder) else database
        // The database could not be told: the stored queue stays as it is, unrestored and not
        // written over (the rows are nobody's, so nothing persists them), for a later start.
        if (marker == null) return
        val stored = runCatching { settings.downloadQueueNow() }.getOrDefault(emptyList())
        // A queue of another TDLib database (Sign in again, a sign out and in, an ended session):
        // its file ids are other files now. Dropped, never offered (HX88).
        val storedFor = runCatching { settings.downloadQueueDatabaseNow() }.getOrNull()
        val old = beforeMarkers ?: (database == DATABASE_OF_STORE && Td.databaseFromBeforeMarkers(owner.folder, marker))
        val usable = if (stored.isEmpty() || DatabaseMarker.matches(storedFor, marker, old)) stored else emptyList()
        val live = _active.value
        var order = -usable.size.toLong()
        val restored = usable.filter { it.fileId !in live }.mapNotNull { request ->
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
        afterStoredRead?.invoke()
        val merged = synchronized(this) {
            // Another profile or database opened while this read: its rows are not these.
            if (Accounts.stamp != owner || rowsGeneration != generation) return@synchronized false
            // A second restore of the same rows got here first (both passed the check above while
            // they read the store): its merge stands, and this one would bring back what the
            // viewer cancelled since.
            if (restoredFor == owner to generation) return@synchronized false
            // What is in memory now wins over what was stored under the same id.
            _active.value = restored + _active.value
            rowsOwner = owner
            restoredFor = owner to generation
            true
        }
        if (!merged) return
        // Through the one write path: under the lock, from the rows as they are now, and dropped when
        // they went meanwhile (a switch or a session end after the merge).
        afterMerge?.invoke()
        persistQueue(settings, database = marker, onlyFor = owner to generation)
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
        synchronized(this) {
            _active.update { it + (progress.fileId to progress) }
        }
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
        val REFUSED_ANYWHERE: String get() = L.downloadsRefusedAnywhere
    }
}

/**
 * Whether [failure], a failed row's sentence, says the drive was full: the downloads' own refusals
 * and TDLib's "no space left", in the language they were written in. A row that failed for room
 * offers Free up space before Try again, which would only find the same full drive.
 */
fun isNoSpaceFailure(failure: String?): Boolean {
    if (failure.isNullOrBlank()) return false
    val mark = "\u0000"
    val stems = listOf(
        com.tmplayer.i18n.L.downloadsNoSpace(mark),
        com.tmplayer.i18n.L.downloadsNoSpaceFolder(mark),
        com.tmplayer.i18n.L.downloadsNotEnoughSpace(mark),
    ).map { it.substringBefore(mark) }.filter { it.isNotBlank() }
    return failure == com.tmplayer.i18n.L.errorsNoSpace || stems.any { failure.startsWith(it) }
}
