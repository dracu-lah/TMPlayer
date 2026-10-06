package com.tmplayer.data

/**
 * Where a search on the chat list looks.
 *
 * [Chats] is the list filtered by name, as it always was. [AllVideos] also asks Telegram for videos
 * across every chat in the main list, and the answer comes back in two parts: the chats whose names
 * match, then the videos.
 */
enum class SearchScope { Chats, AllVideos }

/**
 * Where the next page of an all-chats search starts.
 *
 * Telegram searches one kind of message at a time, so videos posted as videos and videos posted as
 * files are two searches, each with its own offset. An empty offset is the start; a search is done
 * when Telegram answers with no next offset.
 */
data class AllChatsCursor(
    val videoOffset: String = "",
    val documentOffset: String = "",
    val videoDone: Boolean = false,
    val documentDone: Boolean = false,
) {
    val done: Boolean get() = videoDone && documentDone
}

/** One page of an all-chats search, already screened down to playable videos. */
data class AllChatsPage(
    val items: List<MediaItem>,
    val cursor: AllChatsCursor,
)

/** The two halves of an all-chats search, as the screen draws them. */
data class SearchResults(
    val query: String,
    val chats: List<ChatSummary>,
    val videos: List<MediaItem>,
    val loadingVideos: Boolean = false,
    val endReached: Boolean = false,
) {
    val isEmpty: Boolean get() = chats.isEmpty() && videos.isEmpty()
}

/** The pure half of the all-chats search: matching chats, merging pages, the fallback query. */
object AllChatsSearch {

    /** As many chats as fit above the videos without pushing them off a television's screen. */
    const val CHAT_LIMIT = 8

    /** Videos asked for per page, per kind. */
    const val PAGE_SIZE = 30

    /**
     * The chats whose names answer [query], best first. Archived chats count: the search is across
     * all chats, and somebody looking for one by name does not care which list it was filed in.
     */
    fun matchingChats(chats: List<ChatSummary>, query: String, limit: Int = CHAT_LIMIT): List<ChatSummary> {
        if (query.isBlank()) return emptyList()
        return Fuzzy.rank(chats, query) { it.title }.take(limit)
    }

    /**
     * [page] after [existing]: each video once, the new ones newest first. What is already on
     * screen keeps its place, so paging never moves a tile out from under the remote.
     */
    fun merge(existing: List<MediaItem>, page: List<MediaItem>): List<MediaItem> {
        val seen = existing.mapTo(HashSet()) { it.id }
        val fresh = page.filter { seen.add(it.id) }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
        return existing + fresh
    }

    /**
     * What to ask Telegram when the whole query found nothing.
     *
     * Telegram matches the query as one run of characters in the file name, so "harbour notes 2"
     * misses "Harbour.Notes.S02E01". The longest word is the most distinctive, and the pages it
     * returns are then held to the whole query by [keep]. Null when there is nothing better to try.
     */
    fun fallbackQuery(query: String): String? {
        val words = Fuzzy.tokens(query)
        if (words.size < 2) return null
        return words.maxByOrNull { it.length }
    }

    /**
     * The videos a page returned that answer what was typed. A page for the full query is taken
     * as Telegram gave it; a page for the [fallbackQuery] is screened against the whole query.
     */
    fun keep(items: List<MediaItem>, query: String, fallback: Boolean): List<MediaItem> =
        if (!fallback) items else items.filter { Fuzzy.score(it.fileName.ifBlank { it.title }, query) > 0 }

    /** Both halves together, for the screen. */
    fun split(
        query: String,
        chats: List<ChatSummary>,
        videos: List<MediaItem>,
        loadingVideos: Boolean = false,
        endReached: Boolean = false,
    ): SearchResults = SearchResults(
        query = query,
        chats = matchingChats(chats, query),
        videos = videos,
        loadingVideos = loadingVideos,
        endReached = endReached,
    )
}
