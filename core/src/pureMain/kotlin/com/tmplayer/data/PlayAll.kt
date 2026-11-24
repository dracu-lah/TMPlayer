package com.tmplayer.data

import kotlin.concurrent.Volatile

/**
 * Play all and Shuffle for a chat's grid or a list (WP-32, WP-87): what a press asks of the player,
 * and the rules that find the video after the one playing. Pure, so the phone, the television, the
 * desktop and the web step the same way, and tested here once.
 *
 * Play all of a chat is the order the chat's grid is sorted in: the first listed video starts, and
 * what follows is [ChatOrder] (a page at a time from the video playing, never the whole chat, the
 * neighbour of a video far down a chat of 20,000 one page away), forced on for this run whatever
 * the "Play the chat in order" setting says. Play all of a list is the list's own order, every video
 * a [MediaItem] the page already holds ([queue]), because a list spans chats.
 *
 * Shuffle puts the videos in a seeded order that needs no list to be known whole: each video has a
 * key made of the [seed] and its own ids ([key]), and the order is the keys' order. The video after
 * this one is the unplayed one of lowest key among those read so far ([ShufflePool]), so nothing
 * repeats until the pages read are used up, the same seed gives the same order again (a player that
 * is rebuilt after its process died picks the sequence up where it was), and the chat is read a
 * page at a time as the pool runs out.
 */
object PlayAll {

    /** What the player does after a video. [Off] is every video that was not started from Play all or Shuffle. */
    enum class Mode {
        Off,

        /** A chat's grid order ([ChatOrder]). */
        Chat,

        /** A chat shuffled by a seed ([ShufflePool]). */
        ChatShuffle,

        /** A list's order, the videos in [Request.queue]. */
        List,

        /** A list shuffled by a seed. */
        ListShuffle,
        ;

        val shuffled: Boolean get() = this == ChatShuffle || this == ListShuffle
        val ofList: Boolean get() = this == List || this == ListShuffle

        companion object {
            fun of(ordinal: Int): Mode = entries.getOrNull(ordinal) ?: Off
        }
    }

    /**
     * Whether the end of a video goes on to the next one: always in a Play all or Shuffle run, whose
     * press is the request to play on (PB2-07), else as the Autoplay next setting says. "Still
     * watching?" and the sleep timer still stop a run as they stop autoplay.
     */
    fun playsOn(mode: Mode, autoplaySetting: Boolean): Boolean = mode != Mode.Off || autoplaySetting

    /** One press of Play all or Shuffle: the video to start with, and how the rest follows. */
    class Request(
        val chatId: Long,
        val messageId: Long,
        val mode: Mode,
        val seed: Long = 0L,
        /** A list's videos in the order the page shows them; empty for a chat. */
        val queue: List<MediaItem> = emptyList(),
        /** The pages a chat's Shuffle has read so far, handed from one video's player to the next one's. */
        val pool: ShufflePool? = null,
    )

    @Volatile
    private var armed: Request? = null

    /** A press: the player that opens [Request.chatId] and [Request.messageId] next takes it ([take]). */
    fun arm(request: Request) {
        armed = request
    }

    /**
     * The press for the video [chatId] and [messageId], once: a player that opens any other video
     * (a different press, a share, a resume) takes nothing, and a second open of the same video is
     * not a second press.
     */
    fun take(chatId: Long, messageId: Long): Request? {
        val request = armed ?: return null
        if (request.chatId != chatId || request.messageId != messageId) return null
        armed = null
        return request
    }

    /** A fresh non-zero seed for a Shuffle press. */
    fun newSeed(clock: Long): Long = mix(clock).let { if (it == 0L) 1L else it }

    private fun mix(input: Long): Long {
        var x = input + -7046029254386353131L
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return (x xor (x ushr 31)) ushr 1
    }

    /** A video's place in the shuffled order for [seed]: the same every time, whatever else is loaded. */
    fun key(seed: Long, chatId: Long, messageId: Long): Long = mix(seed xor mix(chatId) xor (messageId * 31 + 7))

    fun key(seed: Long, item: MediaItem): Long = key(seed, item.chatId, item.messageId)

    /** [items] in the shuffled order for [seed]; the same list gives the same order. */
    fun shuffled(seed: Long, items: List<MediaItem>): List<MediaItem> = items.sortedBy { key(seed, it) }

    /**
     * The video to start a Shuffle with: the first of [items] (a grid's loaded pages) in the
     * shuffled order, or null for none. Usable videos only: pieces of a split video are left out.
     */
    fun shuffleFirst(seed: Long, items: List<MediaItem>): MediaItem? =
        items.filter { it.splitPiece == null }.minByOrNull { key(seed, it) }

    /**
     * The neighbours of [current] in a list's [queue] (a list is read whole, so nothing is asked of
     * Telegram): the video before and after it in the list's order, or in the shuffled order for a
     * [seed] other than 0. [current] not in the queue (the viewer went elsewhere) has none.
     */
    fun listSteps(queue: List<MediaItem>, current: MediaItem, seed: Long = 0L): EpisodeSteps {
        val usable = queue.filter { it.splitPiece == null }
        val order = if (seed == 0L) usable else shuffled(seed, usable)
        val at = order.indexOfFirst { it.chatId == current.chatId && it.messageId == current.messageId }
        if (at < 0) return EpisodeSteps()
        return EpisodeSteps(previous = order.getOrNull(at - 1), next = order.getOrNull(at + 1))
    }

    /**
     * The pages of a chat read for a Shuffle, a few at a time. [steps] gives the video after the one
     * playing: the unplayed video of lowest [key] among those read so far, so the order is the
     * seed's and nothing repeats until every video read has played; the one before it is the one
     * played just before. A page of the chat is read (at most [PAGES_PER_ADVANCE] in one call) only
     * when every video read has played, so a step costs a page at most and a chat of 20,000 is never
     * fetched whole. The pool stops growing at [MAX_POOL] videos, so a chat of 100,000 holds a
     * bounded list; its end ends the Shuffle, as the last video ends autoplay today.
     *
     * A pool is made fresh for a Shuffle just pressed, and again for a player rebuilt after its
     * process died: its first look takes every video that lies earlier in the order than the one
     * playing as played already, so the run goes on from there in the same order for the same
     * seed, as far as the chat's pages are the same. Handed from one video's player to the next
     * ([Request.pool]) it also knows exactly what was played.
     *
     * The pool holds data only (PB2-06): the page reader is handed to each [steps] call by the player
     * asking, so a pool handed on through a whole run never keeps the first player (its views, its
     * client) alive, and a player after a profile switch reads with its own client. [fits] must
     * capture values only, never a screen.
     */
    class ShufflePool(
        private val seed: Long,
        private val fits: (MediaItem) -> Boolean = { true },
    ) {
        private val pool = HashMap<Long, MediaItem>()
        private val played = HashSet<Long>()
        private val trail = ArrayList<MediaItem>()
        private var cursor = 0L
        private var exhausted = false

        /** Pages read over this pool's life, for tests and the logs. */
        var pagesRead = 0
            private set

        val size: Int get() = pool.size

        /**
         * The steps around [current]. [fetch] reads a page of the chat around an anchor (0 is the
         * newest), null when it could not be asked (offline, a failed search): the pool keeps what
         * it had and nothing further is invented. It is used during this call only, never kept.
         */
        suspend fun steps(current: MediaItem, fetch: suspend (anchor: Long, newer: Boolean) -> ChatOrder.Window?): EpisodeSteps {
            // A pool that has read nothing yet (a Shuffle just pressed, or a player rebuilt after its process died) may
            // read further to find where this video stands in the order; after that a step costs a page or two.
            val allowed = if (pagesRead == 0) FRESH_PAGES else PAGES_PER_ADVANCE
            played += current.messageId
            val at = trail.indexOfFirst { it.messageId == current.messageId }
            if (at >= 0) {
                while (trail.size > at + 1) trail.removeAt(trail.lastIndex)
            } else {
                trail += current
                if (trail.size > MAX_TRAIL) trail.removeAt(0)
            }
            val previous = trail.getOrNull(trail.size - 2)
            var read = 0
            while (true) {
                val next = pool.values.filter { it.messageId !in played && usable(it, current) }.minByOrNull { key(seed, it) }
                if (next != null || exhausted || read >= allowed || pool.size >= MAX_POOL) {
                    return EpisodeSteps(previous = previous, next = next)
                }
                if (!readPage(current, fetch)) return EpisodeSteps(previous = previous, next = null)
                read++
            }
        }

        private fun usable(item: MediaItem, current: MediaItem): Boolean =
            item.messageId != current.messageId && !sameFile(item, current)

        private suspend fun readPage(current: MediaItem, fetch: suspend (anchor: Long, newer: Boolean) -> ChatOrder.Window?): Boolean {
            val page = fetch(cursor, false) ?: return false
            val first = pagesRead == 0
            pagesRead++
            for (item in page.items) {
                if (item.splitPiece == null && fits(item)) pool[item.messageId] = item
            }
            if (first) {
                // What lies earlier in the order than the video playing was played already (or never will be).
                val here = key(seed, current)
                for (item in pool.values) if (key(seed, item) < here) played += item.messageId
            }
            // The cursor must move on: a page that answers the anchor asked is the same page again.
            if (page.next == 0L || page.next == cursor) exhausted = true else cursor = page.next
            return true
        }

        private fun sameFile(a: MediaItem, b: MediaItem): Boolean =
            a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes && a.fileName.isNotBlank() && a.fileName == b.fileName
    }

    /** Pages read for one step of a Shuffle: one is the usual, two when the first held nothing ahead. */
    const val PAGES_PER_ADVANCE = 2

    /** Pages a fresh pool may read for its first step: a video far into the order needs a few to find what is ahead of it. */
    const val FRESH_PAGES = ChatOrder.MAX_PAGES

    /** The most videos a Shuffle keeps in memory: about 50 pages of a chat. */
    const val MAX_POOL = 2_000

    /** How many played videos Previous can walk back through. */
    const val MAX_TRAIL = 200
}
