package com.tmplayer.player

import com.tmplayer.data.Failures
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.LocalFilePolicy
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.errorMessage
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.File as TdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * The part of streaming a Telegram file that does not depend on the player reading it.
 *
 * TDLib is asked to fill the file starting at whatever byte the player wants; reads then come off
 * the partial file on disk as soon as the bytes land. Seeking works because the player re-opens
 * at a new offset and this simply points TDLib at that offset instead: no full download, no
 * waiting for the end of a 12 GB remux to watch minute 90.
 *
 * The player on each platform wraps one of these: Media3's `TdDataSource` on Android, and the
 * desktop's media source. They decide how to block and how to report the end of input; this keeps
 * the window of bytes TDLib has confirmed, the one subscription that keeps it current, and the
 * rules for when a stalled read asks TDLib again.
 *
 * One instance serves one source, which may be opened at several files and offsets in turn:
 * [watch], then [lookUp], then reads, then [close], and again.
 *
 * Written against TDLib's public file API only.
 */
class TdByteWindow(private val td: TdlClient) {

    var fileId = -1
        private set

    /** The file's full size, as of the last [lookUp]. */
    var size = 0L
        private set

    private var handle: FileChannel? = null
    private var localPath: String? = null

    /**
     * The stretch of the file a real [TdFile] last confirmed was on disk, so reads inside it need
     * no call to TDLib at all.
     *
     * Players issue a great many small reads, and asking TDLib about the file on each one would
     * put a request round-trip in front of every byte.
     *
     * Held as one value rather than a pair of fields because both ends have to move together: a
     * reader that caught a new start against an old end would be trusting a window that never
     * existed.
     */
    @Volatile
    private var window = Window.EMPTY

    /** Whether the whole file is on disk, as of the last update seen. */
    @Volatile
    var completed = false
        private set

    /** Whether TDLib says it is currently filling the file, as of the last update seen. */
    @Volatile
    private var downloading = false

    /** Every byte of the file on disk, wherever it is, as of the last update seen. */
    @Volatile
    private var downloadedTotal = 0L

    /**
     * One subscription to `updateFile` for the whole of an open source, rather than a fresh one
     * per slow read.
     *
     * The collector runs from [watch] to [close] and keeps [window] warm on its own, so a read
     * that has to wait is woken by the update itself and reads the answer out of memory instead
     * of paying a `getFile` round trip per second of waiting.
     */
    private var updates: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * Bumped once per absorbed update, so a waiting read can tell "TDLib said something" from
     * "the poll timed out" without comparing windows itself.
     */
    private val revision = MutableStateFlow(0L)

    /** Bytes `[start, end)` of the file, as TDLib last reported them. */
    private data class Window(val start: Long, val end: Long) {
        companion object {
            val EMPTY = Window(0, 0)
        }
    }

    /**
     * Points this at [fileId] and starts the one subscription that keeps the window current.
     *
     * Called before the first [lookUp], so nothing arriving while it is in flight is missed: an
     * update dropped there is a second of stall for no reason.
     */
    fun watch(fileId: Int) {
        this.fileId = fileId
        stopWatching()
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = watcher
        val watched = fileId
        updates = watcher.launch {
            td.fileUpdates.filter { it.file.id == watched }.collect { update ->
                absorb(update.file)
                revision.value += 1
            }
        }
    }

    /** Asks TDLib about the file, records what it says, and returns its size. */
    suspend fun lookUp(): Long {
        val file = td.getFile(fileId).valueOrNull
            ?: throw IOException("Telegram lost track of file $fileId")
        size = if (file.size > 0) file.size else file.expectedSize
        if (size <= 0) throw IOException("Unknown size for file $fileId")
        absorb(file)
        return size
    }

    /**
     * How many bytes at [target] are already known-good, or `null` if TDLib has to be asked.
     *
     * Never optimistic: it only answers from a window that a real [TdFile] confirmed earlier, and
     * any doubt returns `null` so the slow path re-checks.
     *
     * Both ends of that window matter. TDLib fills one stretch at a time and frees what falls
     * outside it, but the partial file on disk keeps its length, so a read below the window
     * succeeds and hands back a hole instead of failing, and the extractor dies on the zeroes.
     * Seeking forward and then back lands exactly there, so the arithmetic is deferred to
     * [DownloadWindow] rather than repeated here.
     */
    fun cachedAvailable(target: Long): Long? {
        if (localPath == null) return null
        val known = window
        return DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = known.start,
            downloadedPrefixSize = known.end - known.start,
            completed = completed,
        ).takeIf { it > 0 }
    }

    /**
     * Reads up to [length] bytes at [position] off the file on disk. The caller has already
     * established, through [cachedAvailable] or [awaitBytesAt], that they are there.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val read = synchronized(this) {
            openHandle().read(ByteBuffer.wrap(buffer, offset, length), position)
        }
        if (read <= 0) {
            // TDLib reported the bytes but the file on disk is shorter: it was trimmed or
            // re-created underneath us. Drop the cached window too, so the next read goes back
            // and asks TDLib what is really there instead of trusting a stale boundary.
            closeHandle()
            window = Window.EMPTY
            completed = false
            throw EOFException("Short read at $position of file $fileId")
        }
        return read
    }

    /**
     * Stops listening and forgets the window. The player may reopen this at a different offset,
     * or a different file, and nothing learned about the old window is safe to carry across.
     */
    fun close() {
        stopWatching()
        closeHandle()
        window = Window.EMPTY
        completed = false
    }

    /** Records what a fresh [TdFile] tells us, so later reads can skip the round-trip. */
    private fun absorb(file: TdFile) {
        val path = file.local.path
        val diskFile = path.takeIf { it.isNotBlank() }?.let(::File)
        val usablePath = path.takeIf { diskFile?.isFile == true }
        // TDLib moves a file when a download finishes (the partial name is renamed away), so
        // the handle is dropped on any path change rather than reading a stale inode.
        synchronized(this) {
            if (usablePath != localPath) {
                runCatching { handle?.close() }
                handle = null
                localPath = usablePath
            }
        }
        val availability = LocalFilePolicy.evaluate(
            downloadCompleted = file.local.isDownloadingCompleted,
            pathPresent = diskFile != null,
            regularFile = diskFile?.isFile == true,
            length = diskFile?.length() ?: 0,
            size = file.size,
            expectedSize = file.expectedSize,
        )
        downloading = file.local.isDownloadingActive
        downloadedTotal = file.local.downloadedSize
        if (availability == LocalFileAvailability.Complete) {
            completed = true
            window = Window(0, size)
        } else {
            completed = false
            val start = file.local.downloadOffset
            window = Window(start, start + file.local.downloadedPrefixSize)
        }
    }

    private fun stopWatching() {
        updates = null
        scope?.cancel()
        scope = null
    }

    /**
     * Suspends until at least one byte at [target] is on disk, restarting the download whenever
     * TDLib's window has drifted away from where the player is reading.
     *
     * The collector is doing the listening; this only decides when to give up and when to ask
     * TDLib for a download that is not happening. A read that outlives its own subscription (the
     * collector died with the client) still makes progress, because a poll that hears nothing
     * falls back to `getFile`.
     */
    suspend fun awaitBytesAt(target: Long): Long {
        // The collector may already have absorbed what this read needs.
        cachedAvailable(target)?.let { return it }

        val file = td.getFile(fileId).valueOrNull ?: throw IOException("File $fileId disappeared")
        var available = available(file, target)
        if (available > 0) return available

        // Wall clock, not a count of turns round the loop: a file updating busily without ever
        // reaching the byte being read must still get the full stall timeout.
        val deadline = System.nanoTime() + STALL_TIMEOUT_MS * 1_000_000
        var nudgeAt = System.nanoTime() + NUDGE_AFTER_MS * 1_000_000
        var totalAtAsk = downloadedTotal
        var nudges = 0
        while (available == 0L) {
            val known = window
            // TDLib can take a request, report itself busy, and fill everything but the bytes that
            // were asked for: seen on the jump to an MKV's index in its last half megabyte, where
            // tens of megabytes came down elsewhere and the prefix at the offset stayed at zero
            // until the viewer backed out and opened the video again. That reopen is what this
            // does, without the viewer: drop TDLib's download and ask afresh, from the byte itself
            // and then from the start of its megabyte, which is how Telegram hands out parts.
            // Only when bytes are visibly arriving somewhere else: a slow link bringing nothing at
            // all is left to the stall timeout, since restarting it every few seconds would only
            // make it slower.
            if (
                downloading &&
                System.nanoTime() >= nudgeAt &&
                downloadedTotal - totalAtAsk >= NUDGE_ELSEWHERE_BYTES
            ) {
                val from = if (nudges % 2 == 0) target else target - target % NUDGE_ALIGN_BYTES
                nudges++
                Logger.i(TAG, "No bytes at $target of file $fileId; asking again from $from ($nudges)")
                // A download the viewer asked to keep shares this file in TDLib; it is not ours to
                // cancel, and the fresh request below moves it just the same.
                if (!OfflineDownloads.isDownloading(fileId)) {
                    td.cancelDownloadFile(fileId, onlyIfPending = false)
                }
                requestDownloadFrom(from)
                nudgeAt = System.nanoTime() + NUDGE_AFTER_MS * 1_000_000
                totalAtAsk = downloadedTotal
            }
            if (DownloadWindow.needsRestart(
                    position = target,
                    downloadOffset = known.start,
                    downloadedPrefixSize = known.end - known.start,
                    active = downloading,
                    completed = completed,
                )
            ) {
                requestDownloadFrom(target)
            }

            // updateFile arrives on every few hundred KB, so this normally returns immediately;
            // the timeout is only there so a silent connection still gets re-poked.
            val seen = revision.value
            val heard = withTimeoutOrNull(POLL_INTERVAL_MS) {
                revision.first { it != seen }
            } != null

            available = if (heard) {
                cachedAvailable(target) ?: 0L
            } else {
                // Silence. Either nothing is moving, or this source has no collector to hear it,
                // so the question goes to TDLib directly.
                val fresh = td.getFile(fileId).valueOrNull
                    ?: throw IOException("File $fileId disappeared")
                available(fresh, target)
            }
            if (available == 0L && System.nanoTime() >= deadline) {
                throw IOException("Telegram stopped sending file $fileId at byte $target")
            }
        }
        return available
    }

    private fun available(file: TdFile, target: Long): Long {
        absorb(file)
        if (localPath == null) return 0
        return DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = file.local.downloadOffset,
            downloadedPrefixSize = file.local.downloadedPrefixSize,
            completed = completed,
        )
    }

    /**
     * `limit = 0` means "keep going to the end of the file", exactly what playback wants.
     *
     * The result is checked rather than dropped. A refused request looks exactly like a slow
     * one from here (no bytes arrive), and swallowing it turns a flood wait or an expired file
     * reference into a minute of blank screen followed by "Telegram stopped sending", which
     * says nothing a viewer can act on.
     */
    suspend fun requestDownloadFrom(offset: Long) {
        val result = td.downloadFile(
            fileId = fileId,
            priority = PLAYBACK_PRIORITY,
            offset = offset,
            limit = 0,
            synchronous = false,
        )
        val error = result.errorMessage ?: return
        throw IOException(Failures.humanise(error))
    }

    /**
     * Call under the instance lock. [awaitBytesAt] has already recorded a usable path.
     *
     * A NIO channel rather than a `RandomAccessFile`: on Windows, NIO opens with delete sharing,
     * so TDLib can still rename the partial file into place when the download finishes while
     * the player has it open; `java.io` would hold the rename off with a sharing violation. On
     * Android and Linux the two read the same.
     */
    private fun openHandle(): FileChannel = handle ?: run {
        val path = localPath ?: throw IOException("No local file for $fileId yet")
        FileChannel.open(Paths.get(path), StandardOpenOption.READ).also { handle = it }
    }

    private fun closeHandle() = synchronized(this) {
        runCatching { handle?.close() }
        handle = null
        localPath = null
    }

    companion object {
        /** How long a read may wait for its byte before the stream is declared stalled. */
        const val STALL_TIMEOUT_MS = 60_000L

        /** 1..32; playback gets the top of the range so thumbnails never starve it. */
        private const val PLAYBACK_PRIORITY = 32
        private const val POLL_INTERVAL_MS = 1_000L

        /** The Android log tag this has always written under, from before it left the source. */
        private const val TAG = "TdDataSource"

        /** How long a busy download may bring nothing at the read position before it is redone. */
        private const val NUDGE_AFTER_MS = 3_000L
        private const val NUDGE_ALIGN_BYTES = 1024L * 1024

        /** What has to arrive elsewhere in the file meanwhile for the wait to count as stuck. */
        private const val NUDGE_ELSEWHERE_BYTES = 2L * 1024 * 1024
    }
}
