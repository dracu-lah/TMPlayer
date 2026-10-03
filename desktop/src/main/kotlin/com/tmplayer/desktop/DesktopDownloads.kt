package com.tmplayer.desktop

import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.Failures
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.errorMessage
import com.tmplayer.platform.Logger
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The desktop's [DownloadRunner]: the queue is fetched by a coroutine in the app's own process.
 *
 * Android needs a foreground service so that a download outlives the app being put away; a desktop
 * app that is open is simply running, so the same queue is a scope and a list. One video at a time,
 * as on Android, for the same reason: bandwidth split three ways finishes all three late. The
 * unfinished part of the queue is written to the settings store as it changes, so the next launch
 * restores it paused through [OfflineDownloads.restore].
 */
class DesktopDownloadRunner(private val settings: SettingsStore) : DownloadRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val requests = mutableMapOf<Int, DownloadRequest>()
    private val waiting = ArrayDeque<Int>()
    private val running = mutableMapOf<Int, Job>()
    private var arrivals = 0L
    private val persistLock = Mutex()

    override fun download(request: DownloadRequest) {
        val known = OfflineDownloads.active.value[request.fileId]
        // Already coming down or already waiting: pressing download twice is not two downloads.
        if (known != null && known.busy) return
        val position = synchronized(lock) {
            adoptExistingQueue()
            if (running.containsKey(request.fileId) || waiting.contains(request.fileId)) return
            requests[request.fileId] = request
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

    override fun cancel(fileId: Int) {
        idsFor(fileId).forEach { id ->
            val job = synchronized(lock) {
                requests.remove(id)
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            OfflineDownloads.forget(id)
            Td.cancelDownloadInBackground(id)
        }
        persist()
        pump()
    }

    /** TDLib has no pause: a cancellation that keeps the bytes, and a later fetch carries on. */
    override fun pause(fileId: Int) {
        idsFor(fileId).filter { OfflineDownloads.active.value[it]?.busy == true }.forEach { id ->
            val job = synchronized(lock) {
                waiting.remove(id)
                running.remove(id)
            }
            job?.cancel()
            Td.cancelDownloadInBackground(id)
            OfflineDownloads.stage(id, OfflineDownloads.Stage.Paused)
        }
        persist()
        pump()
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
                val job = scope.launch { fetch(request) }
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
            withContext(NonCancellable) { finished(request) }
            return@coroutineScope
        }

        // Room is checked before the request goes out: TDLib's own answer to a full disk is a
        // message written for a developer, and it arrives after most of the data has been spent.
        val landedAlready = Td.localDownloadedBytes(request.fileId)
        val stillToCome = (request.sizeBytes - landedAlready).coerceAtLeast(0)
        val free = DesktopPaths.disk().freeBytes
        if (stillToCome > 0 && free in 1 until stillToCome + CacheShelf.HEADROOM_BYTES) {
            val short = stillToCome + CacheShelf.HEADROOM_BYTES - free
            withContext(NonCancellable) {
                fail(request, landedAlready, "Not enough space: ${StreamStats.formatBytes(short)} short")
            }
            return@coroutineScope
        }

        val session = Td.awaitConnectedSessionOrNull(CONNECT_WAIT_MS)
        if (session == null) {
            withContext(NonCancellable) {
                fail(request, Td.localDownloadedBytes(request.fileId), Failures.OFFLINE)
            }
            return@coroutineScope
        }

        val ticker = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                OfflineDownloads.sample(request.fileId, Td.localDownloadedBytes(request.fileId))
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

        withContext(NonCancellable) {
            val complete = Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete
            if (error == null && complete) {
                finished(request)
            } else {
                Logger.w(TAG, "Download of ${request.title} did not finish: ${error ?: "incomplete"}")
                fail(
                    request,
                    Td.localDownloadedBytes(request.fileId),
                    error?.let(Failures::humanise) ?: FAILED_TEXT,
                )
            }
        }
    }

    private suspend fun finished(request: DownloadRequest) {
        settings.noteDownload(request.item(), request.chatTitle)
        synchronized(lock) { requests.remove(request.fileId) }
        OfflineDownloads.forget(request.fileId)
        persistNow()
    }

    /** Writes a failure only while this fetch still owns the row: a pause or cancel may have won. */
    private suspend fun fail(request: DownloadRequest, landed: Long, text: String) {
        val row = OfflineDownloads.active.value[request.fileId]
        if (row != null && row.stage == OfflineDownloads.Stage.Running) {
            OfflineDownloads.note(
                row.copy(
                    downloadedBytes = landed,
                    stage = OfflineDownloads.Stage.Failed,
                    failure = text,
                    bytesPerSecond = 0,
                ),
            )
        }
        persistNow()
    }

    private fun persist() {
        scope.launch { persistNow() }
    }

    private suspend fun persistNow() = persistLock.withLock {
        runCatching { settings.saveDownloadQueue(OfflineDownloads.ordered.map { it.request }) }
        Unit
    }

    private companion object {
        const val TAG = "DesktopDownloads"
        const val MAX_PARALLEL = 1
        const val DOWNLOAD_PRIORITY = 32
        const val PROGRESS_INTERVAL_MS = 1_000L
        const val CONNECT_WAIT_MS = 120_000L
        const val MAX_TAKEOVERS = 60
        const val TAKEOVER_BACKOFF_MS = 2_000L
        const val TAKEN_OVER = "Canceled by another downloadFile"
        const val FAILED_TEXT = "The download did not finish. Try again."
    }
}
