package com.tmplayer.data

/**
 * What has to happen to the cache before a given video can be played.
 *
 * TDLib downloads a file whole and keeps it, so an 8 GB stick can hold about one video at a time.
 * The rule is therefore simple and stated plainly to the viewer: keep the video being watched,
 * drop the last one. Kept pure so the arithmetic is testable without a device or TDLib.
 */
object CachePolicy {

    /** Space left alone for TDLib's database, thumbnails and the system itself. */
    const val HEADROOM_BYTES = 400L * 1024 * 1024

    /** Below this the cache is thumbnails and scraps; clearing it would only annoy. */
    const val WORTH_CLEARING_BYTES = 64L * 1024 * 1024

    sealed interface Decision {
        /** Nothing in the way, so start playing. */
        data object Proceed : Decision

        /** Old media must go first. [reclaimBytes] is what the viewer gets back. */
        data class FreeUp(val reclaimBytes: Long) : Decision

        /** It will not fit even with the cache emptied; [shortfallBytes] is how far short. */
        data class TooLarge(val shortfallBytes: Long, val reclaimBytes: Long) : Decision
    }

    /**
     * @param fileSizeBytes size of the video about to play
     * @param alreadyCached true when this exact file is the one already on disk; re-watching
     *   must never trigger a clear that would delete the very thing being opened
     * @param cacheBytes what TDLib currently holds in video/document/animation files
     * @param freeBytes free space on the volume TDLib writes to
     */
    fun decide(
        fileSizeBytes: Long,
        alreadyCached: Boolean,
        cacheBytes: Long,
        freeBytes: Long,
    ): Decision {
        if (alreadyCached) return Decision.Proceed

        val freeAfterClearing = freeBytes + cacheBytes
        val needed = fileSizeBytes + HEADROOM_BYTES
        if (fileSizeBytes > 0 && freeAfterClearing < needed) {
            return Decision.TooLarge(needed - freeAfterClearing, cacheBytes)
        }
        if (cacheBytes >= WORTH_CLEARING_BYTES) return Decision.FreeUp(cacheBytes)
        return Decision.Proceed
    }
}
