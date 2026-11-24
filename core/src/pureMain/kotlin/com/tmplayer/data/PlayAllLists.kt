package com.tmplayer.data

/**
 * Play all and Shuffle on a list's page (WP-32, WP-87): which of its lines are videos to play, and the
 * press the player is armed with. A show saved whole (a line with a [WatchLaterRecord.series]) is a page,
 * not a video: it is left out of the queue, and a list of shows alone has nothing to play all of.
 * Pure, so the phone, the television and the desktop build the same run.
 */
object PlayAllLists {

    /** The videos of [records] in the order they were added, one per line, a show saved whole left out. */
    fun queue(records: List<WatchLaterRecord>): List<MediaItem> =
        records.filter { !it.isShow }.map { it.toMediaItem() }

    /** How many of [records] play: what Play all and Shuffle are offered by. */
    fun playable(records: List<WatchLaterRecord>): Int = records.count { !it.isShow }

    /**
     * The press for [records]: Play all starts with the first line and goes on in the list's order; Shuffle starts
     * with the first of a seeded order and follows it ([PlayAll.listSteps] with the same seed). Null when nothing plays.
     */
    fun request(records: List<WatchLaterRecord>, shuffle: Boolean, seed: Long): PlayAll.Request? {
        val queue = queue(records)
        val first = (if (shuffle) PlayAll.shuffled(seed, queue) else queue).firstOrNull() ?: return null
        val mode = if (shuffle) PlayAll.Mode.ListShuffle else PlayAll.Mode.List
        return PlayAll.Request(first.chatId, first.messageId, mode, if (shuffle) seed else 0L, queue)
    }
}
