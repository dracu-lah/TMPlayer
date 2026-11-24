package com.tmplayer.data

/**
 * What plays after a video that is not an episode (EG-F30): the next one of its chat, in the order
 * the chat's grid is sorted in. Pure, so the rule is tested here and the phone, the television, the
 * desktop and the web all step the same way. The answer is an [EpisodeSteps], so the next-up card,
 * the countdown, the autoplay switch and "Still watching?" apply to it as they do to an episode.
 *
 * The grid lists a chat newest first ([VideoSort.Newest], and every sort that cannot be known
 * without the loaded list), so the video after this one is the next older post. Sorted
 * [VideoSort.Oldest] the list runs forward in time and so does the next one: the lecture chat that
 * is heard from its first post. The sort is read when the lookup is made, so a change of sort
 * between two videos is followed by the next one.
 *
 * A chat is read a page at a time from the video playing, not from its newest post: the neighbour
 * of a video far down a chat of 20,000 is one page away. A page with no fit video (posts between,
 * or a run of ones the size limits hide) is followed by the next, up to [MAX_PAGES], and the end
 * of the chat ends the search, so the last video has no next one.
 */
object ChatOrder {

    /** Which way along the chat's own timeline the list runs from the video playing. */
    enum class Way { Older, Newer }

    /**
     * One page of a chat's videos read around an anchor: [items] in any order, and [next], the
     * anchor to ask the page after this one from, or 0 when the chat has nothing further that way.
     */
    class Window(val items: List<MediaItem>, val next: Long)

    /** Pages read at most for one neighbour: forty messages of each kind a page. */
    const val MAX_PAGES = 6

    /** Where the video after this one lies for [sort]: the oldest-first grid runs forward in time. */
    fun wayOf(sort: VideoSort): Way = if (sort == VideoSort.Oldest) Way.Newer else Way.Older

    fun opposite(way: Way): Way = if (way == Way.Older) Way.Newer else Way.Older

    /**
     * The video nearest to [current] on [way] among [items], or null when none lies that way.
     * Skipped: [current] itself, another copy of the same file (forwarded or sent twice, which would
     * play the same video again), a piece of a split video, and whatever [fits] refuses (the size
     * limits the grid applies).
     */
    fun pick(current: MediaItem, items: List<MediaItem>, way: Way, fits: (MediaItem) -> Boolean = { true }): MediaItem? {
        val usable = items.asSequence()
            .filter { it.chatId == current.chatId && it.messageId != current.messageId }
            .filter { !sameFile(it, current) && it.splitPiece == null && fits(it) }
        return when (way) {
            Way.Older -> usable.filter { it.messageId < current.messageId }.maxByOrNull { it.messageId }
            Way.Newer -> usable.filter { it.messageId > current.messageId }.minByOrNull { it.messageId }
        }
    }

    private fun sameFile(a: MediaItem, b: MediaItem): Boolean =
        a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes && a.fileName.isNotBlank() && a.fileName == b.fileName

    /**
     * The next video of the chat from [current] on [way], read a page at a time through [fetch]
     * (a page around an anchor, null when the chat could not be asked: offline, a failed search),
     * or null at the end of the chat, after [MAX_PAGES] without one, or when the chat cannot be asked.
     */
    suspend fun lookUp(
        current: MediaItem,
        way: Way,
        fits: (MediaItem) -> Boolean = { true },
        fetch: suspend (anchor: Long, newer: Boolean) -> Window?,
    ): MediaItem? {
        var anchor = current.messageId
        for (read in 0 until MAX_PAGES) {
            val page = fetch(anchor, way == Way.Newer) ?: return null
            pick(current, page.items, way, fits)?.let { return it }
            // The cursor must move on: a page that answers the anchor asked is the same page again.
            if (page.next == 0L || page.next == anchor) return null
            anchor = page.next
        }
        return null
    }

    /**
     * The videos either side of [current] for a chat sorted [sort]: [EpisodeSteps.next] is the one
     * the list goes on to, [EpisodeSteps.previous] the one before. Nothing in a direction where the
     * chat has no more, so the last video of the chat stops.
     */
    suspend fun steps(
        current: MediaItem,
        sort: VideoSort,
        fits: (MediaItem) -> Boolean = { true },
        /** A further test for the way forward only (WP-32's "skip watched"): Previous still goes back to what was played. */
        fitsNext: (MediaItem) -> Boolean = { true },
        fetch: suspend (anchor: Long, newer: Boolean) -> Window?,
    ): EpisodeSteps {
        val way = wayOf(sort)
        val next = lookUp(current, way, { fits(it) && fitsNext(it) }, fetch)
        val previous = lookUp(current, opposite(way), fits, fetch)
        return EpisodeSteps(previous = previous, next = next)
    }
}
