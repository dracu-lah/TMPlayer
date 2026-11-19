package com.tmplayer.data

import kotlin.random.Random

/**
 * The choice [PlayRandom] makes, apart from its memory of what each list played last: the part with
 * no JDK in it, so the web player (WebRules) plays "Play random" by the same rule as the apps (U16).
 */
object RandomPick {

    /**
     * One of [items], or null for an empty list (the menu entry is then left out).
     *
     * - Unwatched videos are the pool while there is any ([watched] says which are not); once
     *   everything is watched, the whole list is.
     * - [last], the id of the video this list played before, is left out of the pool when anything
     *   else is in it, and when only it is left in the unwatched pool, a watched video is chosen
     *   rather than a repeat. A list of one video has nothing else to offer, so it plays that one.
     * - [id] must be stable and unique within [items].
     */
    fun <T> pick(
        items: List<T>,
        id: (T) -> String,
        watched: (T) -> Boolean,
        last: String? = null,
        random: Random = Random.Default,
    ): T? {
        if (items.isEmpty()) return null
        val unwatched = items.filter { !watched(it) }
        val preferred = unwatched.ifEmpty { items }
        val fresh = preferred.filter { id(it) != last }
        val pool = when {
            fresh.isNotEmpty() -> fresh
            // Only the last one is left among the unwatched: another, watched, video beats a repeat.
            else -> items.filter { id(it) != last }.ifEmpty { items }
        }
        return pool[random.nextInt(pool.size)]
    }
}
