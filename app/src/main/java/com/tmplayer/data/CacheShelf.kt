package com.tmplayer.data

/**
 * How many downloaded videos to keep, and what has to go before the next one can be fetched.
 *
 * The rule used to be a constant: one video at a time, because a television stick has about 8 GB
 * and TDLib downloads a file whole. It is a number now, and the viewer owns it. A stick still
 * defaults to one, a phone to three, and either can be moved. The arithmetic is the same on both
 * and it is all here, pure, so it can be tested without a device or TDLib.
 *
 * Two things can force a deletion: the shelf being full, which is the count, and the disk being
 * full, which is the bytes. Both are answered before a single byte is fetched, so the viewer is
 * told the video will not fit instead of watching it stall two thirds of the way through.
 */
object CacheShelf {

    /** Space left alone for TDLib's database, thumbnails and the system itself. */
    const val HEADROOM_BYTES = 400L * 1024 * 1024

    /** Below this what would be freed is scraps, and asking about it would only annoy. */
    const val WORTH_ASKING_BYTES = 64L * 1024 * 1024

    /** [keepCount] of this means no limit on the count; only the disk decides. */
    const val UNLIMITED = 0

    /**
     * What the app is allowed to keep on disk, given the size of the disk.
     *
     * This was a flat four gigabytes, written for "a device whose whole disk is eight". The stick
     * this app was built for reports 4.85 GB of usable data partition, not eight, so the ceiling
     * was very nearly the whole volume: the app was behaving exactly as designed while filling the
     * device to the last megabyte, and the trim that is supposed to stop that had no work to do
     * until the disk was already gone. Anything expressed in absolute bytes has this failure in
     * it somewhere, so the ceiling is a share of the volume with a floor and a cap.
     *
     * Two limits, whichever binds first: a share of the volume, and enough left over that the
     * device still works. [MIN_CEILING_BYTES] stops the arithmetic from arriving at a number too
     * small to hold one film on a device that is already nearly full, where the honest answer is
     * that the video will not fit and [plan] is what says so.
     */
    fun ceiling(totalBytes: Long): Long {
        if (totalBytes <= 0) return MAX_CEILING_BYTES
        val share = (totalBytes * VOLUME_SHARE).toLong()
        val leavingRoom = totalBytes - RESERVED_FOR_THE_DEVICE
        return minOf(MAX_CEILING_BYTES, share, leavingRoom).coerceAtLeast(MIN_CEILING_BYTES)
    }

    /** What the app keeps at most, on a device with room to spare: a couple of films. */
    const val MAX_CEILING_BYTES = 4L * 1024 * 1024 * 1024

    /** Never less than this, or a stick could not hold a single video. */
    const val MIN_CEILING_BYTES = 1024L * 1024 * 1024

    /** The rest of the volume belongs to the system, the launcher and everything else installed. */
    private const val RESERVED_FOR_THE_DEVICE = 2L * 1024 * 1024 * 1024

    private const val VOLUME_SHARE = 0.4

    /** One video already on this device: what it is, what it takes, and when it was last played. */
    data class Held(val fileId: Int, val bytes: Long, val updatedAt: Long)

    sealed interface Plan {
        /** Room for it as things stand. */
        data object Proceed : Plan

        /**
         * These have to go first, oldest chosen first. [reclaimBytes] is what comes back, which is
         * the figure to quote: it is what the viewer actually gets, not what the shelf holds.
         */
        data class Evict(val fileIds: List<Int>, val reclaimBytes: Long) : Plan

        /**
         * It will not fit even with every other download deleted. [shortfallBytes] is how far
         * short the device is, and [reclaimBytes] what is sitting in downloads, so the message can
         * say both rather than just refusing.
         */
        data class NotEnoughSpace(val shortfallBytes: Long, val reclaimBytes: Long) : Plan
    }

    /**
     * @param targetFileId the video about to play, which is never evicted: on a resumed watch it
     *   is the largest thing on the shelf, and deleting it would restart the download from zero.
     * @param targetSizeBytes what the whole file will take once it has landed
     * @param targetPartialBytes how much of it is already here from an earlier, unfinished watch
     * @param alreadyCached true when the whole file is already here, which nothing may disturb
     * @param held every download this device is holding, the target included if it is among them
     * @param freeBytes free space on the volume TDLib writes to
     * @param keepCount how many videos the viewer wants kept, or [UNLIMITED] for as many as fit
     */
    fun plan(
        targetFileId: Int,
        targetSizeBytes: Long,
        targetPartialBytes: Long,
        alreadyCached: Boolean,
        held: List<Held>,
        freeBytes: Long,
        keepCount: Int,
    ): Plan {
        if (alreadyCached) return Plan.Proceed

        // Oldest first, which is the order things are given up in. The target is set aside rather
        // than sorted: it is the one file on the shelf that is not a candidate.
        // Deduplicated by file id: the same Telegram file forwarded into two chats has two rows
        // in the index and one copy on disk, and counting it twice promised space that deleting
        // it would not return.
        val others = held
            .filter { it.fileId != targetFileId }
            .distinctBy { it.fileId }
            .sortedBy { it.updatedAt }
        val onShelf = others.sumOf { it.bytes }

        // The shelf has to end up with the new video and at most [keepCount] videos in total, so
        // the oldest go until there is a slot free for it.
        val slotsForOthers = if (keepCount == UNLIMITED) others.size else (keepCount - 1).coerceAtLeast(0)
        val doomed = others.take((others.size - slotsForOthers).coerceAtLeast(0)).toMutableList()

        // Some documents arrive with no size at all. There is no arithmetic to do for those, and
        // deleting a shelf of videos to make room for a file of unknown size would be deleting
        // them for nothing: the count still applies, and the download itself can say.
        if (targetSizeBytes <= 0) {
            val reclaimed = doomed.sumOf { it.bytes }
            return if (doomed.isEmpty()) {
                Plan.Proceed
            } else {
                Plan.Evict(doomed.map { it.fileId }, reclaimed)
            }
        }

        val needed = (targetSizeBytes - targetPartialBytes).coerceAtLeast(0) + HEADROOM_BYTES
        var available = freeBytes + doomed.sumOf { it.bytes }

        // Still short after the count has had its say: keep giving up the oldest until it fits.
        // Provisional until the end, because a shelf that still does not add up must be reported
        // rather than emptied: deleting everything and failing anyway is the worst of both.
        for (entry in others) {
            if (available >= needed) break
            if (entry in doomed) continue
            doomed += entry
            available += entry.bytes
        }

        if (available < needed) {
            return Plan.NotEnoughSpace(
                shortfallBytes = needed - available,
                reclaimBytes = onShelf,
            )
        }

        val reclaim = doomed.sumOf { it.bytes }
        return if (doomed.isEmpty()) Plan.Proceed else Plan.Evict(doomed.map { it.fileId }, reclaim)
    }

    /** One of several videos asked for at once. [partialBytes] is what an earlier attempt left. */
    data class Candidate(
        val fileId: Int,
        val sizeBytes: Long,
        val partialBytes: Long = 0,
        /** Already on the device or already coming down, so it costs nothing and asks for nothing. */
        val alreadyHere: Boolean = false,
    )

    /** Why the shelf turned a video away, which is the difference between "full" and "too small". */
    enum class Stopper { Count, Space }

    /**
     * Indices into the candidates given, so the caller keeps its own objects and their order.
     *
     * [stoppedBy] is null when everything asked for was taken, and otherwise says which limit bit
     * first. It carries no wording: what the viewer is told depends on the screen doing the telling.
     */
    data class Batch(
        val fits: List<Int>,
        val alreadyHere: List<Int>,
        val stoppedBy: Stopper?,
    )

    /**
     * How much of a selection this device can actually keep.
     *
     * Twenty videos queued onto a device that keeps three is not twenty downloads: it is three
     * kept and seventeen deleted on arrival, each one evicting the last, which costs the viewer
     * their data allowance and leaves them with less than they would have had by asking for three.
     * So the answer is worked out before a byte is fetched, and it is worked out by running the
     * single video decision above once per candidate against a shelf that grows as each is
     * accepted. The rules therefore live in exactly one place, and a batch is only ever what a
     * sequence of ordinary downloads would have been.
     *
     * Nothing here is evicted. [plan] is willing to give up the oldest video to make room, which is
     * right when a viewer presses Play on one thing, and wrong here: they ticked these on top of
     * what they already had, and quietly deleting last week's film to honour the tick is not what
     * the tick meant. Anything short of [Plan.Proceed] is therefore a refusal.
     *
     * A refusal is not the end of the loop. A single large video in the middle of a selection can
     * be the only one that will not fit, and stopping there would refuse the small ones behind it
     * for no reason.
     */
    fun planBatch(
        candidates: List<Candidate>,
        held: List<Held>,
        freeBytes: Long,
        keepCount: Int,
    ): Batch {
        val shelf = held.distinctBy { it.fileId }.toMutableList()
        var free = freeBytes
        val fits = mutableListOf<Int>()
        val alreadyHere = mutableListOf<Int>()
        var stoppedBy: Stopper? = null
        // Each acceptance has to land newer than everything on the shelf, or the video just
        // granted a slot would be the oldest thing there and the next candidate would evict it.
        var arrival = (shelf.maxOfOrNull { it.updatedAt } ?: 0L) + 1

        for ((index, candidate) in candidates.withIndex()) {
            if (candidate.alreadyHere) {
                alreadyHere += index
                continue
            }
            val plan = plan(
                targetFileId = candidate.fileId,
                targetSizeBytes = candidate.sizeBytes,
                targetPartialBytes = candidate.partialBytes,
                alreadyCached = false,
                held = shelf,
                freeBytes = free,
                keepCount = keepCount,
            )
            if (plan !is Plan.Proceed) {
                if (stoppedBy == null) {
                    stoppedBy = if (plan is Plan.NotEnoughSpace) {
                        Stopper.Space
                    } else if (keepCount != UNLIMITED && shelf.size >= keepCount) {
                        Stopper.Count
                    } else {
                        Stopper.Space
                    }
                }
                continue
            }
            fits += index
            shelf += Held(candidate.fileId, maxOf(candidate.sizeBytes, candidate.partialBytes), arrival++)
            free -= (candidate.sizeBytes - candidate.partialBytes).coerceAtLeast(0)
        }

        return Batch(fits = fits, alreadyHere = alreadyHere, stoppedBy = stoppedBy)
    }
}
