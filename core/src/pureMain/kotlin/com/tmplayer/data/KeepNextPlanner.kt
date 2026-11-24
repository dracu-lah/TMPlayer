package com.tmplayer.data

/**
 * Smart downloads, the planner (WP-43): "keep the next N unwatched episodes of this show on the
 * device", worked out without touching anything. Given the show's episodes in watching order,
 * which are watched and which already have a copy here, the free space, whether the connection is
 * metered and the show's "keep the next N" number, it says what to queue, what to remove and what
 * it skipped and why, with the numbers a line can quote.
 *
 * Rules, in the order they are checked:
 * - **Off is nothing.** N of 0 or less plans nothing, removes nothing.
 * - **Restricted is never planned.** An episode from a chat or post that restricts saving
 *   ([MediaItem.canBeSaved] false) is never queued ([ContentProtection], Telegram's terms): it is
 *   skipped with [Reason.Restricted] and still takes its place among the N, so the planner never
 *   reaches further ahead to make up for it.
 * - **Next means after the furthest watched.** The window starts after the last watched episode in
 *   watching order (the first unwatched one for a show not started), and takes the next N
 *   unwatched episodes. A show with none left says [Reason.NothingNext].
 * - **Already here counts.** A copy on the device or in the queue fills its place and asks for
 *   nothing; the same episode listed twice is planned once.
 * - **Space first.** Every episode to fetch is checked against the free space as it stands after
 *   the removals below, less [CacheShelf.HEADROOM_BYTES], with a running total: the episodes that
 *   fit are queued in order, and from the first that does not, every later one is skipped with
 *   [Reason.NoSpace], the space it needs and the space left, so nothing half fits and episode 3 is
 *   never kept without episode 2. A size not known is never guessed: [Reason.SizeUnknown].
 * - **Wi-Fi only.** On a metered connection with [Input.wifiOnly] on (the default for smart
 *   downloads) nothing is queued: each episode to fetch says [Reason.WaitingForWifi].
 * - **Removal only with Remove after watching**, and only of copies the planner kept itself
 *   ([Episode.keptForYou]): a download the viewer asked for by name is theirs and never touched
 *   ([CacheShelf]). Watched ones go; their bytes count as free for the space check when they are
 *   whole on the device.
 *
 * Linear in the number of episodes, allocation bounded by N plus the removals; pure, so it is
 * tested rather than reasoned about from a phone on a train.
 */
object KeepNextPlanner {

    /** How many of the next episodes the switch keeps (WP-84 "Keep the next 2 unwatched episodes"). */
    const val DEFAULT_COUNT = 2

    /** The most a show may keep: the switch never fills a disk by accident. */
    const val MAX_COUNT = 10

    /** Where one episode's copy stands on this device. */
    enum class Copy {
        /** Not here and not asked for. */
        None,

        /** In the download queue or downloading now. */
        Queued,

        /** A whole copy is here (cached or downloaded). */
        Here,
    }

    /** One episode as the planner sees it: one copy, the one that would be fetched. */
    data class Episode(
        val item: MediaItem,
        val watched: Boolean,
        val copy: Copy = Copy.None,
        /** The copy here was kept by this planner, not asked for by the viewer: only such a copy is removed. */
        val keptForYou: Boolean = false,
    ) {
        val id: String get() = item.id
    }

    /** Why an episode is not queued. */
    enum class Reason {
        /** The chat or the post restricts saving content: never planned. */
        Restricted,

        /** Not enough free space: [Skip.neededBytes] and [Skip.freeBytes] say how much. */
        NoSpace,

        /** A metered connection with Wi-Fi only on. */
        WaitingForWifi,

        /** Telegram did not say how large it is, so the space check cannot be made. */
        SizeUnknown,

        /** The show has no unwatched episode after the last one watched. */
        NothingNext,
    }

    /** An episode the planner looked at and did not queue; [item] is null for [Reason.NothingNext]. */
    data class Skip(val item: MediaItem?, val reason: Reason, val neededBytes: Long = 0L, val freeBytes: Long = 0L)

    data class Input(
        /** The show's episodes in watching order ([Series.episodes]). */
        val episodes: List<Episode>,
        /** "Keep the next N": 0 or less is off. */
        val count: Int,
        /** Free bytes on the volume downloads go to; 0 or less when it could not be read. */
        val freeBytes: Long,
        val metered: Boolean,
        val wifiOnly: Boolean = true,
        /** Settings, Downloads, "Remove after watching". */
        val removeAfterWatching: Boolean = false,
    )

    data class Plan(
        /** To fetch, in watching order. */
        val queue: List<MediaItem> = emptyList(),
        /** Kept-for-you copies of watched episodes to delete, only with Remove after watching on. */
        val remove: List<MediaItem> = emptyList(),
        val skipped: List<Skip> = emptyList(),
        /** Episodes in the window that are already here or queued: kept, nothing asked. */
        val kept: List<MediaItem> = emptyList(),
        /** The bytes [queue] takes, for the running total a row shows. */
        val queueBytes: Long = 0L,
        /** The free space the plan was checked against, after the removals and the headroom. */
        val roomBytes: Long = 0L,
    ) {
        /** The reason a row leads with: the first skip's, or null when everything wanted is kept or queued. */
        val headline: Skip? get() = skipped.firstOrNull()
        val empty: Boolean get() = queue.isEmpty() && remove.isEmpty()
    }

    fun plan(input: Input): Plan {
        val n = input.count.coerceAtMost(MAX_COUNT)
        if (n <= 0) return Plan()
        // One entry per episode id: two listings of one file are one episode.
        val seen = HashSet<String>()
        val episodes = input.episodes.filter { seen.add(it.id) }

        val remove = if (input.removeAfterWatching) {
            episodes.filter { it.watched && it.keptForYou && it.copy != Copy.None }.map { it.item }
        } else {
            emptyList()
        }
        val freed = if (input.removeAfterWatching) {
            episodes.sumOf { if (it.watched && it.keptForYou && it.copy == Copy.Here) it.item.sizeBytes.coerceAtLeast(0L) else 0L }
        } else {
            0L
        }

        val lastWatched = episodes.indexOfLast { it.watched }
        val window = ArrayList<Episode>(n)
        for (i in (lastWatched + 1) until episodes.size) {
            if (window.size >= n) break
            val e = episodes[i]
            if (!e.watched) window += e
        }
        if (window.isEmpty()) return Plan(remove = remove, skipped = listOf(Skip(null, Reason.NothingNext)))

        val room = (input.freeBytes.coerceAtLeast(0L) + freed - CacheShelf.HEADROOM_BYTES).coerceAtLeast(0L)
        val queue = ArrayList<MediaItem>(n)
        val kept = ArrayList<MediaItem>(n)
        val skipped = ArrayList<Skip>()
        var planned = 0L
        var full = false
        for (e in window) {
            val item = e.item
            when {
                !item.canBeSaved -> skipped += Skip(item, Reason.Restricted)
                e.copy != Copy.None -> kept += item
                input.metered && input.wifiOnly -> skipped += Skip(item, Reason.WaitingForWifi, item.sizeBytes.coerceAtLeast(0L))
                item.sizeBytes <= 0L -> skipped += Skip(item, Reason.SizeUnknown)
                full || planned + item.sizeBytes > room -> {
                    full = true
                    skipped += Skip(item, Reason.NoSpace, neededBytes = item.sizeBytes, freeBytes = (room - planned).coerceAtLeast(0L))
                }
                else -> {
                    queue += item
                    planned += item.sizeBytes
                }
            }
        }
        return Plan(queue = queue, remove = remove, skipped = skipped, kept = kept, queueBytes = planned, roomBytes = room)
    }

    /**
     * The planner's episodes for [series]: each episode's own copy ([SeriesEpisode.item]), watched
     * when any copy is ([SeriesEpisode.watched]), with [copyOf] and [keptForYou] read from what the
     * device holds. Linear in the episodes.
     */
    fun episodesOf(
        series: Series,
        finished: (MediaItem) -> Boolean,
        copyOf: (MediaItem) -> Copy,
        keptForYou: (MediaItem) -> Boolean,
    ): List<Episode> = series.episodes.map { e ->
        Episode(item = e.item, watched = e.watched(finished), copy = copyOf(e.item), keptForYou = keptForYou(e.item))
    }
}
