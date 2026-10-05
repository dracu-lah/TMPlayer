package com.tmplayer.data

/**
 * The last few things searched for inside a chat, newest first, offered again as chips.
 *
 * Kept as plain list arithmetic so the rules are testable without a store: [SettingsStore] only
 * reads and writes the result through [encode] and [decode].
 */
object RecentSearches {

    /** Five: one row of chips on a phone, and as many as anybody scans before typing again. */
    const val LIMIT = 5

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
