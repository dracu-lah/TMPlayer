package com.tmplayer.player

/**
 * How much of a partially downloaded Telegram file can be read right now.
 *
 * TDLib fills one contiguous window at a time: bytes `[downloadOffset, downloadOffset +
 * downloadedPrefixSize)` of the file are on disk and valid, everything else is a hole. Seeking
 * moves that window, which is the whole reason playback needs this arithmetic rather than
 * just trusting the file length.
 *
 * Pure by design: the seek behaviour that matters most is unit-tested through this function.
 */
object DownloadWindow {

    /**
     * Number of bytes readable starting at [position], or 0 if that byte is not downloaded yet.
     *
     * @param size total file size, or 0 when TDLib does not know it yet
     */
    fun availableAt(
        position: Long,
        size: Long,
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        completed: Boolean,
    ): Long {
        if (position < 0) return 0
        if (completed) return (size - position).coerceAtLeast(0)

        val windowEnd = downloadOffset + downloadedPrefixSize
        if (position < downloadOffset || position >= windowEnd) return 0
        return windowEnd - position
    }

    /**
     * Whether the download has to be restarted at [position].
     *
     * True when the byte we want sits outside the current window, either behind it (the user
     * seeked back) or far enough ahead that waiting for the window to reach it would stall.
     */
    fun needsRestart(
        position: Long,
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        active: Boolean,
        completed: Boolean,
    ): Boolean {
        if (completed) return false
        if (position < downloadOffset) return true
        val windowEnd = downloadOffset + downloadedPrefixSize
        if (position > windowEnd + FORWARD_SLACK_BYTES) return true
        // Sitting exactly at the edge with nothing downloading means TDLib gave up on us.
        return !active && position >= windowEnd
    }

    /**
     * How much of a probe of `[start, end)` (a small fetch of its own for a read far from the
     * stream, see `TdByteWindow`) has to be on the disk, counted from [start], before the read at
     * [target] it was fetched for is handed its bytes: [readyBytes] at [target], or all that is
     * left of the probe from there when that is less.
     */
    fun probeReady(start: Long, end: Long, target: Long, readyBytes: Long): Long {
        val at = target.coerceIn(start, end)
        return minOf(end, at + readyBytes) - start
    }

    /**
     * A seek within this many bytes ahead of what is already downloaded is left alone: the
     * running download will reach it sooner than a restart would.
     */
    const val FORWARD_SLACK_BYTES = 4L * 1024 * 1024
}

/**
 * The arithmetic of Low storage mode: a streamed video keeps only a bounded window of itself on
 * the disk, so a film larger than the free space can still be watched.
 *
 * TDLib writes every downloaded byte to its file and only lets go of a whole file at once, but it
 * downloads a range on request and leaves the rest of the file as holes, which the file system
 * keeps sparse: after a delete and a fresh start at 509 MB, the file was 549 MB long and took 40 MB
 * of disk (measured with `du` on the desktop's TDLib). So the window is bounded by asking for
 * [WINDOW_BYTES] at a time instead of the rest of the file, and moved on by a roll: delete the
 * file, ask again from just behind the read position. To the reader a roll is a seek it made to
 * itself.
 *
 * The bytes on disk are tracked as the ranges asked for since the last delete, which can only
 * overstate them: TDLib keeps a range it was moved away from, which is why a seek far away has to
 * delete first, too.
 *
 * Pure, so the roll and the budget are tested without TDLib.
 */
object LowStorageWindow {

    /** How much of a video is asked for at a time, and so roughly the most it holds on disk. */
    const val WINDOW_BYTES = 200L * 1024 * 1024

    /** The smallest window worth streaming through; below this every few seconds is a roll. */
    const val MIN_WINDOW_BYTES = 32L * 1024 * 1024

    /** Kept behind the read position at a roll, so a jump back of a few seconds needs no fetch. */
    const val BACK_BYTES = 2L * 1024 * 1024

    /** A roll restarts on a part boundary: Telegram hands files out in parts of this size. */
    const val ALIGN_BYTES = 1024L * 1024

    /** How close reading may get to the end of what was asked for before the window rolls on. */
    const val ROLL_MARGIN_BYTES = 4L * 1024 * 1024

    /**
     * What the ranges on disk may add up to before a request has to delete first. A little over
     * the window, so the few megabytes an extractor reads at the far end of a file (Matroska's
     * index) do not cost the start of the film.
     */
    const val SLACK_BYTES = 16L * 1024 * 1024

    /** One stretch of the file asked for, bytes `[start, end)`. */
    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    /**
     * What one download request should be.
     *
     * @property limit for TDLib's `downloadFile`. Never 0, which TDLib reads as "no limit": having
     *   reached the end of the file it carries on from the start until it has the lot, which on a
     *   TV emulator filled 332 MB of a 369 MB film after a seek into its last third.
     * @property deleteFirst the file has to be deleted before the request goes out.
     * @property ranges what is on disk once it has, to be kept for the next request.
     */
    data class Request(val limit: Long, val deleteFirst: Boolean, val ranges: List<Range>)

    /**
     * The window for a device with [freeBytes] free: [WINDOW_BYTES], less on a nearly full disk,
     * leaving [headroomBytes] (the room check's headroom) and the back buffer alone.
     */
    fun windowBytes(freeBytes: Long, headroomBytes: Long): Long =
        (freeBytes - headroomBytes - BACK_BYTES).coerceIn(MIN_WINDOW_BYTES, WINDOW_BYTES)

    /**
     * The room a stream of [remainingBytes] needs in this mode: the window and its back buffer,
     * or the rest of the video when that is less.
     */
    fun roomNeeded(remainingBytes: Long, windowBytes: Long): Long =
        minOf(remainingBytes.coerceAtLeast(0), windowBytes + BACK_BYTES)

    /** Every byte the [ranges] cover, overlaps counted once. */
    fun bytes(ranges: List<Range>): Long = merge(ranges).sumOf { it.length }

    /** [ranges] sorted and with touching or overlapping ones joined. */
    fun merge(ranges: List<Range>): List<Range> {
        val sorted = ranges.filter { it.length > 0 }.sortedBy { it.start }
        val out = mutableListOf<Range>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end) {
                out[out.lastIndex] = Range(last.start, maxOf(last.end, r.end))
            } else {
                out += r
            }
        }
        return out
    }

    /**
     * The request for a download from [offset] of a file of [size] bytes, given what is already on
     * disk. It asks for [window] bytes, or the rest of the file when that is closer, and deletes
     * first when keeping what is there would take the disk past the window and its slack, or when
     * it is a [roll]. The file's index, when [indexStart] says where it is ([indexOf]), is held on
     * top of that budget: a film's moov or Cues has to be read whatever its size, and counting it
     * against the window is what made reading a 12 MB one delete the head (HX79).
     */
    fun plan(ranges: List<Range>, offset: Long, size: Long, window: Long, roll: Boolean = false, indexStart: Long = NO_INDEX): Request {
        val end = if (size > 0) minOf(size, offset + window) else offset + window
        val limit = (end - offset).coerceAtLeast(1)
        val wanted = Range(offset, end)
        val kept = merge(ranges + wanted)
        val budget = window + SLACK_BYTES
        // A roll always starts afresh: keeping the window it moves on from is what it is there to stop.
        if (roll) return Request(limit, deleteFirst = true, ranges = listOf(wanted))
        if (counted(kept, indexStart, size) <= budget) return Request(limit, deleteFirst = false, ranges = kept)

        // A request from inside what is already on disk, such as the jump back from a Matroska or
        // MP4 index at the far end: deleting would throw away the very bytes about to be read. It
        // grows that stretch only as far as the budget allows instead, and the window rolls on
        // from there in the ordinary way once reading nears its end.
        val held = merge(ranges)
        val here = held.firstOrNull { offset >= it.start && offset < it.end }
        if (here != null) {
            val spare = (budget - counted(held, indexStart, size)).coerceAtLeast(0)
            val shortEnd = if (size > 0) minOf(size, here.end + spare) else here.end + spare
            if (shortEnd - offset > ROLL_MARGIN_BYTES) {
                return Request(shortEnd - offset, deleteFirst = false, ranges = merge(held + Range(offset, shortEnd)))
            }
        }
        return Request(limit, deleteFirst = true, ranges = listOf(wanted))
    }

    /**
     * Whether reading at [position] should roll the window on now.
     *
     * Only inside a range that stops short of the end of the file: a read outside every range is a
     * seek, which the seek path already restarts, and a window that reaches the end has nowhere
     * further to go.
     */
    fun shouldRoll(position: Long, ranges: List<Range>, size: Long): Boolean {
        val here = merge(ranges).firstOrNull { position >= it.start && position < it.end } ?: return false
        if (size > 0 && here.end >= size) return false
        return position >= here.end - ROLL_MARGIN_BYTES
    }

    /**
     * What to do with a stretch of [run] bytes at [offset] that TDLib already has on the disk,
     * outside the download it is running: an index a warm-up or an earlier play brought down, the
     * head of a video played before, a stretch watched before a seek back.
     *
     * Reading it costs no disk at all, so it is always worth reading when it reaches the end of
     * the file (an index: nothing follows it) or when the download can carry on from its end
     * within the window. Otherwise it is recorded and read as long as it is long enough to be
     * worth it, and the window rolls on near its end as for any stretch the mode asked for, which
     * is where whatever more than the window is on the disk goes. Too short for that, it is
     * fetched the ordinary way, as before.
     *
     * [ranges] are what the mode holds so far; the stretch is added to them in the answer, since
     * it is on the disk whatever is done with it. One in the index's part of the file
     * ([nearTheEnd]) is recorded as reaching the end of it ([indexRange]), so reading it never
     * rolls the window.
     */
    fun onDisk(ranges: List<Range>, offset: Long, run: Long, size: Long, window: Long, indexStart: Long = NO_INDEX): OnDisk {
        if (run <= 0) return OnDisk(OnDisk.Use.Fetch, ranges)
        val end = if (size > 0) minOf(size, offset + run) else offset + run
        if (nearTheEnd(offset, size, indexStart)) {
            return OnDisk(OnDisk.Use.Read, merge(ranges + indexRange(offset, end, size, indexStart)))
        }
        val held = merge(ranges + Range(offset, end))
        if (size > 0 && end >= size) return OnDisk(OnDisk.Use.Read, held)
        if (!plan(held, end, size, window, indexStart = indexStart).deleteFirst) return OnDisk(OnDisk.Use.ReadAndFollow, held)
        if (end - offset > ROLL_MARGIN_BYTES + ALIGN_BYTES) return OnDisk(OnDisk.Use.ReadThenRoll, held)
        return OnDisk(OnDisk.Use.Fetch, ranges)
    }

    /** The answer of [onDisk]: how the stretch is used, and the ranges held once it is. */
    data class OnDisk(val use: Use, val ranges: List<Range>) {
        enum class Use {
            /** Read it; it runs to the end of the file or lies in its index, so nothing follows it. */
            Read,

            /** Read it, and ask for the download from its end now: that fits the window. */
            ReadAndFollow,

            /** Read it, ask nothing now, and roll the window on near its end. */
            ReadThenRoll,

            /** Leave it: fetch from [offset] the ordinary way. */
            Fetch,
        }
    }

    /**
     * Whether a probe of `[start, end)` (a small fetch of its own, see `TdByteWindow`) still keeps
     * what [ranges] hold within the window and its slack, the known index aside ([counted]).
     */
    fun probeFits(ranges: List<Range>, start: Long, end: Long, size: Long, window: Long, indexStart: Long = NO_INDEX): Boolean =
        counted(ranges + indexRange(start, end, size, indexStart), indexStart, size) <= window + SLACK_BYTES

    /**
     * The range held for `[start, end)` read as an index: up to the end of the file when it lies
     * in the index's part of it ([nearTheEnd]). This overstates the disk by a few megabytes at
     * most, which the slack covers, and keeps [shouldRoll] from rolling the window on at an
     * index read, which would delete the film's start for its last megabyte.
     */
    fun indexRange(start: Long, end: Long, size: Long, indexStart: Long = NO_INDEX): Range =
        Range(start, if (nearTheEnd(start, size, indexStart)) size else end)

    /**
     * Whether a read at [position] is close enough to the end of a file of [size] bytes to be its
     * index (an MKV's Cues and Tags, an MP4's moov) rather than a seek: such a read is fetched as
     * a probe and the download goes back to where the picture is, instead of moving there.
     *
     * The last [INDEX_TAIL_BYTES] always count; a known index ([indexStart], [indexOf]) counts
     * from where it starts, however much longer it is, up to [MAX_INDEX_BYTES].
     */
    fun nearTheEnd(position: Long, size: Long, indexStart: Long = NO_INDEX): Boolean {
        if (size <= 0 || position >= size) return false
        val index = indexOf(indexStart, size)
        if (index != null && position >= index.start) return true
        return size - position <= INDEX_TAIL_BYTES
    }

    /**
     * The index of a file of [size] bytes starting at [indexStart] (from the file's head:
     * `StreamIndex.locate`), taken to run to the end of the file as a trailing moov or Cues does;
     * null when it is not known, outside the file, or longer than [MAX_INDEX_BYTES], which no
     * index is and anything else (a bad hint) must not be read as.
     */
    fun indexOf(indexStart: Long, size: Long): Range? {
        if (size <= 0 || indexStart < 0 || indexStart >= size) return null
        if (size - indexStart > MAX_INDEX_BYTES) return null
        return Range(indexStart, size)
    }

    /** What [ranges] take on the disk against the window's budget: every byte, less the known index's. */
    fun counted(ranges: List<Range>, indexStart: Long, size: Long): Long {
        val all = merge(ranges)
        val index = indexOf(indexStart, size) ?: return bytes(all)
        val inIndex = all.sumOf { (minOf(it.end, index.end) - maxOf(it.start, index.start)).coerceAtLeast(0) }
        return bytes(all) - inIndex
    }

    /** No index known for the file. */
    const val NO_INDEX = -1L

    /** The largest index held beside the window: a long film's moov is tens of megabytes at most. */
    const val MAX_INDEX_BYTES = 32L * 1024 * 1024

    /** How much of the end of a file [nearTheEnd] counts as its index; within [SLACK_BYTES]. */
    const val INDEX_TAIL_BYTES = 8L * 1024 * 1024

    /** Where a roll at [position] asks TDLib to start: a little behind it, on a part boundary. */
    fun rollStart(position: Long): Long {
        val back = (position - BACK_BYTES).coerceAtLeast(0)
        return back - back % ALIGN_BYTES
    }
}
