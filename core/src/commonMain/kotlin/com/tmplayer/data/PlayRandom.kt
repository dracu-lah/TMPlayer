package com.tmplayer.data

import kotlin.random.Random

/**
 * Which video "Play random" plays (U16), the same on the phone, the television and the desktop.
 *
 * It picks from the videos already loaded for the list in front of the viewer and never asks for
 * more, so a chat of 100,000 videos costs nothing extra. With no setting to flip, it leaves out
 * what was watched while anything unwatched is left, and never plays the video it played last
 * from the same list straight away again.
 */
object PlayRandom {

    /**
     * One of [items], or null for an empty list (the menu entry is then left out): unwatched first,
     * never [last] again while anything else is left. The rule is [RandomPick]'s, which the web
     * player runs too.
     */
    fun <T> pick(
        items: List<T>,
        id: (T) -> String,
        watched: (T) -> Boolean,
        last: String? = null,
        random: Random = Random.Default,
    ): T? = RandomPick.pick(items, id, watched, last, random)

    /**
     * What each list played last, by the list's own key (a chat and topic, Downloads), for this run
     * only, least recently used first. Kept in order by hand rather than by an access-ordered
     * LinkedHashMap subclass, which Kotlin/Native (the iOS app) does not have.
     */
    private val lastPlayed = LinkedHashMap<String, String>()

    /** [lastPlayed]'s entry for [scope], moved to the newest end. Called under its lock. */
    private fun touch(scope: String): String? = lastPlayed.remove(scope)?.also { lastPlayed[scope] = it }

    /** Records [id] for [scope] as the newest, dropping the oldest past [KEPT_LISTS]. Called under its lock. */
    private fun remember(scope: String, id: String) {
        lastPlayed.remove(scope)
        lastPlayed[scope] = id
        while (lastPlayed.size > KEPT_LISTS) {
            val oldest = lastPlayed.keys.iterator().next()
            lastPlayed.remove(oldest)
        }
    }

    private const val KEPT_LISTS = 32

    /**
     * [pick] for the list named [scope], remembering what it chose so the next press does not
     * repeat it. Safe to call from any thread.
     */
    fun <T> next(
        scope: String,
        items: List<T>,
        id: (T) -> String,
        watched: (T) -> Boolean,
        random: Random = Random.Default,
    ): T? {
        val last = synchronized(lastPlayed) { touch(scope) }
        val chosen = pick(items, id, watched, last, random) ?: return null
        synchronized(lastPlayed) { remember(scope, id(chosen)) }
        return chosen
    }

    /** Forgets what every list played last (tests, and signing out). */
    fun forget() = synchronized(lastPlayed) { lastPlayed.clear() }

    /** The list a chat's (or one of its topics') random press belongs to. */
    fun chatScope(chatId: Long, topicId: Int = 0): String = "chat:$chatId:$topicId"

    /** The Downloads list's key. */
    const val DOWNLOADS_SCOPE = "downloads"
}
