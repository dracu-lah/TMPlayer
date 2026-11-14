package com.tmplayer.data

/**
 * One topic found by its name in the search across every chat: listed as "Chat > Topic", and a
 * tap opens [chatId] already on [topic].
 */
@androidx.compose.runtime.Immutable
data class TopicHit(
    val chatId: Long,
    val chatTitle: String,
    val topic: ForumTopicSummary,
    /** How well the name answers the words typed ([TopicSearch.score]); never 0 for a listed hit. */
    val score: Int,
) {
    /** One per topic of one chat, so the same name in two chats is two hits. */
    val key: String get() = "$chatId:${topic.id}"
}

/**
 * Finding a group's topics by their names (U14), the pure half: which chats are asked, how a
 * name is matched, and the order hits are listed in. The asking itself is the search's
 * ([com.tmplayer.data.ChatRepository.searchForumTopics]).
 *
 * **Budget.** Telegram can only be asked one group at a time, and only groups with topics turned on
 * have any, so a search asks at most [CHATS_PER_SEARCH] of them, [AT_ONCE] at a time, in the chat
 * list's order (the ones the viewer used last first), [TOPICS_PER_CHAT] topics each. Telling a
 * group with topics from one without is a read of TDLib's memory, not of the network, and it too
 * stops after [FORUM_CHECKS_PER_SEARCH] chats. Nothing is asked before the viewer types, nothing
 * after a new letter (the search before it is cancelled), and nothing in the background. A group
 * whose topics are already known (its tabs were opened) is matched from them, for free.
 *
 * **Matching** goes through [Fuzzy]: case, accents and emoji fold away, so "cafe" finds
 * "☕ Café talks", and a word one letter out still lands.
 */
object TopicSearch {
    /** Groups with topics asked per search, at most: Telegram calls, so few. */
    const val CHATS_PER_SEARCH = 12

    /** Of those, asked at the same time. */
    const val AT_ONCE = 3

    /** Topics read per group per search: Telegram's own matches, best first. */
    const val TOPICS_PER_CHAT = 20

    /** Chats looked at in TDLib's memory per search to find the groups with topics. */
    const val FORUM_CHECKS_PER_SEARCH = 150

    /** Hits listed at most: a heading of topics, not a second results list. */
    const val MAX_HITS = 30

    /** A group's topic tabs grow a "Find a topic" field once it has more than this many topics. */
    const val CHIP_SEARCH_FROM = 20

    /**
     * How well [name] answers [query], or 0: see [Fuzzy.score]. Every word typed has to land
     * (a typo forgiven), stricter than a video's search, as "Season 2" must not list "Season 7"
     * of every group. A blank query, or one of emoji alone, matches nothing.
     */
    fun score(name: String, query: String): Int {
        val words = Fuzzy.tokens(query)
        if (words.isEmpty()) return 0
        if (words.any { Fuzzy.score(name, it) == 0 }) return 0
        return Fuzzy.score(name, query)
    }

    /**
     * The chats worth asking for [query]: groups (a channel or a person has no topics), allowed
     * by the kids lock and the added chats ([allows]), not already known to have no topics
     * ([forum] answers false), in the chat list's own order. Chats whose own title answers the
     * query go first, as the viewer may be typing "Course Lessons" for a topic of that group.
     * Archived chats only with [includeArchived], as the search's own switch says.
     */
    fun candidates(
        chats: List<ChatSummary>,
        query: String,
        allows: (Long) -> Boolean,
        forum: (Long) -> Boolean?,
        includeArchived: Boolean = true,
    ): List<ChatSummary> {
        val groups = chats.filter { it.kind == ChatKind.Group && (includeArchived || !it.isArchived) && allows(it.id) && forum(it.id) != false }
        if (groups.size < 2) return groups
        val (named, rest) = groups.partition { Fuzzy.score(it.title, query) > 0 }
        return named + rest
    }

    /**
     * [topics] of one chat that answer [query], as hits. [fromTelegram] is Telegram's own
     * answer to the query: it matched them on its side, so one our folding misses (a spelling
     * only Telegram knows) is kept with the lowest score rather than dropped.
     */
    fun hits(chatId: Long, chatTitle: String, topics: List<ForumTopicSummary>, query: String, fromTelegram: Boolean): List<TopicHit> =
        topics.distinctBy { it.id }.mapNotNull { topic ->
            val score = score(topic.name, query)
            when {
                score > 0 -> TopicHit(chatId, chatTitle, topic, score)
                fromTelegram -> TopicHit(chatId, chatTitle, topic, 1)
                else -> null
            }
        }

    /**
     * [hits] best first: the better match, then a topic that holds videos over one that holds
     * none, then a pinned one, then an open one over a closed one, then the chat that comes first
     * in the chat list ([chatOrder], by id; unknown last), then the name. At most [MAX_HITS], one
     * per chat and topic.
     */
    fun rank(hits: List<TopicHit>, chatOrder: Map<Long, Int> = emptyMap()): List<TopicHit> =
        hits.distinctBy { it.key }
            .sortedWith(
                compareByDescending<TopicHit> { it.score }
                    .thenByDescending { it.topic.hasVideos }
                    .thenByDescending { it.topic.isPinned }
                    .thenBy { it.topic.isClosed }
                    .thenBy { chatOrder[it.chatId] ?: Int.MAX_VALUE }
                    .thenBy { Fuzzy.normalize(it.topic.name) },
            )
            .take(MAX_HITS)

    /**
     * The chips a group's tab row shows while "Find a topic" holds [query]: the topics that
     * answer it, best first, and the chosen one ([selected]) always, so the tab in force never
     * vanishes from under the viewer. All of [topics] while [query] is blank.
     */
    fun chips(topics: List<ForumTopicSummary>, query: String, selected: Int): List<ForumTopicSummary> {
        if (Fuzzy.tokens(query).isEmpty()) return topics
        val matched = topics.map { it to score(it.name, query) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
        val chosen = topics.firstOrNull { it.id == selected && selected != 0 }
        return if (chosen == null || chosen in matched) matched else listOf(chosen) + matched
    }

    /** Whether a tab row of [count] topics offers the field: past [CHIP_SEARCH_FROM]. */
    fun chipSearchWanted(count: Int): Boolean = count > CHIP_SEARCH_FROM
}
