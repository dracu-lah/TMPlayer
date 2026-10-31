package com.tmplayer.data

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
    /**
     * The search inside the added chats (HX27) walks the chats instead of Telegram's offsets:
     * this is how many of them it has read. Both done flags go true together once it runs out of
     * chats, so [done] means the same thing whichever search ran.
     */
    val chatsSearched: Int = 0,
) {
    val done: Boolean get() = videoDone && documentDone
}

/** One page of an all-chats search, already screened down to playable videos. */
data class AllChatsPage(
    val items: List<MediaItem>,
    val cursor: AllChatsCursor,
    /** The chats [items] came from, for their names and pictures. */
    val chats: List<ChatBrief> = emptyList(),
)

/**
 * The paging figures and the fallback query of the search across every chat. The rest of it, the
 * cards, their order and the filters, is [GlobalSearch].
 */
object AllChatsSearch {

    /** Videos asked for per page, per kind. */
    const val PAGE_SIZE = 30

    /**
     * Added chats read per page by the search inside them ([ChatRepository.searchAddedChats]).
     * Eight chats is two calls each, which fills a page for an ordinary query while leaving the
     * per-call budget of AGENTS.md room for Home and the chat list beside it.
     */
    const val CHATS_PER_PAGE = 8

    /**
     * What to ask Telegram when the whole query found nothing.
     *
     * Telegram matches the query as one run of characters in the file name, so "harbour notes 2"
     * misses "Harbour.Notes.S02E01". The longest word is the most distinctive, and the pages it
     * returns are then held to the whole query. Null when there is nothing better to try.
     */
    fun fallbackQuery(query: String): String? {
        val words = Fuzzy.tokens(query)
        if (words.size < 2) return null
        return words.maxByOrNull { it.length }
    }
}
