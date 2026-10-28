package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import java.io.File

/**
 * The local record of what has been watched to the end: TMPlayer's own small database for it.
 *
 * A file of its own rather than more keys in [SettingsStore]: this list grows with use and is read
 * whole on every change, settings are not, and "Clear watched" should never be one stray edit away
 * from someone's preferences. Everything goes through this class, so moving the list into SQLite or
 * syncing it later changes this file and nothing that calls it.
 *
 * Like [SettingsStore], it takes an already opened store: DataStore allows one live instance per
 * file in a process, so each app opens it once with [openDataStore] and shares it.
 */
class WatchedStore(private val store: DataStore<Preferences>) {

    /**
     * Watch later, kept in this same file under a prefix of its own, so that [markWatched] crosses
     * a video off it in the same write that ticks it. [clear] leaves it alone.
     */
    val watchLater: WatchLaterStore = WatchLaterStore(store)

    private fun <T> read(transform: (Preferences) -> T): Flow<T> = store.data
        .map(transform)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Every watched video keyed by [SettingsStore.progressKey], for the ticks on the grid. */
    val watched: Flow<Map<String, WatchedRecord>> = read { prefs ->
        decodeAll(prefs).associateBy { it.key }
    }

    /**
     * The "Watched" list: most recently finished first, without the chats the kids lock leaves
     * out ([ChatGate]). The ticks in [watched] are not screened: a grid only ever shows a chat the
     * lock already let through.
     */
    val history: Flow<List<WatchedRecord>> = read { prefs ->
        decodeAll(prefs).sortedByDescending { it.watchedAt }
    }.combine(ChatGate.allowed) { records, allowed -> KidsLock.filter(records, allowed) { it.chatId } }

    suspend fun isWatched(chatId: Long, messageId: Long): Boolean =
        store.data.first()[keyOf(chatId, messageId)] != null

    /**
     * Records [record] as watched. A manual mark is never downgraded to an automatic one: if the
     * viewer said so first, the player finishing it later keeps their word and the new time.
     *
     * Either way the video is done with, so it leaves [watchLater] in the same edit, and so do
     * its [copies] (the same film or episode posted elsewhere, [MediaItem.versions]): one Watch
     * later card left behind for a copy would offer a finished video again.
     */
    suspend fun markWatched(record: WatchedRecord, copies: List<MediaItem> = emptyList()) {
        store.edit { prefs ->
            val key = keyOf(record.chatId, record.messageId)
            val before = WatchedRecord.decode(record.key, prefs[key])
            val kept = if (before?.manual == true && !record.manual) record.copy(manual = true) else record
            prefs[key] = kept.encode()
            prefs.remove(WatchLaterStore.keyOf(record.chatId, record.messageId))
            for (copy in copies) prefs.remove(WatchLaterStore.keyOf(copy.chatId, copy.messageId))
            evictOldest(prefs)
        }
        // The player saw it through to the end: a good moment, and a finished watch for the
        // support ladder. A manual mark is neither.
        if (!record.manual) SupportReminder.watchFinished(record.key)
    }

    /**
     * Takes the mark off, and off every one of [copies] too: a copy still marked kept the folded
     * card ticked, so Mark as unwatched seemed to do nothing.
     */
    suspend fun markUnwatched(chatId: Long, messageId: Long, copies: List<MediaItem> = emptyList()) {
        store.edit { prefs ->
            prefs.remove(keyOf(chatId, messageId))
            for (copy in copies) prefs.remove(keyOf(copy.chatId, copy.messageId))
        }
    }

    /**
     * Marks many videos watched by hand in one edit (a season or a whole show, HX20), each taken
     * off Watch later as [markWatched] does, and every one of [copies] too. One write for a show of
     * 500 episodes rather than 500, the cap applied once after.
     */
    suspend fun markAllWatched(records: List<WatchedRecord>, copies: List<MediaItem> = emptyList()) {
        if (records.isEmpty()) return
        store.edit { prefs ->
            for (record in records) {
                val key = keyOf(record.chatId, record.messageId)
                val before = WatchedRecord.decode(record.key, prefs[key])
                // Already watched: kept as it was, so the Watched list keeps the day it was seen.
                if (before == null) prefs[key] = record.encode()
                prefs.remove(WatchLaterStore.keyOf(record.chatId, record.messageId))
            }
            for (copy in copies) prefs.remove(WatchLaterStore.keyOf(copy.chatId, copy.messageId))
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

    /**
     * Remove from history for the marks of [ids] ([SettingsStore.progressKey]s): taken off in one
     * edit and handed back, for Undo ([putBackMarks]). An id without a mark is skipped.
     */
    suspend fun takeMarks(ids: Collection<String>): List<WatchedRecord> {
        if (ids.isEmpty()) return emptyList()
        val taken = ArrayList<WatchedRecord>()
        store.edit { prefs ->
            for (id in ids.toSet()) {
                val key = stringPreferencesKey(PREFIX + id)
                val record = WatchedRecord.decode(id, prefs[key])
                if (prefs[key] != null) prefs.remove(key)
                if (record != null) taken += record
            }
        }
        return taken
    }

    /** Undo for [takeMarks]: each mark back as it was, unless the video was marked again since. */
    suspend fun putBackMarks(records: List<WatchedRecord>) {
        if (records.isEmpty()) return
        store.edit { prefs ->
            for (record in records) {
                val key = keyOf(record.chatId, record.messageId)
                if (prefs[key] == null) prefs[key] = record.encode()
            }
            evictOldest(prefs)
        }
    }

    /** Every stored mark under its stored name, for [Backups]. */
    suspend fun rawEntries(): Map<String, Any> = store.data.first().asMap().mapKeys { it.key.name }

    /**
     * As [SettingsStore.restoreEntries]: one edit over the file as it is then, the watched list and
     * [watchLater] alike, each capped after.
     */
    suspend fun restoreEntries(writes: (Map<String, Any>) -> Map<String, Any>) {
        store.edit { prefs ->
            val now = prefs.asMap().mapKeys { it.key.name }
            for ((name, value) in writes(now)) prefs.putRaw(name, value)
            evictOldest(prefs)
            watchLater.evictOldest(prefs)
        }
    }

    private fun decodeAll(prefs: Preferences): List<WatchedRecord> = buildList {
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (!name.startsWith(PREFIX)) continue
            WatchedRecord.decode(name.removePrefix(PREFIX), value as? String)?.let(::add)
        }
    }

    /**
     * Keeps the list to [MAX_WATCHED]. Generous, since this is the record a viewer works through a
     * long series with, but bounded, because the whole file is read on every change.
     */
    private fun evictOldest(prefs: MutablePreferences) {
        val all = decodeAll(prefs)
        if (all.size <= MAX_WATCHED) return
        all.sortedBy { it.watchedAt }
            .take(all.size - MAX_WATCHED)
            .forEach { prefs.remove(keyOf(it.chatId, it.messageId)) }
    }

    companion object {
        const val FILE_NAME = "watched.preferences_pb"

        /** How many finished videos are remembered before the oldest start dropping off. */
        const val MAX_WATCHED = 5_000

        private const val PREFIX = "w_"

        private fun keyOf(chatId: Long, messageId: Long) =
            stringPreferencesKey(PREFIX + SettingsStore.progressKey(chatId, messageId))

        /** Opens the store at [file]. Call once per process and share the result. */
        fun openDataStore(file: File): DataStore<Preferences> = openSafeDataStore(file)
    }
}
