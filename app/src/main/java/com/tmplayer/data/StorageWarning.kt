package com.tmplayer.data

/**
 * Whether the device is short enough of room to be worth telling the viewer about.
 *
 * [CachePolicy] already clears the previous film to make space for the next one, so a warning here
 * is about the rest of the stick (other apps, other downloads), which this app cannot tidy up on
 * the viewer's behalf. Kept pure and free of Android so the thresholds can be tested directly.
 */
object StorageWarning {

    private const val MB = 1024L * 1024
    private const val GB = 1024 * MB

    /**
     * Under this there is no longer reliable room for one more film.
     *
     * Two gigabytes is roughly one ordinary-quality film plus the headroom [CachePolicy] reserves,
     * so it is the point at which the next thing the viewer picks may simply refuse to download.
     */
    const val LOW_FREE_BYTES = 2 * GB

    /**
     * How much further free space has to fall after a dismissal before the warning returns.
     *
     * Large enough that ordinary churn, a thumbnail cache growing or a log file, cannot bring the
     * card back moments after it was waved away, small enough that a genuine slide towards full
     * gets a second word in.
     */
    const val RENOTIFY_DROP_BYTES = 512 * MB

    /**
     * Above this the situation the viewer dismissed no longer exists, so the stored figure is
     * stale and should be forgotten rather than used as a baseline for the next slide down.
     */
    const val RECOVERED_FREE_BYTES = LOW_FREE_BYTES + RENOTIFY_DROP_BYTES

    /** Stored dismissal figure meaning the viewer has never hidden this warning. */
    const val NEVER_DISMISSED = -1L

    /**
     * Whether free space is genuinely low.
     *
     * A total of zero means [DiskSpace.read] failed rather than that the disk is full, and an
     * unreadable disk must not produce an alarming message about a problem nobody can confirm.
     */
    fun isLow(freeBytes: Long, totalBytes: Long): Boolean {
        if (totalBytes <= 0) return false
        return freeBytes < LOW_FREE_BYTES
    }

    /**
     * Whether to put the card on screen, given what the viewer has already dismissed.
     *
     * A dismissal is an acknowledgement of one particular amount of room left, not a permanent
     * mute: someone who waves the card away at 1.9 GB still deserves to hear about it at 300 MB.
     */
    fun shouldShow(freeBytes: Long, totalBytes: Long, dismissedAtFreeBytes: Long): Boolean {
        if (!isLow(freeBytes, totalBytes)) return false
        if (dismissedAtFreeBytes < 0) return true
        return freeBytes <= dismissedAtFreeBytes - RENOTIFY_DROP_BYTES
    }

    /** Whether there is comfortably enough room again for an old dismissal to be discarded. */
    fun hasRecovered(freeBytes: Long, totalBytes: Long): Boolean =
        totalBytes > 0 && freeBytes >= RECOVERED_FREE_BYTES
}
