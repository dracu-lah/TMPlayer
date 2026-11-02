package com.tmplayer.data

/**
 * The rules behind "Add chats" (HX27): which rows the picker lists, in which bands, what Select
 * all and Clear mean while a search or a filter narrows the list, and what the foot adds up.
 *
 * Pure, so the same answers hold on the phone, the television, the desktop and (ported) the web
 * app, and so each rule has a test without a screen. The screen decides how a band looks and what
 * its heading says; it decides nothing about what is in it.
 */
object ChatPicking {

    /**
     * Which chats the list is narrowed to. Telegram's folders join this with HX29; until then a
     * folder is reached on the Chats page as before.
     */
    enum class Scope { All, Archived, Channels, Groups, People }

    /**
     * A band of the list, in the order they are drawn.
     *
     * [Recommended] and [Starred] only stand while the whole list is shown: a search or a filter
     * is a question about every chat, and answering it with three headings in the way hides what
     * was asked for.
     */
    enum class Band { Saved, Recommended, Starred, Chats, Archived, Gone }

    /**
     * One row. [chat] is null for a [Band.Gone] row, which is drawn from the name the chat had
     * when it was added, since there is nothing left to read it from.
     */
    data class Row(
        val id: Long,
        val title: String,
        val band: Band,
        val chat: ChatSummary? = null,
        val starred: Boolean = false,
    )

    /** What the foot says: how many chats are ticked, and how many videos they hold between them. */
    data class Total(
        val chats: Int,
        /** Summed over the chats whose count is known; [counted] says how many that was. */
        val videos: Int,
        val counted: Int,
    ) {
        /** Whether every ticked chat has been counted, so the figure is not still filling in. */
        val whole: Boolean get() = counted == chats
    }

    /** Whether [chat] belongs in [scope]. Saved Messages counts as a person, as it does elsewhere. */
    fun inScope(chat: ChatSummary, scope: Scope): Boolean = when (scope) {
        Scope.All -> true
        Scope.Archived -> chat.isArchived
        Scope.Channels -> chat.kind == ChatKind.Channel
        Scope.Groups -> chat.kind == ChatKind.Group
        Scope.People -> chat.kind == ChatKind.Direct || chat.kind == ChatKind.Saved
    }

    /**
     * The rows the picker lists.
     *
     * @param chats every chat of the account, archive included, unnarrowed: the picker is the one
     *   screen that must see a chat the viewer has not added, or it could never add it.
     * @param added the ids added now, for the rows that have no chat left.
     * @param addedNames what those chats were called when they were added.
     * @param recommended [ChatSuggestions.recommend]'s answer, in its order.
     * @param listComplete whether the chat list is whole. A chat still on its way is simply not
     *   here yet, and must not be called gone: HX32's partial load would otherwise mark half an
     *   account as left.
     */
    fun rows(
        chats: List<ChatSummary>,
        added: Set<Long>,
        addedNames: List<AddedChat> = emptyList(),
        starred: Set<Long> = emptySet(),
        recommended: List<Long> = emptyList(),
        query: String = "",
        scope: Scope = Scope.All,
        listComplete: Boolean = false,
    ): List<Row> {
        val searching = query.isNotBlank()
        val whole = !searching && scope == Scope.All
        val inScope = chats.filter { inScope(it, scope) }
        // Ranked, not filtered, by the same rule as the Chats page: the accent nobody types and
        // the letter mistyped on a remote still find the chat.
        val matching = if (searching) Fuzzy.rank(inScope, query) { it.title } else inScope
        val saved = matching.filter { it.kind == ChatKind.Saved }
        val rest = matching.filter { it.kind != ChatKind.Saved }
        val taken = HashSet<Long>(saved.map { it.id })
        val out = ArrayList<Row>(matching.size + added.size)

        fun add(chat: ChatSummary, band: Band) {
            if (!taken.add(chat.id)) return
            out += Row(chat.id, chat.title, band, chat, chat.id in starred)
        }

        for (chat in saved) out += Row(chat.id, chat.title, Band.Saved, chat, chat.id in starred)
        if (whole) {
            val byId = rest.associateBy { it.id }
            // In the recommendation's order, which is fullest first.
            for (id in recommended) byId[id]?.let { add(it, Band.Recommended) }
            // Starred next: the viewer said these matter, so they are not hunted for down the list.
            for (chat in rest) if (chat.id in starred && !chat.isArchived) add(chat, Band.Starred)
        }
        for (chat in rest) if (!chat.isArchived) add(chat, Band.Chats)
        // The archive has a band of its own under the rest, not a separate screen: a movie channel
        // archived to keep Telegram tidy is still a movie channel. Under the Archived scope the
        // chats above are already the archived ones, so nothing lands here twice.
        for (chat in rest) if (chat.isArchived) add(chat, if (scope == Scope.Archived) Band.Chats else Band.Archived)
        if (whole && listComplete) {
            val names = addedNames.associate { it.id to it.title }
            for (id in added) {
                if (id in taken) continue
                taken += id
                out += Row(id, names[id].orEmpty(), Band.Gone)
            }
        }
        return out
    }

    /**
     * One line of the television's list: a chat, or the single row that stands for the chats of
     * [band] counted at no videos (HX76).
     */
    sealed interface Item {
        val band: Band

        data class Chat(val row: Row) : Item {
            override val band: Band get() = row.band
        }

        /** [count] chats of [band] with nothing to watch, closed; pressing it opens them in place. */
        data class Folded(override val band: Band, val count: Int) : Item
    }

    /**
     * The short list: chats counted at 0 videos fold into one row at the end of their band, so the
     * default list holds what can be watched and most viewers press Done without scrolling.
     *
     * Only a chat that is counted at none folds: one not counted yet (or that could not be) stays
     * where it is, since "no videos" is an answer and not a guess. A ticked or starred chat never
     * folds, nor does Saved Messages or a chat that is gone (its row is how it gets removed).
     * [open] bands show their folded chats in place, at the end of the band, with no fold row.
     */
    fun fold(
        rows: List<Row>,
        counts: Map<Long, ChatCount>,
        ticked: Set<Long>,
        open: Set<Band> = emptySet(),
    ): List<Item> {
        val out = ArrayList<Item>(rows.size)
        var at = 0
        while (at < rows.size) {
            val band = rows[at].band
            var end = at
            while (end < rows.size && rows[end].band == band) end++
            val folded = ArrayList<Row>()
            for (i in at until end) {
                if (folds(rows[i], counts, ticked)) folded += rows[i] else out += Item.Chat(rows[i])
            }
            if (folded.isNotEmpty()) {
                if (band in open) folded.forEach { out += Item.Chat(it) } else out += Item.Folded(band, folded.size)
            }
            at = end
        }
        return out
    }

    private fun folds(row: Row, counts: Map<Long, ChatCount>, ticked: Set<Long>): Boolean =
        row.band != Band.Saved && row.band != Band.Gone && !row.starred && row.id !in ticked &&
            (counts[row.id] as? ChatCount.Known)?.videos == 0

    /** The chats [fold] holds back for [band], in list order: what pressing the fold opens. */
    fun foldedIn(rows: List<Row>, counts: Map<Long, ChatCount>, ticked: Set<Long>, band: Band): List<Row> =
        rows.filter { it.band == band && folds(it, counts, ticked) }

    /**
     * The chats of [rows] still to count in the background, past the head start: uncounted, not
     * Gone, in list order, at most [limit] so one pass is bounded.
     */
    fun toCount(rows: List<Row>, counts: Map<Long, ChatCount>, limit: Int = Int.MAX_VALUE): List<Long> {
        val out = ArrayList<Long>()
        for (row in rows) {
            if (out.size >= limit) break
            if (row.band != Band.Gone && !counts.containsKey(row.id)) out += row.id
        }
        return out
    }

    /** The ids of [rows], which is what Select all and Clear act on: what is on the screen now. */
    fun visible(rows: List<Row>): Set<Long> = rows.asSequence().filter { it.band != Band.Gone }.mapTo(LinkedHashSet()) { it.id }

    /**
     * Select all over what is shown: the ticks already made elsewhere stay, so narrowing the list
     * twice and ticking both times adds up rather than starting again.
     */
    fun selectAll(ticked: Set<Long>, visible: Set<Long>): Set<Long> = LinkedHashSet(ticked).apply { addAll(visible) }

    /** Clear over what is shown, and only that: a tick made under another filter is not undone. */
    fun clear(ticked: Set<Long>, visible: Set<Long>): Set<Long> = ticked.filterTo(LinkedHashSet()) { it !in visible }

    /** Whether Select all would tick nothing new, which is when the button reads Clear instead. */
    fun allTicked(ticked: Set<Long>, visible: Set<Long>): Boolean = visible.isNotEmpty() && ticked.containsAll(visible)

    fun toggle(ticked: Set<Long>, id: Long): Set<Long> =
        if (id in ticked) ticked.filterTo(LinkedHashSet()) { it != id } else LinkedHashSet(ticked).apply { add(id) }

    /**
     * Above this many chats in one go, the viewer is asked first: every added chat is read, and
     * two hundred of them is a wait worth warning about rather than discovering.
     */
    const val CONFIRM_OVER = 50

    /** Whether ticking [adding] more chats at once is worth a word first. */
    fun needsConfirm(adding: Int): Boolean = adding > CONFIRM_OVER

    /** The foot's figures. A chat with no count yet simply adds nothing to the number. */
    fun total(ticked: Set<Long>, counts: Map<Long, ChatCount>): Total {
        var videos = 0
        var counted = 0
        for (id in ticked) {
            val known = counts[id] as? ChatCount.Known ?: continue
            videos += known.videos
            counted++
        }
        return Total(ticked.size, videos, counted)
    }

    /**
     * The chats worth counting before any row is drawn, so the Recommended band can appear within
     * the first seconds: the head of the list, in Telegram's own order of recency, and Saved
     * Messages. Everything past this is counted as its row comes on screen, and nothing is
     * counted at all once the picker closes.
     */
    fun headStart(chats: List<ChatSummary>, ahead: Int = COUNT_AHEAD): List<Long> {
        val out = LinkedHashSet<Long>()
        for (chat in chats) if (chat.kind == ChatKind.Saved) out += chat.id
        for (chat in chats) {
            if (out.size >= ahead) break
            out += chat.id
        }
        return out.toList()
    }

    /**
     * How far ahead the counts run before the viewer scrolls. Sixty chats at three at a time is
     * twenty rounds, which an account of three thousand finishes in seconds and a television does
     * not feel; the recommendation is made from these.
     */
    const val COUNT_AHEAD = 60

    /** The counted chats [ChatSuggestions] weighs, from the list and the counts in hand. */
    fun counted(chats: List<ChatSummary>, counts: Map<Long, ChatCount>): List<CountedChat> =
        chats.mapIndexed { index, chat ->
            CountedChat(
                id = chat.id,
                order = index,
                videos = (counts[chat.id] as? ChatCount.Known)?.videos,
                savedMessages = chat.kind == ChatKind.Saved,
            )
        }
}
