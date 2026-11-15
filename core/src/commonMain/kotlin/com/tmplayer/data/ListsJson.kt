package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [NamedLists] as the backup file and the sync file write them: `{"lists": [...], "entries": [...],
 * "removed": [...]}`, a line in [Backups.watchLaterJson]'s fields plus its list. One reader for both.
 *
 * What it reads is outside data: an entry of the wrong type, an id that is not one, a list that is
 * not there, a name of ten million characters or a hundred thousand entries are each skipped or cut
 * on their own, and the rest still reads.
 */
internal object NamedListsJson {

    fun json(state: NamedLists, removals: Boolean = true): JSONObject = JSONObject().apply {
        put(
            "lists",
            JSONArray(state.lists.sortedBy { it.id }.map { JSONObject().put("id", it.id).put("name", it.name).put("at", it.at).put("home", it.home) }),
        )
        put(
            "entries",
            JSONArray(state.entries.sortedWith(compareBy<ListEntry>({ it.listId }, { it.at }, { it.id })).map { Backups.watchLaterJson(it.record).put("list", it.listId) }),
        )
        if (removals) {
            put(
                "removed",
                JSONArray(state.removed.map { r -> JSONObject().put("list", r.listId).put("at", r.at).also { if (!r.wholeList) it.put("entry", r.entryId) } }),
            )
        }
    }

    fun of(json: JSONObject?, removals: Boolean = true): NamedLists {
        if (json == null) return NamedLists()
        val lists = Backups.objects(json.optJSONArray("lists")).take(NamedListRules.MAX_LISTS * 2).mapNotNull { o ->
            val id = (o.opt("id") as? String)?.takeIf(NamedListRules::isId) ?: return@mapNotNull null
            val name = NamedListRules.cleanName(o.opt("name") as? String) ?: return@mapNotNull null
            val at = (o.opt("at") as? Number)?.toLong() ?: return@mapNotNull null
            ListInfo(id, name, at, home = (o.opt("home") as? Boolean) ?: true)
        }
        val entries = Backups.objects(json.optJSONArray("entries")).take(NamedListRules.MAX_ENTRIES * 2).mapNotNull { o ->
            val list = (o.opt("list") as? String)?.takeIf(NamedListRules::isId) ?: return@mapNotNull null
            val record = runCatching { Backups.watchLaterOf(o) }.getOrNull() ?: return@mapNotNull null
            if (record.chatId == 0L || record.messageId <= 0L || record.id.length > NamedListRules.ENTRY_ID_MAX) return@mapNotNull null
            ListEntry(list, record.copy(title = record.title.take(TEXT_MAX), fileName = record.fileName.take(TEXT_MAX), chatTitle = record.chatTitle.take(TEXT_MAX), mimeType = record.mimeType.take(100)))
        }
        val removed = if (!removals) emptyList() else Backups.objects(json.optJSONArray("removed")).take(NamedListRules.MAX_REMOVALS * 2).mapNotNull { o ->
            val list = (o.opt("list") as? String)?.takeIf(NamedListRules::isId) ?: return@mapNotNull null
            val at = (o.opt("at") as? Number)?.toLong() ?: return@mapNotNull null
            val entry = (o.opt("entry") as? String).orEmpty()
            if (entry.length > NamedListRules.ENTRY_ID_MAX) return@mapNotNull null
            ListRemoval(list, entry, at)
        }
        return NamedLists(lists, entries, removed)
    }

    /** A title or name longer than this in a file is cut: no card or heading shows more. */
    private const val TEXT_MAX = 500
}
