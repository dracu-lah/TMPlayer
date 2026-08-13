package com.tmplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches videos the viewer asked to keep, with the app closed if need be, one at a time.
 *
 * The app had no downloads in this sense at all: playing a video filled the cache with it and
 * leaving the player cancelled whatever had not arrived, which is right for a television that
 * watches one thing at a time and wrong for somebody filling a phone before a train. A foreground
 * service is what Android gives for work the viewer started and expects to finish while they are
 * somewhere else, and the notification it must post is the same thing they will look at to see how
 * far it has come.
 *
 * It is a queue rather than a fan-out. Three videos fetched at once share one connection three ways
 * and all three land at the end; fetched in turn, the first is watchable while the third is still
 * coming, and the figures on the notification mean something because they describe one file. So
 * [MAX_PARALLEL] is one, the rest wait their turn where the viewer can see them, and the order they
 * were ticked in is the order they arrive in.
 *
 * TDLib does the fetching; this only asks, watches and reports. The synchronous form of
 * `downloadFile` returns when the whole file has landed, so the progress loop beside it exists
 * purely to move the notification along.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Everything the service is looking after, and which of it is moving.
     *
     * All three are read and written from both the main thread, where the intents arrive, and an
     * IO thread, where a fetch finishes, so every touch of them is inside [lock]. They are the
     * bookkeeping only: what the viewer sees is [OfflineDownloads], written in step with these.
     */
    private val lock = Any()
    private val requests = LinkedHashMap<Int, DownloadRequest>()
    private val waiting = ArrayDeque<Int>()
    private val running = LinkedHashMap<Int, Job>()

    /** Counts requests in, so a row keeps the place in the list it was given when it was asked for. */
    private var arrivals = 0L

    /**
     * Why the connection cannot carry a download at this moment, or null when it can.
     *
     * Kept here as well as acted on, because a video ticked while the phone is in a tunnel has to
     * arrive on the list already waiting for a signal. Without it the new row went straight onto
     * the queue and was started by the next pump, which then failed for the reason the app already
     * knew about.
     */
    @Volatile
    private var holdReason: OfflineDownloads.Stage? = null

    /** What each video's own notification last said, so an unchanged one is not posted again. */
    private val lines = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** File ids this service has already asked Telegram about. See [resourceAndRequeue]. */
    private val resourced = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * The last start this service was given, so stopping it cannot swallow the next one.
     *
     * `stopSelf()` with no argument stops the service even if an intent arrived while the decision
     * to stop was being made, which here is a download the viewer just asked for being dropped on
     * the floor. The numbered form stops it only if no newer start has come in, and Android hands
     * back the number for exactly this.
     */
    private var lastStartId = 0

    /**
     * True from the moment the ongoing notification has been taken down, so nothing puts it back.
     *
     * This is the whole of a bug that outlived every download it was about. The last video of a
     * queue finishing calls [done], which pumps, finds nothing left, takes the notification down
     * and stops the service, and then calls [refresh], which posts the summary again. What went up
     * was an ongoing notification with no service behind it: nothing owned it, so nothing took it
     * away, and swiping does not dismiss an ongoing one. It sat there reading "Finishing up" with
     * a bar that never moved. Pressing Cancel made it worse rather than better, because that is
     * another start command, and the same sequence ran again and put it straight back.
     *
     * Ordering alone would not fix it: [stopSelf] is a request, not a return, and refreshes can
     * arrive from the network watcher or a fetch's last breath after the decision to stop. So the
     * decision is recorded, and every path that would draw the summary checks it first.
     */
    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
        // A service restarted into an app process that still holds a queue (the activity was
        // recreated, or restore() ran at launch) adopts it rather than starting from nothing.
        adoptExistingQueue()
        // Started here as well as by the activity: the service can outlive every screen, and a
        // download is exactly the thing that wants to know about the signal after the app is gone.
        NetworkMonitor.start(this)
        watchTheNetwork()
    }

    /**
     * Holds the queue when the connection cannot carry it, and puts it back when it can.
     *
     * Two things stop a download that the viewer has not stopped, and both used to end the same
     * way: badly. A phone walking into a tunnel produced a row reading "The download did not
     * finish. Try again", which describes what happened and not what to do about it, since the
     * answer is to wait and the app can do that itself. Mobile data was worse than that: the
     * setting "Only download over Wi-Fi" was obeyed by the player and by nothing else, so a viewer
     * who had turned it on and ticked three films paid for three films.
     *
     * Neither is a failure, so neither is written down as one. TDLib keeps the partial file, so
     * what resumes is the rest of the video and not the whole of it again.
     */
    private fun watchTheNetwork() {
        scope.launch {
            val wifiOnly = SettingsStore(applicationContext).wifiOnlyDownloads
            combine(
                NetworkMonitor.status,
                NetworkMonitor.metered,
                wifiOnly,
            ) { status, metered, onlyOverWifi ->
                when {
                    status == NetworkStatus.Offline -> OfflineDownloads.Stage.Offline
                    onlyOverWifi && metered -> OfflineDownloads.Stage.NoWifi
                    else -> null
                }
            }
                // Only the edges. A stream of capability changes on a connection that stayed
                // usable must not keep re-queueing a download that is running perfectly well.
                .distinctUntilChanged()
                .collect { reason ->
                    holdReason = reason
                    if (reason == null) releaseWhenUsable() else hold(reason)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start means this service is wanted again, whatever the last one decided.
        stopping = false
        lastStartId = startId
        // Android gives a few seconds from startForegroundService to this call, whatever the
        // intent turns out to be, so the notification goes up before anything else is decided.
        if (!goToForeground(ongoing())) {
            // Only a download carries a whole request. A Cancel intent carries a file id and
            // nothing else, and building a row out of that would put a nameless failed video on
            // the screen in answer to somebody pressing Cancel.
            val asked = intent
                ?.takeIf { it.action == ACTION_DOWNLOAD }
                ?.let(DownloadRequest::from)
            refuseEverything(asked)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_DOWNLOAD -> enqueue(DownloadRequest.from(intent))
            ACTION_CANCEL -> cancel(intent.fileId())
            ACTION_PAUSE -> pause(intent.fileId())
            ACTION_RESUME -> resume(intent.fileId())
            else -> Unit
        }
        pump()
        refresh()
        // Not sticky: a process the system killed should not silently start fetching a gigabyte
        // again with nobody watching. The partial file is kept, the queue is on disk, and the
        // Downloads screen offers to carry on.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Nothing draws the summary from here on, whichever thread is part way through something.
        stopping = true
        runCatching { notifier().cancel(SUMMARY_ID) }
        // Whatever was moving has stopped moving, and the rows have to say so: a service torn down
        // by the system leaving rows reading "downloading" is the one lie this screen can tell.
        val stopped = synchronized(lock) {
            val ids = running.keys.toList() + waiting.toList()
            running.values.forEach { it.cancel() }
            running.clear()
            waiting.clear()
            ids
        }
        stopped.forEach { OfflineDownloads.stage(it, OfflineDownloads.Stage.Paused) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- the queue ---------------------------------------------------------------------------

    /** Takes on rows a previous life of this service, or [OfflineDownloads.restore], left behind. */
    private fun adoptExistingQueue() = synchronized(lock) {
        for (progress in OfflineDownloads.ordered) {
            requests[progress.fileId] = progress.request
            arrivals = maxOf(arrivals, progress.order + 1)
        }
    }

    private fun enqueue(request: DownloadRequest?) {
        if (request == null) return
        val known = OfflineDownloads.active.value[request.fileId]
        // Already coming down or already waiting: pressing download twice is not two downloads.
        // Paused and failed rows do fall through here, which is what makes Resume and Try again
        // the same intent as the original press.
        if (known != null && known.busy) return

        // A video asked for while the phone has no signal, or has only the mobile data the viewer
        // said not to use, joins the list already waiting for that to change rather than being
        // started and failing for a reason the app knew before it began.
        val held = holdReason
        val position = synchronized(lock) {
            if (running.containsKey(request.fileId) || waiting.contains(request.fileId)) return
            requests[request.fileId] = request
            if (held == null) waiting.addLast(request.fileId)
            known?.order ?: arrivals++
        }
        OfflineDownloads.note(
            OfflineDownloads.Progress(
                request = request,
                // What an earlier attempt already put on disk, so a resumed row does not restart
                // its bar at zero for bytes that are already here.
                downloadedBytes = known?.downloadedBytes ?: 0,
                totalBytes = request.sizeBytes,
                stage = held ?: OfflineDownloads.Stage.Queued,
                order = position,
            ),
        )
        persist()
    }

    /**
     * Stops a download and forgets it: the row goes.
     *
     * A file id of [EVERYTHING] means the notification's "Cancel all", which is what the button
     * says when more than one video is on the list and there is no room on it to ask which.
     */
    private fun cancel(fileId: Int) {
        val doomed = idsFor(fileId) { true }
        doomed.forEach { id ->
            val job = synchronized(lock) {
                requests.remove(id)
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            OfflineDownloads.forget(id)
            Td.cancelDownloadInBackground(id)
            notifier().cancel(id.notificationId())
        }
        persist()
    }

    /**
     * Holds a download where it is, keeping the bytes that landed.
     *
     * TDLib has no pause: what it has is a cancellation that leaves the partial file alone, and a
     * later `downloadFile` for the same id that carries on from it. That is the whole of the
     * mechanism, and it is why resuming a paused video is the same call as starting it.
     */
    private fun pause(fileId: Int) {
        val held = idsFor(fileId) { it.busy }
        held.forEach { id ->
            val job = synchronized(lock) {
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            Td.cancelDownloadInBackground(id)
            OfflineDownloads.stage(id, OfflineDownloads.Stage.Paused)
        }
    }

    /**
     * Stops everything that was moving and says which of the two reasons it is waiting on.
     *
     * The order is kept, so the video that was half way down comes back first rather than the
     * queue being reshuffled by an event that had nothing to do with the viewer. A row the viewer
     * paused is left alone: they said not now, and a connection changing does not unsay it.
     */
    private fun hold(reason: OfflineDownloads.Stage) {
        val moving = OfflineDownloads.ordered
            .filter {
                it.stage == OfflineDownloads.Stage.Running ||
                    it.stage == OfflineDownloads.Stage.Queued ||
                    // The other waiting state: cellular coming back after a tunnel, with Wi-Fi only
                    // on, moves a row from one kind of waiting to the other rather than starting it.
                    (it.busy && it.stage != reason)
            }
            .map { it.fileId }
        if (moving.isEmpty()) return
        Log.i(TAG, "Holding ${moving.size} download(s): $reason")
        moving.forEach { id ->
            val job = synchronized(lock) {
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            Td.cancelDownloadInBackground(id)
            OfflineDownloads.stage(id, reason)
        }
        refresh()
    }

    /** The connection can carry them again: everything that was only waiting on it goes back on. */
    private fun releaseWhenUsable() {
        val held = OfflineDownloads.ordered
            .filter {
                it.stage == OfflineDownloads.Stage.Offline || it.stage == OfflineDownloads.Stage.NoWifi
            }
            .sortedBy { it.order }
        if (held.isEmpty()) return
        Log.i(TAG, "Connection usable: resuming ${held.size} download(s)")
        held.forEach { progress ->
            synchronized(lock) {
                if (running.containsKey(progress.fileId) || waiting.contains(progress.fileId)) return@forEach
                requests[progress.fileId] = progress.request
                waiting.addLast(progress.fileId)
            }
            OfflineDownloads.stage(progress.fileId, OfflineDownloads.Stage.Queued)
        }
        pump()
        refresh()
    }

    /** Puts held rows back on the end of the queue, oldest first, in the order they were asked for. */
    private fun resume(fileId: Int) {
        val held = idsFor(fileId) { !it.busy }
        held.sortedBy { OfflineDownloads.active.value[it]?.order ?: 0 }
            .forEach { id -> enqueue(OfflineDownloads.active.value[id]?.request) }
    }

    /**
     * Which rows an action means: the one it named, or every row that [wanted] accepts.
     *
     * The notification's buttons carry [EVERYTHING] because they have room for one button and no
     * room to say which video it would act on. A button that silently picked whichever download
     * happened to be first would be worse than no button.
     */
    private inline fun idsFor(fileId: Int, wanted: (OfflineDownloads.Progress) -> Boolean): List<Int> =
        if (fileId == EVERYTHING) {
            OfflineDownloads.ordered.filter(wanted).map { it.fileId }
        } else {
            listOfNotNull(OfflineDownloads.active.value[fileId]?.takeIf(wanted)?.fileId)
        }

    /** Starts as many as [MAX_PARALLEL] allows, and stops the service when there is nothing left. */
    private fun pump() {
        while (true) {
            // An id that reached the front of the queue with no request behind it. It has been
            // taken off `waiting` by now, so without this it would leave a row on the Downloads
            // screen reading "Queued" that nothing would ever start, and the same pass would then
            // decide the queue was empty and stop the service.
            var stale = 0
            val next = synchronized(lock) {
                if (running.size >= MAX_PARALLEL) return@synchronized null
                val id = waiting.removeFirstOrNull() ?: return@synchronized null
                val request = requests[id]
                if (request == null) {
                    stale = id
                    return@synchronized null
                }
                val job = scope.launch { fetch(request) }
                running[id] = job
                // Registered before the job can finish, so a fetch that fails immediately still
                // finds its own entry to clear and still pumps whatever is behind it.
                job.invokeOnCompletion { done(id) }
                id
            }
            if (stale != 0) {
                OfflineDownloads.forget(stale)
                continue
            }
            if (next == null) break
            OfflineDownloads.stage(next, OfflineDownloads.Stage.Running)
        }
        stopWhenIdle()
    }

    /** One fetch has ended, whatever the reason. The next one starts and the shade is redrawn. */
    private fun done(fileId: Int) {
        synchronized(lock) { running.remove(fileId) }
        // Drawn before the queue is pumped, not after. Pumping is what discovers there is nothing
        // left and takes the notification down, and anything drawn after that would be putting it
        // back with no service behind it.
        refresh()
        pump()
    }

    private suspend fun fetch(request: DownloadRequest) = coroutineScope {
        // Already here in full. A video can finish between being ticked and reaching the front of
        // the queue: the viewer played it while it waited, or an earlier attempt had all but the
        // last block. Asking TDLib for a file it already has is a round trip to be told nothing,
        // and the row belongs in the finished list either way.
        if (Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete) {
            withContext(NonCancellable) {
                SettingsStore(applicationContext).noteDownload(request.item(), request.chatTitle)
                synchronized(lock) { requests.remove(request.fileId) }
                OfflineDownloads.forget(request.fileId)
                persistNow()
            }
            return@coroutineScope
        }

        // Room is checked again here, and not only when the video was ticked. A queue outlives the
        // plan it was made with: the film behind two others no longer fits if the viewer watched
        // something large while it waited, or ticked a second batch, or filled the phone with
        // photographs. Asked before the request goes out, because TDLib's own answer to a full
        // disk is a message written for a developer, and by the time it arrives the download has
        // spent the viewer's data getting most of the way there.
        val landedAlready = Td.localDownloadedBytes(request.fileId)
        val stillToCome = (request.sizeBytes - landedAlready).coerceAtLeast(0)
        val free = DiskSpace.read(applicationContext).freeBytes
        if (stillToCome > 0 && free < stillToCome + CacheShelf.HEADROOM_BYTES) {
            withContext(NonCancellable) {
                val short = stillToCome + CacheShelf.HEADROOM_BYTES - free
                Log.w(TAG, "No room for ${request.title}: ${StreamStats.formatBytes(short)} short")
                val row = OfflineDownloads.active.value[request.fileId]
                if (row != null) {
                    OfflineDownloads.note(
                        row.copy(
                            downloadedBytes = landedAlready,
                            stage = OfflineDownloads.Stage.Failed,
                            failure = "Not enough space: ${StreamStats.formatBytes(short)} short",
                            bytesPerSecond = 0,
                        ),
                    )
                    notifier().notify(request.fileId.notificationId(), failedNotification(request))
                }
                persistNow()
            }
            return@coroutineScope
        }

        // Bounded, where it used to wait for ever. The row is already marked as running by the time
        // this line is reached, so a TDLib that never gets a socket left the viewer looking at a
        // video that claimed to be downloading, sat at zero bytes, with no error and nothing to
        // press: the single most confusing thing this screen can show. Giving up says so instead,
        // and Try again is one press away.
        val session = Td.awaitConnectedSessionOrNull(CONNECT_WAIT_MS)
        if (session == null) {
            withContext(NonCancellable) {
                Log.w(TAG, "No connection to Telegram for ${request.title}")
                val waitingInstead = holdReason
                if (waitingInstead != null) {
                    OfflineDownloads.stage(request.fileId, waitingInstead)
                } else {
                    writeFailure(request, Td.localDownloadedBytes(request.fileId), Failures.OFFLINE)
                }
                persistNow()
            }
            return@coroutineScope
        }

        // TDLib will not fetch a file it cannot trace back to a message it has seen this session,
        // and a file id only means anything to the run of the client that issued it. A queue
        // written down before the process was killed comes back holding ids in exactly that state,
        // so the request failed the moment it was made and Try again failed the same way for ever.
        // Asking Telegram for the message again hands back a current id and gives TDLib the source
        // it wanted. Once per id, so a video whose number keeps moving cannot become a loop.
        if (resourceAndRequeue(request)) return@coroutineScope

        // Watches the same file TDLib is filling, only so the notification and the row have
        // something to move. The download itself is the call below. A child of this coroutine, so
        // pausing or cancelling the fetch takes the ticker with it rather than leaving it running
        // against a download that has stopped.
        val ticker = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val done = Td.localDownloadedBytes(request.fileId)
                OfflineDownloads.sample(request.fileId, done)
                refresh()
            }
        }

        var error: String? = null
        // TDLib allows one synchronous request per file: the player asking for the same video from
        // a seek position replaces this one and answers it with "Canceled by another downloadFile
        // request". That is somebody else's request arriving, not this download failing, and it
        // happened for real on the phone the moment the viewer opened the video they had just put
        // on to download. So the request is simply made again, and the fetch survives being watched.
        for (attempt in 1..MAX_TAKEOVERS) {
            val result = runCatching {
                session.client.downloadFile(
                    fileId = request.fileId,
                    priority = DOWNLOAD_PRIORITY,
                    offset = 0,
                    // Zero means the whole file, which is the entire point of this service.
                    limit = 0,
                    synchronous = true,
                )
            }
            // runCatching catches everything, cancellation included, and a paused download reported
            // as a failure would put back the row the viewer just held. Rethrown here, so pausing
            // and cancelling leave through this function rather than being written down as errors.
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            error = result.exceptionOrNull()?.message ?: result.getOrNull()?.errorMessage
            if (error == null || !error.contains(TAKEN_OVER, ignoreCase = true)) break
            if (Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete) {
                error = null
                break
            }
            Log.i(TAG, "Another request took over ${request.title}; asking again ($attempt)")
            // A pause, so a viewer scrubbing through the video is not raced press for press.
            delay(TAKEOVER_BACKOFF_MS)
        }
        ticker.cancel()

        // The id went stale while this was waiting its turn, or Telegram's file reference expired
        // under it. Both are answered by asking for the message again rather than by a red row.
        if (error != null && Failures.needsFreshFileReference(error)) {
            var moved = false
            withContext(NonCancellable) { moved = resourceAndRequeue(request) }
            if (moved) return@coroutineScope
        }

        // Uncancellable from here down, and that includes asking TDLib what actually landed: these
        // are the lines that decide what the row says and whether the video is listed as
        // downloaded. A pause arriving in the same instant the last byte did would otherwise leave
        // a complete file on the disk with nothing anywhere claiming it.
        withContext(NonCancellable) {
            val complete = Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete
            val landed = Td.localDownloadedBytes(request.fileId)
            if (error != null || !complete) {
                Log.w(TAG, "Download of ${request.title} did not finish: ${error ?: "incomplete"}")
                // Only if the row is still this fetch's to write. A cancel that arrived a moment
                // ago has already taken it away, and a pause has already given it a state; putting
                // a failure over either would resurrect a row the viewer dismissed or contradict
                // the button they just pressed.
                val row = OfflineDownloads.active.value[request.fileId]
                if (row != null && row.stage == OfflineDownloads.Stage.Running) {
                    // A download that stopped because the connection did is not a failure the
                    // viewer has to do anything about, whichever noticed first: the request coming
                    // back with an error, or the connectivity callback. It waits, like every other
                    // held row, and the watcher above puts it back.
                    val waitingInstead = holdReason
                    OfflineDownloads.note(
                        row.copy(
                            downloadedBytes = landed,
                            stage = waitingInstead ?: OfflineDownloads.Stage.Failed,
                            failure = if (waitingInstead != null) {
                                null
                            } else {
                                error?.let(Failures::humanise) ?: FAILED_TEXT
                            },
                            bytesPerSecond = 0,
                        ),
                    )
                    if (waitingInstead == null) {
                        notifier().notify(request.fileId.notificationId(), failedNotification(request))
                    }
                }
            } else {
                // Only a finished file is written to the Downloads list. A half of a film is not
                // something to offer somebody on a train as though it were there.
                SettingsStore(applicationContext).noteDownload(request.item(), request.chatTitle)
                synchronized(lock) { requests.remove(request.fileId) }
                OfflineDownloads.forget(request.fileId)
                notifier().notify(request.fileId.notificationId(), doneNotification(request))
            }
            persistNow()
        }
    }

    /**
     * Asks Telegram for the video's message again, and moves the row onto the id it hands back.
     *
     * True when the row moved, which means this fetch is finished with: the old id is gone from
     * every list, the new one is on the queue in the same place, and the pump behind [done] starts
     * it. False when nothing needed to change, or when the message could not be reached, in which
     * case the caller carries on with the id it has: Telegram being slow is not a reason to give up
     * on a download that may well work.
     *
     * Each id is re-sourced once. A video whose number moves every time it is asked for would
     * otherwise be a queue that fetches nothing and never stops trying.
     */
    private suspend fun resourceAndRequeue(request: DownloadRequest): Boolean {
        if (request.chatId == 0L || request.messageId == 0L) return false
        if (!resourced.add(request.fileId)) return false
        val fresh = runCatching { Td.refreshMedia(request.chatId, request.messageId) }.getOrNull()
        val id = fresh?.fileId ?: return false
        if (id <= 0 || id == request.fileId) return false
        Log.i(TAG, "Re-sourced ${request.title}: file ${request.fileId} is now $id")
        val moved = request.copy(
            fileId = id,
            // The old row's size came from the same message, but a re-fetched one is the newer
            // answer and the bar is drawn against it.
            sizeBytes = fresh.sizeBytes.takeIf { it > 0 } ?: request.sizeBytes,
        )
        synchronized(lock) { requests.remove(request.fileId) }
        OfflineDownloads.forget(request.fileId)
        lines.remove(request.fileId)
        runCatching { notifier().cancel(request.fileId.notificationId()) }
        enqueue(moved)
        persistNow()
        return true
    }

    /** The one way a row is written down as failed: only when this fetch still owns it. */
    private fun writeFailure(request: DownloadRequest, landed: Long, text: String) {
        val row = OfflineDownloads.active.value[request.fileId] ?: return
        if (row.stage != OfflineDownloads.Stage.Running) return
        OfflineDownloads.note(
            row.copy(
                downloadedBytes = landed,
                stage = OfflineDownloads.Stage.Failed,
                failure = text,
                bytesPerSecond = 0,
            ),
        )
        runCatching { notifier().notify(request.fileId.notificationId(), failedNotification(request)) }
    }

    /**
     * Writes the unfinished queue down, so a crash or a kill comes back to the same list.
     *
     * Failed rows are written too. "Try again" on a row that a flat tunnel killed is the most
     * likely next press there is, and losing the row means losing the only thing that knows which
     * video it was.
     */
    private fun persist() {
        scope.launch { persistNow() }
    }

    private suspend fun persistNow() {
        val queued = OfflineDownloads.ordered.map { it.request }
        runCatching { SettingsStore(applicationContext).saveDownloadQueue(queued) }
    }

    /**
     * Stops the service once nothing is moving and nothing is waiting on the world to change.
     *
     * A paused download does not hold it up. Pausing means the viewer said not now, and leaving a
     * permanent notification in the shade for something nobody is doing is the app talking over
     * them: the row is on the Downloads screen, which is where they paused it from and where they
     * will press Resume. Failed rows do not hold it either, since they post a notification of
     * their own that outlives the service.
     *
     * Rows waiting for a signal are the exception, and the only one. Nothing else is going to put
     * them back when the signal returns, so the service that will do it has to still be here, and
     * its notification is then telling the truth: this app is waiting on your connection.
     */
    private fun stopWhenIdle() {
        synchronized(lock) {
            if (running.isNotEmpty() || waiting.isNotEmpty()) return
        }
        val waitingOnTheWorld = OfflineDownloads.active.value.values.any {
            it.stage == OfflineDownloads.Stage.Offline || it.stage == OfflineDownloads.Stage.NoWifi
        }
        if (waitingOnTheWorld) {
            refresh()
            return
        }
        // The terminal notifications stay: "downloaded" and "did not finish" are both things the
        // viewer wants to find later. Only the ongoing one holding the service up goes, and it is
        // marked as gone before it is taken down so that nothing arriving late puts it back.
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Asked for by hand as well. STOP_FOREGROUND_REMOVE only takes down a notification this
        // service is actually in the foreground with, and an earlier refresh may have replaced it
        // with an ordinary one of the same id, which that flag leaves exactly where it is.
        runCatching { notifier().cancel(SUMMARY_ID) }
        stopSelf(lastStartId)
    }

    // ---- the notification --------------------------------------------------------------------

    /**
     * Puts the notification up, and says whether Android allowed it.
     *
     * This used to be an unguarded call, which is a crash waiting for the right phone. From
     * Android 14 `startForeground` throws when the start came from the background, and from
     * Android 15 it throws when a data sync service has spent its allowance for the day. Both
     * arrive as an exception out of `onStartCommand`, which takes the process down and with it the
     * queue: the viewer loses every row rather than one download. Caught here, and the caller
     * writes the queue down as failed so there is something left to press.
     */
    private fun goToForeground(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SUMMARY_ID, notification)
        }
    }.onFailure { Log.w(TAG, "Android would not let the download service run", it) }.isSuccess

    /**
     * Says so on every row, when this service is not allowed to exist.
     *
     * Including the video that was being asked for as this happened, which has no row yet: without
     * one, the press the viewer just made leaves nothing at all on the screen, which is the whole
     * complaint. Everything that was moving is written down as failed rather than left claiming to
     * download, because nothing is going to move it now.
     */
    private fun refuseEverything(asked: DownloadRequest?) {
        if (asked != null && OfflineDownloads.active.value[asked.fileId] == null) {
            OfflineDownloads.note(
                OfflineDownloads.Progress(
                    request = asked,
                    downloadedBytes = 0,
                    totalBytes = asked.sizeBytes,
                    stage = OfflineDownloads.Stage.Failed,
                    failure = OfflineDownloads.REFUSED,
                    order = OfflineDownloads.nextOrder(),
                ),
            )
        }
        OfflineDownloads.ordered
            .filter { it.busy }
            .forEach {
                OfflineDownloads.stage(
                    it.fileId,
                    OfflineDownloads.Stage.Failed,
                    OfflineDownloads.REFUSED,
                )
            }
        persist()
    }

    private fun refresh() {
        // Nothing draws the summary again once it has been taken down. The per-video notifications
        // are a different matter: "downloaded" and "did not finish" are posted as the queue empties
        // and are meant to outlive the service.
        runCatching {
            if (!stopping) notifier().notify(SUMMARY_ID, ongoing())
            perVideoNotifications()
        }
    }

    /**
     * One notification per video, under the summary, saying which is which.
     *
     * The summary alone could not answer the question the viewer actually has when they queue three
     * films: not "how far along is this" but "did it take all three, and which one is it on". A
     * count in a second line ("2 waiting") says how many and never says what, and the two waiting
     * are exactly the ones with nothing on screen anywhere unless the app is open.
     *
     * Android groups them under [SUMMARY_ID] on its own, so the shade shows one entry that expands
     * into the queue. Each carries a Cancel for its own video, which is the one thing the summary's
     * button cannot do: from there, with several queued, cancelling has to mean all of them.
     *
     * Re-posted only when a line actually changes. The progress ticker runs once a second, and
     * posting four unchanged notifications a second is how a download turns into a battery
     * complaint.
     */
    private fun perVideoNotifications() {
        val rows = OfflineDownloads.ordered
        val queued = rows.filter { it.stage == OfflineDownloads.Stage.Queued }

        for (row in rows) {
            // Failures already have a notification of their own, posted once, that outlives the
            // service. A second one saying the same thing is not twice as informative.
            if (row.stage == OfflineDownloads.Stage.Failed) continue
            val line = when (row.stage) {
                OfflineDownloads.Stage.Running -> listOfNotNull(
                    row.fraction?.let(StreamStats::formatPercent),
                    if (row.totalBytes > 0) {
                        "${bytes(row.downloadedBytes)} of ${bytes(row.totalBytes)}"
                    } else {
                        bytes(row.downloadedBytes)
                    },
                    StreamStats.formatEta(row.remainingSeconds).ifEmpty { null },
                ).joinToString(DOT)
                OfflineDownloads.Stage.Queued -> {
                    val place = queued.indexOfFirst { it.fileId == row.fileId }
                    if (place <= 0) "Next in the queue" else "${place + 1} in the queue"
                }
                OfflineDownloads.Stage.Paused -> "Paused"
                OfflineDownloads.Stage.Offline -> "Waiting for a connection"
                OfflineDownloads.Stage.NoWifi -> "Waiting for Wi-Fi"
                OfflineDownloads.Stage.Failed -> continue
            }
            if (lines[row.fileId] == line) continue
            lines[row.fileId] = line
            notifier().notify(row.fileId.notificationId(), videoNotification(row, line))
        }

        // Anything that has left the list takes its notification with it: a finished video's row
        // is replaced by the "Downloaded" one on the same id, and a cancelled one has none.
        val live = rows.map { it.fileId }.toSet()
        lines.keys.retainAll(live)
    }

    private fun videoNotification(
        row: OfflineDownloads.Progress,
        line: String,
    ): Notification {
        val running = row.stage == OfflineDownloads.Stage.Running
        val fraction = row.fraction
        return builder()
            .setContentTitle(row.title)
            .setContentText(line)
            .setGroup(GROUP)
            .setOngoing(row.busy)
            .apply {
                if (running) {
                    setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
                } else if (fraction != null && row.downloadedBytes > 0) {
                    // A held video keeps its bar, unmoving, because what it has already fetched is
                    // the thing the viewer is deciding whether to wait for.
                    setProgress(100, (fraction * 100).toInt(), false)
                }
                addAction(
                    action(
                        icon = R.drawable.ic_notify_cancel,
                        label = "Cancel",
                        action = ACTION_CANCEL,
                        fileId = row.fileId,
                    ),
                )
                if (row.stage == OfflineDownloads.Stage.Running) {
                    addAction(
                        action(
                            icon = R.drawable.ic_notify_pause,
                            label = "Pause",
                            action = ACTION_PAUSE,
                            fileId = row.fileId,
                        ),
                    )
                } else if (!row.busy) {
                    addAction(
                        action(
                            icon = R.drawable.ic_notify_resume,
                            label = "Resume",
                            action = ACTION_RESUME,
                            fileId = row.fileId,
                        ),
                    )
                }
            }
            .build()
    }

    /**
     * The one notification a download gets.
     *
     * There used to be two: a foreground one saying "Downloading for later" and a second one per
     * file carrying the actual figures, which meant a single video produced a pair of entries in
     * the shade, one of them saying nothing the other did not. The service still needs exactly one
     * notification to hold it up, so that is the one given the title, the bar and the numbers, and
     * nothing else is posted while the fetching goes on.
     *
     * It names the video being fetched, not the batch, because now only one is ever being fetched.
     * What is behind it is a count on the second line, which is what a queue looks like from the
     * shade: this one, and how many after it.
     */
    private fun ongoing(): Notification {
        val rows = OfflineDownloads.ordered.filter { it.stage != OfflineDownloads.Stage.Failed }
        val current = rows.firstOrNull { it.stage == OfflineDownloads.Stage.Running }
        val queued = rows.count { it.stage == OfflineDownloads.Stage.Queued }
        val paused = rows.count { it.stage == OfflineDownloads.Stage.Paused }
        val offline = rows.count { it.stage == OfflineDownloads.Stage.Offline }
        val noWifi = rows.count { it.stage == OfflineDownloads.Stage.NoWifi }
        val builder = builder().setOngoing(true).setGroup(GROUP).setGroupSummary(true)

        if (current == null) {
            // Nothing moving. Either the signal went, which the service deliberately stays alive
            // for, or it is on its way out and this is the last thing it will draw.
            if (offline > 0 || noWifi > 0) {
                val forWifi = noWifi > offline
                val held = maxOf(offline, noWifi)
                return builder
                    .setContentTitle(
                        when {
                            forWifi && held == 1 -> "Waiting for Wi-Fi"
                            forWifi -> "$held downloads waiting for Wi-Fi"
                            held == 1 -> "Waiting for a connection"
                            else -> "$held downloads waiting for a connection"
                        },
                    )
                    .setContentText(
                        if (forWifi) {
                            "They start on Wi-Fi. Change this in Settings."
                        } else {
                            "They carry on by themselves when the signal is back."
                        },
                    )
                    .setProgress(0, 0, false)
                    .addAction(pauseAction())
                    .addAction(cancelAction(rows.size))
                    .build()
            }
            val title = when {
                paused > 0 && paused == 1 -> "Download paused"
                paused > 0 -> "$paused downloads paused"
                queued == 1 -> "Starting a download"
                queued > 1 -> "Starting $queued downloads"
                else -> "Downloading for later"
            }
            return builder
                .setContentTitle(title)
                // "Finishing up" is the last moment of a queue, not a state anything sits in: with
                // rows still queued it said the opposite of what was happening.
                .setContentText(
                    when {
                        paused > 0 -> "Paused"
                        queued > 0 -> "Waiting to start"
                        else -> "Finishing up"
                    },
                )
                .setProgress(0, 0, paused == 0)
                .apply { if (paused > 0) addAction(resumeAction(paused)) }
                .addAction(cancelAction(rows.size))
                .build()
        }

        val fraction = current.fraction
        val line = listOfNotNull(
            fraction?.let(StreamStats::formatPercent),
            if (current.totalBytes > 0) {
                "${bytes(current.downloadedBytes)} of ${bytes(current.totalBytes)}"
            } else {
                bytes(current.downloadedBytes)
            },
            StreamStats.formatSpeed(current.bytesPerSecond).takeIf { current.bytesPerSecond >= 1024 },
            waitingLine(queued + paused + offline + noWifi),
        ).joinToString(DOT)

        return builder
            .setContentTitle(current.title)
            .setContentText(line)
            .setSubText(StreamStats.formatEta(current.remainingSeconds).ifEmpty { null })
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(pauseAction())
            .addAction(cancelAction(rows.size))
            .build()
    }

    private fun waitingLine(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 waiting"
        else -> "$count waiting"
    }

    /**
     * Pause, which stops the fetching and keeps every byte that arrived.
     *
     * It acts on everything, the queue included, for the same reason "Cancel all" does: one button,
     * several videos, and no honest way to choose between them from here. Holding one of several is
     * on its own row in the Downloads screen, which has the space to name it.
     */
    private fun pauseAction(): Notification.Action = action(
        icon = R.drawable.ic_notify_pause,
        label = "Pause",
        action = ACTION_PAUSE,
    )

    private fun resumeAction(paused: Int): Notification.Action = action(
        icon = R.drawable.ic_notify_resume,
        label = if (paused > 1) "Resume all" else "Resume",
        action = ACTION_RESUME,
    )

    private fun cancelAction(rows: Int): Notification.Action = action(
        icon = R.drawable.ic_notify_cancel,
        label = if (rows > 1) "Cancel all" else "Cancel",
        action = ACTION_CANCEL,
    )

    private fun action(
        icon: Int,
        label: String,
        action: String,
        /** [EVERYTHING] on the summary, which has no room to say which video it means. */
        fileId: Int = EVERYTHING,
    ): Notification.Action {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_FILE_ID, fileId)
        }
        val pending = PendingIntent.getService(
            this,
            // One request code per action and per video, or the buttons would share a
            // PendingIntent and the last one built would quietly become all of them.
            action.hashCode() + fileId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            label,
            pending,
        ).build()
    }

    private fun doneNotification(request: DownloadRequest): Notification = builder()
        .setContentTitle(request.title)
        .setContentText("Downloaded. It plays without a connection.")
        .setAutoCancel(true)
        .build()

    private fun failedNotification(request: DownloadRequest): Notification = builder()
        .setContentTitle(request.title)
        .setContentText(FAILED_TEXT)
        .setAutoCancel(true)
        .build()

    private fun builder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)

    /**
     * Where pressing the notification goes: the Downloads screen, which is what it is about.
     *
     * It used to open the app and leave the viewer wherever they had last been, which for a
     * notification whose entire subject is a list of downloads is the wrong list.
     */
    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)
        return PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifier(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            // Low: a progress bar that pings and vibrates every time it moves is not information.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Videos being kept for watching without a connection"
            setShowBadge(false)
        }
        notifier().createNotificationChannel(channel)
    }

    private fun bytes(value: Long): String = StreamStats.formatBytes(value)

    private fun Int.notificationId(): Int = SUMMARY_ID + 1 + (this and 0xFFFF)

    private fun Intent.fileId(): Int = getIntExtra(EXTRA_FILE_ID, EVERYTHING)

    companion object {
        const val ACTION_DOWNLOAD = "com.tmplayer.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.tmplayer.action.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.tmplayer.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.tmplayer.action.RESUME_DOWNLOAD"

        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SIZE = "size"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_CHAT_TITLE = "chatTitle"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_MIME = "mime"
        const val EXTRA_FILE_NAME = "fileName"

        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"

        /** Ties the per-video notifications to the summary, so the shade shows one entry. */
        private const val GROUP = "com.tmplayer.downloads"
        private const val SUMMARY_ID = 4200

        /**
         * How many videos are fetched at once.
         *
         * One. See the note at the top of the class: the alternative is every video finishing at
         * the same late moment, and figures on a notification that describe no file in particular.
         */
        private const val MAX_PARALLEL = 1

        /** Above the streaming player's priority: this is what the viewer is waiting on. */
        private const val DOWNLOAD_PRIORITY = 32

        private const val PROGRESS_INTERVAL_MS = 1_000L

        /**
         * How long a download waits for TDLib to find a line to Telegram before saying it cannot.
         *
         * Longer than a screen would wait, because nobody is watching a spinner: a phone coming
         * out of a tunnel deserves a couple of minutes. Not for ever, which is what it used to be.
         */
        private const val CONNECT_WAIT_MS = 120_000L

        /** What TDLib says when a newer request for the same file replaced this one. */
        private const val TAKEN_OVER = "Canceled by another downloadFile"

        /** Enough to sit through a viewer seeking around the video they are also keeping. */
        private const val MAX_TAKEOVERS = 60
        private const val TAKEOVER_BACKOFF_MS = 2_000L

        /** The file id the notification's buttons carry, since no real file has it. */
        private const val EVERYTHING = 0

        /** Kept clear of the action buttons' codes, which are their action strings' hashes. */
        private const val OPEN_REQUEST_CODE = 1

        private const val DOT = "  ·  "

        private const val FAILED_TEXT = "The download did not finish. Try again."
    }
}
