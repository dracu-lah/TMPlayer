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

    /** The viewer's named lists (HX43), in this same file: see [ListsStore]. [clear] leaves them alone. */
    val lists: ListsStore = ListsStore(store)

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
        var fresh = false
        store.edit { prefs ->
            val key = keyOf(record.chatId, record.messageId)
            val before = WatchedRecord.decode(record.key, prefs[key])
            // Only a video not marked before is newly watched: a replay of one already watched,
            // or leaving it past the end again, keeps its download (as the setting has always said).
            fresh = before == null
            val kept = if (before?.manual == true && !record.manual) record.copy(manual = true) else record
            prefs[key] = kept.encode()
            SyncRemovals.forgetWatched(prefs, record.key)
            prefs.remove(WatchLaterStore.keyOf(record.chatId, record.messageId))
            for (copy in copies) prefs.remove(WatchLaterStore.keyOf(copy.chatId, copy.messageId))
            evictOldest(prefs)
        }
        // A mark made here, now, for the profile it was written for: what "Remove after
        // watching" acts on, and nothing else is (HX88).
        if (fresh) noteMarked(setOf(record.key))
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
            // Noted for resume on other devices, so their older copies of the marks go too.
            for ((chat, message) in listOf(chatId to messageId) + copies.map { it.chatId to it.messageId }) {
                if (prefs[keyOf(chat, message)] != null) SyncRemovals.watched(prefs, SettingsStore.progressKey(chat, message))
                prefs.remove(keyOf(chat, message))
            }
            SyncRemovals.prune(prefs)
        }
    }

    /**
     * Marks many videos watched by hand in one edit (a season or a whole show, HX20), each taken
     * off Watch later as [markWatched] does, and every one of [copies] too. One write for a show of
     * 500 episodes rather than 500, the cap applied once after.
     */
    suspend fun markAllWatched(records: List<WatchedRecord>, copies: List<MediaItem> = emptyList()) {
        if (records.isEmpty()) return
        val fresh = HashSet<String>()
        store.edit { prefs ->
            fresh.clear()
            for (record in records) {
                val key = keyOf(record.chatId, record.messageId)
                val before = WatchedRecord.decode(record.key, prefs[key])
                // Already watched: kept as it was, so the Watched list keeps the day it was seen.
                if (before == null) {
                    prefs[key] = record.encode()
                    SyncRemovals.forgetWatched(prefs, record.key)
                    fresh += record.key
                }
                prefs.remove(WatchLaterStore.keyOf(record.chatId, record.messageId))
            }
            for (copy in copies) prefs.remove(WatchLaterStore.keyOf(copy.chatId, copy.messageId))
            evictOldest(prefs)
        }
        noteMarked(fresh)
    }

    /**
     * Tells [WatchedMarks] of new marks, for the profile they were written for: the writing
     * coroutine's owner, or the profile open now; none when that owner is no longer open (its
     * write was dropped).
     */
    private suspend fun noteMarked(keys: Set<String>) {
        val owner = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp ?: Accounts.stamp
        if (owner == Accounts.stamp) WatchedMarks.marked(keys.toSet(), owner)
    }

    /** Empties the list. Keys are collected first: the map cannot be walked while it is written. */
    suspend fun clear() {
        store.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }
            val at = System.currentTimeMillis()
            for (key in doomed) {
                prefs.remove(key)
                SyncRemovals.watched(prefs, key.name.removePrefix(PREFIX), at)
            }
            SyncRemovals.prune(prefs, at)
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
                if (prefs[key] != null) {
                    prefs.remove(key)
                    SyncRemovals.watched(prefs, id)
                }
                if (record != null) taken += record
            }
            SyncRemovals.prune(prefs)
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
                SyncRemovals.forgetWatched(prefs, record.key)
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

    /**
     * What resume on other devices ([ResumeSync]) takes in, in one edit, each entry checked again
     * against the file as it is then: a mark made or a line saved here while the sync ran is never
     * overwritten by an older one, and nothing goes that is newer here than the removal.
     * Returns how many entries changed.
     */
    suspend fun applySync(apply: SyncApply): Int {
        var changed = 0
        store.edit { prefs ->
            for (record in apply.watchedIn) {
                val key = keyOf(record.chatId, record.messageId)
                val mine = WatchedRecord.decode(record.key, prefs[key])
                if (mine != null && mine.watchedAt > record.watchedAt) continue
                if (mine != null && mine.watchedAt == record.watchedAt && (mine.manual || !record.manual)) continue
                prefs[key] = (if (mine?.manual == true) record.copy(manual = true) else record).encode()
                SyncRemovals.forgetWatched(prefs, record.key)
                changed++
            }
            for ((id, at) in apply.watchedOut) {
                val key = stringPreferencesKey(PREFIX + id)
                val mine = WatchedRecord.decode(id, prefs[key]) ?: continue
                if (mine.watchedAt >= at) continue
                prefs.remove(key)
                changed++
            }
            changed += watchLater.applySync(prefs, apply)
            evictOldest(prefs)
            watchLater.evictOldest(prefs)
        }
        return changed
    }

    /** Marks and Watch later lines that came from another device without this device's file id, at most [limit]. */
    suspend fun withoutFile(limit: Int): List<Pair<Long, Long>> {
        val prefs = store.data.first()
        val marks = decodeAll(prefs).filter { it.fileId <= 0 }.sortedByDescending { it.watchedAt }.map { it.chatId to it.messageId }
        val later = watchLater.withoutFile(prefs)
        return (later + marks).distinct().take(limit)
    }

    /**
     * Gives entries that came without a file id ([withoutFile]) this device's own, read from the
     * message, with its title and chat name where the entry had none.
     */
    suspend fun healFiles(found: Map<String, Pair<MediaItem, String>>) {
        if (found.isEmpty()) return
        store.edit { prefs ->
            for ((id, pair) in found) {
                val (item, chatTitle) = pair
                val key = stringPreferencesKey(PREFIX + id)
                val mine = WatchedRecord.decode(id, prefs[key]) ?: continue
                if (mine.fileId > 0 || item.fileId <= 0) continue
                prefs[key] = mine.copy(
                    fileId = item.fileId,
                    title = mine.title.ifBlank { item.title },
                    chatTitle = mine.chatTitle.ifBlank { chatTitle },
                    fileName = mine.fileName ?: item.fileName.ifBlank { null },
                ).encode()
            }
            watchLater.healFiles(prefs, found)
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
        fun openDataStore(file: File): DataStore<Preferences> =
            AccountScopedDataStore.opened(openSafeDataStore(file), AccountScopedDataStore.ALL_ACCOUNT)
    }
}
