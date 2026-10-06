package com.tmplayer.data

/**
 * One row on Home: what the viewer was part way through, the newest videos in a starred chat, or
 * the newest videos everywhere else.
 *
 * A chat row and the Recent row can be on screen before their videos are: [loaded] false is a row
 * still asking Telegram, which draws as a placeholder strip so the rows under it do not jump when
 * it fills.
 */
sealed interface HomeRow {
    /** Stable while the row loads and refills, so a lazy list can key on it. */
    val key: String

    data class Continue(val records: List<ResumeRecord>) : HomeRow {
        override val key: String get() = "continue"
    }

    data class Chat(
        val chatId: Long,
        val title: String,
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
    ) : HomeRow {
        override val key: String get() = "chat-$chatId"
    }

    data class Recent(val entries: List<ShelfEntry>, val loaded: Boolean) : HomeRow {
        override val key: String get() = "recent"
    }
}

/**
 * Builds Home's rows from what is on this device and what Telegram has answered so far.
 *
 * Pure, so the rules live in one place for the phone, the television and the desktop:
 * - Continue watching first, then one row per starred chat in the chat list's own order, then
 *   "Recently added across chats".
 * - Every row holds at most [LIMIT] tiles. A show folded into one tile counts once.
 * - A video appears once on the whole page, in the first row that has it: something part way
 *   through is in Continue and nowhere else, and Recent leaves the starred chats to their own rows.
 * - A row that has loaded and found nothing is left out rather than drawn as an empty strip.
 */
object HomeRows {

    /** Tiles per row. Ten fill a television's width twice over and keep each row cheap to compose. */
    const val LIMIT = 10

    /** The starred chats that get a row, in the chat list's order, which is Telegram's recency. */
    fun favouriteChats(chats: List<ChatSummary>, favourites: Set<Long>): List<ChatSummary> =
        if (favourites.isEmpty()) emptyList() else chats.filter { it.id in favourites }

    /**
     * @param favourites the starred chats, already in display order (see [favouriteChats]).
     * @param loaded each starred chat's newest videos, by chat id; a chat missing here is still loading.
     * @param recent the newest videos across every chat, or null while that search is running.
     * @param seriesView fold a row's episodes of one show into a single tile, as the chat grid does.
     */
    fun build(
        continueWatching: List<ResumeRecord>,
        favourites: List<ChatSummary>,
        loaded: Map<Long, List<MediaItem>>,
        recent: List<MediaItem>?,
        seriesView: Boolean = true,
    ): List<HomeRow> = buildList {
        val seen = HashSet<String>()

        val resume = continueWatching
            .distinctBy { keyOf(it.chatId, it.messageId) }
            .take(LIMIT)
        resume.forEach { seen += keyOf(it.chatId, it.messageId) }
        if (resume.isNotEmpty()) add(HomeRow.Continue(resume))

        val starred = favourites.distinctBy { it.id }
        starred.forEach { chat ->
            val items = loaded[chat.id]
            if (items == null) {
                add(HomeRow.Chat(chat.id, chat.title, emptyList(), loaded = false))
                return@forEach
            }
            val entries = row(items, seen, seriesView)
            if (entries.isNotEmpty()) add(HomeRow.Chat(chat.id, chat.title, entries, loaded = true))
        }

        val starredIds = starred.mapTo(HashSet()) { it.id }
        if (recent == null) {
            add(HomeRow.Recent(emptyList(), loaded = false))
        } else {
            val entries = row(recent.filterNot { it.chatId in starredIds }, seen, seriesView)
            if (entries.isNotEmpty()) add(HomeRow.Recent(entries, loaded = true))
        }
    }

    /**
     * Newest first, without anything an earlier row already showed, folded into shows, cut to
     * [LIMIT]. Whatever ends up in the row is added to [seen], every episode of a folded show
     * included, so a later row cannot show one of them again on its own.
     */
    private fun row(items: List<MediaItem>, seen: MutableSet<String>, seriesView: Boolean): List<ShelfEntry> {
        val fresh = items
            .filterNot { keyOf(it.chatId, it.messageId) in seen }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
        val entries = (if (seriesView) SeriesShelf.arrange(fresh) else fresh.map { ShelfEntry.File(it) })
            .take(LIMIT)
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> seen += keyOf(entry.item.chatId, entry.item.messageId)
                is ShelfEntry.Show -> entry.series.episodes.forEach { seen += keyOf(it.item.chatId, it.item.messageId) }
            }
        }
        return entries
    }

    /** The same key [SettingsStore.progressKey] writes, so a resume record and a video match. */
    private fun keyOf(chatId: Long, messageId: Long) = SettingsStore.progressKey(chatId, messageId)
}
