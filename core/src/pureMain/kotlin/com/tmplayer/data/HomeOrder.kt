package com.tmplayer.data

/**
 * The order of Home's rows, as the viewer arranged it in Settings, Home (EG-F7, EG-F23, EG-F27).
 * A row is named by its [HomeRow.key] ("continue", "recent", "watch-later", "new-episodes",
 * "chat-<id>", "list-<id>" for a named list), and the order is the list of those keys, stored as one text setting
 * (`home_order`, comma separated). The one list serves Home on every platform and Settings' own
 * list of rows.
 *
 * The stored list is outside data (an older build's, a newer build's, a backup, a hand edit), so
 * [parse] keeps only what looks like a key and [arrange] trusts nothing else about it:
 * - a key the rows do not have (a newer version's row, an unstarred chat, a row Home does not
 *   build) is ignored, and costs nothing;
 * - a row the list has never heard of (a chat starred since) stays at its default place: right
 *   after the row that stood before it by default;
 * - an empty list is Home's default order.
 */
object HomeOrder {

    const val CONTINUE = "continue"
    const val RECENT = "recent"
    const val WATCH_LATER = "watch-later"
    const val NEW_EPISODES = "new-episodes"
    private const val CHAT_PREFIX = "chat-"

    /** Keys kept from the stored text: an account with thousands of stars still fits the setting. */
    const val MAX_KEYS = 2_000

    /** A longer word than any key ("chat-" and a 64 bit id) is not one. */
    private const val MAX_KEY_LENGTH = 32

    fun chatKey(chatId: Long): String = "$CHAT_PREFIX$chatId"

    private fun isKey(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_KEY_LENGTH) return false
        return word.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    /** The keys in the stored text, in order, each once; whatever is not a key is dropped. */
    fun parse(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        var from = 0
        // Walked by hand: a huge garbage value must not become a huge list first.
        while (from <= stored.length && out.size < MAX_KEYS) {
            var to = stored.indexOf(',', from)
            if (to < 0) to = stored.length
            val word = stored.substring(from, to).trim()
            if (isKey(word)) out += word
            from = to + 1
        }
        return out.toList()
    }

    /** The text [parse] reads back. */
    fun encode(order: List<String>): String = order.asSequence().filter(::isKey).distinct().take(MAX_KEYS).joinToString(",")

    /**
     * [defaults], the keys in the order Home builds them, arranged by [stored]: the stored keys
     * that are among them first, in their stored order, then every other key placed after the
     * key that stands before it in [defaults] (the first of them at the top when nothing before
     * it is placed).
     */
    fun arrange(defaults: List<String>, stored: List<String>): List<String> {
        val every = defaults.distinct()
        if (stored.isEmpty() || every.size < 2) return every
        val wanted = every.toHashSet()
        val placed = LinkedHashSet<String>()
        stored.forEach { if (it in wanted) placed += it }
        if (placed.size == every.size) return placed.toList()
        val out = placed.toMutableList()
        every.forEachIndexed { at, key ->
            if (key in placed) return@forEachIndexed
            // Its default neighbour above, or the top when there is none.
            var spot = 0
            for (before in at - 1 downTo 0) {
                val found = out.indexOf(every[before])
                if (found >= 0) {
                    spot = found + 1
                    break
                }
            }
            out.add(spot, key)
        }
        return out
    }

    /** [rows] in the order [stored] asks for; the rows keep their own order where it says nothing. */
    fun sort(rows: List<HomeRow>, stored: List<String>): List<HomeRow> {
        if (stored.isEmpty() || rows.size < 2) return rows
        val byKey = rows.associateBy { it.key }
        return arrange(rows.map { it.key }, stored).mapNotNull { byKey[it] }
    }

    /** [order] with [key] moved [by] places (negative is up), stopping at either end. Unknown keys change nothing. */
    fun move(order: List<String>, key: String, by: Int): List<String> {
        val from = order.indexOf(key)
        if (from < 0) return order
        return moveTo(order, from, from + by)
    }

    /** [order] with the row at [from] put at [to] (both clamped to the list); the same list when nothing moves. */
    fun moveTo(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices) return order
        val target = to.coerceIn(0, order.lastIndex)
        if (target == from) return order
        return order.toMutableList().also { it.add(target, it.removeAt(from)) }
    }

    /**
     * What Settings lists: the rows Home can draw, in the order Home draws them. Continue watching
     * and Recently added, then a row for each starred chat in the chat list's order
     * ([HomeRows.favouriteChats]); Watch later and New episodes are on the History page, not Home
     * (Home fills itself, [HomeRows]). Each [Entry]'s [Entry.chatTitle] is set for a chat's row only.
     */
    fun editorRows(starred: List<ChatSummary>, stored: List<String>, lists: List<ListInfo> = emptyList()): List<Entry> {
        val defaults = buildList {
            add(Entry(CONTINUE))
            add(Entry(RECENT))
            starred.distinctBy { it.id }.forEach { add(Entry(chatKey(it.id), it.title)) }
            // The named lists on Home (HX43), after the chats, as Home builds them.
            lists.distinctBy { it.id }.forEach { add(Entry(NamedListRules.homeKey(it.id), listName = it.name)) }
        }
        val byKey = defaults.associateBy { it.key }
        return arrange(defaults.map { it.key }, stored).mapNotNull { byKey[it] }
    }

    /** One row in Settings' list: [chatTitle] set for a chat's row, [listName] for a named list's. */
    data class Entry(val key: String, val chatTitle: String? = null, val listName: String? = null)
}
