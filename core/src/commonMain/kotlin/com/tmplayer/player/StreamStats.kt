package com.tmplayer.player

import com.tmplayer.i18n.L
import kotlin.math.roundToLong

/**
 * Everything the loading screen needs to say "how fast, how much longer".
 *
 * Pure and free of Android so the arithmetic can be tested directly.
 */
object StreamStats {

    /** Below this a reading is noise rather than a speed, and no honest estimate is possible. */
    const val MIN_MEANINGFUL_SPEED = 8 * 1024L

    /**
     * How full the pre-roll buffer is, 0..1.
     *
     * Measured against the buffer ExoPlayer needs before it will start, so the bar reaching the
     * end genuinely coincides with the picture appearing.
     */
    fun progress(bufferedAheadMs: Long, requiredMs: Long): Float {
        if (requiredMs <= 0) return 1f
        if (bufferedAheadMs <= 0) return 0f
        return (bufferedAheadMs.toFloat() / requiredMs).coerceIn(0f, 1f)
    }

    /**
     * Seconds until enough is buffered to start, or `null` when there is nothing solid to base
     * an estimate on. The caller shows no figure at all until there is one.
     */
    fun etaSeconds(
        bufferedAheadMs: Long,
        requiredMs: Long,
        bytesPerMs: Double,
        speedBytesPerSec: Long,
    ): Long? {
        val missingMs = requiredMs - bufferedAheadMs
        if (missingMs <= 0) return 0
        if (speedBytesPerSec < MIN_MEANINGFUL_SPEED || bytesPerMs <= 0.0) return null
        val missingBytes = missingMs * bytesPerMs
        return (missingBytes / speedBytesPerSec).roundToLong().coerceAtLeast(1)
    }

    /**
     * How far into the video the download has reached, 0..1.
     *
     * TDLib fills one contiguous window at a time, so the end of that window is the point the
     * video is watchable up to, which is what a viewer means by "how much has downloaded?" rather
     * than the number of bytes sitting on disk.
     */
    fun downloadedFraction(
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        size: Long,
        completed: Boolean,
    ): Float {
        if (completed) return 1f
        if (size <= 0) return 0f
        return ((downloadOffset + downloadedPrefixSize).toDouble() / size).toFloat().coerceIn(0f, 1f)
    }

    /** `62%`, rounded down so a video still arriving is never reported as fully here. */
    fun formatPercent(fraction: Float): String {
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        return "$percent%"
    }

    /** Seconds until [remainingBytes] have arrived, or `null` when the speed says nothing yet. */
    fun secondsForBytes(remainingBytes: Long, speedBytesPerSec: Long): Long? {
        if (remainingBytes <= 0) return 0
        if (speedBytesPerSec < MIN_MEANINGFUL_SPEED) return null
        return (remainingBytes / speedBytesPerSec).coerceAtLeast(1)
    }

    /** Average bytes per millisecond of playback, used to turn "bytes" into "seconds". */
    fun bytesPerMs(sizeBytes: Long, durationSec: Int): Double {
        if (sizeBytes <= 0 || durationSec <= 0) return 0.0
        return sizeBytes.toDouble() / (durationSec * 1000.0)
    }

    fun formatSpeed(bytesPerSec: Long): String = L.messages.formatter.speed(bytesPerSec)

    fun formatEta(seconds: Long?): String = L.messages.formatter.eta(seconds)

    fun formatBytes(bytes: Long): String = L.messages.formatter.bytes(bytes)

    /** `1:23:45` / `4:07`, the form people read on a seek bar. */
    fun formatClock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}

/**
 * Turns a series of "total bytes so far" readings into a download speed.
 *
 * Measured over a span of at least [MIN_SPAN_MS] and then exponentially smoothed, because a raw
 * delta between two TDLib updates a few milliseconds apart swings wildly enough to make the
 * figure on screen unreadable. Two kinds of reading are not a speed and only move the baseline:
 * the count going backwards (a seek moved the download window), and a jump far larger than any
 * connection could carry in the time since the last reading, which is bytes that were already on
 * the disk (a cached run) joining the prefix. The second is what once put "4,004.1 MB/s" on the
 * television's chip at the start of a video half in the watch cache.
 */
class SpeedMeter(private val smoothing: Double = 0.3) {

    private var lastBytes = -1L
    private var lastAtMs = 0L
    private var baseBytes = 0L
    private var baseAtMs = 0L
    private var smoothed = 0.0

    val bytesPerSec: Long get() = smoothed.roundToLong()

    fun reset() {
        lastBytes = -1L
        lastAtMs = 0L
        smoothed = 0.0
    }

    private fun rebase(totalBytes: Long, atMs: Long) {
        lastBytes = totalBytes
        lastAtMs = atMs
        baseBytes = totalBytes
        baseAtMs = atMs
    }

    /** Feed a cumulative byte count and the time it was observed; returns the smoothed speed. */
    fun sample(totalBytes: Long, atMs: Long): Long {
        if (lastBytes < 0) {
            rebase(totalBytes, atMs)
            return bytesPerSec
        }
        if (atMs <= lastAtMs) return bytesPerSec
        if (totalBytes < lastBytes) {
            // The window jumped backwards (a seek): the old baseline says nothing about now.
            smoothed = 0.0
            rebase(totalBytes, atMs)
            return 0
        }
        val step = totalBytes - lastBytes
        if (step > MAX_PLAUSIBLE_BYTES_PER_SEC * (atMs - lastAtMs) / 1000 + JUMP_SLACK_BYTES) {
            // Bytes that were already here joined the prefix; nothing was fetched that fast.
            rebase(totalBytes, atMs)
            return bytesPerSec
        }
        lastBytes = totalBytes
        lastAtMs = atMs
        val span = atMs - baseAtMs
        if (span < MIN_SPAN_MS) return bytesPerSec
        val instant = (totalBytes - baseBytes) * 1000.0 / span
        smoothed = if (smoothed <= 0.0) instant else smoothed + smoothing * (instant - smoothed)
        baseBytes = totalBytes
        baseAtMs = atMs
        return bytesPerSec
    }

    companion object {
        /** The shortest stretch a reading is taken over; shorter deltas are mostly timing noise. */
        const val MIN_SPAN_MS = 500L

        /** Faster than any Telegram download seen; a step beyond it was already on the disk. */
        const val MAX_PLAUSIBLE_BYTES_PER_SEC = 64L * 1024 * 1024

        /** Room for one late TDLib update carrying a few chunks at once. */
        const val JUMP_SLACK_BYTES = 2L * 1024 * 1024
    }
}
