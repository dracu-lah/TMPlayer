package com.tmplayer.ios

import com.tmplayer.platform.Logger
import com.tmplayer.platform.PlainLock
import com.tmplayer.player.DownloadWindow
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One Telegram video as bytes for mpv, read while TDLib is still downloading it.
 *
 * The iOS stand in for :core's TdByteWindow until that leaves the JDK (docs/IOS.md, M2),
 * with the same idea: a read at a byte TDLib does not have moves the download there instead of
 * waiting for everything before it, and what was downloaded before is read from the disk. The seek
 * rule is [DownloadWindow]'s. The Swift side (TdStreamProtocol) reads the partial file itself; this
 * only says how many bytes from a position are there, waiting for TDLib when none are yet.
 */
class VideoStream internal constructor(private val td: TdlClient, val fileId: Int, val size: Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = PlainLock()
    private val changes = MutableStateFlow(0L)

    // All behind [lock]: the update collector writes them, mpv's demuxer thread reads them.
    private var path = ""
    private var windowStart = 0L
    private var windowLength = 0L
    private var active = false
    private var completed = false
    private var closed = false
    private var requestedFrom = -1L
    private var requestedAt = TimeSource.Monotonic.markNow()

    init {
        scope.launch {
            td.fileUpdates.filter { it.file.id == fileId }.collect { update ->
                absorb(update.file)
                changes.value++
            }
        }
    }

    internal fun absorb(file: File) = lock.locked {
        if (file.local.path.isNotEmpty()) path = file.local.path
        windowStart = file.local.downloadOffset
        windowLength = file.local.downloadedPrefixSize
        active = file.local.isDownloadingActive
        completed = file.local.isDownloadingCompleted
    }

    /** The partial file on the disk, once TDLib has made it. */
    fun localPath(): String? = lock.locked { path.ifEmpty { null } }

    /**
     * How many bytes from [position] can be read now, waiting for TDLib to bring them when there
     * are none yet: 0 at the end of the file, -1 when the stream was closed or nothing came within
     * the stall timeout. Blocks, as mpv's demuxer thread expects.
     */
    fun available(position: Long): Long = runBlocking { awaitAvailable(position) }

    private suspend fun awaitAvailable(position: Long): Long {
        if (position >= size) return 0
        val started = TimeSource.Monotonic.markNow()
        while (!lock.locked { closed }) {
            val onDisk = (td.getFileDownloadedPrefixSize(fileId, position) as? TdlResult.Success)?.result?.size ?: 0L
            if (onDisk > 0 && localPath() != null) return onDisk
            if (shouldRequest(position)) request(position)
            if (started.elapsedNow() > STALL_TIMEOUT) {
                Logger.w(TAG, "No bytes at $position of file $fileId within $STALL_TIMEOUT")
                return -1
            }
            val seen = changes.value
            withTimeoutOrNull(WAKE_INTERVAL) { changes.first { it != seen } }
        }
        return -1
    }

    /**
     * Whether the download has to be pointed at [position]: nothing asked yet, the byte is behind
     * what was asked for, it is further ahead than the running download will reach soon, or TDLib
     * stopped short of it a while ago.
     */
    private fun shouldRequest(position: Long): Boolean = lock.locked {
        if (completed) return@locked false
        if (requestedFrom < 0 || position < requestedFrom) return@locked true
        val reach = maxOf(windowStart + windowLength, requestedFrom)
        if (position > reach + DownloadWindow.FORWARD_SLACK_BYTES) return@locked true
        !active && requestedAt.elapsedNow() > IDLE_RETRY
    }

    private suspend fun request(position: Long) {
        lock.locked {
            requestedFrom = position
            requestedAt = TimeSource.Monotonic.markNow()
        }
        // TDLib keeps one download per file and the last request wins, so a seek needs no cancel.
        val result = td.downloadFile(fileId, PLAYBACK_PRIORITY, position, 0L, false)
        if (result is TdlResult.Success) absorb(result.result) else Logger.w(TAG, "Download of $fileId at $position refused")
    }

    /** Wakes a waiting read so it gives up: mpv asks for this when playback stops. */
    fun cancel() {
        lock.locked { closed = true }
        changes.value++
    }

    /** Stops the download (what is on the disk stays in TDLib's cache) and ends the stream. */
    fun close() {
        com.tmplayer.desktop.player.ActiveStreams.closed(fileId)
        cancel()
        scope.cancel()
        // The player's fetching stops with it, unless the Downloads queue is fetching the same file.
        if (!com.tmplayer.data.OfflineDownloads.isDownloading(fileId)) {
            CoroutineScope(Dispatchers.Default).launch { td.cancelDownloadFile(fileId, false) }
        }
    }

    private companion object {
        const val TAG = "VideoStream"
        const val PLAYBACK_PRIORITY = 32
        val STALL_TIMEOUT = 30.seconds
        val IDLE_RETRY = 3.seconds
        val WAKE_INTERVAL = 0.25.seconds
    }
}

/** Where the engine's `tdfile://` stream (TdStreamProtocol.swift) opens a video, by TDLib file id. */
object VideoStreams {
    /** The stream for [fileId], or null when nobody is signed in or TDLib has no such file. Blocks. */
    fun open(fileId: Int): VideoStream? = runBlocking {
        val td = runCatching { com.tmplayer.data.Td.client }.getOrNull() ?: return@runBlocking null
        // What TDLib had of it in the app's folder before an update moved it is dropped first.
        runCatching { com.tmplayer.ios.media3.MovedParts.clearFile(fileId) }
        val file = (td.getFile(fileId) as? TdlResult.Success)?.result ?: return@runBlocking null
        val size = if (file.size > 0) file.size else file.expectedSize
        Logger.i(
            "VideoStream",
            "open $fileId: size $size, on disk ${file.local.downloadedSize}, complete ${file.local.isDownloadingCompleted}, " +
                "path ${if (file.local.path.isEmpty()) "none" else file.local.path.substringAfterLast('/')}",
        )
        if (size <= 0) {
            null
        } else {
            // A finished download is not moved out from under the player (ActiveStreams).
            com.tmplayer.desktop.player.ActiveStreams.opened(fileId)
            VideoStream(td, fileId, size).also { it.absorb(file) }
        }
    }
}

/** How the app's screens open the player: the Swift side presents upstream's PlayerActivity. */
interface PlayerLauncher {
    /**
     * Makes the app's playback audio session ready, which can wait on the audio server and so is
     * done off the main thread, then runs [then] on the main thread.
     */
    fun audioReady(then: () -> Unit)

    /** An Android activity of Views (upstream's PlayerActivity), full screen over the screens. */
    fun present(screen: ActivityScreen)
}

