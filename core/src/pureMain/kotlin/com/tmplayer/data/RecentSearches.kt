package com.tmplayer.data

/**
 * The last few things searched for, newest first, offered again: as chips inside a chat, and as
 * the list an empty search across every chat opens on. One list for both.
 *
 * Kept as plain list arithmetic so the rules are testable without a store: [SettingsStore] only
 * reads and writes the result through [encode] and [decode].
 */
object RecentSearches {

    /**
     * Ten kept. A chat's chip row shows the first few that fit, and the search across every chat
     * lists eight ([GlobalSearch.RECENT_SHOWN]): enough to find last week's search again.
     */
    const val LIMIT = 10

    /**
     * [query] put at the front of [current].
     *
     * Blank queries are ignored. The same words in another case or with other spacing are one
     * search, moved to the front rather than listed twice. A query typed one letter at a time is
     * recorded as it settles, so a query that only extends the newest entry replaces it, and one
     * that is a shorter start of the newest entry (a backspace on the way to another word) is not
     * recorded at all: "sev" followed by "severance" leaves "severance", not both.
     */
    fun add(current: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val clean = normalise(query)
        if (clean.isEmpty()) return current.take(limit)
        val key = clean.lowercase()
        val newest = current.firstOrNull()?.lowercase()
        if (newest != null && newest != key && newest.startsWith(key)) return current.take(limit)
        val rest = current.filterIndexed { at, it ->
            val other = it.lowercase()
            other != key && !(at == 0 && key.startsWith(other))
        }
        return (listOf(clean) + rest).take(limit)
    }

    /**
     * The list Clear all took ([before]) put back for its Undo, under whatever was searched since
     * ([current], newest first): a search made in the seconds before Undo is not lost, and one that
     * is in both lists is kept once.
     */
    fun restore(before: List<String>, current: List<String>, limit: Int = LIMIT): List<String> {
        val kept = current.map { it.lowercase() }.toSet()
        return (current + before.filter { it.lowercase() !in kept }).take(limit)
    }

    /** [current] without [query], matched as [add] matches: any case, any spacing. */
    fun remove(current: List<String>, query: String): List<String> {
        val key = normalise(query).lowercase()
        return current.filter { it.lowercase() != key }
    }

    /** One line, single spaces: a chip is not the place for a pasted paragraph. */
    fun normalise(query: String): String =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAX_LENGTH)

    /** One query per line; [normalise] guarantees none of them contains a line break. */
    fun encode(searches: List<String>): String = searches.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split('\n').map(::normalise).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(LIMIT)

    /** Longer than any real title search, short enough to fit a chip. */
    private const val MAX_LENGTH = 80
}
