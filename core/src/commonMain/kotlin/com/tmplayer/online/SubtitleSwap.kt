package com.tmplayer.online

/**
 * When a growing subtitle (SubMaker's translation) may be swapped into the player. On Android a
 * swap rebuilds the media item at the current position, which makes a streamed video rebuffer, so
 * it must not land while the player is already buffering or the viewer is scrubbing: it waits for
 * the next quiet moment, or for playback to be paused. Still at most once in [MIN_GAP_MS].
 * Pure, so the decision is a test ([SubtitleSwapTest]).
 */
object SubtitleSwap {
    /** The least time between two swaps. */
    const val MIN_GAP_MS = 60_000L

    /** How often a waiting swap looks again for a quiet moment. */
    const val RECHECK_MS = 1_500L

    /**
     * Whether to swap now. [lastSwapMs] is when the last swap was made (0 for none yet), [nowMs] the
     * same clock. [buffering] is the player's own state, [scrubbing] a drag on the bar or a swipe
     * scrub, and [paused] a player ready and not playing: a swap then costs the picture nothing.
     */
    fun swapNow(nowMs: Long, lastSwapMs: Long, buffering: Boolean, scrubbing: Boolean, paused: Boolean): Boolean {
        if (lastSwapMs > 0L && nowMs - lastSwapMs in 0 until MIN_GAP_MS) return false
        if (paused && !scrubbing) return true
        return !buffering && !scrubbing
    }
}
