package com.tmplayer.player

/**
 * When the player's corner chip is worth showing while the watch cache fills: "Loading 32%" while
 * the video is starting or stalled, "Getting the next part" while it plays on a buffer that ran short.
 *
 * "Loading" reads as buffering, so a chip that stayed up while the video played smoothly looked
 * like a problem that was not there (UX review, 2026-10-27); the seek bar's lighter run already
 * shows what is here. So the chip shows only while the data ahead of the playhead runs short: under
 * [SHOW_BELOW_MS] it comes up, and once up it stays until [HIDE_FROM_MS] is buffered ahead, so a
 * buffer that hovers round one figure does not make it blink. A download the viewer asked for is
 * not this chip's business here: its progress is what they want to see, and it always shows.
 *
 * The player's buffer is also capped in bytes, so on a high rate file it can stop growing well under
 * [HIDE_FROM_MS] (20 MB is a few seconds of a 40 Mbps remux). A player that is playing, has stopped
 * loading because its buffer is as full as it will get, and holds [SHOW_BELOW_MS] or more has nothing
 * left to wait for, so the chip goes (a TV kept "Loading 17%" over a smooth picture, 2026-11-15).
 * The line speed is not on the chip: it is a line in Playback details.
 */
object LoadingChip {

    /** Ahead of the playhead, under this the chip comes up. */
    const val SHOW_BELOW_MS = 10_000L

    /** Once up, the chip goes again only with this much ahead. */
    const val HIDE_FROM_MS = 30_000L

    /**
     * @param shown whether the chip is up now.
     * @param aheadMs what the player has buffered past the playhead; negative when it cannot say,
     *   which keeps the chip as it is.
     * @param realDownload the file is in Downloads or queued for it, rather than the cache filling.
     * @param starting the player is not ready (still opening, or stalled): always shows, worded
     *   "Loading n%" where a smooth picture says "Getting the next part".
     * @param bufferFull the player has stopped loading because its buffer holds all it will keep.
     */
    fun shows(
        shown: Boolean,
        aheadMs: Long,
        realDownload: Boolean,
        starting: Boolean = false,
        bufferFull: Boolean = false,
    ): Boolean {
        if (realDownload) return true
        if (starting) return true
        if (aheadMs < 0) return shown
        if (bufferFull && aheadMs >= SHOW_BELOW_MS) return false
        return if (shown) aheadMs < HIDE_FROM_MS else aheadMs < SHOW_BELOW_MS
    }
}
