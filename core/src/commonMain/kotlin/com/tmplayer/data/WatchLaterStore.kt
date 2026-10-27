package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Watch later: single videos the viewer saved for another time, newest first.
 *
 * Starring a chat puts all of it on Home, which is too much for one film somebody posted in a busy
 * group. This keeps the one video instead. Nothing is downloaded, so it works in a chat that
 * restricts saving content as well as anywhere else.
 *
 * It lives in the Watched list's file ([WatchedStore.watchLater]) under a prefix of its own, which
 * is what lets finishing a video, or marking it watched, cross it off here in the same write that
 * ticks it. "Clear watched" leaves it alone; signing out clears both.
 */
class WatchLaterStore internal constructor(private val store: DataStore<Preferences>) {

    private fun <T> read(transform: (Preferences) -> T): Flow<T> = store.data
        .map(transform)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * The list, most recently added first, without the chats the kids lock leaves out
     * ([ChatGate], CP57): the Home row and the History tab both read this.
     */
    val items: Flow<List<WatchLaterRecord>> = read { prefs ->
        decodeAll(prefs).sortedByDescending { it.addedAt }
    }.combine(ChatGate.allowed) { records, allowed -> KidsLock.filter(records, allowed) { it.chatId } }

    /** Every saved video's [SettingsStore.progressKey], for a menu deciding between add and remove. */
    val keys: Flow<Set<String>> = read { prefs ->
        prefs.asMap().keys.mapNotNullTo(HashSet()) { key -> key.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) }
    }

    suspend fun contains(chatId: Long, messageId: Long): Boolean =
        store.data.first()[keyOf(chatId, messageId)] != null

    /** Adds [record], or moves it to the top with the new time when it is there already. */
    suspend fun add(record: WatchLaterRecord) {
        store.edit { prefs ->
            prefs[keyOf(record.chatId, record.messageId)] = record.encode()
            evictOldest(prefs)
        }
    }

    suspend fun remove(chatId: Long, messageId: Long) {
        store.edit { it.remove(keyOf(chatId, messageId)) }
    }

    /** Takes [ids] ([SettingsStore.progressKey]s) off the list in one edit, handed back for Undo ([putBack]). */
    suspend fun take(ids: Collection<String>): List<WatchLaterRecord> {
        if (ids.isEmpty()) return emptyList()
        val taken = ArrayList<WatchLaterRecord>()
        store.edit { prefs ->
            for (id in ids.toSet()) {
                val key = stringPreferencesKey(PREFIX + id)
                val record = WatchLaterRecord.decode(id, prefs[key])
                if (prefs[key] != null) prefs.remove(key)
                if (record != null) taken += record
            }
        }
        return taken
    }

    /** Undo for [take]: each video back where it stood, by the time it was first added. */
    suspend fun putBack(records: List<WatchLaterRecord>) {
        if (records.isEmpty()) return
        store.edit { prefs ->
            for (record in records) {
                val key = keyOf(record.chatId, record.messageId)
                if (prefs[key] == null) prefs[key] = record.encode()
            }
            evictOldest(prefs)
        }
    }

    /** Empties the list. Keys are collected first: the map cannot be walked while it is written. */
    suspend fun clear() {
        store.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }
            for (key in doomed) prefs.remove(key)
        }
    }

    private fun decodeAll(prefs: Preferences): List<WatchLaterRecord> = buildList {
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (!name.startsWith(PREFIX)) continue
            WatchLaterRecord.decode(name.removePrefix(PREFIX), value as? String)?.let(::add)
        }
    }

    /**
     * Keeps the list to [MAX_WATCH_LATER]. A list longer than that has stopped being a plan and
     * become an archive, and the whole file is read on every change.
     */
    internal fun evictOldest(prefs: MutablePreferences) {
        val all = decodeAll(prefs)
        if (all.size <= MAX_WATCH_LATER) return
        all.sortedBy { it.addedAt }
            .take(all.size - MAX_WATCH_LATER)
            .forEach { prefs.remove(keyOf(it.chatId, it.messageId)) }
    }

    companion object {
        /** How many videos the list holds before the oldest start dropping off. */
        const val MAX_WATCH_LATER = 1_000

        /** Not the Watched list's "w_": the two share a file and must never read each other's lines. */
        private const val PREFIX = "l_"

        internal fun keyOf(chatId: Long, messageId: Long) =
            stringPreferencesKey(PREFIX + SettingsStore.progressKey(chatId, messageId))
    }
}
