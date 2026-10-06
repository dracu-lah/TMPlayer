package com.tmplayer.data

/**
 * Thumbnails over the scrub bar, drawn only from bytes already on the disk.
 *
 * A frame is never worth a network request: Telegram hands a file out at a few megabytes a second,
 * and a scrub that pulled a slice from every point it passed would steal that from the playback it
 * is meant to serve. So a position gets a picture only when its part of the file is already here,
 * and the rest of the bar simply has none.
 *
 * Telegram describes a file in bytes, not seconds, so where a position sits in the file is
 * estimated from the average bit rate, with [SLACK_MS] of room either side for the keyframe the
 * decoder starts from and for a bit rate that is not quite even.
 */
object Trickplay {

    /**
     * Devices with less memory than this get no thumbnails. A "1 GB" device reports a little under
     * a gigabyte to apps and a "1.5 GB" one a little under that, so the line sits between them.
     */
    const val MIN_TOTAL_MEMORY_BYTES = 1_288_490_189L // 1.2 GiB

    /** Positions share a thumbnail within this step, which is what the cache is keyed by. */
    const val STEP_MS = 10_000L

    /** Room either side of the estimated byte for the keyframe and an uneven bit rate. */
    const val SLACK_MS = 6_000L

    /** And never less than this, for a low bit rate where six seconds is a few hundred kilobytes. */
    const val MIN_SLACK_BYTES = 1_048_576L

    /**
     * The head of the file a decoder reads before any frame: the container's header and track
     * descriptions. Without it no position is readable, however much of the middle is here.
     */
    const val HEAD_BYTES = 2_097_152L

    /** A run of bytes on disk, [start] inclusive, [end] exclusive. */
    data class Span(val start: Long, val end: Long) {
        val isEmpty: Boolean get() = end <= start
    }

    /**
     * Whether this device has the memory for it at all.
     *
     * A device Android itself calls low on memory is out whatever it reports in total: decoding a
     * second stream of frames while the first plays is exactly what it cannot spare.
     */
    fun memoryAllows(totalMemoryBytes: Long, lowRamDevice: Boolean): Boolean =
        !lowRamDevice && totalMemoryBytes >= MIN_TOTAL_MEMORY_BYTES

    /** The setting, and the memory under it: both have to say yes. */
    fun available(enabled: Boolean, totalMemoryBytes: Long, lowRamDevice: Boolean): Boolean =
        enabled && memoryAllows(totalMemoryBytes, lowRamDevice)

    /** Spans sorted and joined wherever they touch or overlap; empty ones dropped. */
    fun merge(spans: List<Span>): List<Span> {
        val sorted = spans.filterNot { it.isEmpty }.sortedBy { it.start }
        val out = ArrayList<Span>(sorted.size)
        for (span in sorted) {
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end) {
                out[out.lastIndex] = Span(last.start, maxOf(last.end, span.end))
            } else {
                out += span
            }
        }
        return out
    }

    /**
     * Whether a frame at [positionMs] can be read from the bytes on disk.
     *
     * A finished file is all there. Otherwise the head of the file has to be here, and the
     * estimated byte for the position, with its slack either side, has to sit inside one
     * downloaded span.
     */
    fun covered(
        positionMs: Long,
        durationMs: Long,
        sizeBytes: Long,
        spans: List<Span>,
        complete: Boolean,
    ): Boolean {
        if (complete) return true
        if (durationMs <= 0 || sizeBytes <= 0) return false
        val merged = merge(spans)
        val head = minOf(HEAD_BYTES, sizeBytes)
        if (merged.none { it.start <= 0 && it.end >= head }) return false
        val bytesPerMs = sizeBytes.toDouble() / durationMs
        val at = (positionMs.coerceIn(0, durationMs) * bytesPerMs).toLong()
        val slack = maxOf(MIN_SLACK_BYTES, (bytesPerMs * SLACK_MS).toLong())
        val from = (at - slack).coerceAtLeast(0)
        val to = (at + slack).coerceAtMost(sizeBytes)
        return merged.any { it.start <= from && it.end >= to }
    }

    /** The cache slot for a position: the start of its [STEP_MS] step. */
    fun bucketMs(positionMs: Long): Long = positionMs.coerceAtLeast(0) / STEP_MS * STEP_MS
}
