package com.tmplayer.player

import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.RandomAccessFile

/**
 * Brings a video's first bytes, and the index its demuxer jumps to next ([StreamIndex]), onto the
 * disk before the player asks for them, so the start costs no round trip the viewer waits on.
 *
 * Why: TDLib keeps one download per file, and the player's first request used to go out only once
 * the player existed, after the settings reads and the player build; then an MKV's Cues or an
 * MP4's trailing moov sent the download to the end of the file and back, a round trip each way.
 * Warmed, the head is already here and the index too, and [TdByteWindow] reads the index from the
 * disk without moving the download ([TdByteWindow.awaitBytesAt]).
 *
 * The call to make, from anywhere and as often as wanted (it is cheap, deduplicated and never
 * blocks): `StreamWarmup.warm(item)`, or `StreamWarmup.warm(fileId, sizeBytes)`. Wanted on a
 * press of Play (at once), on a card that has had the focus or the pointer for about 250 ms, on
 * a video page's Play button, and when the next episode card comes up.
 *
 * Then TDLib is left downloading the file from the end of the head with no limit, where playback
 * will read first, for [CONTINUE_MS] or until the player claims it: never idle in the second or
 * two between a press of Play and the player's first request. A video with a saved place (the
 * `startAt` a caller passes: Continue watching, a resumed open) is left downloading from there
 * instead, the byte its seek will read found in the index just fetched ([StreamIndex.offsetAt])
 * or else guessed from the running time ([ResumeWindow]): the head and the index still come
 * first, and the place is asked for only once they are here. A warm-up that gives way (to a
 * newer one, or to a play of another file) or runs out cancels its download, so a card the focus
 * passed over does not keep the line busy. While a video plays, a warm-up (of the next episode)
 * brings the head and the index only.
 *
 * Never in the player's way: a player that opens the file claims it ([claim]), and from then on
 * nothing is asked of TDLib here; claim and request are serialised, so the player's own request
 * is always the last word. A claim also stops the warm-ups of every other file, which would share
 * the line with the stream. Skipped for a file already whole, one the viewer is downloading (that
 * download is not to be moved), a cut video's pieces or a film in parts, and wherever [allowed]
 * says no ("Save mobile data" on a metered line).
 *
 * In Low storage mode ([lowStorage]) only the head and the index come, a few megabytes that the
 * mode's window counts once the player finds them ([LowStorageStreams.onDisk]): no download is
 * left running after them, no resumed place is fetched, and a file that had nothing on the disk
 * before and is not played within [LOW_STORAGE_KEEP_MS] is deleted again, so a card the focus
 * passed over leaves nothing behind. Without them the player waited on Telegram for the index at
 * the far end of the file and then for the way back, which is most of why a start in that mode
 * took so much longer.
 */
object StreamWarmup {

    /**
     * The platform's say: false with "Save mobile data" on a metered line. False until the
     * platform has read its settings, so nothing is warmed before it knows whether Low storage
     * mode ([lowStorage]) is on.
     */
    @Volatile
    var allowed: () -> Boolean = { false }

    /** Whether Low storage mode is on, which keeps a warm-up to the head and the index. */
    @Volatile
    var lowStorage: () -> Boolean = { false }

    /** How long a Low storage warm-up's bytes wait for a player before they are deleted again. */
    private const val LOW_STORAGE_KEEP_MS = 5 * 60_000L

    /** How much of the start to bring: what the demuxer reads before the first frame. */
    const val HEAD_BYTES = 1L * 1024 * 1024

    /** 1..32; under playback's 32, over thumbnails. */
    private const val PRIORITY = 24

    /** How long one stretch may take before the warm-up gives up on it. */
    private const val STRETCH_TIMEOUT_MS = 8_000L

    /** A backstop for an update missed: TDLib's own updates wake the wait as the bytes land. */
    private const val POLL_MS = 500L

    /** How long the download is left running after the warm-up for a player that has not come. */
    private const val CONTINUE_MS = 20_000L

    /** How long the caller's saved place may take to read before the warm-up does without it. */
    private const val START_LOOKUP_MS = 1_500L

    /** A file warmed this recently is not warmed again. */
    private const val FRESH_MS = 10 * 60_000L

    /** At most this many warm-ups at once: the newest ones, as the focus moves. */
    private const val AT_ONCE = 2

    private val lock = Mutex()
    private val claimed = HashSet<Int>()
    private val warmedAt = LinkedHashMap<Int, Long>()
    private val jobs = LinkedHashMap<Int, Job>()

    /** When each file was last claimed by a player, so a Low storage clean-up spares a played one. */
    private val claimedAt = LinkedHashMap<Int, Long>()

    /**
     * Warms [item]'s file; see the class. Does nothing for a cut video or a film in parts.
     * [startAt], when given, says where the player will open it (0 for the start); asked only
     * once the head and the index are here, and given [START_LOOKUP_MS] to answer.
     */
    fun warm(item: MediaItem, startAt: (suspend () -> Long)? = null) {
        if (item.pieces.isNotEmpty() || item.parts.size > 1) return
        warm(item.fileId, item.sizeBytes, item.durationSec * 1000L, startAt)
    }

    /**
     * Warms file [fileId] of [sizeBytes] bytes (0 when unknown) and [durationMs] long (0 when
     * unknown); see the class and [warm] for [startAt].
     */
    fun warm(fileId: Int, sizeBytes: Long, durationMs: Long = 0L, startAt: (suspend () -> Long)? = null) {
        if (fileId <= 0) return
        if (!runCatching { allowed() }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (fileId in claimed || jobs[fileId]?.isActive == true) return
            if ((warmedAt[fileId] ?: 0L) > now - FRESH_MS) return
            warmedAt[fileId] = now
            if (warmedAt.size > 64) warmedAt.remove(warmedAt.keys.first())
            // The oldest give way to the newest: the focus has moved on from them.
            while (jobs.count { it.value.isActive } >= AT_ONCE) {
                val oldest = jobs.entries.first { it.value.isActive }
                oldest.value.cancel()
                jobs.remove(oldest.key)
            }
            jobs[fileId] = Background.scope.launch { run(fileId, sizeBytes, durationMs, startAt) }
        }
    }

    /**
     * The player opens [fileId]: nothing more is asked of TDLib for it here. Suspends only for the
     * moment a warm-up's own request is being sent, so the player's request always comes after it.
     */
    suspend fun claim(fileId: Int) {
        lock.withLock {
            synchronized(this) {
                val starting = claimed.add(fileId)
                claimedAt[fileId] = System.currentTimeMillis()
                if (claimedAt.size > 64) claimedAt.remove(claimedAt.keys.first())
                jobs.remove(fileId)?.cancel()
                // A play starting has the line to itself: every other warm-up stops, and its
                // download with it (see [run]). Only at the start, so the next episode's warm-up
                // during the play is not stopped by the stream's every request.
                if (starting) {
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                }
            }
        }
    }

    /** The player let go of [fileId]. */
    fun release(fileId: Int) {
        synchronized(this) { claimed -= fileId }
    }

    private fun isClaimed(fileId: Int) = synchronized(this) { fileId in claimed }

    private suspend fun run(fileId: Int, sizeHint: Long, durationMs: Long, startAt: (suspend () -> Long)?) {
        val started = System.currentTimeMillis()
        val session = Td.awaitConnectedSessionOrNull(3_000) ?: return
        val td = session.client
        val file = td.getFile(fileId).valueOrNull ?: return
        if (file.local.isDownloadingCompleted || OfflineDownloads.isDownloading(fileId)) return
        val size = if (file.size > 0) file.size else file.expectedSize.takeIf { it > 0 } ?: sizeHint
        if (size <= 0) return
        val light = runCatching { lowStorage() }.getOrDefault(true)
        // Only a file this warm-up starts from nothing is its own to delete again.
        if (light && file.local.downloadedSize <= 0L) cleanUpLater(td, fileId, started)
        // TDLib's updates for the file wake the waits below as the bytes land.
        val heard = Channel<Unit>(Channel.CONFLATED)
        val listener = Background.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.fileUpdates.filter { it.file.id == fileId }.collect { heard.trySend(Unit) }
        }
        try {
            val head = minOf(HEAD_BYTES, size)
            if (!fetch(td, fileId, 0, head, heard)) return
            val bytes = readAt(td, fileId, 0, head) ?: return
            val index = StreamIndex.locate(bytes, size)
            // Low storage mode reads a read anywhere in it as the index, whatever its size (HX79).
            index?.let { LowStorageStreams.knowIndex(fileId, it.start, size) }
            val indexHere = index != null && fetch(td, fileId, index.start, index.length, heard)
            Logger.i(TAG, "Warmed file $fileId: head ${head / 1024} KB${index?.let { ", index ${it.length / 1024} KB at ${it.start}" } ?: ""} in ${System.currentTimeMillis() - started} ms")
            // Low storage mode: the head and the index only; the player's window does the rest.
            if (light) return
            val resume = startAt?.let { resumeFrom(td, fileId, bytes, index?.takeIf { indexHere }, size, durationMs, it, heard) }
            keepGoing(td, fileId, resume ?: head, size)
        } finally {
            listener.cancel()
            // Whatever was asked for and is not the player's now stops: a warm-up given way, run
            // out or failed leaves no download behind.
            withContext(NonCancellable) {
                lock.withLock {
                    if (!isClaimed(fileId) && !OfflineDownloads.isDownloading(fileId)) {
                        runCatching { td.cancelDownloadFile(fileId, onlyIfPending = false) }
                    }
                }
            }
        }
    }

    /**
     * Where a resumed video's download is to carry on from: the byte its seek to the place
     * [startAt] gives will read, put back a little ([ResumeWindow]); null for a video opening at
     * the start, a place inside the head, or while a video plays (the line is the stream's then).
     * [head] is the file's first bytes and [index] the index [StreamIndex.locate] found, when it
     * is on the disk. An MP4 whose moov comes first but runs past the head has the rest of its
     * moov fetched here, which the player reads next anyway.
     */
    private suspend fun resumeFrom(
        td: TdlClient,
        fileId: Int,
        head: ByteArray,
        index: StreamIndex.Range?,
        size: Long,
        durationMs: Long,
        startAt: suspend () -> Long,
        heard: Channel<Unit>,
    ): Long? {
        if (playingAny() || isClaimed(fileId)) return null
        val startMs = withTimeoutOrNull(START_LOOKUP_MS) { runCatching { startAt() }.getOrDefault(0L) } ?: 0L
        if (startMs <= 0) return null
        var found = index
        if (found == null) {
            val moov = StreamIndex.mp4Moov(head)
            if (moov != null && moov.length <= StreamIndex.MOOV_BYTES && moov.end <= size &&
                fetch(td, fileId, head.size.toLong(), moov.end - head.size, heard)
            ) {
                found = moov
            }
        }
        val indexBytes = found?.let { readAt(td, fileId, it.start, it.length) }
        val mapped = StreamIndex.offsetAt(head, indexBytes, found?.start ?: -1L, startMs)
        val from = mapped?.let { ResumeWindow.indexed(it, size) } ?: ResumeWindow.estimate(startMs, durationMs, size)
        Logger.i(TAG, "Resume of file $fileId at $startMs ms: from byte ${from ?: "-"} (${if (mapped != null) "index" else "running time"})")
        return from?.takeIf { it > head.size }
    }

    private fun playingAny() = synchronized(this) { claimed.isNotEmpty() }

    /**
     * In Low storage mode, deletes what a warm-up started at [warmedFrom] brought of [fileId]
     * (which had nothing on the disk before it) once [LOW_STORAGE_KEEP_MS] have gone by with no
     * player claiming it since. A file played meanwhile keeps its bytes as any played file does,
     * and so does one the viewer is downloading or with the mode turned off.
     */
    private fun cleanUpLater(td: TdlClient, fileId: Int, warmedFrom: Long) {
        Background.scope.launch {
            delay(LOW_STORAGE_KEEP_MS)
            if (!runCatching { lowStorage() }.getOrDefault(false)) return@launch
            lock.withLock {
                val played = synchronized(this@StreamWarmup) {
                    fileId in claimed || (claimedAt[fileId] ?: 0L) >= warmedFrom || jobs[fileId]?.isActive == true
                }
                if (played || OfflineDownloads.isDownloading(fileId)) return@withLock
                val file = td.getFile(fileId).valueOrNull ?: return@withLock
                if (file.local.isDownloadingCompleted || file.local.downloadedSize <= 0L) return@withLock
                Logger.i(TAG, "Low storage: file $fileId was warmed and not played, deleting it again")
                runCatching { td.deleteFile(fileId) }
            }
        }
    }

    /**
     * Leaves TDLib downloading the file from [from] with no limit, where playback will read once
     * the head is used up, for [CONTINUE_MS] or until the player claims it. Not while a video
     * plays: the next episode's warm-up must not share the line with the stream.
     */
    private suspend fun keepGoing(td: TdlClient, fileId: Int, from: Long, size: Long) {
        if (from >= size) return
        lock.withLock {
            val playing = synchronized(this) { claimed.isNotEmpty() }
            if (playing || isClaimed(fileId) || OfflineDownloads.isDownloading(fileId)) return
            td.downloadFile(fileId, PRIORITY, from, 0, false)
        }
        delay(CONTINUE_MS)
    }

    /** Brings `[offset, offset + length)` onto the disk; false when it could not, or the player came. */
    private suspend fun fetch(td: TdlClient, fileId: Int, offset: Long, length: Long, heard: Channel<Unit>): Boolean {
        if (have(td, fileId, offset) >= length) return true
        lock.withLock {
            if (isClaimed(fileId) || OfflineDownloads.isDownloading(fileId)) return false
            td.downloadFile(fileId, PRIORITY, offset, length, false)
        }
        val done = withTimeoutOrNull(STRETCH_TIMEOUT_MS) {
            while (have(td, fileId, offset) < length) {
                if (isClaimed(fileId)) return@withTimeoutOrNull false
                withTimeoutOrNull(POLL_MS) { heard.receive() }
            }
            true
        }
        return done == true
    }

    private suspend fun have(td: TdlClient, fileId: Int, offset: Long): Long =
        td.getFileDownloadedPrefixSize(fileId, offset).valueOrNull?.size ?: 0L

    /** Bytes `[offset, offset + length)` of the file as they are on the disk, cut at its end. */
    private suspend fun readAt(td: TdlClient, fileId: Int, offset: Long, length: Long): ByteArray? {
        val path = td.getFile(fileId).valueOrNull?.local?.path?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            RandomAccessFile(path, "r").use { f ->
                val n = minOf(length, f.length() - offset).toInt()
                if (n <= 0) return@use null
                f.seek(offset)
                ByteArray(n).also { f.readFully(it) }
            }
        }.getOrNull()
    }

    private const val TAG = "StreamWarmup"
}
