package com.tmplayer.data

import kotlin.random.Random

/**
 * The order a chat's audio plays in (U11): the files as posted, oldest first (a course or an
 * album is heard from its start), or shuffled, and whether the end of the list starts it over.
 * Pure and immutable: [PlayQueue] holds the current plan, and every change (a toggle, another
 * page of the chat) answers a new one, so a player reading it from another thread sees one whole
 * plan or the next, never half of a change.
 *
 * A chat with thousands of files costs a list and a map of its keys; asking for the neighbours of
 * the file playing is one lookup, not a scan.
 */
class QueuePlan private constructor(
    /** The files in the order they play. */
    val order: List<MediaItem>,
    val shuffle: Boolean,
    /** After the last file the first plays again, and Previous on the first goes to the last. */
    val repeatAll: Boolean,
    private val seed: Long,
    private val positions: Map<Long, Int>,
) {
    val size: Int get() = order.size

    /** The files either side of [item], or null when it is not in the plan. */
    fun stepsFor(item: MediaItem): EpisodeSteps? {
        val at = positionOf(item)
        if (at < 0) return null
        val wrap = repeatAll && order.size > 1
        val previous = order.getOrNull(at - 1) ?: if (wrap) order.last() else null
        val next = order.getOrNull(at + 1) ?: if (wrap) order.first() else null
        return EpisodeSteps(previous = previous, next = next)
    }

    /** Where [item] plays in the order, or -1. */
    fun positionOf(item: MediaItem): Int {
        val at = positions[item.messageId] ?: return -1
        return if (order[at].chatId == item.chatId) at else -1
    }

    /**
     * The same files shuffled when [on], with [current] (the file playing, or null before any
     * does) first so nothing the viewer is hearing is played again; or in the order posted when
     * not, from [current] on.
     */
    fun withShuffle(on: Boolean, current: MediaItem? = null): QueuePlan {
        if (on == shuffle) return this
        val files = if (on) shuffled(order, current, seed) else order.sortedWith(POSTED)
        return build(files, shuffle = on, repeatAll = repeatAll, seed = seed)
    }

    fun withRepeatAll(on: Boolean): QueuePlan =
        if (on == repeatAll) this else QueuePlan(order, shuffle, on, seed, positions)

    /**
     * Another page of the chat's files joined in: files already here are kept as they are (a page
     * asked for twice adds nothing). In the posted order they take their place by date, so older
     * pages land at the front; shuffled, they are mixed in after the file at [current], so the
     * listener is never moved.
     */
    fun merged(more: List<MediaItem>, current: MediaItem? = null): QueuePlan {
        val fresh = more.filter { positions[it.messageId]?.let { at -> order[at].chatId == it.chatId } != true }
            .distinctBy { it.chatId to it.messageId }
        if (fresh.isEmpty()) return this
        if (!shuffle) return build((order + fresh).sortedWith(POSTED), shuffle = false, repeatAll = repeatAll, seed = seed)
        val keep = current?.let { positionOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: order.size
        val head = order.subList(0, keep)
        val tail = order.subList(keep, order.size) + fresh
        return build(head + tail.shuffled(Random(seed + order.size)), shuffle = true, repeatAll = repeatAll, seed = seed)
    }

    companion object {
        /** Posted order: oldest first, the message number breaking a tie between chats' equal numbers. */
        private val POSTED = compareBy<MediaItem> { it.messageId }.thenBy { it.chatId }

        val Empty = of(emptyList(), shuffle = false, repeatAll = false, seed = 0)

        /** A plan for [files] in the order posted, or shuffled; [seed] makes a shuffle repeatable. */
        fun of(files: List<MediaItem>, shuffle: Boolean = false, repeatAll: Boolean = false, seed: Long = Random.nextLong(), current: MediaItem? = null): QueuePlan {
            val ordered = if (shuffle) shuffled(files.distinctBy { it.chatId to it.messageId }, current, seed) else files.sortedWith(POSTED)
            return build(ordered, shuffle, repeatAll, seed)
        }

        private fun shuffled(files: List<MediaItem>, first: MediaItem?, seed: Long): List<MediaItem> {
            val mixed = files.shuffled(Random(seed))
            if (first == null) return mixed
            val at = mixed.indexOfFirst { it.chatId == first.chatId && it.messageId == first.messageId }
            if (at <= 0) return mixed
            return listOf(mixed[at]) + mixed.filterIndexed { index, _ -> index != at }
        }

        private fun build(order: List<MediaItem>, shuffle: Boolean, repeatAll: Boolean, seed: Long): QueuePlan {
            // A queue is one chat's files: the message number alone names a file in it. A second
            // chat's file with the same number (never queued together) is found by chat as well.
            val positions = HashMap<Long, Int>(order.size * 2)
            order.forEachIndexed { index, item -> positions.putIfAbsent(item.messageId, index) }
            return QueuePlan(order, shuffle, repeatAll, seed, positions)
        }
    }
}
