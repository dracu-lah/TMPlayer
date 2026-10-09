package com.tmplayer.data

/**
 * One row on Home: what the viewer was part way through, the newest videos in a starred chat, or
 * the newest videos across the starred chats together.
 *
 * A chat row and the Recent row can be on screen before their videos are: [loaded] false is a row
 * still asking Telegram, which draws as a placeholder strip so the rows under it do not jump when
 * it fills.
 */
sealed interface HomeRow {
    /** Stable while the row loads and refills, so a lazy list can key on it. */
    val key: String

    /** What a row's "See all" has to say: how many videos there are and whether that is all of them. */
    val total: Int get() = 0

    /** True when the count is only what has been fetched so far, so "12+" rather than "12". */
    val totalAtLeast: Boolean get() = false

    /** Whether the row leaves anything out, which is the only time it offers "See all". */
    val hasMore: Boolean get() = false

    /**
     * @param more the records past the row's [HomeRows.LIMIT], newest first, for the See all
     *   tile's picture and count.
     */
    data class Continue(
        val records: List<ResumeRecord>,
        val more: List<ResumeRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "continue"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * @param more the chat's fetched videos that the row does not show, newest first.
     * @param complete false when Telegram has more of this chat than was fetched for the row.
     * @param fetched how many of the chat's videos were fetched, within the size limits.
     */
    data class Chat(
        val chatId: Long,
        val title: String,
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
        val more: List<MediaItem> = emptyList(),
        val complete: Boolean = true,
        val fetched: Int = 0,
    ) : HomeRow {
        override val key: String get() = "chat-$chatId"
        override val total: Int get() = fetched
        override val totalAtLeast: Boolean get() = !complete
        override val hasMore: Boolean get() = loaded && (more.isNotEmpty() || !complete)
    }

    /** The starred chats' newest videos in one line, newest first, whichever chat they came from. */
    data class Recent(val entries: List<ShelfEntry>, val loaded: Boolean) : HomeRow {
        override val key: String get() = "recent"
    }
}

/**
 * Builds Home's rows from what is on this device and what Telegram has answered so far.
 *
 * Pure, so the rules live in one place for the phone, the television and the desktop:
 * - Continue watching first, then one row per starred chat in the chat list's own order, then
 *   "Recently added in starred chats".
 * - Recently added draws only from the starred chats. Somebody's other chats are family groups,
 *   work and news as often as films, so what is new there is not what Home is for. It needs two
 *   starred chats at least: with one, it would repeat that chat's own row.
 * - Every row holds at most [LIMIT] tiles. A show folded into one tile counts once.
 * - A video appears once among Continue and the chat rows: something part way through is in
 *   Continue and nowhere else. Recently added is the starred chats' videos again, merged into one
 *   timeline, so it leaves out only what is in Continue.
 * - A row that has loaded and found nothing is left out rather than drawn as an empty strip.
 */
object HomeRows {

    /** Tiles per row. Ten fill a television's width twice over and keep each row cheap to compose. */
    const val LIMIT = 10

    /** Starred chats it takes for Recently added: one alone already has a row of its own. */
    const val RECENT_MIN_STARRED = 2

    /** The starred chats that get a row, in the chat list's order, which is Telegram's recency. */
    fun favouriteChats(chats: List<ChatSummary>, favourites: Set<Long>): List<ChatSummary> =
        if (favourites.isEmpty()) emptyList() else chats.filter { it.id in favourites }

    /**
     * @param favourites the starred chats, already in display order (see [favouriteChats]).
     * @param loaded each starred chat's newest videos, by chat id; a chat missing here is still loading.
     * @param recent the newest videos across every chat, or null while that search is running. Only
     *   the starred chats' are kept, together with what [loaded] already has of them.
     * @param partial the starred chats whose fetch stopped short of their oldest video.
     */
    fun build(
        continueWatching: List<ResumeRecord>,
        favourites: List<ChatSummary>,
        loaded: Map<Long, List<MediaItem>>,
        recent: List<MediaItem>?,
        partial: Set<Long> = emptySet(),
    ): List<HomeRow> = buildList {
        val seen = HashSet<String>()

        val everyResume = continueWatching.distinctBy { keyOf(it.chatId, it.messageId) }
        val resume = everyResume.take(LIMIT)
        resume.forEach { seen += keyOf(it.chatId, it.messageId) }
        if (resume.isNotEmpty()) add(HomeRow.Continue(resume, everyResume.drop(LIMIT)))
        val inContinue = HashSet(seen)

        val starred = favourites.distinctBy { it.id }
        starred.forEach { chat ->
            val items = loaded[chat.id]
            if (items == null) {
                add(HomeRow.Chat(chat.id, chat.title, emptyList(), loaded = false))
                return@forEach
            }
            val entries = row(items, seen)
            if (entries.isNotEmpty()) {
                val all = items.distinctBy { it.id }
                add(
                    HomeRow.Chat(
                        chat.id,
                        chat.title,
                        entries,
                        loaded = true,
                        more = beyond(all, entries),
                        complete = chat.id !in partial,
                        fetched = all.size,
                    ),
                )
            }
        }

        if (starred.size < RECENT_MIN_STARRED) return@buildList
        val starredIds = starred.mapTo(HashSet()) { it.id }
        if (recent == null) {
            add(HomeRow.Recent(emptyList(), loaded = false))
        } else {
            // The search across chats reaches a starred chat further back than its own row's
            // fetch, and a starred chat's fetch fills in whatever the search did not reach.
            val pool = recent.filter { it.chatId in starredIds } + starredIds.flatMap { loaded[it].orEmpty() }
            val entries = row(pool, inContinue)
            if (entries.isNotEmpty()) add(HomeRow.Recent(entries, loaded = true))
        }
    }

    /**
     * Newest first, without anything an earlier row already showed, folded into shows, cut to
     * [LIMIT]. Whatever ends up in the row is added to [seen], every episode of a folded show
     * included (every copy of each), so a later row cannot show one of them again on its own.
     */
    private fun row(items: List<MediaItem>, seen: MutableSet<String>): List<ShelfEntry> {
        val fresh = items
            .filterNot { keyOf(it.chatId, it.messageId) in seen }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
        val entries = SeriesShelf.arrange(fresh).take(LIMIT)
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> seen += keyOf(entry.item.chatId, entry.item.messageId)
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.forEach { seen += keyOf(it.chatId, it.messageId) }
            }
        }
        return entries
    }

    /** A chat's videos that none of the row's tiles stands for, newest first: what See all adds. */
    private fun beyond(items: List<MediaItem>, entries: List<ShelfEntry>): List<MediaItem> {
        val shown = HashSet<String>()
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> shown += entry.item.id
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.forEach { shown += it.id }
            }
        }
        return items
            .filterNot { it.id in shown }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
    }

    /** The same key [SettingsStore.progressKey] writes, so a resume record and a video match. */
    private fun keyOf(chatId: Long, messageId: Long) = SettingsStore.progressKey(chatId, messageId)
}
