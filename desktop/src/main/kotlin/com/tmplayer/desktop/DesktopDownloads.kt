package com.tmplayer.desktop

import com.tmplayer.data.AccountOwner
import com.tmplayer.data.AccountStamp
import com.tmplayer.data.Accounts
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DownloadStamps
import com.tmplayer.data.OwnedSessions
import com.tmplayer.data.ChatGate
import com.tmplayer.data.DownloadResults
import com.tmplayer.data.Notice
import com.tmplayer.data.Connectivity
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.DownloadYieldController
import com.tmplayer.data.Failures
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.errorMessage
import com.tmplayer.desktop.player.ActiveStreams
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The desktop's [DownloadRunner]: the queue is fetched by a coroutine in the app's own process.
 *
 * Android needs a foreground service so that a download outlives the app being put away; a desktop
 * app that is open is simply running, so the same queue is a scope and a list. One video at a time,
 * as on Android, for the same reason: bandwidth split three ways finishes all three late. The
 * unfinished part of the queue is written to the settings store as it changes, so the next launch
 * restores it paused through [OfflineDownloads.restore].
 *
 * A finished file does not stay in TDLib's cache, where any clean up could take it: it is moved
 * into the Downloads folder ([Stage.Moving][OfflineDownloads.Stage.Moving]), recorded in the
 * index with its new path, and TDLib is told to forget its copy. The move runs beside the queue
 * rather than in its one slot, because it may have to wait for a player to let the file go, and a
 * film being watched is no reason for the next download to sit idle.
 *
 * @param downloadsDir where finished files go for the profile on a folder name (null: the adopted
 *   one), asked each time, since a storage move changes it (HX88: the owner's own folder).
 * @param isOpen whether a player has the file open, and the move must wait.
 * @param disk free space where TDLib writes, and in the Downloads folder.
 */
class DesktopDownloadRunner(
    private val settings: SettingsStore,
    private val downloadsDir: (String?) -> File = { DesktopPaths.downloadsDirFor(it) },
    private val isOpen: (Int) -> Boolean = ActiveStreams::isOpen,
    private val cacheDisk: () -> DiskInfo = { DesktopPaths.disk() },
    private val downloadsDisk: (File) -> DiskInfo = { DiskInfo.of(it) },
    private val connectivity: Connectivity? = null,
) : DownloadRunner {

    /** Puts a held row back on the queue in its place. */
    private fun requeue(fileId: Int) {
        synchronized(lock) {
            if (!requests.containsKey(fileId)) {
                OfflineDownloads.active.value[fileId]?.let { requests[fileId] = it.request } ?: return
            }
            if (running.containsKey(fileId) || waiting.contains(fileId)) return
            waiting.addLast(fileId)
        }
        OfflineDownloads.stage(fileId, OfflineDownloads.Stage.Queued)
        pump()
    }

    /** The network is known to be down, so a stop is a wait rather than a failure. */
    private fun offline(): Boolean = connectivity?.status?.value == NetworkStatus.Offline

    /** Where progress and completion are shown outside the window. Set once the window exists. */
    @Volatile
    var notifier: TransferNotifier = NoTransferNotifier

    /** A download that reached the Downloads folder, for the window's own toast. */
    data class Finished(val title: String, val file: File)

    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 8)

    /** Every download as it lands in the Downloads folder. */
    val finished: SharedFlow<Finished> = _finished.asSharedFlow()

    private val _pausedAtLaunch = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * How many downloads a restore brought back paused and nobody has been told about yet (WP-46):
     * the window says "3 downloads were paused" with a Resume, then calls [noticeShown].
     */
    val pausedAtLaunch: kotlinx.coroutines.flow.StateFlow<Int> = _pausedAtLaunch

    fun noticeShown() {
        _pausedAtLaunch.value = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("DesktopDownloads"))
    private val lock = Any()

    /**
     * The profile each request was asked under and the rows it was queued in (HX88, review LOW 2),
     * taken when it is queued: a request asked before a switch never starts in the next profile,
     * whose database knows nothing of its file id. Guarded by [lock].
     */
    private val stamps = DownloadStamps()

    /** What a job of [id] runs as: its profile, and the set of rows it was queued in. Call under [lock]. */
    private fun ownerOf(id: Int) = AccountOwner(stamps.of(id)) + OfflineDownloads.Rows(stamps.rowsOf(id))

    /** A row update of a job: dropped once the rows it was queued in are gone (a switch, a sign out, an ended session). */
    private suspend inline fun ownRows(crossinline update: () -> Unit) {
        val rows = kotlinx.coroutines.currentCoroutineContext()[OfflineDownloads.Rows]?.generation
        OfflineDownloads.ifRows(rows) { update() }
    }

    /**
     * A call to the notifier as the work of the running job's profile (HX88, review P5): a transfer
     * begun, or a result never begun, belongs to it, not to the profile open by then.
     */
    private suspend inline fun asOwner(crossinline call: () -> Unit) {
        val owner = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp
        notifyAs(owner) { call() }
    }

    /**
     * Runs a call to [notifier] as the work of a profile; the desktop sets it to
     * `ProfileOutside.asOwner` with its notifier (this file is compiled for iOS too, which has no
     * such notifier, so it is not named here).
     */
    @Volatile
    var notifyAs: (AccountStamp?, () -> Unit) -> Unit = { _, call -> call() }

    /** The profile the running job works for; the open one for work with no owner. */
    private suspend fun ownerNow(): AccountStamp = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp

    private val requests = mutableMapOf<Int, DownloadRequest>()
    private val waiting = ArrayDeque<Int>()
    private val running = mutableMapOf<Int, Job>()

    /** Moves into the Downloads folder in flight, beside the queue. */
    private val moves = mutableMapOf<Int, Job>()

    /** File ids already asked about again. See [resourceAndRequeue]. */
    private val resourced = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var arrivals = 0L

    /**
     * Steps downloads aside for a video playing from the network ([DownloadYield]). Held rows are
     * in memory only, so a crash restores them paused like every other, never waiting for a
     * player that is gone.
     */
    private val yielding = DownloadYieldController(
        scope = scope,
        enabled = settings.pauseDownloadsWhilePlaying,
        hold = ::holdForPlayback,
        release = ::releaseFromPlayback,
    )

    init {
        yielding.start()
        followProfiles()
        // A download that stopped because the network did waits rather than failing, and goes
        // back on the queue by itself when the network returns: nobody asked for it to stop.
        connectivity?.let { net ->
            scope.launch {
                net.status.collect { status ->
                    if (status == NetworkStatus.Online) {
                        OfflineDownloads.ordered.filter { it.stage == OfflineDownloads.Stage.Offline }.forEach { requeue(it.fileId) }
                    }
                }
            }
        }
    }

    /**
     * A switch (HX88): the jobs of the profile left stop where they are (their queue is written for
     * the profile that asked) and what is in memory belongs to a database that is closed now.
     * Nothing of it is started, finished or written in the next one. A new database of the same
     * profile (a sign out, an ended session) does the same.
     */
    private fun followProfiles() {
        scope.launch { Accounts.activeFolder.drop(1).collect { stopForSwitch() } }
        scope.launch { OfflineDownloads.databaseChanges.collect { stopForSwitch() } }
    }

    private fun stopForSwitch() {
        val (jobs, ids) = synchronized(lock) {
            val all = running.values.toList() + moves.values.toList()
            val ids = (running.keys + moves.keys + waiting).toSet()
            running.clear()
            moves.clear()
            waiting.clear()
            requests.clear()
            stamps.clear()
            all to ids
        }
        // The ids asked about again were the last database's: the next one may need its own asked.
        resourced.clear()
        jobs.forEach { it.cancel() }
        ids.forEach {
            notifier.cancel(it.toLong())
            notifier.cancel(moveId(it))
        }
        if (jobs.isNotEmpty()) Logger.i(TAG, "A profile switch: ${jobs.size} download(s) of the last profile stopped")
    }

    override fun download(request: DownloadRequest) {
        val known = OfflineDownloads.active.value[request.fileId]
        // Already coming down or already waiting: pressing download twice is not two downloads.
        if (known != null && known.busy) return
        // Started again by hand: the viewer's decision, which carries on beside a playing video.
        if (known != null) yielding.exempt(request.fileId)
        val position = synchronized(lock) {
            adoptExistingQueue()
            if (running.containsKey(request.fileId) || waiting.contains(request.fileId)) return
            requests[request.fileId] = request
            // The profile and the rows it is asked in, now, not when the queue reaches it.
            stamps.queued(request.fileId)
            waiting.addLast(request.fileId)
            known?.order ?: arrivals++
        }
        OfflineDownloads.note(
            OfflineDownloads.Progress(
                request = request,
                downloadedBytes = known?.downloadedBytes ?: 0,
                totalBytes = request.sizeBytes,
                stage = OfflineDownloads.Stage.Queued,
                order = position,
            ),
        )
        persist()
        pump()
    }

    /** Stops the named downloads where they are, keeping the bytes, and says why on their rows. */
    private fun holdForPlayback(ids: List<Int>) {
        val jobs = ArrayList<Job>()
        val held = ArrayList<Int>()
        synchronized(lock) {
            for (id in ids) {
                val stage = OfflineDownloads.active.value[id]?.stage
                if (stage != OfflineDownloads.Stage.Running && stage != OfflineDownloads.Stage.Queued) continue
                waiting.remove(id)
                running.remove(id)?.let { jobs.add(it) }
                held.add(id)
            }
        }
        if (held.isEmpty()) return
        // The rows first, so a fetch that notices it was cancelled finds a row that is not its to
        // write over.
        held.forEach { OfflineDownloads.stage(it, OfflineDownloads.Stage.Yielding) }
        jobs.forEach { it.cancel() }
        held.forEach {
            Td.cancelDownloadInBackground(it)
            notifier.cancel(it.toLong())
        }
        pump()
    }

    /** The video is over, or paused for long: the held rows go back on the queue in their order. */
    private fun releaseFromPlayback(ids: List<Int>) {
        ids.mapNotNull { OfflineDownloads.active.value[it] }
            .filter { it.stage == OfflineDownloads.Stage.Yielding }
            .sortedBy { it.order }
            .forEach { row ->
                // Still no network: waiting for that instead, and the network's own watcher puts it back.
                if (offline()) OfflineDownloads.stage(row.fileId, OfflineDownloads.Stage.Offline) else requeue(row.fileId)
            }
    }

    override fun cancel(fileId: Int) {
        idsFor(fileId).forEach { id ->
            val (job, move) = synchronized(lock) {
                requests.remove(id)
                waiting.remove(id)
                running.remove(id) to moves.remove(id)
            }
            job?.cancel()
            // A move cancelled part way removes its part file and leaves the cache copy alone.
            move?.cancel()
            OfflineDownloads.forget(id)
            notifier.cancel(id.toLong())
            notifier.cancel(moveId(id))
            Td.cancelDownloadInBackground(id)
        }
        persist()
        pump()
    }

    /** TDLib has no pause: a cancellation that keeps the bytes, and a later fetch carries on. */
    override fun pause(fileId: Int) {
        // A move is not paused: it is a rename, or a copy that is over in moments, and a half moved
        // file is the one state worth never leaving behind.
        idsFor(fileId).filter {
            val row = OfflineDownloads.active.value[it]
            row?.busy == true && row.stage != OfflineDownloads.Stage.Moving
        }.forEach { id ->
            val job = synchronized(lock) {
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            Td.cancelDownloadInBackground(id)
            notifier.cancel(id.toLong())
            OfflineDownloads.stage(id, OfflineDownloads.Stage.Paused)
        }
        persist()
        pump()
    }

    /**
     * Brings back what an earlier run left: [OfflineDownloads.restore] for the unfinished part,
     * paused, and then the move for every video the last run finished fetching but had not got
     * into Downloads when it ended. Restore brings those back paused like the rest; a move needs no
     * connection and fetches nothing, so it is carried on at once rather than left for a Resume
     * nobody would think to press on a finished video.
     */
    suspend fun restore() {
        OfflineDownloads.restore(settings)
        var moved = 0
        val held = OfflineDownloads.ordered.count { !it.busy }
        for (row in OfflineDownloads.ordered) {
            if (row.busy) continue
            if (runCatching { Td.localFileAvailability(row.fileId) }.getOrNull() == LocalFileAvailability.Complete) {
                OfflineDownloads.resume(this, row.fileId)
                moved++
            }
        }
        // What is left paused is what the viewer is told about, once, with a Resume in the notice
        // (the finished ones above are carried on, not paused).
        _pausedAtLaunch.value = (held - moved).coerceAtLeast(0)
    }

    private fun idsFor(fileId: Int): List<Int> =
        if (fileId == OfflineDownloads.EVERYTHING) OfflineDownloads.active.value.keys.toList() else listOf(fileId)

    /** Takes on rows a restored queue left behind, so their order survives a resume. */
    private fun adoptExistingQueue() {
        for (progress in OfflineDownloads.ordered) {
            requests.getOrPut(progress.fileId) { progress.request }
            arrivals = maxOf(arrivals, progress.order + 1)
        }
    }

    private fun pump() {
        while (true) {
            val next = synchronized(lock) {
                if (running.size >= MAX_PARALLEL) return
                val id = waiting.removeFirstOrNull() ?: return
                // An id with no request behind it would otherwise sit reading "Queued" for ever.
                val request = requests[id] ?: run {
                    OfflineDownloads.forget(id)
                    return@synchronized null
                }
                // Asked under another profile or database than the open one: its file id means
                // nothing here, so it never starts. It is that profile's paused row again there.
                if (!stamps.startable(id)) {
                    requests.remove(id)
                    stamps.forget(id)
                    return@synchronized null
                }
                // As the work of the profile it was asked under (HX88): its writes never land in another.
                val job = scope.launch(ownerOf(id)) { fetch(request) }
                running[id] = job
                job.invokeOnCompletion {
                    synchronized(lock) { running.remove(id) }
                    pump()
                }
                id
            } ?: continue
            OfflineDownloads.stage(next, OfflineDownloads.Stage.Running)
        }
    }

    private suspend fun fetch(request: DownloadRequest) = coroutineScope {
        if (Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete) {
            withContext(NonCancellable) { startMove(request, kotlinx.coroutines.currentCoroutineContext()) }
            return@coroutineScope
        }

        // Room is checked before the request goes out: TDLib's own answer to a full disk is a
        // message written for a developer, and it arrives after most of the data has been spent.
        // A disk that reads as no size at all could not be measured, and is let through; one that
        // was measured and has nothing free is not.
        val landedAlready = Td.localDownloadedBytes(request.fileId)
        val stillToCome = (request.sizeBytes - landedAlready).coerceAtLeast(0)
        val disk = cacheDisk()
        if (stillToCome > 0 && disk.totalBytes > 0 && disk.freeBytes < stillToCome + CacheShelf.HEADROOM_BYTES) {
            val short = stillToCome + CacheShelf.HEADROOM_BYTES - disk.freeBytes
            withContext(NonCancellable) {
                fail(request, landedAlready, L.downloadsNoSpace(StreamStats.formatBytes(short)))
            }
            return@coroutineScope
        }

        // The client of the profile the download was queued under (HX88), never the next one's.
        val session = if (offline()) null else Td.sessionFor(ownerNow(), CONNECT_WAIT_MS)
        if (session == null) {
            withContext(NonCancellable) {
                if (offline()) {
                    hold(request)
                } else {
                    fail(request, Td.localDownloadedBytes(request.fileId), Failures.OFFLINE)
                }
            }
            return@coroutineScope
        }

        // TDLib will not fetch a file it cannot trace back to a message it has seen this session,
        // and a file id only means anything to the client that issued it: a queue restored from an
        // earlier run holds ids in exactly that state. Asking for the message again hands back a
        // current id. Once per id, so a video whose number keeps moving cannot become a loop.
        if (withContext(NonCancellable) { resourceAndRequeue(request) }) return@coroutineScope

        asOwner { notifier.begin(request.fileId.toLong(), TransferNotifier.Kind.Download, request.title) }
        val ticker = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val done = Td.localDownloadedBytes(request.fileId)
                ownRows { OfflineDownloads.sample(request.fileId, done) }
                val rate = OfflineDownloads.active.value[request.fileId]?.bytesPerSecond?.takeIf { it > 0 }
                notifier.progress(request.fileId.toLong(), done, request.sizeBytes.takeIf { it > 0 }, rate)
            }
        }

        var error: String? = null
        // TDLib allows one synchronous request per file: the player asking for the same video from
        // a seek position replaces this one. That is somebody else's request arriving, not this
        // download failing, so the request is made again.
        for (attempt in 1..MAX_TAKEOVERS) {
            val result = runCatching {
                session.client.downloadFile(
                    fileId = request.fileId,
                    priority = DOWNLOAD_PRIORITY,
                    offset = 0,
                    limit = 0,
                    synchronous = true,
                )
            }
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            error = result.exceptionOrNull()?.message ?: result.getOrNull()?.errorMessage
            if (error == null || !error.contains(TAKEN_OVER, ignoreCase = true)) break
            if (Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete) {
                error = null
                break
            }
            delay(TAKEOVER_BACKOFF_MS)
        }
        ticker.cancel()

        // The id went stale while this waited its turn, or Telegram's file reference expired under
        // it. Both are answered by asking for the message again rather than by a red row.
        if (error != null && Failures.needsFreshFileReference(error)) {
            if (withContext(NonCancellable) { resourceAndRequeue(request) }) return@coroutineScope
        }

        withContext(NonCancellable) {
            val complete = Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete
            if (error == null && complete) {
                notifier.cancel(request.fileId.toLong())
                startMove(request, kotlinx.coroutines.currentCoroutineContext())
            } else if (offline()) {
                hold(request)
            } else {
                Logger.w(TAG, "Download of ${request.title} did not finish: ${error ?: "incomplete"}")
                fail(
                    request,
                    Td.localDownloadedBytes(request.fileId),
                    // Gone from its chat (HX21): that, not TDLib's word for a file it lost.
                    com.tmplayer.data.GoneVideos.failureText(request.chatId, request.messageId)
                        ?: error?.let(Failures::humanise) ?: FAILED_TEXT,
                )
            }
        }
    }

    /**
     * Asks Telegram for the video's message again and moves the row onto the id it hands back,
     * at the head of the queue so it keeps its turn. True when the row moved, which means this
     * fetch is over; false when nothing changed or the message could not be reached, and the
     * fetch carries on with the id it has.
     */
    private suspend fun resourceAndRequeue(request: DownloadRequest): Boolean {
        if (request.chatId == 0L || request.messageId == 0L) return false
        if (!OwnedSessions.mayAct(ownerNow())) return false
        if (!resourced.add(request.fileId)) return false
        val fresh = runCatching { Td.refreshMedia(request.chatId, request.messageId) }.getOrNull()
        val id = fresh?.fileId ?: return false
        if (id <= 0 || id == request.fileId) return false
        Logger.i(TAG, "Re-sourced ${request.title}: file ${request.fileId} is now $id")
        val moved = request.copy(fileId = id, sizeBytes = fresh.sizeBytes.takeIf { it > 0 } ?: request.sizeBytes)
        resourced.add(id)
        val old = OfflineDownloads.active.value[request.fileId]
        val rows = kotlinx.coroutines.currentCoroutineContext()[OfflineDownloads.Rows]?.generation
        // Only into the rows it was queued in: after a switch or a sign out the id is another's.
        if (OfflineDownloads.ifRows(rows) { Unit } == null) return true
        synchronized(lock) {
            requests.remove(request.fileId)
            requests[id] = moved
            stamps.forget(request.fileId)
            stamps.queued(id)
            waiting.remove(id)
            waiting.addFirst(id)
        }
        ownRows { OfflineDownloads.forget(request.fileId) }
        val landed = Td.localDownloadedBytes(id)
        ownRows { OfflineDownloads.note(
            OfflineDownloads.Progress(
                request = moved,
                downloadedBytes = landed,
                totalBytes = moved.sizeBytes,
                stage = OfflineDownloads.Stage.Queued,
                order = old?.order ?: OfflineDownloads.nextOrder(),
            ),
        ) }
        persistNow()
        return true
    }

    /**
     * Hands a complete file to its own coroutine for the move into Downloads, so the queue's slot
     * is free for the next video while this one waits for a player or copies across drives. It
     * runs as the profile and rows of the fetch that called it ([fetchJob]), taken from that job,
     * never looked up again: a switch has cleared the stamps by then (HX88, review D1).
     */
    private fun startMove(request: DownloadRequest, fetchJob: kotlin.coroutines.CoroutineContext) {
        val context = moveContextOf(fetchJob) ?: run {
            Logger.i(TAG, "Not moving ${request.title}: its profile or its rows are no longer the open ones")
            return
        }
        synchronized(lock) {
            if (moves.containsKey(request.fileId)) return
            val job = scope.launch(context) { finished(request) }
            moves[request.fileId] = job
            job.invokeOnCompletion { synchronized(lock) { if (moves[request.fileId] === job) moves.remove(request.fileId) } }
        }
        OfflineDownloads.ifRows(context[OfflineDownloads.Rows]?.generation) { OfflineDownloads.moving(request.fileId) }
    }

    /** Waits for every move into Downloads now in flight; a storage move must not start under one. */
    suspend fun settleMoves() {
        while (true) {
            val pending = synchronized(lock) { moves.values.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    /**
     * The move step (B3.2): wait while a player has the file open, move it into the Downloads
     * folder, record it there, and tell TDLib its copy is gone.
     */
    private suspend fun finished(request: DownloadRequest) {
        val fileId = request.fileId
        // Its profile is no longer open (a switch since it was handed over): nothing is read from
        // the next profile's client, moved or written.
        val owner = ownerNow()
        if (!OwnedSessions.mayAct(owner)) return
        val path = Td.localFilePath(fileId)
        if (path == null) {
            fail(request, Td.localDownloadedBytes(fileId), FAILED_TEXT)
            return
        }
        // Moving a file a player is reading loses the picture, and on Windows is refused outright.
        if (isOpen(fileId)) {
            ownRows { OfflineDownloads.moving(fileId, heldByPlayer = true) }
            while (isOpen(fileId)) delay(HOLD_POLL_MS)
        }
        ownRows { OfflineDownloads.moving(fileId) }
        // Its profile is no longer open (a switch while it waited): nothing is moved or written.
        if (!OwnedSessions.mayAct(owner)) return

        // Asked again at the last moment: the chat may have turned on "restrict saving content"
        // since the download was queued. The file stays in TDLib's cache, counted there like any
        // played video so the cache rules evict it in their turn, and the row goes, since trying
        // again would only meet the same answer.
        if (!Td.maySave(request.chatId, request.messageId)) {
            Logger.i(TAG, "Not moving ${request.title} into Downloads: its chat does not allow saving")
            withContext(NonCancellable) {
                runCatching { settings.rememberCachedVideo(request.item(), request.chatTitle) }
                synchronized(lock) { requests.remove(fileId) }
                ownRows { OfflineDownloads.forget(fileId) }
                persistNow()
                asOwner { notifier.fail(fileId.toLong(), request.title, ContentProtection.NOT_SAVABLE, retryable = false) }
            }
            return
        }

        val src = File(path)
        // The owner's own downloads folder, from its folder name (invariant 4), never the open one's.
        val dir = downloadsDir(owner.folder)
        if (!DownloadFiles.sameStore(src, dir)) {
            val room = downloadsDisk(dir)
            val needed = src.length() + CacheShelf.HEADROOM_BYTES
            if (room.totalBytes > 0 && room.freeBytes < needed) {
                fail(
                    request,
                    src.length(),
                    L.downloadsNoSpaceFolder(StreamStats.formatBytes(needed - room.freeBytes)),
                )
                return
            }
        }

        val moveId = moveId(fileId)
        val rowsOfJob = kotlinx.coroutines.currentCoroutineContext()[OfflineDownloads.Rows]?.generation
        asOwner { notifier.begin(moveId, TransferNotifier.Kind.MoveToDownloads, request.title) }
        val target = try {
            DownloadFiles.moveIntoDownloads(src, dir, DownloadFiles.safeName(request.title, request.fileName)) { done, total ->
                OfflineDownloads.ifRows(rowsOfJob) { OfflineDownloads.moving(fileId, movedBytes = done) }
                notifier.progress(moveId, done, total, null)
            }
        } catch (e: CancellationException) {
            notifier.cancel(moveId)
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "Could not move ${request.title} into Downloads", e)
            notifier.cancel(moveId)
            fail(request, src.length(), MOVE_FAILED_TEXT)
            return
        }

        // The file is in the owner's folder now: its record is written for the owner even after a
        // switch ([com.tmplayer.data.AccountAddress]), so it never points at a copy that is gone.
        withContext(NonCancellable + com.tmplayer.data.AccountAddress(owner.folder)) {
            settings.noteDownload(request.item(), request.chatTitle, target.absolutePath)
        }
        withContext(NonCancellable) {
            com.tmplayer.data.SupportReminder.downloadFinished()
            // It belongs to the viewer now: no cache rule may count it, and TDLib, whose copy has
            // just left its directory, is told so instead of finding out on the next stream. Only
            // while its profile's client is the live one (invariant 3).
            runCatching { settings.forgetCachedVideo(request.chatId, request.messageId) }
            if (OwnedSessions.mayAct(owner)) runCatching { Td.deleteFile(fileId) }
            synchronized(lock) {
                requests.remove(fileId)
                stamps.forget(fileId)
            }
            ownRows { OfflineDownloads.forget(fileId) }
            persistNow()
            settled(request, ok = true, notifierId = moveId, reason = null, target = target)
            _finished.tryEmit(Finished(request.title, target))
        }
    }

    /** Holds a running row until the network is back, keeping what landed. */
    private suspend fun hold(request: DownloadRequest) {
        val row = OfflineDownloads.active.value[request.fileId] ?: return
        if (row.stage != OfflineDownloads.Stage.Running) return
        val landed = Td.localDownloadedBytes(request.fileId)
        ownRows {
            OfflineDownloads.note(row.copy(downloadedBytes = landed, stage = OfflineDownloads.Stage.Offline, bytesPerSecond = 0))
        }
        notifier.cancel(request.fileId.toLong())
        persistNow()
    }

    /** Writes a failure only while this runner still owns the row: a pause or cancel may have won. */
    private suspend fun fail(request: DownloadRequest, landed: Long, text: String) {
        val row = OfflineDownloads.active.value[request.fileId]
        if (row != null && (row.stage == OfflineDownloads.Stage.Running || row.stage == OfflineDownloads.Stage.Moving)) {
            val rows = kotlinx.coroutines.currentCoroutineContext()[OfflineDownloads.Rows]?.generation
            // A late failure of a job whose rows are gone (a switch, a sign out) is no one's to show.
            OfflineDownloads.ifRows(rows) {
                OfflineDownloads.note(
                    row.copy(
                        downloadedBytes = landed,
                        stage = OfflineDownloads.Stage.Failed,
                        failure = text,
                        bytesPerSecond = 0,
                        heldByPlayer = false,
                    ),
                )
            } ?: return
            settled(request, ok = false, notifierId = request.fileId.toLong(), reason = text, target = null)
        }
        persistNow()
    }

    /**
     * One video has finished or failed for good. Once nothing else of its season or cut video is
     * on the queue, the one notification for all of it goes out ([DownloadResults], CP76), behind
     * the "Finished downloads" switch; until then, and with the switch off, its own line just goes.
     * A lone video keeps the wording and the Open of before; with the kids lock hiding its chat,
     * or the app lock asked for on opening, it names nothing.
     */
    private suspend fun settled(request: DownloadRequest, ok: Boolean, notifierId: Long, reason: String?, target: File?) {
        val outstanding = OfflineDownloads.ordered
            .filter { it.fileId != request.fileId && it.stage != OfflineDownloads.Stage.Failed }
            .map { it.request }
        val result = DownloadResults.record(request, ok, outstanding)
        if (result == null || !runCatching { settings.noticeOnNow(Notice.Downloads) }.getOrDefault(true)) {
            notifier.cancel(notifierId)
            return
        }
        val private = runCatching {
            DownloadResults.isPrivate(request.chatId, ChatGate.awaitReady(), settings.appLock.first())
        }.getOrDefault(false)
        val words = DownloadResults.words(result, private, reason.takeIf { result.total == 1 })
        asOwner { when {
            result.failed.isNotEmpty() -> notifier.fail(notifierId, words.title, words.body.orEmpty(), retryable = true)
            result.total == 1 && target != null && !private -> notifier.complete(
                notifierId,
                L.downloadsDownloaded,
                L.downloadsIsInDownloads(request.title),
                TransferNotifier.OpenTarget.File(target.absolutePath),
            )
            else -> notifier.complete(notifierId, words.title, words.body.orEmpty(), TransferNotifier.OpenTarget.DownloadsScreen)
        } }
    }

    private fun persist() {
        scope.launch { persistNow() }
    }

    /**
     * The one write path of the queue (HX88): [OfflineDownloads.persistQueue] reads the rows and
     * the profile they belong to together, writes for that owner only, and re-checks the rows
     * generation inside the edit, so a persist that straddles a switch or a session end can never
     * tag one database's rows with another's marker or write into the next profile's queue.
     */
    private suspend fun persistNow() = OfflineDownloads.persistQueue(settings)

    private companion object {
        const val TAG = "DesktopDownloads"
        const val MAX_PARALLEL = 1
        const val DOWNLOAD_PRIORITY = 32
        const val PROGRESS_INTERVAL_MS = 1_000L
        const val CONNECT_WAIT_MS = 120_000L
        const val MAX_TAKEOVERS = 60
        const val TAKEOVER_BACKOFF_MS = 2_000L
        const val TAKEN_OVER = "Canceled by another downloadFile"
        val FAILED_TEXT: String get() = L.downloadsDidNotFinish
        val MOVE_FAILED_TEXT: String get() = L.downloadsMoveFailedVideo
        const val HOLD_POLL_MS = 1_000L

        /** The notifier id of a move, apart from the download's own so the two never collide. */
        fun moveId(fileId: Int): Long = (1L shl 33) + fileId
    }
}

/**
 * What the move of a finished download runs as (HX88, review D1): the owner and the rows of the
 * fetch job that finished it ([fetchJob]), never looked up again by its file id, since a switch
 * clears the runner's stamps and a lookup would record the profile open at that moment. Null when
 * the job carries no owner or its rows are no longer the open ones ([rowsNow]: a switch, a sign
 * out, an ended session): the move does not start and the file stays where its profile fetched it.
 */
internal fun moveContextOf(
    fetchJob: kotlin.coroutines.CoroutineContext,
    rowsNow: () -> Long = { OfflineDownloads.rowsGeneration },
): kotlin.coroutines.CoroutineContext? {
    val owner = fetchJob[AccountOwner] ?: return null
    val rows = fetchJob[OfflineDownloads.Rows] ?: return null
    if (rows.generation != rowsNow()) return null
    return AccountOwner(owner.stamp) + OfflineDownloads.Rows(rows.generation)
}
