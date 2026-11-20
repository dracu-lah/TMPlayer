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
     * Which chats the list is narrowed to by kind. A Telegram folder is the other way to narrow it
     * (HX29): [rows]' `folder`, one at a time with a scope, never both.
     */
    enum class Scope { All, Archived, Channels, Groups, People }

    /**
     * A band of the list, in the order they are drawn.
     *
     * [Added], [Recommended] and [Starred] only stand while the whole list is shown: a search or a
     * filter is a question about every chat, and answering it with headings in the way hides what
     * was asked for. [Added] holds the chats the viewer had added when the picker opened, first
     * under Saved Messages, so reopening the picker to add one more shows what is already in and
     * never lists those chats a second time under Recommended.
     */
    enum class Band { Saved, Added, Recommended, Starred, Chats, Archived, Gone }

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

    /** Whether [chat] is in Telegram folder [folderId], as the folders were last read ([ChatSummary.folderIds]). */
    fun inFolder(chat: ChatSummary, folderId: Int): Boolean = folderId in chat.folderIds

    /**
     * The folders the picker offers as filters (HX29): Telegram's own, in its order, without the
     * ones that already are a scope (its suggested Channels, Groups and Private folders list what
     * those scopes list). Folders sharing a title stay separate, told apart by id.
     */
    fun pickable(folders: List<ChatFolderSummary>): List<ChatFolderSummary> = folders
        .distinctBy { it.id }
        .filter { scopeOf(it) == null }

    /** The scope a Telegram suggested folder (known by its icon, which only those carry) duplicates, or null. */
    fun scopeOf(folder: ChatFolderSummary): Scope? = when (folder.icon) {
        "Channels" -> Scope.Channels
        "Groups" -> Scope.Groups
        "Private" -> Scope.People
        else -> null
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
     * @param alreadyAdded the chats added when the picker opened, fixed for the opening so a row
     *   never moves between bands as it is ticked or unticked.
     * @param folder the Telegram folder the list is narrowed to, or null. It stands in place of a
     *   scope: a folder lists what Telegram puts in it, archived chats among them, with no band of
     *   their own (as under the Archived scope).
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
        alreadyAdded: Set<Long> = emptySet(),
        folder: Int? = null,
    ): List<Row> {
        val searching = query.isNotBlank()
        val whole = !searching && scope == Scope.All && folder == null
        val inScope = if (folder != null) chats.filter { inFolder(it, folder) } else chats.filter { inScope(it, scope) }
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
            // What is in already, in the chat list's order, before anything is offered.
            if (alreadyAdded.isNotEmpty()) for (chat in rest) if (chat.id in alreadyAdded) add(chat, Band.Added)
            // In the recommendation's order, which is fullest first.
            for (id in recommended) byId[id]?.let { add(it, Band.Recommended) }
            // Starred next: the viewer said these matter, so they are not hunted for down the list.
            for (chat in rest) if (chat.id in starred && !chat.isArchived) add(chat, Band.Starred)
        }
        for (chat in rest) if (!chat.isArchived) add(chat, Band.Chats)
        // The archive has a band of its own under the rest, not a separate screen: a movie channel
        // archived to keep Telegram tidy is still a movie channel. Under the Archived scope the
        // chats above are already the archived ones, so nothing lands here twice.
        for (chat in rest) if (chat.isArchived) add(chat, if (scope == Scope.Archived || folder != null) Band.Chats else Band.Archived)
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

    /** Saved Messages' id in [chats], or null while the list has not brought it. */
    fun savedId(chats: List<ChatSummary>): Long? = chats.firstOrNull { it.kind == ChatKind.Saved }?.id

    /**
     * The ticks a viewer who has never answered starts with (HX86): Saved Messages, which every
     * account has and where a forwarded video lands, so the setup's Done works at once. Empty
     * while the list has not brought Saved Messages yet.
     */
    fun freshTicks(chats: List<ChatSummary>): Set<Long> = savedId(chats)?.let { setOf(it) }.orEmpty()

    /**
     * The ticks that come back when "All chats" is switched off (HX86): the ones kept, minus a chat
     * the viewer has since left (dropped quietly, but only once the list is whole, so a chat still
     * on its way is never called gone). Never empty: with nothing kept, or nothing left of it, the
     * answer is Saved Messages, as for a fresh install, when the list has it.
     */
    fun afterAllOff(kept: Set<Long>, chats: List<ChatSummary>, listComplete: Boolean): Set<Long> {
        val here = if (listComplete) chats.mapTo(HashSet()) { it.id } else null
        val back = if (here == null) kept else kept.filterTo(LinkedHashSet()) { it in here }
        return back.ifEmpty { freshTicks(chats) }
    }

    /**
     * Above this many chats in one go, the viewer is asked first: every added chat is read, and
     * two hundred of them is a wait worth warning about rather than discovering.
     */
    const val CONFIRM_OVER = 50

    /** Whether ticking [adding] more chats at once is worth a word first. */
    fun needsConfirm(adding: Int): Boolean = adding > CONFIRM_OVER

    /**
     * The order the account is counted in: Saved Messages, then channels (a video library is nearly
     * always one), then groups, then people, Telegram's own recency inside each. A stable order, so
     * two runs over the same account agree.
     */
    fun scanOrder(chats: List<ChatSummary>): List<ChatSummary> {
        fun rank(chat: ChatSummary) = when (chat.kind) {
            ChatKind.Saved -> 0
            ChatKind.Channel -> 1
            ChatKind.Group -> 2
            ChatKind.Direct -> 3
        }
        return chats.sortedBy(::rank)
    }

    /**
     * The chats counted before any row is drawn, so the Recommended band can appear within the
     * first seconds: Saved Messages and the head of the list in Telegram's own order of recency,
     * which is where the chats the viewer watches from are. The rest of the account is counted
     * behind it in [scanOrder], in the background, and nothing is counted at all once the picker
     * closes.
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
     * How far ahead the counts run first. Sixty chats at three at a time is twenty rounds, which
     * the first band is made from within seconds; the rest of the account follows in the
     * background and the band is ranked again as it arrives ([ChatSuggestions.refine]).
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
