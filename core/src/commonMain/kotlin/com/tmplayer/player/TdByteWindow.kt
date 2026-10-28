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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * A stretch away from TDLib's download that is on the disk all the same, as
     * `getFileDownloadedPrefixSize` confirmed: an MKV's Cues or an MP4's moov that [StreamWarmup]
     * (or an earlier play) brought down. Read from where it is, without moving the download there
     * and back, which used to cost a round trip each way before the first frame. In Low storage
     * mode too, where the stretch is recorded as held ([LowStorageStreams.onDisk]) so the window's
     * budget counts it, and dropped with the window at every delete ([deleteForLowStorage]).
     */
    @Volatile
    private var side = Window.EMPTY

    /**
     * Where the viewer's stream of this file is, shared with every other window open at it: see
     * [Playhead]. Taken at [watch], let go at [close].
     */
    @Volatile
    private var playhead: Playhead? = null

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

    /**
     * Whether this file streams in Low storage mode, as [LowStorageStreams] said at [watch]. Only
     * a shortcut for the fast path; every decision asks [LowStorageStreams] again.
     */
    @Volatile
    private var lowStorage = false

    /**
     * Whether this file streams with "Save mobile data", as [SaveDataStreams] said at [watch].
     * Only a shortcut for the fast path, like [lowStorage]; every decision asks again.
     */
    @Volatile
    private var saveData = false

    /**
     * Up while a roll or a seek in Low storage mode is deleting the file: updates about the file
     * being deleted say nothing true about the one coming next, so they are not absorbed.
     */
    @Volatile
    private var deleting = false

    /**
     * Up from [close] until the next [watch]: a read still waiting on another thread when the
     * player let go (Media3's loader, the cast server) gives up instead of asking TDLib again,
     * which used to send a fresh request for the rest of the film after the player had gone.
     */
    @Volatile
    private var shut = false

    /** One roll at a time, however many readers find it due together. */
    private val rollLock = Mutex()

    /** Bytes `[start, end)` of the file, as TDLib last reported them. */
    private data class Window(val start: Long, val end: Long) {
        val isEmpty: Boolean get() = end <= start

        companion object {
            val EMPTY = Window(0, 0)
        }
    }

    /**
     * The one download of a file that only a real seek moves: the stream playback reads from.
     *
     * Demuxers read away from the picture they are playing all the time: an MKV's Cues and Tags
     * at the far end, an MP4's moov, the index lookups of a seek. TDLib keeps one download per
     * file, so aiming it at each of those and leaving it there used to send the stream to the end
     * of the film and stall the picture until a read came back for it. Such a read (a probe) is
     * served from the disk where the bytes are there, else by a small fetch of its own
     * ([probeAt]), and the download goes straight back to [mainAt]. A probe the reader carries
     * on reading past was a seek after all, and the download follows it from there
     * ([promoteIfDue]).
     *
     * Shared by every window open at the file, because Media3 closes and opens its source at
     * each jump, and forgotten once no window has had the file open for a few seconds, so the
     * next play starts afresh.
     */
    private class Playhead {
        /** Where the stream last read, or -1 before its first read. */
        @Volatile
        var mainAt = -1L

        /** The stretch a probe reads, which reads inside it leave [mainAt] alone for. */
        @Volatile
        var probe = Window.EMPTY

        var open = 0
        var closedAt = 0L
    }

    /**
     * Points this at [fileId] and starts the one subscription that keeps the window current.
     *
     * Called before the first [lookUp], so nothing arriving while it is in flight is missed: an
     * update dropped there is a second of stall for no reason.
     */
    fun watch(fileId: Int) {
        shut = false
        this.fileId = fileId
        playhead?.let(::letGo)
        playhead = takePlayhead(fileId)
        lowStorage = LowStorageStreams.isRolling(fileId)
        saveData = !lowStorage && SaveDataStreams.isActive(fileId)
        stopWatching()
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("TdByteWindow"))
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
        val result = td.getFile(fileId)
        val file = result.valueOrNull ?: run {
            // Refused as signed out: the session ended (HX23), and asking for the message again,
            // which the player does for a lost file, is refused the same way.
            val failure = result as? dev.g000sha256.tdl.TdlResult.Failure
            if (failure != null && com.tmplayer.data.SessionCheck.isAuthError(failure.code, failure.message)) {
                throw com.tmplayer.data.SessionLostException()
            }
            Logger.w(TAG, "getFile($fileId) failed: ${failure?.code} ${failure?.message}")
            throw IOException("Telegram lost track of file $fileId")
        }
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
        // A roll is due: the slow path performs it before this read is served.
        if (lowStorage && LowStorageStreams.rollDue(fileId, target, size)) return null
        // Likewise an extension, or a change of connection, in "Save mobile data".
        if (saveData && SaveDataStreams.extendDue(fileId, target, size)) return null
        val known = window
        val main = DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = known.start,
            downloadedPrefixSize = known.end - known.start,
            completed = completed,
        )
        if (main > 0) return main
        // A probe read on to its last stretch is due to be followed: the slow path does it.
        if (promotionDue(target)) return null
        val aside = side
        return (if (target >= aside.start && target < aside.end) aside.end - target else 0L).takeIf { it > 0 }
    }

    /**
     * Reads up to [length] bytes at [position] off the file on disk. The caller has already
     * established, through [cachedAvailable] or [awaitBytesAt], that they are there.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val read = synchronized(this) {
            // Checked again under the lock that also guards the path and the window: between the
            // caller's check and this line a roll or a seek may have deleted the file and a new
            // one may stand at the path, holes and all. A read that lost that race is sent back
            // to ask again rather than handed zeros.
            val known = window
            val aside = side
            val fits = when {
                completed -> (size - position).coerceAtLeast(0)
                position >= known.start && position < known.end -> known.end - position
                position >= aside.start && position < aside.end -> aside.end - position
                else -> 0L
            }
            if (fits <= 0) throw EOFException("Window moved away from $position of file $fileId")
            openHandle().read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), fits).toInt()), position)
        }
        // The stream's place, unless this was a probe's read.
        playhead?.let { ph ->
            val p = ph.probe
            if (read > 0 && (position < p.start || position >= p.end)) ph.mainAt = position + read
        }
        if (read <= 0) {
            // TDLib reported the bytes but the file on disk is shorter: it was trimmed or
            // re-created underneath us. Drop the cached window too, so the next read goes back
            // and asks TDLib what is really there instead of trusting a stale boundary.
            closeHandle()
            window = Window.EMPTY
            side = Window.EMPTY
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
        shut = true
        stopWatching()
        closeHandle()
        window = Window.EMPTY
        side = Window.EMPTY
        completed = false
        playhead?.let(::letGo)
        playhead = null
        if (fileId > 0) StreamWarmup.release(fileId)
    }

    /** Records what a fresh [TdFile] tells us, so later reads can skip the round-trip. */
    private fun absorb(file: TdFile) {
        if (deleting) return
        val path = file.local.path
        val diskFile = path.takeIf { it.isNotBlank() }?.let(::File)
        val usablePath = path.takeIf { diskFile?.isFile == true }
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
        // The path and the window move together, under the lock [read] checks them with, so no
        // read can pair a new file with an old window.
        synchronized(this) {
            // TDLib moves a file when a download finishes (the partial name is renamed away), and a
            // deleted file comes back under a new name, so the handle is dropped on any path change
            // rather than reading a stale inode.
            if (usablePath != localPath) {
                runCatching { handle?.close() }
                handle = null
                localPath = usablePath
                // A stretch found on the old file says nothing about the new one: a delete by
                // another reader's roll, or the file removed under us, comes back empty.
                side = Window.EMPTY
            }
            if (availability == LocalFileAvailability.Complete) {
                completed = true
                window = Window(0, size)
            } else {
                completed = false
                val start = file.local.downloadOffset
                window = Window(start, start + file.local.downloadedPrefixSize)
            }
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
        if (lowStorage) rollIfDue(target)
        if (saveData) extendIfDue(target)
        promoteIfDue(target)
        // The collector may already have absorbed what this read needs.
        cachedAvailable(target)?.let { return it }

        val file = td.getFile(fileId).valueOrNull ?: throw IOException("File $fileId disappeared")
        var available = available(file, target)
        if (available > 0) return available
        onDiskAt(target).takeIf { it > 0 }?.let { run ->
            followSide(target, run)
            return run
        }
        // A read far from the stream's place is a probe: fetched on its own, the download put back.
        if (isFar(target)) probeAt(target)?.let { return it }

        // Wall clock, not a count of turns round the loop: a file updating busily without ever
        // reaching the byte being read must still get the full stall timeout.
        val deadline = System.nanoTime() + STALL_TIMEOUT_MS * 1_000_000
        var nudgeAt = System.nanoTime() + NUDGE_AFTER_MS * 1_000_000
        var totalAtAsk = downloadedTotal
        var nudges = 0
        while (available == 0L) {
            if (shut) throw IOException("File $fileId was closed while a read waited")
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
                markSeek(target)
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

    /**
     * Bytes at [target] on the disk outside TDLib's current download (see [side]), recorded so the
     * reads can be served without re-aiming the download; 0 when there are none or it cannot say.
     *
     * In Low storage mode, 0 too for a stretch the mode would rather fetch afresh (too short to be
     * worth reading when the window could not carry on from its end: [LowStorageWindow.onDisk]),
     * and any other is recorded as held, so the budget counts it and the window rolls on near its
     * end like any stretch it asked for.
     */
    private suspend fun onDiskAt(target: Long): Long {
        if (localPath == null) return 0
        val here = td.getFileDownloadedPrefixSize(fileId, target).valueOrNull?.size ?: return 0
        if (here <= 0) return 0
        if (lowStorage && LowStorageStreams.onDisk(fileId, target, here, size) == LowStorageWindow.OnDisk.Use.Fetch) return 0
        synchronized(this) { side = Window(target, minOf(size, target + here)) }
        return here
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
     * After a read at [target] gave up ("Telegram stopped sending"): TDLib's download of the file
     * dropped and asked for afresh from [target], the reopen the stall wait does when bytes arrive
     * elsewhere, for a reader (the cast server's) that tries again rather than failing. A download
     * the viewer asked to keep is left running, and moved by the fresh request just the same.
     */
    suspend fun askAgainFrom(target: Long) {
        if (completed) return
        Logger.i(TAG, "Asking again for file $fileId from $target after a stall")
        if (!OfflineDownloads.isDownloading(fileId)) {
            td.cancelDownloadFile(fileId, onlyIfPending = false)
        }
        requestDownloadFrom(target)
    }

    /**
     * `limit = 0` means "keep going to the end of the file", exactly what playback wants, unless
     * Low storage mode or "Save mobile data" bounds the request.
     *
     * The result is checked rather than dropped. A refused request looks exactly like a slow
     * one from here (no bytes arrive), and swallowing it turns a flood wait or an expired file
     * reference into a minute of blank screen followed by "Telegram stopped sending", which
     * says nothing a viewer can act on.
     */
    suspend fun requestDownloadFrom(offset: Long, roll: Boolean = false) {
        // Nothing is asked for a source that has been closed: see [shut].
        if (shut) throw IOException("File $fileId is closed")
        // In Low storage mode the request is bounded to the window, and one that would leave more
        // than the window on disk deletes the file first: a roll, or a seek far from what is here.
        val bounded = LowStorageStreams.plan(fileId, offset, size, roll)
        if (bounded?.deleteFirst == true) deleteForLowStorage(offset)
        // On mobile data with "Save mobile data" on, a request reaches only so far ahead and is
        // extended as reading nears its end; nothing is deleted. Low storage mode's own window
        // wins when both apply, which [SaveDataStreams] sees to.
        val saving = if (bounded == null) SaveDataStreams.plan(fileId, offset, size) else null
        // Any warm-up of this file stops here, and its last request is already out: this one wins.
        StreamWarmup.claim(fileId)
        val result = td.downloadFile(
            fileId = fileId,
            priority = PLAYBACK_PRIORITY,
            offset = offset,
            limit = bounded?.limit ?: saving ?: 0L,
            synchronous = false,
        )
        val error = result.errorMessage
        if (error != null) throw IOException(Failures.humanise(error))
        // After a delete the window is empty, which the wait would read as a seek far ahead and
        // move the download again. What TDLib says now is the new file, starting at [offset].
        if (bounded?.deleteFirst == true) td.getFile(fileId).valueOrNull?.let(::absorb)
    }

    /**
     * The request a source makes when it opens at [offset].
     *
     * Nothing, when the running download will reach [offset] sooner than a new one would: a small
     * jump forward within [DownloadWindow.FORWARD_SLACK_BYTES] of what has arrived, which a fresh
     * request would only throw parts in flight away for. Nothing either when the bytes there are
     * on the disk already, an index [StreamWarmup] or an earlier play brought down, or a stretch
     * played before: the reads come off the disk ([side]), and for a seek back into a stretch
     * played before the download carries on from its end ([followSide]). A jump far from where
     * the stream reads is left to the first read, which fetches it as a probe ([probeAt]); any
     * other open is a seek, and asked for at once.
     *
     * Low storage mode does the same within its window ([lowStorageOpen]).
     */
    suspend fun requestUnlessOnDisk(offset: Long) {
        if (lowStorage) {
            lowStorageOpen(offset)
            return
        }
        val known = window
        if (!DownloadWindow.needsRestart(
                position = offset,
                downloadOffset = known.start,
                downloadedPrefixSize = known.end - known.start,
                active = downloading,
                completed = completed,
            )
        ) {
            return
        }
        val run = onDiskAt(offset)
        if (run >= ON_DISK_ENOUGH) {
            Logger.i(TAG, "File $fileId opened at $offset, on the disk already")
            followSide(offset, run)
            return
        }
        if (isFar(offset)) return
        markSeek(offset)
        requestDownloadFrom(offset)
    }

    /**
     * [requestUnlessOnDisk] in Low storage mode, which used to ask TDLib from [offset] at every
     * open and so waited on Telegram for bytes already on the disk: the head of a video played or
     * warmed before, and its index at the far end, which also sent the download there and back.
     *
     * Nothing is asked when the window's own download is running and will reach [offset]. Bytes
     * on the disk there are read from where they are ([onDiskAt]): an index without moving the
     * download, anything else with the download carried on from its end when that fits the window
     * ([followSide]). A read in the index's part of the file away from the picture is left to the
     * first read, which fetches it as a probe ([probeAt]). Anything else is a seek, as before:
     * asked for at once, deleting first when the window says so.
     */
    private suspend fun lowStorageOpen(offset: Long) {
        val known = window
        if (LowStorageStreams.holds(fileId, offset) && !DownloadWindow.needsRestart(
                position = offset,
                downloadOffset = known.start,
                downloadedPrefixSize = known.end - known.start,
                active = downloading,
                completed = completed,
            )
        ) {
            return
        }
        val run = onDiskAt(offset)
        if (run >= ON_DISK_ENOUGH || (run > 0 && offset + run >= size)) {
            Logger.i(TAG, "Low storage: file $fileId opened at $offset, on the disk already")
            followSide(offset, run)
            return
        }
        if (isFar(offset)) return
        markSeek(offset)
        requestDownloadFrom(offset)
    }

    /**
     * The stream reads at [target], in a stretch of [run] bytes on the disk away from TDLib's
     * download ([side]). A probe there (far ahead of where the stream reads: an index at the end)
     * leaves the download alone. Otherwise it is where the stream is now, a seek back into what
     * was played before most often, and the download is asked for from the end of that stretch
     * straight away rather than once the reads have run out of it.
     */
    private suspend fun followSide(target: Long, run: Long) {
        val ph = playhead ?: return
        if (inProbe(ph, target)) return
        val end = minOf(size, target + run)
        if (isFar(target) && target > ph.mainAt) {
            ph.probe = Window(target, end)
            return
        }
        markSeek(target)
        if (end >= size) return
        // In Low storage mode the window has to know the download goes on from [end], or the
        // stretch alone counts as held and the first read in it rolls the window on, deleting the
        // head that was just found on the disk (seen at the start of a video the warm-up brought
        // down, 2026-10-23). The request from [end] records it, and moves nothing that runs there.
        if (!lowStorage && continuesInto(end)) return
        // In Low storage mode a request from its end that would delete the file would take the
        // stretch being read with it: the window rolls on near its end instead, as it does for
        // any stretch it holds (see [LowStorageWindow.onDisk]).
        if (lowStorage && LowStorageStreams.wouldDelete(fileId, end, size)) return
        requestDownloadFrom(end)
    }

    /**
     * Whether [target] is far enough from where the stream reads to be a probe; see [Playhead].
     * In Low storage mode only in the index's part of the file ([LowStorageWindow.nearTheEnd]):
     * anywhere else a probe that the reader then carried on from would be followed by a request
     * that deletes it, so such a jump is a seek from the start.
     */
    private fun isFar(target: Long): Boolean {
        if (saveData || completed) return false
        if (lowStorage && !LowStorageWindow.nearTheEnd(target, size)) return false
        val ph = playhead ?: return false
        val at = ph.mainAt
        // Before the first read there is no stream to keep: wherever it starts is the stream.
        if (at < 0) return false
        // A download that reached the end of the file has nothing to be put back to.
        if (size > 0 && window.end >= size) return false
        if (inProbe(ph, target)) return false
        return kotlin.math.abs(target - at) > DownloadWindow.FORWARD_SLACK_BYTES
    }

    private fun inProbe(ph: Playhead, target: Long): Boolean {
        val p = ph.probe
        return !p.isEmpty && target >= p.start && target < p.end
    }

    /** The stream is at [target] now: a seek, or the first read. */
    private fun markSeek(target: Long) {
        val ph = playhead ?: return
        ph.mainAt = target
        ph.probe = Window.EMPTY
    }

    /** Whether TDLib's download is running and reaches [end] from within what is on the disk. */
    private fun continuesInto(end: Long): Boolean {
        if (completed) return true
        val known = window
        return downloading && known.start <= end && known.end >= end
    }

    /**
     * Whether a read at [target] has come to the last stretch of a probe that stops short of the
     * end of the file. A probe that ends at the end of the file is an index, read to its last
     * byte and then left: never followed.
     */
    private fun promotionDue(target: Long): Boolean {
        val ph = playhead ?: return false
        val p = ph.probe
        if (p.isEmpty || p.end >= size) return false
        return target >= p.end - PROMOTE_MARGIN_BYTES && target <= p.end + PROBE_ALIGN_BYTES
    }

    /**
     * A probe the reader carried on reading to its end was a seek: the stream is there now, and
     * the download follows it from the end of what the probe brought.
     */
    private suspend fun promoteIfDue(target: Long) {
        if (!promotionDue(target)) return
        val p = playhead?.probe ?: return
        Logger.i(TAG, "File $fileId read on past the probe at ${p.start}: a seek, the download follows from $target")
        markSeek(target)
        if (!continuesInto(minOf(target, p.end))) requestDownloadFrom(target)
    }

    /**
     * A read at [target] far from where the stream reads ([isFar]): a megabyte or two around it,
     * on part boundaries, fetched on its own, and then the download pointed back at the stream.
     * Returns the bytes at [target] once [PROBE_READY_BYTES] of them (or the rest of the probe,
     * when less) are on the disk, while the rest of the probe comes down behind the read and the
     * download goes back once it has; or null when they did not land there, and the ordinary
     * wait takes over.
     */
    private suspend fun probeAt(target: Long): Long? {
        val ph = playhead ?: return null
        val start = target - target % PROBE_ALIGN_BYTES
        var end = start + PROBE_BYTES
        // At least half a probe after the byte asked for, so the read it serves is not cut short.
        if (end - target < PROBE_BYTES / 2) end += PROBE_BYTES
        end = minOf(end, size)
        if (end <= target) return null
        // Low storage mode counts the probe against its window, and sends a probe that would take
        // the disk past it down the seek path instead.
        if (lowStorage && !LowStorageStreams.probe(fileId, start, end, size)) return null
        ph.probe = Window(start, end)
        Logger.i(TAG, "File $fileId probed at $target: $start to $end on its own, the stream stays at ${ph.mainAt}")
        if (shut) throw IOException("File $fileId is closed")
        StreamWarmup.claim(fileId)
        suspend fun ask() {
            val result = td.downloadFile(
                fileId = fileId,
                priority = PLAYBACK_PRIORITY,
                offset = start,
                limit = end - start,
                synchronous = false,
            )
            result.errorMessage?.let { throw IOException(Failures.humanise(it)) }
        }
        ask()
        // TDLib sometimes takes such a request and brings nothing for it, until it is asked again:
        // on the 1 GB TV a probe of an MKV's index sat for the whole minute of the stall timeout
        // and then came in a second after the wait below re-asked (2026-10-23, 64 s before the
        // first frame). So a probe with no bytes for [PROBE_NUDGE_MS] is asked for again, and one
        // whose read is still short after [PROBE_GIVE_UP_MS] is left to the ordinary wait, which
        // moves the download to the byte as streaming did before probes.
        val wait = ProbeWait(System.nanoTime())
        val probe = Window(start, end)
        // The read is handed its bytes as soon as a useful amount at [target] is on the disk,
        // rather than once the whole megabyte or two is: an index is parsed while the rest of it
        // comes down, and an index shorter than the probe is not kept waiting for bytes past it.
        val ready = DownloadWindow.probeReady(start, end, target, PROBE_READY_BYTES)
        val have = awaitProbe(target, start, ready, wait) { ask() }
        if (have < 0) {
            Logger.i(TAG, "File $fileId: the probe at $target brought too little of ${end - start} bytes; reading it as a seek")
            ph.probe = Window.EMPTY
            return null
        }
        val run = onDiskAt(target)
        val rest = scope
        if (have >= end - start || run <= 0 || rest == null) {
            // Whole already, or nothing to hand the read yet, or no scope to finish in: the whole
            // of it is waited for here, as before.
            val finished = have >= end - start || awaitProbe(target, start, end - start, wait) { ask() } >= 0
            if (!finished) {
                Logger.i(TAG, "File $fileId: the probe at $target did not finish; reading it as a seek")
                ph.probe = Window.EMPTY
                return null
            }
            val whole = if (run > 0 && have >= end - start) run else onDiskAt(target)
            putBack(ph)
            return whole.takeIf { it > 0 }
        }
        // The rest of the probe keeps coming down, and only once it is whole does the download go
        // back to the stream: the request that puts it back drops whatever parts of the probe are
        // still in flight. A source closed meanwhile (the reader went back to the picture, which
        // opens the stream again and asks for it there) cancels this with its scope.
        rest.launch {
            try {
                val whole = awaitProbe(target, start, end - start, wait) { ask() }
                // The stream moved since (a seek, or the reader carried on past the probe and the
                // download followed it): nothing here is to be put back.
                if (ph.probe != probe) return@launch
                if (whole < 0) {
                    Logger.i(TAG, "File $fileId: the probe at $target did not finish; its reads wait the ordinary way")
                    return@launch
                }
                // The reads still to come in it are served from the disk once the download leaves.
                onDiskAt(target)
                putBack(ph)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.i(TAG, "File $fileId: the rest of the probe at $target stopped: ${e.message}")
            }
        }
        return run
    }

    /** How long a probe has been waited for, and what it brought: one per [probeAt], shared by both of its waits. */
    private class ProbeWait(val started: Long) {
        var lastHave = -1L
        var lastGrowth = started
        var asked = 1
    }

    /**
     * Waits until [need] bytes from [start] are on the disk, asking for the probe again ([ask])
     * once when nothing came for [PROBE_NUDGE_MS]. Returns the bytes there, or -1 once
     * [PROBE_GIVE_UP_MS] have gone by since the probe was first asked for.
     */
    private suspend fun awaitProbe(target: Long, start: Long, need: Long, wait: ProbeWait, ask: suspend () -> Unit): Long {
        while (true) {
            if (shut) throw IOException("File $fileId was closed while a read waited")
            val have = td.getFileDownloadedPrefixSize(fileId, start).valueOrNull?.size ?: 0L
            if (have >= need) return have
            val now = System.nanoTime()
            if (have > wait.lastHave) {
                wait.lastHave = have
                wait.lastGrowth = now
            }
            if (now - wait.started >= PROBE_GIVE_UP_MS * 1_000_000) {
                Logger.i(TAG, "File $fileId: the probe at $target has $have of the $need bytes it waits for")
                return -1
            }
            if (now - wait.lastGrowth >= PROBE_NUDGE_MS * 1_000_000 && wait.asked < 2) {
                Logger.i(TAG, "File $fileId: no bytes for the probe at $target; asking again")
                if (!OfflineDownloads.isDownloading(fileId)) td.cancelDownloadFile(fileId, onlyIfPending = false)
                ask()
                wait.asked++
                wait.lastGrowth = now
            }
            val seen = revision.value
            if (withTimeoutOrNull(POLL_INTERVAL_MS) { revision.first { it != seen } } == null) {
                td.getFile(fileId).valueOrNull?.let(::absorb)
            }
        }
    }

    /** After a probe: the download pointed back at where the stream reads. */
    private suspend fun putBack(ph: Playhead) {
        val back = ph.mainAt
        // Not in Low storage mode when going back would delete the file, the probe with it: the
        // stream's next read asks from where it is.
        val allowed = !(lowStorage && LowStorageStreams.wouldDelete(fileId, back, size))
        if (back in 0 until size && !completed && allowed) requestDownloadFrom(back)
    }

    /**
     * Rolls the Low storage window on when reading at [target] has nearly reached the end of it:
     * a seek this source makes to itself, to just behind [target], through [requestDownloadFrom],
     * which deletes the file first because keeping it would double what is on disk. Readers that
     * find the roll due meanwhile wait here, and read only once TDLib reports the new file.
     */
    private suspend fun rollIfDue(target: Long) = rollLock.withLock {
        if (!LowStorageStreams.rollDue(fileId, target, size)) return@withLock
        val from = LowStorageWindow.rollStart(target)
        Logger.i(TAG, "Low storage: rolling file $fileId on at $target, from $from")
        requestDownloadFrom(from, roll = true)
    }

    /**
     * Asks TDLib again when [SaveDataStreams] says a read at [target] is due one: reading has
     * nearly reached the end of the stretch asked for on mobile data, or the connection changed
     * between metered and not since the last request. The request starts at [target], on a part
     * boundary, and deletes nothing: TDLib skips what it already has and carries on from the end
     * of it. Readers that find it due meanwhile wait here, as for a roll.
     */
    private suspend fun extendIfDue(target: Long) = rollLock.withLock {
        if (!SaveDataStreams.extendDue(fileId, target, size)) return@withLock
        val from = SaveDataWindow.extendStart(target)
        Logger.i(TAG, "Save mobile data: asking again for file $fileId from $from")
        requestDownloadFrom(from)
    }

    /**
     * Deletes the file ahead of a request from [offset] in Low storage mode.
     *
     * The window is dropped first, under the lock reads take, so nothing is read from a file that
     * is going; updates about it are ignored until TDLib has been asked for the next one.
     */
    private suspend fun deleteForLowStorage(offset: Long) {
        synchronized(this) {
            deleting = true
            window = Window.EMPTY
            side = Window.EMPTY
        }
        try {
            Logger.i(TAG, "Low storage: deleting file $fileId to restart at $offset")
            runCatching { td.deleteFile(fileId) }
        } finally {
            deleting = false
        }
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
        /** What [requestUnlessOnDisk] wants on the disk at an offset before it leaves the download be. */
        private const val ON_DISK_ENOUGH = 256L * 1024

        /** A probe's fetch: one of the parts Telegram hands files out in, on its boundary. */
        private const val PROBE_BYTES = 1024L * 1024
        private const val PROBE_ALIGN_BYTES = 1024L * 1024

        /** How near a probe's end a read has to come before the download follows it. */
        private const val PROMOTE_MARGIN_BYTES = 256L * 1024

        /** How much at a probe's byte is enough to hand its read, while the rest comes down. */
        private const val PROBE_READY_BYTES = 64L * 1024

        /** How long a probe may bring nothing before it is asked for again; see [probeAt]. */
        private const val PROBE_NUDGE_MS = 2_000L

        /** How long a probe may take before its read waits the ordinary way instead. */
        private const val PROBE_GIVE_UP_MS = 8_000L

        /** With no window open at a file for this long, its next open is a new play. */
        private const val NEW_PLAY_AFTER_MS = 5_000L

        private val playheads = LinkedHashMap<Int, Playhead>()

        private fun takePlayhead(fileId: Int): Playhead = synchronized(playheads) {
            val ph = playheads.getOrPut(fileId) { Playhead() }
            if (ph.open == 0 && System.currentTimeMillis() - ph.closedAt > NEW_PLAY_AFTER_MS) {
                ph.mainAt = -1L
                ph.probe = Window.EMPTY
            }
            ph.open++
            // Only the last few plays' files are worth remembering.
            if (playheads.size > 16) {
                playheads.entries.firstOrNull { it.value.open == 0 && it.key != fileId }?.let { playheads.remove(it.key) }
            }
            ph
        }

        /**
         * Whether a player has [fileId] open now. TDLib keeps one download per file, so a saved
         * download of the same file waits while it is (DownloadService): taking the download back
         * from the player every two seconds left the player's reads unanswered for minutes.
         */
        fun isStreaming(fileId: Int): Boolean = synchronized(playheads) { (playheads[fileId]?.open ?: 0) > 0 }

        private fun letGo(ph: Playhead) = synchronized(playheads) {
            ph.open = (ph.open - 1).coerceAtLeast(0)
            if (ph.open == 0) ph.closedAt = System.currentTimeMillis()
        }

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
