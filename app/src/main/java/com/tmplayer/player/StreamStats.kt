package com.tmplayer.player

import kotlin.math.roundToLong

/**
 * Everything the loading screen needs to say "how fast, how much longer".
 *
 * Pure and free of Android so the arithmetic can be tested directly. The numbers on screen are
 * the difference between a viewer waiting patiently and a viewer assuming the app has hung.
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
     * an estimate on. Guessing is worse than saying nothing.
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

    /** Average bytes per millisecond of playback, used to turn "bytes" into "seconds". */
    fun bytesPerMs(sizeBytes: Long, durationSec: Int): Double {
        if (sizeBytes <= 0 || durationSec <= 0) return 0.0
        return sizeBytes.toDouble() / (durationSec * 1000.0)
    }

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec < MIN_MEANINGFUL_SPEED -> "…"
        bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }

    fun formatEta(seconds: Long?): String = when {
        seconds == null -> ""
        seconds <= 1 -> "almost there"
        seconds < 60 -> "about ${seconds}s left"
        seconds < 3600 -> "about ${seconds / 60}m ${seconds % 60}s left"
        else -> "about ${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** `1:23:45` / `4:07` — the form people read on a seek bar. */
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
 * Exponentially smoothed, because a raw delta between two TDLib updates swings wildly enough to
 * make the figure on screen unreadable. Resets rather than reports nonsense when the byte count
 * goes backwards, which happens on a seek when the download window moves.
 */
class SpeedMeter(private val smoothing: Double = 0.3) {

    private var lastBytes = -1L
    private var lastAtMs = 0L
    private var smoothed = 0.0

    val bytesPerSec: Long get() = smoothed.roundToLong()

    fun reset() {
        lastBytes = -1L
        lastAtMs = 0L
        smoothed = 0.0
    }

    /** Feed a cumulative byte count and the time it was observed; returns the smoothed speed. */
    fun sample(totalBytes: Long, atMs: Long): Long {
        val previousBytes = lastBytes
        val previousAt = lastAtMs
        lastBytes = totalBytes
        lastAtMs = atMs

        if (previousBytes < 0 || atMs <= previousAt) return bytesPerSec
        if (totalBytes < previousBytes) {
            // The window jumped backwards (a seek): the old baseline says nothing about now.
            smoothed = 0.0
            return 0
        }

        val instant = (totalBytes - previousBytes) * 1000.0 / (atMs - previousAt)
        smoothed = if (smoothed <= 0.0) instant else smoothed + smoothing * (instant - smoothed)
        return bytesPerSec
    }
}
