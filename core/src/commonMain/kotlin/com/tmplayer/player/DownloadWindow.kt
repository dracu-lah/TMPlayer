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
     * A seek within this many bytes ahead of what is already downloaded is left alone: the
     * running download will reach it sooner than a restart would.
     */
    const val FORWARD_SLACK_BYTES = 4L * 1024 * 1024
}
