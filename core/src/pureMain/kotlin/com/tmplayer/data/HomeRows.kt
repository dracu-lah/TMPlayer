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

    /**
     * The videos the viewer put aside for later, newest first.
     *
     * @param more the records past the row's [HomeRows.LIMIT], for the See all count.
     */
    data class WatchLater(
        val records: List<WatchLaterRecord>,
        val more: List<WatchLaterRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "watch-later"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * Episodes of the shows the viewer is watching that arrived since they last watched them (see
     * [com.tmplayer.data.NewEpisodes]). Only ever built with something in it, so it has no loading
     * state: a row that may well stay empty should not hold a placeholder strip open on Home.
     */
    data class NewEpisodes(val episodes: List<NewEpisode>) : HomeRow {
        override val key: String get() = "new-episodes"
    }

    /**
     * Home's chats' newest videos in one line, newest first, whichever chat they came from.
     *
     * @param starredOnly true when every chat it draws from is starred, which is what its heading
     *   says; false once Home fills itself from the chats with videos ([HomeRows.homeChats]).
     */
    data class Recent(
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
        val starredOnly: Boolean = true,
        /** The chats it draws from, which its See all page reads on through ([HomeRows.seeAll]). */
        val chatIds: List<Long> = emptyList(),
        /**
         * False when Settings turned the row off but kept the carousel, which is drawn from it:
         * the row is there for the carousel's pages and is not drawn as a row ([HomeLayout]).
         */
        val asRow: Boolean = true,
        /**
         * Recently added reads chosen chats ([RecentSource.Chosen]) and none is chosen, or none of
         * them is left in the chat list: a line in the row's place says so and leads to Settings.
         */
        val noneChosen: Boolean = false,
    ) : HomeRow {
        override val key: String get() = "recent"

        /** Its See all page reads on through [chatIds], past the row's [HomeRows.LIMIT]. */
        override val hasMore: Boolean get() = loaded && chatIds.isNotEmpty() && entries.isNotEmpty()
    }
}

/**
 * Builds Home's rows from what is on this device and what Telegram has answered so far.
 *
 * Pure, so the rules live in one place for the phone, the television and the desktop:
 * - Continue watching first, then "Recently added in starred chats", so what just arrived in a
 *   favourite is near the top, then Watch later, then New episodes when there are any, then one
 *   row per starred chat in the chat list's own order.
 * - Recently added draws from Home's chats: the starred ones, and where Home fills itself
 *   ([homeChats], the app's default since 2026-10-21) the chats the search across chats found the
 *   most videos in, which leaves out family groups and news that only ever post a clip. Starred
 *   chats alone need two at least: with one, it would repeat that chat's own row.
 * - Filling itself, Home is the carousel, Continue watching, Recently added and a row for each
 *   starred chat, and nothing else (user, 2026-10-23, after the Movies and Shows rows of
 *   2026-10-21 sorted videos wrongly): no Watch later or New episodes, which stay in their own
 *   pages, and no row for a chat Home picked for itself, which only feeds Recently added.
 * - Every row holds at most [LIMIT] tiles. A show folded into one tile counts once.
 * - Continue watching holds one record per film or episode: copies of it (a 1080p and a 720p)
 *   fold into the one watched last ([foldCopies]).
 * - A video appears once among Continue, Watch later and the chat rows: something part way
 *   through is in Continue and nowhere else, something saved for later is in Watch later. Recently added is the starred chats' videos again, merged into one
 *   timeline, so it leaves out only what is in Continue.
 * - A row that has loaded and found nothing is left out rather than drawn as an empty strip.
 * - New episodes are not taken out of the chat rows: a starred chat's show tile stands for the
 *   whole show, and losing its newest episodes there would make it look further behind.
 */
object HomeRows {

    /** Tiles per row. Ten fill a television's width twice over and keep each row cheap to compose. */
    const val LIMIT = 10

    /**
     * Chosen chats Recently added waits on before it says it found nothing: the ones Home reads
     * at once (HomeViewModel's EAGER_CHATS). The rest count through the search across chats.
     */
    const val CHOSEN_WAIT = 8

    /** Starred chats it takes for Recently added: one alone already has a row of its own. */
    const val RECENT_MIN_STARRED = 2

    /** The starred chats that get a row, in the chat list's order, which is Telegram's recency. */
    fun favouriteChats(chats: List<ChatSummary>, favourites: Set<Long>): List<ChatSummary> =
        if (favourites.isEmpty()) emptyList() else chats.filter { it.id in favourites }

    /** Chats Home picks for itself, besides the starred ones: a screen's worth of rows. */
    const val AUTO_CHATS = 6

    /** Pinned chats Home always reads, in the chat list's order: the first few, as a list shows them. */
    const val PINNED_CHATS = 5

    /**
     * The chats Home draws from: the starred ones first, in the chat list's order, each with a
     * row of its own, then up to [limit] more that the search across every chat ([recent]) found
     * videos in, the most videos first, then the newest. So Home is full on the first open with
     * nothing starred (user, 2026-10-21); starring a chat only puts it first. Saved Messages,
     * where most people keep their videos, always follows the starred ones, whether or not it had
     * an upload among the newest across the account (user, 2026-10-22: a show kept there was
     * missing from Home). The chats pinned in the list come next, the first [PINNED_CHATS] of
     * them: a pinned channel is one somebody keeps their shows in, however long ago it last
     * posted (user, 2026-10-22: Loki and other shows in a pinned channel were missing). An
     * archived chat is never picked, nor one missing from [chats] (the kids lock's, or one this
     * account left). Before [recent] arrives only the starred, Saved Messages and pinned chats
     * are known.
     */
    fun homeChats(
        chats: List<ChatSummary>,
        favourites: Set<Long>,
        recent: List<MediaItem>?,
        limit: Int = AUTO_CHATS,
    ): List<ChatSummary> {
        val starred = favouriteChats(chats, favourites)
        val saved = chats.firstOrNull { it.kind == ChatKind.Saved && !it.isArchived && it.id !in favourites }
        val pinned = chats.filter { it.isPinned && !it.isArchived && it.id !in favourites && it.id != saved?.id }.take(PINNED_CHATS)
        val fixed = starred + listOfNotNull(saved) + pinned
        if (recent.isNullOrEmpty() || limit <= 0) return fixed
        val taken = fixed.mapTo(HashSet()) { it.id }
        val listed = chats.filterNot { it.isArchived || it.id in taken }.associateBy { it.id }
        val picked = recent
            .filter { it.chatId in listed }
            .groupBy { it.chatId }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, List<MediaItem>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.maxOf { it.date } }
                    .thenBy { it.key },
            )
            .take(limit)
            .mapNotNull { listed[it.key] }
        return fixed + picked
    }

    /**
     * @param favourites the starred chats, already in display order (see [favouriteChats]).
     * @param loaded each starred chat's newest videos, by chat id; a chat missing here is still loading.
     * @param recent the newest videos across every chat, or null while that search is running. Only
     *   the starred chats' are kept, together with what [loaded] already has of them.
     * @param partial the starred chats whose fetch stopped short of their oldest video.
     * @param watchLater the Watch later list, newest first.
     * @param fresh the new episodes of the shows in progress, as [NewEpisodes.collect] lists them.
     * @param recentMinStarred the starred chats it takes for Recently added: [RECENT_MIN_STARRED],
     *   or one on a phone, where the row is the featured carousel ([HomeFeatured]) rather than a
     *   repeat of the one chat's own row.
     * @param starred which of [favourites] the viewer starred, when [favourites] is [homeChats]'
     *   answer; null when they all are. Only says what Recently added's heading may claim.
     * @param autoFill true where Home fills itself ([homeChats]): with nothing starred and the
     *   search across chats still running, Recently added holds its place as loading rather than
     *   Home opening on the first-run card, and only the [starred] chats get a row of their own.
     * @param chatsPending the chat list has not come in yet while [recent] (kept from the last
     *   run) already has videos: Home's chats cannot be picked, so Recently added holds its place
     *   as loading. Without it a cold start drew Continue watching alone and the carousel landed
     *   over it seconds later, pushing it down (Mi TV, caches cleared, 2026-10-23).
     */
    fun build(
        continueWatching: List<ResumeRecord>,
        favourites: List<ChatSummary>,
        loaded: Map<Long, List<MediaItem>>,
        recent: List<MediaItem>?,
        partial: Set<Long> = emptySet(),
        watchLater: List<WatchLaterRecord> = emptyList(),
        fresh: List<NewEpisode> = emptyList(),
        recentMinStarred: Int = RECENT_MIN_STARRED,
        starred: Set<Long>? = null,
        autoFill: Boolean = false,
        chatsPending: Boolean = false,
        layout: HomeLayout = HomeLayout(),
        recentFrom: List<Long>? = null,
        noneChosen: Boolean = false,
    ): List<HomeRow> = buildList<HomeRow> {
        val seen = HashSet<String>()
        // Continue watching off: nothing is in progress as far as Home goes, so a film part way
        // through still shows in its chat's row rather than vanishing from Home altogether.
        val continueWatching = if (layout.continueWatching) continueWatching else emptyList()

        val distinct = continueWatching.distinctBy { keyOf(it.chatId, it.messageId) }
        val everyResume = foldCopies(distinct)
        val resume = everyResume.take(LIMIT)
        resume.forEach { seen += keyOf(it.chatId, it.messageId) }
        // A copy folded under the one watched last is in Continue too, through that one.
        val shownCopies = resume.mapNotNullTo(HashSet()) { record -> copyKeyOf(record) }
        // And by the film's name and year, for a copy whose file name does not tie it to them.
        resume.mapNotNullTo(shownCopies) { record -> if (record.fileName != null) SameTitle.filmKey(record.toMediaItem()) else null }
        distinct.forEach { record ->
            val copy = copyKeyOf(record)
            if ((copy != null && copy in shownCopies) || foldKeyOf(record)?.let { it in shownCopies } == true) seen += keyOf(record.chatId, record.messageId)
        }
        if (resume.isNotEmpty()) add(HomeRow.Continue(resume, everyResume.drop(LIMIT)))
        val inContinue = HashSet(seen)

        val homeChats = favourites.distinctBy { it.id }
        // The chats Recently added draws from: Home's chats, or the chosen ones (in Home's order).
        val homeOrder = if (recentFrom == null) homeChats.map { it.id } else recentFrom.distinct()
        val homeIds = homeOrder.toHashSet()
        // Where Home fills itself, its chats are not all known until the chat list and the search
        // across chats are in: before that only the starred ones are picked, and "in starred
        // chats" would read true for a few seconds and then flip to the plain heading once the
        // busiest chats join (TV, 2026-10-27). The plain heading is held until the chats are known.
        val homeKnown = !chatsPending && !(autoFill && recent == null)
        val starredOnly = homeKnown && (starred == null || homeIds.all { it in starred })
        val asRow = layout.recentlyAdded
        val wanted = layout.needsRecent
        if (wanted && noneChosen) {
            add(HomeRow.Recent(emptyList(), loaded = true, starredOnly = false, asRow = asRow, noneChosen = true))
        } else if (wanted && recentFrom != null && homeIds.isEmpty()) {
            // Chosen chats, none of them in the chat list yet: it is still on its way.
            add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = false, asRow = asRow))
        } else if (wanted && (homeIds.size >= recentMinStarred.coerceAtLeast(1) || (recentFrom == null && autoFill && (recent == null || chatsPending)))) {
            val starredIds = homeIds
            if (recent == null) {
                add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
            } else {
                // The search across chats reaches a starred chat further back than its own row's
                // fetch, and a starred chat's fetch fills in whatever the search did not reach.
                val pool = recent.filter { it.chatId in starredIds } + starredIds.flatMap { loaded[it].orEmpty() }
                // A copy, so the rows below still show these videos in their own chats.
                val entries = row(pool, HashSet(inContinue), shownCopies, inContinue)
                if (entries.isNotEmpty()) {
                    add(HomeRow.Recent(entries, loaded = true, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
                } else if (chatsPending || (if (recentFrom == null) starredIds.any { it !in loaded } else homeOrder.take(CHOSEN_WAIT).any { it !in loaded })) {
                    // Nothing yet, but a starred chat's own videos are still on their way: the row
                    // stays as loading, so the carousel holds its place rather than vanishing and
                    // coming back once they land (seen on a cold start on the TV stick).
                    add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
                }
            }
        }

        // Started since it was saved: Continue already has it, with its place.
        // A show stays whatever of it was begun: its page carries on from the episode up next.
        val everyLater = watchLater.distinctBy { it.id }.filterNot { !it.isShow && it.key in inContinue }
        val later = everyLater.take(LIMIT)
        later.forEach { if (!it.isShow) seen += it.key }
        if (later.isNotEmpty() && !autoFill) add(HomeRow.WatchLater(later, everyLater.drop(LIMIT)))
        val newEpisodes = fresh.filterNot { keyOf(it.item.chatId, it.item.messageId) in seen }.take(LIMIT)
        if (newEpisodes.isNotEmpty() && !autoFill) add(HomeRow.NewEpisodes(newEpisodes))

        // A row for each starred chat, under Recently added, in the chat list's order (user,
        // 2026-10-23: carousel, Continue watching, Recently added, then the favourites). The chats
        // Home picked for itself feed Recently added and the carousel only.
        // Off in Settings: no starred chat has a row, and none is read for one.
        if (layout.starredRows) homeChats.filter { starred == null || it.id in starred }.forEach { chat ->
            val items = loaded[chat.id]
            if (items == null) {
                add(HomeRow.Chat(chat.id, chat.title, emptyList(), loaded = false))
                return@forEach
            }
            val entries = row(items, seen, shownCopies, inContinue)
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
    }

    /**
     * What a row's "See all" page lists once [items] have been read of its chats (Home's own and
     * the pages read on past them, see [FeedMerge]): the row's own tiles first, as Home drew
     * them, each show with every episode read since, then everything else, newest first. A page that grows only at its foot, so nothing on screen moves when a page
     * lands. Null for a row that reads no chats (Continue watching, Watch later, New episodes).
     */
    fun seeAll(row: HomeRow, items: List<MediaItem>): List<ShelfEntry>? {
        val entries = when (row) {
            is HomeRow.Chat -> row.entries
            is HomeRow.Recent -> row.entries
            else -> return null
        }
        val own = entries.flatMap { entry ->
            when (entry) {
                is ShelfEntry.File -> listOf(entry.item)
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }
            }
        }
        val pool = (own + items).distinctBy { it.id }.sortedWith(FeedMerge.newestFirst)
        val arranged = SeriesShelf.arrange(pool)
        val byKey = arranged.associateBy { it.key }
        val first = entries.map { byKey[it.key] ?: it }
        val firstKeys = first.mapTo(HashSet()) { it.key }
        // Copies of one film across chats, or at another size, folded as the row folds them
        // ([arranged]): without this the pages read on past the row listed every copy as a tile
        // of its own. A copy folds into the tile already standing, so nothing on screen moves.
        return SameTitle.dedupe(first + arranged.filterNot { it.key in firstKeys })
    }

    /**
     * Newest first, without anything an earlier row already showed, folded into shows, cut to
     * [LIMIT]. Whatever ends up in the row is added to [seen], every episode of a folded show
     * included (every copy of each), so a later row cannot show one of them again on its own.
     * Nor can another copy of a film or episode in Continue ([continuing], as [copyKeyOf] keys
     * them): the 1080p of a film started in 720p is the same film, part way through.
     *
     * An episode in Continue ([inProgress]) still counts towards its show, though: left out, the
     * show's tile said "11 episodes" for a show of 12. It is only never a tile of its own.
     */
    private fun row(
        items: List<MediaItem>,
        seen: MutableSet<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        val entries = arranged(items, seen, continuing, inProgress).take(LIMIT)
        markSeen(entries, seen)
        return entries
    }

    private fun markSeen(entries: List<ShelfEntry>, seen: MutableSet<String>) {
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> {
                    seen += entry.item.progressKey
                    entry.item.versions.forEach { seen += it.progressKey }
                }
                is ShelfEntry.Show -> entry.series.episodes.forEach { episode -> episode.copies.forEach { seen += it.progressKey } }
            }
        }
    }

    /** [row]'s tiles before the cut to [LIMIT], with nothing added to [seen]. */
    private fun arranged(
        items: List<MediaItem>,
        seen: Set<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        fun copyOfContinuing(item: MediaItem): Boolean =
            continuing.isNotEmpty() && (
                VideoCopies.keyOf(item)?.let { it in continuing } == true ||
                    SameTitle.filmKey(item)?.let { it in continuing } == true
                )
        fun episode(item: MediaItem): Boolean = SeriesShelf.episodeOf(item).episode != null
        // One pass, each item's keys read once: Home runs this for every row on every build.
        val fresh = ArrayList<MediaItem>(items.size)
        val ids = HashSet<String>()
        for (item in items) {
            val key = item.progressKey
            val copy = copyOfContinuing(item)
            val partOfAShow = episode(item) && (key in inProgress || copy)
            if (!partOfAShow && (key in seen || copy)) continue
            if (ids.add(item.id)) fresh += item
        }
        fresh.sortWith(NEWEST_FIRST)
        // One tile per title in a row: copies the shelf did not fold, across chats or by their
        // online match, are folded here, the best copy leading ([SameTitle]).
        return SameTitle.dedupe(SeriesShelf.arrange(fresh))
            .filterNot { entry ->
                entry is ShelfEntry.File && entry.item.let { it.progressKey in inProgress || copyOfContinuing(it) }
            }
    }

    /** Newest first, the later message first on the same second; compared without boxing either. */
    private val NEWEST_FIRST = Comparator<MediaItem> { a, b ->
        when {
            a.date != b.date -> b.date.compareTo(a.date)
            else -> b.messageId.compareTo(a.messageId)
        }
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
            .sortedWith(NEWEST_FIRST)
    }

    /**
     * [records] with the copies of one film or one episode in a chat (a 1080p and a 720p of it,
     * see [SeriesShelf.sameVideoKey]) folded into one: the copy watched last, with its own place
     * and quality, where the newest of them stood. Everything else is left as it is, in its order.
     * What Continue watching lists, on Home and in History alike.
     */
    fun foldCopies(records: List<ResumeRecord>): List<ResumeRecord> {
        val latest = HashMap<String, ResumeRecord>()
        records.forEach { record ->
            val key = foldKeyOf(record) ?: return@forEach
            val held = latest[key]
            if (held == null || record.updatedAt > held.updatedAt) latest[key] = record
        }
        val placed = HashSet<String>()
        return records.mapNotNull { record ->
            val key = foldKeyOf(record) ?: return@mapNotNull record
            if (placed.add(key)) latest.getValue(key) else null
        }
    }

    /**
     * The Watched list with the copies of one film or episode as one card, as [foldCopies] folds
     * Continue watching: the copy finished last, where it stood. [records] come newest first.
     */
    fun foldWatched(records: List<WatchedRecord>): List<WatchedRecord> {
        val seen = HashSet<String>()
        return records.filter { record -> watchedKeyOf(record)?.let(seen::add) ?: true }
    }

    /**
     * [record] as the video its folded card stands for: every copy on the Watched list as its
     * [MediaItem.versions], so taking the mark off takes it off all of them.
     */
    fun watchedItem(record: WatchedRecord, records: List<WatchedRecord>): MediaItem {
        val item = record.toMediaItem()
        val key = watchedKeyOf(record) ?: return item
        val copies = records.filter { it.key == record.key || watchedKeyOf(it) == key }.map { it.toMediaItem() }
        return if (copies.size > 1) item.copy(versions = copies) else item
    }

    private fun watchedKeyOf(record: WatchedRecord): String? =
        if (record.fileName != null) VideoCopies.keyOf(record.toMediaItem()) else VideoCopies.keyOfTitle(record.title)

    /**
     * [record] and every other copy of the same film or episode in [records]: what removing a
     * card [foldCopies] made must remove, or the copy behind it takes its place at once.
     */
    fun copiesOf(record: ResumeRecord, records: List<ResumeRecord>): List<ResumeRecord> {
        val key = foldKeyOf(record) ?: return listOf(record)
        return (listOf(record) + records.filter { foldKeyOf(it) == key }).distinctBy { it.chatId to it.messageId }
    }

    /**
     * Which film or episode [record] is a copy of, in any chat ([VideoCopies.keyOf]), or null for a
     * video that names neither. An old record is read cautiously: its title alone has to name a
     * year or an episode, so a caption's bare "S02E04" never folds a whole show into one card.
     */
    private fun copyKeyOf(record: ResumeRecord): String? = VideoCopies.keyOf(record)

    /**
     * What Continue watching folds by: a film's name and year read with its caption
     * ([SameTitle.filmKey]), so a bare "Khalifa.mkv" captioned "Khalifa (2026)" and a "Khalifa
     * 2026 1080p" posted elsewhere are one card; anything else by [copyKeyOf].
     */
    private fun foldKeyOf(record: ResumeRecord): String? =
        (if (record.fileName != null) SameTitle.filmKey(record.toMediaItem()) else null) ?: copyKeyOf(record)

    /** The same key [SettingsStore.progressKey] writes, so a resume record and a video match. */
    private fun keyOf(chatId: Long, messageId: Long) = ProgressKeys.of(chatId, messageId)

    /**
     * [row] with only the tiles that have a picture of their own ([HomeFeatured.hasArt]), as the
     * desktop's Home draws it: never a tile of nothing but a generated cover. A record of Continue
     * watching or Watch later keeps no picture, so it shows once its video has been asked for and
     * is in [art] (keyed as [SettingsStore.progressKey]) with one. Null for a loaded row left with
     * nothing to show; a row still loading is returned as it is.
     */
    fun withArt(row: HomeRow, art: Map<String, MediaItem>): HomeRow? {
        fun pictured(chatId: Long, messageId: Long) = art[keyOf(chatId, messageId)]?.let(HomeFeatured::hasArt) == true
        val kept = when (row) {
            is HomeRow.Continue -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.WatchLater -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.NewEpisodes -> row.copy(episodes = row.episodes.filter { HomeFeatured.hasArt(it.item) })
            is HomeRow.Chat -> if (!row.loaded) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
            // The line saying no chosen chat is there stands as it is.
            is HomeRow.Recent -> if (!row.loaded || row.noneChosen) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
        }
        val empty = when (kept) {
            is HomeRow.Continue -> kept.records.isEmpty()
            is HomeRow.WatchLater -> kept.records.isEmpty()
            is HomeRow.NewEpisodes -> kept.episodes.isEmpty()
            is HomeRow.Chat -> kept.entries.isEmpty()
            is HomeRow.Recent -> kept.entries.isEmpty()
        }
        return if (empty) null else kept
    }
}
