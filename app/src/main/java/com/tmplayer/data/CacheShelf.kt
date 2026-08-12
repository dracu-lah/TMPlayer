package com.tmplayer.data

/**
 * What playing a video is allowed to leave behind, and what has to go before the next one arrives.
 *
 * There are two kinds of video on this device and only one of them is this object's business.
 *
 * A video the viewer ticked and asked for by name is a download. It is theirs, it stays until they
 * delete it, and nothing here ever takes one away.
 *
 * A video that landed only because somebody pressed Play is the cache. There is one of it. Playing
 * something else replaces it, on a television and on a phone alike, because a copy of last night's
 * film that nobody asked to keep is a gigabyte of somebody's device spent on nothing. The rule used
 * to be a number the viewer set, defaulting to one on both, and a setting whose every offered value
 * beyond the default was a way to fill your own disk by accident is a setting that was only ever
 * going to be turned back down.
 *
 * Two things can force a deletion: the cache holding a different film, and the disk being full.
 * Both are answered before a single byte is fetched, so the viewer is told the video will not fit
 * instead of watching it stall two thirds of the way through. The arithmetic is all here, pure, so
 * it can be tested without a device or TDLib.
 */
object CacheShelf {

    /** Space left alone for TDLib's database, thumbnails and the system itself. */
    const val HEADROOM_BYTES = 400L * 1024 * 1024

    /** Below this what would be freed is scraps, and quoting it would only be noise. */
    const val WORTH_ASKING_BYTES = 64L * 1024 * 1024

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
    data class Held(val fileId: Int, val bytes: Long, val updatedAt: Long = 0L)

    sealed interface Plan {
        /** Room for it as things stand. */
        data object Proceed : Plan

        /**
         * The cache has to go first. [reclaimBytes] is what comes back, which is the figure to
         * quote: it is what the device actually gets, not what it is holding altogether.
         */
        data class Evict(val fileIds: List<Int>, val reclaimBytes: Long) : Plan

        /**
         * It will not fit even with the cache emptied. [shortfallBytes] is how far short the device
         * is, and [reclaimBytes] what is sitting in downloads the viewer could delete by hand, so
         * the message can point somewhere rather than just refusing.
         */
        data class NotEnoughSpace(val shortfallBytes: Long, val reclaimBytes: Long) : Plan
    }

    /**
     * @param targetFileId the video about to play, which is never evicted: on a resumed watch it
     *   is the largest thing on the device, and deleting it would restart the download from zero.
     * @param targetSizeBytes what the whole file will take once it has landed
     * @param targetPartialBytes how much of it is already here from an earlier, unfinished watch
     * @param alreadyCached true when the whole file is already here, which nothing may disturb
     * @param cached what the watch cache is holding, which is at most one video and is given up
     *   without being asked about
     * @param keptBytes what the viewer's own downloads take. Not evictable, and quoted only so a
     *   refusal can say where the space went.
     * @param freeBytes free space on the volume TDLib writes to
     */
    fun plan(
        targetFileId: Int,
        targetSizeBytes: Long,
        targetPartialBytes: Long,
        alreadyCached: Boolean,
        cached: List<Held>,
        keptBytes: Long = 0,
        freeBytes: Long,
    ): Plan {
        if (alreadyCached) return Plan.Proceed

        // Everything in the cache except the video being opened, which is not a candidate for its
        // own eviction. Deduplicated by file id: the same Telegram file forwarded into two chats
        // has two rows in the index and one copy on disk, and counting it twice would promise
        // space that deleting it does not return.
        val doomed = cached
            .filter { it.fileId != targetFileId }
            .distinctBy { it.fileId }
        val reclaim = doomed.sumOf { it.bytes }

        // The cache holds one video, so anything else in it goes whatever the sizes say. Some
        // documents arrive with no size at all, and there is no arithmetic to do for those: the
        // download itself can say whether it fits.
        if (targetSizeBytes <= 0) {
            return if (doomed.isEmpty()) Plan.Proceed else Plan.Evict(doomed.map { it.fileId }, reclaim)
        }

        val needed = (targetSizeBytes - targetPartialBytes).coerceAtLeast(0) + HEADROOM_BYTES
        val available = freeBytes + reclaim
        if (available < needed) {
            // Short even with the cache emptied. Nothing is deleted for a play that cannot happen:
            // giving up last night's film and failing anyway is the worst of both.
            return Plan.NotEnoughSpace(
                shortfallBytes = needed - available,
                reclaimBytes = keptBytes,
            )
        }

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

    /**
     * Indices into the candidates given, so the caller keeps its own objects and their order.
     *
     * [reclaimFileIds] is the cache the batch is spending to make room, which the caller deletes
     * before it starts fetching. [outOfSpace] is true when at least one video was turned away for
     * want of room, which is the only thing that can turn one away now.
     */
    data class Batch(
        val fits: List<Int>,
        val alreadyHere: List<Int>,
        val reclaimFileIds: List<Int>,
        val outOfSpace: Boolean,
    )

    /**
     * How much of a selection this device can actually take.
     *
     * Worked out before a byte is fetched, by running the single video decision above once per
     * candidate against a disk that shrinks as each is accepted. The rules therefore live in
     * exactly one place, and a batch is only ever what a sequence of ordinary downloads would
     * have been.
     *
     * The watch cache is fair game here and the viewer's own downloads are not. A film nobody asked
     * to keep should not be the reason a film somebody did ask for is refused, and equally, ticking
     * three videos is not permission to delete last week's.
     *
     * A refusal is not the end of the loop. A single large video in the middle of a selection can
     * be the only one that will not fit, and stopping there would refuse the small ones behind it
     * for no reason.
     */
    fun planBatch(
        candidates: List<Candidate>,
        cached: List<Held>,
        freeBytes: Long,
    ): Batch {
        // Spent up front rather than one video at a time: the cache is a single film, so the first
        // candidate that needs the room takes all of it, and the rest are planned against a disk
        // that already has it back.
        val reclaimable = cached.distinctBy { it.fileId }
        var free = freeBytes
        val fits = mutableListOf<Int>()
        val alreadyHere = mutableListOf<Int>()
        val reclaimed = mutableListOf<Int>()
        var outOfSpace = false

        for ((index, candidate) in candidates.withIndex()) {
            if (candidate.alreadyHere) {
                alreadyHere += index
                continue
            }
            // Anything still in the cache and not itself a candidate is offered as room.
            val offered = if (reclaimed.isEmpty()) {
                reclaimable.filter { held -> candidates.none { it.fileId == held.fileId } }
            } else {
                emptyList()
            }
            val plan = plan(
                targetFileId = candidate.fileId,
                targetSizeBytes = candidate.sizeBytes,
                targetPartialBytes = candidate.partialBytes,
                alreadyCached = false,
                cached = offered,
                freeBytes = free,
            )
            when (plan) {
                is Plan.NotEnoughSpace -> {
                    outOfSpace = true
                    continue
                }
                is Plan.Evict -> {
                    reclaimed += plan.fileIds
                    free += plan.reclaimBytes
                }
                Plan.Proceed -> Unit
            }
            fits += index
            free -= (candidate.sizeBytes - candidate.partialBytes).coerceAtLeast(0)
        }

        return Batch(
            fits = fits,
            alreadyHere = alreadyHere,
            reclaimFileIds = reclaimed,
            outOfSpace = outOfSpace,
        )
    }
}
