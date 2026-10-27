package com.tmplayer.player

/**
 * When the player's corner chip ("Loading 32%  ·  2.7 MB/s") is worth showing while the watch
 * cache fills.
 *
 * "Loading" reads as buffering, so a chip that stayed up while the video played smoothly looked
 * like a problem that was not there (UX review, 2026-10-27); the seek bar's lighter run already
 * shows what is here. So the chip shows only while the data ahead of the playhead runs short: under
 * [SHOW_BELOW_MS] it comes up, and once up it stays until [HIDE_FROM_MS] is buffered ahead, so a
 * buffer that hovers round one figure does not make it blink. A download the viewer asked for is
 * not this chip's business here: its progress is what they want to see, and it always shows.
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
     */
    fun shows(shown: Boolean, aheadMs: Long, realDownload: Boolean): Boolean {
        if (realDownload) return true
        if (aheadMs < 0) return shown
        return if (shown) aheadMs < HIDE_FROM_MS else aheadMs < SHOW_BELOW_MS
    }
}
