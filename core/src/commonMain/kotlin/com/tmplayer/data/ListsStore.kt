package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The viewer's named lists ([NamedLists], HX43), in the watched store's file beside Watch later
 * ([NamedListKeys]): a list grows with use, and the settings file is written on every resume save
 * during playback, so a list of 5,000 lines does not ride along in it. Signing out clears it with the
 * file; Clear watched leaves it alone.
 *
 * Every edit reads the lists as they are in the file at that moment, so two edits never undo each
 * other, and leaves the removals the sync file needs ([NamedListRules]).
 */
class ListsStore internal constructor(private val store: DataStore<Preferences>) {

    /** Everything, the kids lock's chats included: for sync and backups, never for a screen. */
    val all: Flow<NamedLists> = store.data
        .map { prefs -> prefs.asMap().entries.filter { NamedListKeys.owns(it.key.name) }.associate { it.key.name to it.value } }
        .distinctUntilChanged()
        .map { NamedListKeys.read(it) }
        .flowOn(Dispatchers.Default)

    /** The lists as a screen shows them: in the order made, without the lines of a locked chat ([ChatGate]). */
    val visible: Flow<List<VisibleList>> = all
        .combine(ChatGate.allowed) { lists, allowed -> NamedListRules.visible(lists, allowed) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun now(): NamedLists = all.first()

    /** A new list; null for an empty name or once [NamedListRules.MAX_LISTS] are made. */
    suspend fun create(name: String): ListInfo? {
        var made: ListInfo? = null
        edit { state, now -> NamedListRules.create(state, name, now)?.also { made = it.second }?.first ?: state }
        return made
    }

    suspend fun rename(id: String, name: String) = edit { state, now -> NamedListRules.rename(state, id, name, now) }

    suspend fun setHome(id: String, home: Boolean) = edit { state, now -> NamedListRules.setHome(state, id, home, now) }

    suspend fun delete(id: String) = edit { state, now -> NamedListRules.delete(state, id, now) }

    /** Puts [record] (a video, or a show saved whole) in list [listId]. */
    suspend fun add(listId: String, record: WatchLaterRecord): ListAdd {
        var result = ListAdd.NoList
        edit { state, now -> NamedListRules.add(state, listId, record, now).also { result = it.second }.first }
        return result
    }

    /** Takes [entryIds] ([WatchLaterRecord.id]s) out of list [listId]. */
    suspend fun remove(listId: String, entryIds: Set<String>) = edit { state, now -> NamedListRules.removeAll(state, listId, entryIds, now) }

    /** Takes the videos of [keys] out of every list (gone from their chats); how many lines went. */
    suspend fun forgetVideos(keys: Set<String>): Int {
        var count = 0
        edit { state, now -> NamedListRules.forgetVideos(state, keys, now).also { count = it.second }.first }
        return count
    }

    /** Merges [other] (the sync file's lists) into these; how many lists and lines changed here. */
    suspend fun mergeIn(other: NamedLists): Int {
        var changed = 0
        edit { state, now ->
            NamedListRules.mergeInto(state, other, now).also { changed = changes(state, it) }
        }
        return changed
    }

    /** A backup's lists, brought back whatever was removed here since (a restore adds, never takes). */
    suspend fun restore(backup: NamedLists): Int {
        if (backup.lists.isEmpty() && backup.entries.isEmpty()) return 0
        var changed = 0
        edit { state, now ->
            NamedListRules.restore(state, backup, now).also { changed = changes(state, it) }
        }
        return changed
    }

    /** One edit over the file as it is then: only the keys that changed are written. */
    private suspend fun edit(change: (NamedLists, Long) -> NamedLists) {
        store.edit { prefs -> write(prefs, change) }
    }

    internal fun write(prefs: MutablePreferences, change: (NamedLists, Long) -> NamedLists) {
        val before = prefs.asMap().entries.filter { NamedListKeys.owns(it.key.name) }.associate { it.key.name to it.value }
        val state = NamedListKeys.read(before)
        val after = change(state, System.currentTimeMillis())
        if (after === state) return
        val target = NamedListKeys.encode(after)
        for (name in before.keys) if (name !in target) {
            prefs.remove(stringPreferencesKey(name))
            prefs.remove(longPreferencesKey(name))
        }
        for ((name, value) in target) {
            if (before[name] == value) continue
            prefs.putRaw(name, value)
        }
    }

    private fun changes(before: NamedLists, after: NamedLists): Int {
        val a: Set<Any> = before.lists.toHashSet<Any>() + before.entries.map { it.listId to it.record }
        val b: Set<Any> = after.lists.toHashSet<Any>() + after.entries.map { it.listId to it.record }
        return (b - a).size + (a - b).size
    }
}
