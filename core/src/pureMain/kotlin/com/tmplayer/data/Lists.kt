package com.tmplayer.data

import kotlin.random.Random

/**
 * The viewer's own named lists of videos and shows, across chats (HX43, EG-F55): "Tutorials",
 * "For the weekend". Not bookmarks (a moment inside one video) and not Watch later (one plan, crossed
 * off as it is watched): a list keeps what was put in it until the viewer takes it out.
 *
 * Every write carries its time, and what is taken off leaves a [ListRemoval] behind for a while, so
 * two devices (or a device and a backup) merge with the newest write winning ([NamedListRules.merge]).
 */
data class ListInfo(
    /** [NamedListRules.newId]: lowercase letters and digits, so `list-<id>` is a [HomeOrder] key. */
    val id: String,
    /** Already through [NamedListRules.cleanName]. Two lists may share a name; their ids differ. */
    val name: String,
    /** When the name or [home] was last written. */
    val at: Long,
    /** Whether Home gives the list a row of its own. */
    val home: Boolean = true,
)

/**
 * One line of a list: a video, or a whole show by its chat and grouping key, as Watch later keeps
 * them ([WatchLaterRecord]). [WatchLaterRecord.addedAt] is when it was put in the list.
 */
data class ListEntry(val listId: String, val record: WatchLaterRecord) {
    /** The line's name inside its list: [WatchLaterRecord.id]. */
    val id: String get() = record.id
    val at: Long get() = record.addedAt
}

/** Something taken off at [at]: the whole list when [entryId] is empty, else one of its lines. */
data class ListRemoval(val listId: String, val entryId: String, val at: Long) {
    val wholeList: Boolean get() = entryId.isEmpty()
}

/** Every list, every line and the removals still remembered. */
data class NamedLists(
    val lists: List<ListInfo> = emptyList(),
    val entries: List<ListEntry> = emptyList(),
    val removed: List<ListRemoval> = emptyList(),
) {
    val isEmpty: Boolean get() = lists.isEmpty() && entries.isEmpty() && removed.isEmpty()

    fun list(id: String): ListInfo? = lists.firstOrNull { it.id == id }

    /** [listId]'s lines in the order they were added, the first added first. */
    fun entriesOf(listId: String): List<WatchLaterRecord> =
        entries.asSequence().filter { it.listId == listId }.map { it.record }.sortedWith(NamedListRules.ADDED).toList()

    /** The lists in the order the viewer made them (the oldest first), then by name. */
    val ordered: List<ListInfo> get() = lists.sortedWith(compareBy<ListInfo>({ firstMade(it.id) }, { it.name.lowercase() }, { it.id }))

    private fun firstMade(id: String): Long = created[id] ?: Long.MAX_VALUE

    private val created: Map<String, Long> by lazy {
        // A list's age is that of its id's first write; the info's own time moves with a rename.
        lists.associate { it.id to (NamedListRules.idTime(it.id) ?: it.at) }
    }
}

/** What adding a line came to, for the line the viewer is shown. */
enum class ListAdd {
    Added,

    /** Already in the list: nothing changes, it keeps its place. */
    Already,

    /** The list holds [NamedListRules.MAX_PER_LIST] lines, or all lists [NamedListRules.MAX_ENTRIES]. */
    Full,

    /** No such list any more (deleted on another device while the menu was open). */
    NoList,
}

/**
 * The rules of [NamedLists], pure: names, ids, the caps, every edit and the merge. The stores and the
 * sync file only read and write what these say.
 *
 * Merge: the newest write wins per list (its name and Home switch) and per line; a [ListRemoval] beats
 * a write of the same age or older, so a removal and an add in the same millisecond keep the add,
 * as the sync file's other lists do ([SyncDocuments]). A whole list's removal takes its lines older
 * than it; a line added after it on another device brings the list back with that line alone.
 * Removals are remembered for [REMOVAL_DAYS] and at most [MAX_REMOVALS] of them, the newest.
 */
object NamedListRules {

    /** A name past this is cut: enough for a heading and a Home row's title. */
    const val NAME_MAX = 60
    const val MAX_LISTS = 100
    const val MAX_PER_LIST = 5_000
    const val MAX_ENTRIES = 20_000
    const val MAX_REMOVALS = 2_000
    const val REMOVAL_DAYS = 90L

    private const val ID_RANDOM = 6
    private const val ID_MIN = 4
    private const val ID_MAX = 20
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** The order a list's lines show in: the order they were added, ties by name. */
    val ADDED: Comparator<WatchLaterRecord> = compareBy<WatchLaterRecord> { it.addedAt }.thenBy { it.id }

    /**
     * [raw] as a list's name: control characters out, runs of spaces as one, trimmed, cut to
     * [NAME_MAX] at a whole character; null when nothing is left. Reads only as far as it needs, so a
     * name of ten million characters costs no more than one of a hundred.
     */
    fun cleanName(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val out = StringBuilder(NAME_MAX + 1)
        var space = false
        var i = 0
        while (i < raw.length && out.length < NAME_MAX) {
            val c = raw[i]
            i++
            if (c.isWhitespace() || c.isISOControl() || c == '​' || c == '﻿') {
                space = out.isNotEmpty()
                continue
            }
            if (space) {
                out.append(' ')
                space = false
                if (out.length >= NAME_MAX) break
            }
            // A surrogate pair stays whole or goes whole.
            if (c.isHighSurrogate()) {
                if (i < raw.length && raw[i].isLowSurrogate() && out.length + 2 <= NAME_MAX) {
                    out.append(c).append(raw[i])
                    i++
                } else if (i < raw.length && raw[i].isLowSurrogate()) {
                    break
                }
                continue
            }
            if (c.isLowSurrogate()) continue
            out.append(c)
        }
        return out.toString().trim().ifEmpty { null }
    }

    /** Whether [id] could be a list's id: what a file or a store hands back is checked against this. */
    fun isId(id: String?): Boolean =
        id != null && id.length in ID_MIN..ID_MAX && id.all { it in 'a'..'z' || it in '0'..'9' }

    /**
     * A new list's id: the time in base 36, then [ID_RANDOM] random characters, so two devices making
     * a list in the same moment still get two lists, and the id says when the list was first made.
     */
    fun newId(now: Long, taken: Set<String> = emptySet(), random: Random = Random.Default): String {
        val stamp = now.coerceAtLeast(0).toString(36)
        while (true) {
            val id = stamp + (1..ID_RANDOM).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (id !in taken) return id
        }
    }

    /** When [id] was made, read back from [newId]'s stamp; null for an id that has none. */
    fun idTime(id: String): Long? = if (id.length > ID_RANDOM) id.dropLast(ID_RANDOM).toLongOrNull(36) else null

    /** The Home row key of list [id]. */
    fun homeKey(id: String): String = "list-$id"

    /** The list id inside a [homeKey]; null for any other key. */
    fun idOfHomeKey(key: String): String? = key.removePrefix("list-").takeIf { key.startsWith("list-") && isId(it) }

    // ---- edits ---------------------------------------------------------------------------------

    /** [state] with a new list called [name]; null when the name is empty or [MAX_LISTS] are made. */
    fun create(state: NamedLists, name: String, now: Long, id: String = newId(now, state.lists.mapTo(HashSet()) { it.id })): Pair<NamedLists, ListInfo>? {
        val clean = cleanName(name) ?: return null
        if (state.lists.size >= MAX_LISTS || !isId(id) || state.list(id) != null) return null
        val info = ListInfo(id, clean, now)
        return state.copy(lists = state.lists + info, removed = state.removed.filterNot { it.listId == id }) to info
    }

    /** [state] with list [id] called [name]; unchanged for an empty name or a list that is gone. */
    fun rename(state: NamedLists, id: String, name: String, now: Long): NamedLists {
        val clean = cleanName(name) ?: return state
        val info = state.list(id) ?: return state
        if (info.name == clean) return state
        return state.copy(lists = state.lists.map { if (it.id == id) it.copy(name = clean, at = later(it.at, now)) else it })
    }

    /** [state] with list [id] on Home or off it. */
    fun setHome(state: NamedLists, id: String, home: Boolean, now: Long): NamedLists {
        val info = state.list(id) ?: return state
        if (info.home == home) return state
        return state.copy(lists = state.lists.map { if (it.id == id) it.copy(home = home, at = later(it.at, now)) else it })
    }

    /** [state] without list [id] and its lines, the removal remembered. */
    fun delete(state: NamedLists, id: String, now: Long): NamedLists {
        if (state.list(id) == null && state.entries.none { it.listId == id }) return state
        // After every write of the list, so none of them outlives the removal in a merge.
        val newest = maxOf(now, (state.list(id)?.at ?: 0L) + 1, (state.entries.filter { it.listId == id }.maxOfOrNull { it.at } ?: 0L) + 1)
        return prune(
            state.copy(
                lists = state.lists.filterNot { it.id == id },
                entries = state.entries.filterNot { it.listId == id },
                removed = state.removed.filterNot { it.listId == id } + ListRemoval(id, "", newest),
            ),
            now,
        )
    }

    /**
     * [state] with [record] in list [listId] at [now]. The same video (or show) twice is one line,
     * which keeps its first time and place.
     */
    fun add(state: NamedLists, listId: String, record: WatchLaterRecord, now: Long): Pair<NamedLists, ListAdd> {
        if (state.list(listId) == null) return state to ListAdd.NoList
        if (state.entries.any { it.listId == listId && it.id == record.id }) return state to ListAdd.Already
        if (state.entries.size >= MAX_ENTRIES || state.entries.count { it.listId == listId } >= MAX_PER_LIST) return state to ListAdd.Full
        val entry = ListEntry(listId, record.copy(addedAt = now))
        return state.copy(
            entries = state.entries + entry,
            removed = state.removed.filterNot { it.listId == listId && it.entryId == entry.id },
        ) to ListAdd.Added
    }

    /** [state] without line [entryId] of list [listId], the removal remembered. */
    fun remove(state: NamedLists, listId: String, entryId: String, now: Long): NamedLists = removeAll(state, listId, setOf(entryId), now)

    /** [state] without [entryIds] of list [listId], in one go (a show's card stands for several lines). */
    fun removeAll(state: NamedLists, listId: String, entryIds: Set<String>, now: Long): NamedLists {
        val doomed = state.entries.filter { it.listId == listId && it.id in entryIds }
        if (doomed.isEmpty()) return state
        val gone = doomed.mapTo(HashSet()) { it.id }
        return prune(
            state.copy(
                entries = state.entries.filterNot { it.listId == listId && it.id in gone },
                removed = state.removed.filterNot { it.listId == listId && it.entryId in gone } +
                    doomed.map { ListRemoval(listId, it.id, maxOf(now, it.at + 1)) },
            ),
            now,
        )
    }

    /**
     * [state] without the videos of [keys] ([ProgressKeys]) in every list: a video that left its chat
     * ([GoneVideos]). A show's line stays: one gone episode is not the show. Also counts the lines taken.
     */
    fun forgetVideos(state: NamedLists, keys: Set<String>, now: Long): Pair<NamedLists, Int> {
        if (keys.isEmpty()) return state to 0
        val doomed = state.entries.filter { !it.record.isShow && it.record.key in keys }
        if (doomed.isEmpty()) return state to 0
        var next = state
        doomed.groupBy { it.listId }.forEach { (list, lines) -> next = removeAll(next, list, lines.mapTo(HashSet()) { it.id }, now) }
        return next to doomed.size
    }

    /** Removals past [REMOVAL_DAYS] and past [MAX_REMOVALS] (the oldest) forgotten. */
    fun prune(state: NamedLists, now: Long): NamedLists {
        val oldest = now - REMOVAL_DAYS * DAY_MS
        val kept = state.removed.filter { it.at >= oldest }
        val bounded = if (kept.size <= MAX_REMOVALS) kept else kept.sortedByDescending { it.at }.take(MAX_REMOVALS)
        return if (bounded.size == state.removed.size) state else state.copy(removed = bounded)
    }

    /** A write after [before] at [now], never at or before it, so the newer always wins a merge. */
    private fun later(before: Long, now: Long): Long = if (now > before) now else before + 1

    // ---- merge ---------------------------------------------------------------------------------

    /**
     * [a] and [b] as one: commutative and idempotent, so the order devices sync in does not matter.
     * Ids and lines that do not look right are dropped on the way, and the caps hold after.
     */
    fun merge(a: NamedLists, b: NamedLists, now: Long): NamedLists {
        val oldest = now - REMOVAL_DAYS * DAY_MS
        val removedAt = HashMap<Pair<String, String>, Long>()
        for (r in a.removed.asSequence() + b.removed.asSequence()) {
            if (r.at < oldest || !isId(r.listId) || r.entryId.length > ENTRY_ID_MAX) continue
            val key = r.listId to r.entryId
            val known = removedAt[key]
            if (known == null || r.at > known) removedAt[key] = r.at
        }
        fun listRemovedAt(id: String) = removedAt[id to ""] ?: Long.MIN_VALUE
        fun entryRemovedAt(id: String, entry: String) = maxOf(listRemovedAt(id), removedAt[id to entry] ?: Long.MIN_VALUE)

        // Lines first: one newer than its list's removal keeps the list.
        val lines = HashMap<Pair<String, String>, ListEntry>()
        for (e in a.entries.asSequence() + b.entries.asSequence()) {
            if (!isId(e.listId) || e.id.length > ENTRY_ID_MAX) continue
            if (e.at < entryRemovedAt(e.listId, e.id)) continue
            val key = e.listId to e.id
            val known = lines[key]
            if (known == null || newer(e, known)) lines[key] = e
        }
        val linesByList = lines.values.groupBy { it.listId }

        val infos = HashMap<String, ListInfo>()
        for (info in a.lists.asSequence() + b.lists.asSequence()) {
            if (!isId(info.id)) continue
            val name = cleanName(info.name) ?: continue
            val clean = if (name == info.name) info else info.copy(name = name)
            val known = infos[info.id]
            if (known == null || newer(clean, known)) infos[info.id] = clean
        }
        val lists = infos.values.filter { info ->
            info.at >= listRemovedAt(info.id) || linesByList[info.id].orEmpty().isNotEmpty()
        }.sortedWith(compareBy({ idTime(it.id) ?: it.at }, { it.id })).take(MAX_LISTS)
        val live = lists.mapTo(HashSet()) { it.id }

        val entries = linesByList.filterKeys { it in live }.values.flatMap { list ->
            list.sortedWith(compareBy(ADDED) { it.record }).take(MAX_PER_LIST)
        }.let { all ->
            if (all.size <= MAX_ENTRIES) all else all.sortedWith(compareByDescending<ListEntry> { it.at }.thenBy { it.listId }.thenBy { it.id }).take(MAX_ENTRIES)
        }.sortedWith(compareBy<ListEntry>({ it.listId }, { it.at }, { it.id }))

        // A line's removal that a newer write of the line outlived has done its job: an older copy
        // loses to that write anyway. A whole list's removal stays until it expires, even with the
        // list back: it is what keeps the lines older than it out.
        val liveLines = entries.associate { (it.listId to it.id) to it.at }
        val removed = removedAt.mapNotNull { (key, at) ->
            val (list, entry) = key
            val outlived = entry.isNotEmpty() && (liveLines[key] ?: Long.MIN_VALUE) >= at
            if (outlived) null else ListRemoval(list, entry, at)
        }.sortedWith(compareByDescending<ListRemoval> { it.at }.thenBy { it.listId }.thenBy { it.entryId }).take(MAX_REMOVALS)

        return NamedLists(lists, entries, removed)
    }

    /**
     * [incoming] (the sync file's lists, its lines stripped to what a card needs) merged into this
     * device's [local] ones: as [merge], except that a line both hold at the same time keeps this
     * device's copy, with its file id and names.
     */
    fun mergeInto(local: NamedLists, incoming: NamedLists, now: Long): NamedLists {
        val merged = merge(local, incoming, now)
        val mine = local.entries.associateBy { it.listId to it.id }
        return merged.copy(entries = merged.entries.map { e -> mine[e.listId to e.id]?.takeIf { it.at == e.at } ?: e })
    }

    /**
     * A backup's lists into the [device]'s: what the backup holds comes back even where it was
     * removed here since (a restore is asked for, and adds, never takes away), and a line here that
     * is newer keeps its own time.
     */
    fun restore(device: NamedLists, backup: NamedLists, now: Long): NamedLists {
        val lists = backup.lists.mapTo(HashSet()) { it.id }
        val lines = backup.entries.mapTo(HashSet()) { it.listId to it.id }
        val cleared = device.copy(
            removed = device.removed.filterNot { (it.wholeList && it.listId in lists) || (it.listId to it.entryId) in lines },
        )
        return merge(cleared, backup.copy(removed = emptyList()), now)
    }

    /** Of two writes of one list, the later; at the same time, the larger by its text, so both sides agree. */
    private fun newer(a: ListInfo, b: ListInfo): Boolean =
        a.at > b.at || (a.at == b.at && a.toString() > b.toString())

    private fun newer(a: ListEntry, b: ListEntry): Boolean =
        a.at > b.at || (a.at == b.at && a.record.toString() > b.record.toString())

    /** A line's id is a video's two numbers or a show's chat and key: never longer than this. */
    const val ENTRY_ID_MAX = 300

    // ---- what the viewer sees --------------------------------------------------------------------

    /**
     * The lists with what of them the kids lock lets through ([allowed], null when it is off): the
     * names stay, the lines of a locked chat are never listed or counted.
     */
    fun visible(state: NamedLists, allowed: Set<Long>?): List<VisibleList> {
        val byList = state.entries.groupBy({ it.listId }, { it.record })
        return state.ordered.map { info ->
            VisibleList(info, KidsLock.filter(byList[info.id].orEmpty().sortedWith(ADDED), allowed) { it.chatId })
        }
    }
}

/** A list and its lines as the viewer may see them ([NamedListRules.visible]), in the order added. */
data class VisibleList(val info: ListInfo, val records: List<WatchLaterRecord>) {
    /** Which of its lines a menu deciding between add and remove looks up. */
    val ids: Set<String> by lazy { records.mapTo(HashSet()) { it.id } }
}

/**
 * One card of a list's page: a video, or a show standing for every line of it (a show saved whole,
 * and its episodes saved one by one, fold into one card, as a chat's grid folds them).
 *
 * @param tile what the card draws and opens: the show's line (or one made from its first episode) for
 *   a show, the video's own line for a video. Home's row of the list draws these too.
 * @param records the lines the card stands for, which Remove from list takes out together.
 */
data class ListCard(val key: String, val tile: WatchLaterRecord, val records: List<WatchLaterRecord>) {
    val isShow: Boolean get() = tile.isShow
}

/** A list's page and its Home row, grouped like a chat's grid ([SeriesShelf.arrange]). */
object ListPages {

    /**
     * [records] (in the order added) as cards, each where its first line stands. Episodes of one
     * show in one chat fold into a show card, and a show saved whole takes its episodes saved one by
     * one under it. A show of the same name in two chats is two cards: each opens its own chat's
     * episodes. Only what the lines carry is read: no Telegram, so 5,000 lines are one pass.
     */
    fun cards(records: List<WatchLaterRecord>, corrections: Map<String, VideoCorrection> = VideoCorrections.current): List<ListCard> {
        if (records.isEmpty()) return emptyList()
        val order = HashMap<String, Int>(records.size * 2)
        records.forEachIndexed { index, r -> if (r.id !in order) order[r.id] = index }
        val videos = records.filterNot { it.isShow }
        val byItem = HashMap<String, WatchLaterRecord>(videos.size * 2)
        val items = videos.map { record -> record.toMediaItem().also { byItem[it.id] = record } }

        data class Group(val key: String, val tile: WatchLaterRecord, val records: MutableList<WatchLaterRecord>)
        val groups = LinkedHashMap<String, Group>()

        // Shows saved whole, by chat and key.
        for (show in records) {
            if (!show.isShow) continue
            groups.getOrPut("show:${show.id}") { Group("show:${show.id}", show, ArrayList()) }.records += show
        }
        // The grid's own grouping, chat by chat: shows are a chat's.
        for ((chatId, chatItems) in items.groupBy { it.chatId }) {
            val shelf = runCatching { SeriesShelf.arrange(chatItems, corrections) }.getOrNull()
                ?: chatItems.map { ShelfEntry.File(it) }
            for (entry in shelf) {
                when (entry) {
                    is ShelfEntry.Show -> {
                        val lines = entry.series.episodes.flatMap { ep -> (listOf(ep.item) + ep.copies + ep.alsoIn).mapNotNull { byItem[it.id] } }.distinctBy { it.id }
                        if (lines.isEmpty()) continue
                        val id = WatchLaterRecord.showId(chatId, entry.series.key)
                        val group = groups["show:$id"]
                        if (group != null) {
                            group.records += lines
                        } else {
                            val first = lines.minBy { order[it.id] ?: Int.MAX_VALUE }
                            val tile = first.copy(title = entry.series.title, series = entry.series.key, sizeBytes = 0L, durationSec = 0)
                            groups["show:$id"] = Group("show:$id", tile, lines.toMutableList())
                        }
                    }
                    is ShelfEntry.File -> {
                        val record = byItem[entry.item.id] ?: continue
                        // A file the grid folded copies into: the copies are lines of this card too.
                        groups.getOrPut("video:${record.id}") { Group("video:${record.id}", record, ArrayList()) }.records += record
                    }
                }
            }
        }
        // A line the grouping did not place (a piece of a split file, say) still has its own card.
        val placed = groups.values.flatMapTo(HashSet()) { g -> g.records.map { it.id } }
        for (record in videos) {
            if (record.id !in placed) groups["video:${record.id}"] = Group("video:${record.id}", record, mutableListOf(record))
        }
        return groups.values
            .map { g -> ListCard(g.key, g.tile, g.records.distinctBy { it.id }.sortedBy { order[it.id] ?: Int.MAX_VALUE }) }
            .sortedBy { card -> card.records.minOfOrNull { order[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
    }
}

/**
 * How [NamedLists] sit in the watched store's file, beside Watch later's "l_" lines: a list's name
 * under "nm_<list>", each line under "n_<list>_<line>" in [WatchLaterRecord]'s own encoding, and the
 * removals under "xn_<list>" (the whole list) and "xn_<list>_<line>", as times. None of these starts
 * with another list's prefix ("w_", "l_", "xw_", "xl_"), so no reader takes another's lines.
 */
object NamedListKeys {
    const val INFO = "nm_"
    const val LINE = "n_"
    const val REMOVED = "xn_"
    private const val SEP = '\u001F'

    /** Whether [name] is one of the lists' keys. */
    fun owns(name: String): Boolean = name.startsWith(INFO) || name.startsWith(LINE) || name.startsWith(REMOVED)

    /** The lists in [raw] (a store's names to values); anything that does not read is skipped. */
    fun read(raw: Map<String, Any?>): NamedLists {
        val lists = ArrayList<ListInfo>()
        val entries = ArrayList<ListEntry>()
        val removed = ArrayList<ListRemoval>()
        for ((name, value) in raw) {
            when {
                name.startsWith(INFO) -> infoOf(name.removePrefix(INFO), value as? String)?.let(lists::add)
                name.startsWith(LINE) -> {
                    val rest = name.removePrefix(LINE)
                    val at = rest.indexOf('_')
                    if (at <= 0) continue
                    val list = rest.substring(0, at)
                    if (!NamedListRules.isId(list)) continue
                    val record = WatchLaterRecord.decode(rest.substring(at + 1), value as? String) ?: continue
                    entries += ListEntry(list, record)
                }
                name.startsWith(REMOVED) -> {
                    val time = (value as? Long)?.takeIf { it > 0 } ?: continue
                    val rest = name.removePrefix(REMOVED)
                    val at = rest.indexOf('_')
                    val list = if (at < 0) rest else rest.substring(0, at)
                    if (!NamedListRules.isId(list)) continue
                    removed += ListRemoval(list, if (at < 0) "" else rest.substring(at + 1), time)
                }
            }
        }
        return NamedLists(lists, entries, removed)
    }

    /** [state] as the store's names and values: what [read] reads back. */
    fun encode(state: NamedLists): Map<String, Any> = buildMap {
        for (info in state.lists) put(INFO + info.id, "${info.at}$SEP${if (info.home) 1 else 0}$SEP${info.name.replace(SEP, ' ')}")
        for (e in state.entries) put("$LINE${e.listId}_${e.id}", e.record.encode())
        for (r in state.removed) put(if (r.wholeList) REMOVED + r.listId else "$REMOVED${r.listId}_${r.entryId}", r.at)
    }

    private fun infoOf(id: String, value: String?): ListInfo? {
        if (!NamedListRules.isId(id) || value == null) return null
        val parts = value.split(SEP, limit = 3)
        if (parts.size < 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        val name = NamedListRules.cleanName(parts[2]) ?: return null
        return ListInfo(id, name, at, home = parts[1] != "0")
    }
}
