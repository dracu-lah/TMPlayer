package com.tmplayer.data

/** One show with smart downloads on (WP-83): the chat it is in and its key ([Series.key]). */
data class KeepNextShow(val chatId: Long, val showKey: String) {
    val id: String get() = "$chatId|$showKey"
}

/**
 * The shows with "Keep the next episodes" on, as the one setting stores them (`keep_next_shows`,
 * an account key that travels in a backup): one `chatId|showKey` a line. Read from a file a
 * restore or an older version may have cut short or filled with anything, so a bad line is
 * skipped, never thrown on, and the list is bounded.
 */
object KeepNextShows {

    /** At most this many shows keep episodes: a switch for every show of 3,000 chats stays bounded. */
    const val MAX_SHOWS = 100

    /** A show key longer than this is not one [SeriesShelf.keyOf] makes. */
    const val MAX_KEY = 200

    fun encode(shows: Collection<KeepNextShow>): String =
        shows.asSequence()
            .filter { valid(it) }
            .distinctBy { it.id }
            .take(MAX_SHOWS)
            .joinToString("\n") { it.id }

    fun decode(stored: String?): Set<KeepNextShow> {
        if (stored.isNullOrEmpty()) return emptySet()
        val out = LinkedHashSet<KeepNextShow>()
        for (line in stored.lineSequence()) {
            if (out.size >= MAX_SHOWS) break
            val bar = line.indexOf('|')
            if (bar <= 0) continue
            val chatId = line.substring(0, bar).trim().toLongOrNull() ?: continue
            val key = line.substring(bar + 1).trim()
            val show = KeepNextShow(chatId, key)
            if (valid(show)) out += show
        }
        return out
    }

    /** [shows] with [show] on or off. */
    fun with(shows: Set<KeepNextShow>, show: KeepNextShow, on: Boolean): Set<KeepNextShow> =
        if (on) (shows + show).take(MAX_SHOWS).toSet() else shows - show

    private fun valid(show: KeepNextShow): Boolean =
        show.chatId != 0L && show.showKey.isNotBlank() && show.showKey.length <= MAX_KEY &&
            show.showKey.none { it == '\n' || it == '\r' || it == '|' }
}
