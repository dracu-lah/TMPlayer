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
 *
 * A whole show can be saved too (HX20, [WatchLaterRecord.isShow]): one line by its chat and key,
 * which finishing an episode leaves alone; marking the rest of the show watched takes it off.
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

    /** Every saved video's [SettingsStore.progressKey], for a menu deciding between add and remove. Shows are not in it. */
    val keys: Flow<Set<String>> = read { prefs ->
        prefs.asMap().keys.mapNotNullTo(HashSet()) { key ->
            key.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { WatchLaterRecord.SHOW_SEP !in it }
        }
    }

    /** Every saved show's [WatchLaterRecord.showId], for a show's page or menu deciding between add and remove. */
    val showIds: Flow<Set<String>> = read { prefs ->
        prefs.asMap().keys.mapNotNullTo(HashSet()) { key ->
            key.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { WatchLaterRecord.SHOW_SEP in it }
        }
    }

    suspend fun contains(chatId: Long, messageId: Long): Boolean =
        store.data.first()[keyOf(chatId, messageId)] != null

    /** Adds [record], or moves it to the top with the new time when it is there already. */
    suspend fun add(record: WatchLaterRecord) {
        store.edit { prefs ->
            prefs[keyOf(record)] = record.encode()
            SyncRemovals.forgetLater(prefs, record.id)
            evictOldest(prefs)
        }
    }

    suspend fun remove(chatId: Long, messageId: Long) {
        store.edit { removeNoted(it, SettingsStore.progressKey(chatId, messageId)); SyncRemovals.prune(it) }
    }

    /** Takes [record]'s line off the list, a video's or a show's alike. */
    suspend fun remove(record: WatchLaterRecord) {
        store.edit { removeNoted(it, record.id); SyncRemovals.prune(it) }
    }

    /** Takes the show [seriesKey] of [chatId] off the list; its episodes saved one by one stay. */
    suspend fun removeShow(chatId: Long, seriesKey: String) {
        store.edit { removeNoted(it, WatchLaterRecord.showId(chatId, seriesKey)); SyncRemovals.prune(it) }
    }

    /** Takes [ids] ([SettingsStore.progressKey]s) off the list in one edit, handed back for Undo ([putBack]). */
    suspend fun take(ids: Collection<String>): List<WatchLaterRecord> {
        if (ids.isEmpty()) return emptyList()
        val taken = ArrayList<WatchLaterRecord>()
        store.edit { prefs ->
            for (id in ids.toSet()) {
                val key = stringPreferencesKey(PREFIX + id)
                val record = WatchLaterRecord.decode(id, prefs[key])
                if (prefs[key] != null) removeNoted(prefs, id)
                if (record != null) taken += record
            }
            SyncRemovals.prune(prefs)
        }
        return taken
    }

    /** Undo for [take]: each video back where it stood, by the time it was first added. */
    suspend fun putBack(records: List<WatchLaterRecord>) {
        if (records.isEmpty()) return
        store.edit { prefs ->
            for (record in records) {
                val key = keyOf(record)
                if (prefs[key] == null) prefs[key] = record.encode()
                SyncRemovals.forgetLater(prefs, record.id)
            }
            evictOldest(prefs)
        }
    }

    /** Empties the list. Keys are collected first: the map cannot be walked while it is written. */
    suspend fun clear() {
        store.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }
            val at = System.currentTimeMillis()
            for (key in doomed) {
                prefs.remove(key)
                SyncRemovals.later(prefs, key.name.removePrefix(PREFIX), at)
            }
            SyncRemovals.prune(prefs, at)
        }
    }

    /** Takes line [id] off, noting it for resume on other devices ([SyncRemovals]) when it was there. */
    private fun removeNoted(prefs: MutablePreferences, id: String) {
        val key = stringPreferencesKey(PREFIX + id)
        if (prefs[key] == null) return
        prefs.remove(key)
        SyncRemovals.later(prefs, id)
    }

    /** [WatchedStore.applySync]'s Watch later half, inside its edit. */
    internal fun applySync(prefs: MutablePreferences, apply: SyncApply): Int {
        var changed = 0
        for (record in apply.laterIn) {
            val key = keyOf(record)
            val mine = WatchLaterRecord.decode(record.id, prefs[key])
            if (mine != null && mine.addedAt >= record.addedAt) continue
            prefs[key] = record.encode()
            SyncRemovals.forgetLater(prefs, record.id)
            changed++
        }
        for ((id, at) in apply.laterOut) {
            val key = stringPreferencesKey(PREFIX + id)
            val mine = WatchLaterRecord.decode(id, prefs[key]) ?: continue
            if (mine.addedAt > at) continue
            prefs.remove(key)
            changed++
        }
        return changed
    }

    /** Lines without this device's file id, newest first, as chat and message. */
    internal fun withoutFile(prefs: Preferences): List<Pair<Long, Long>> =
        decodeAll(prefs).filter { it.fileId <= 0 }.sortedByDescending { it.addedAt }.map { it.chatId to it.messageId }

    /** [WatchedStore.healFiles]'s Watch later half: a video's line, by its key, gets the file id read. */
    internal fun healFiles(prefs: MutablePreferences, found: Map<String, Pair<MediaItem, String>>) {
        for ((key, value) in prefs.asMap().toList()) {
            val name = key.name
            if (!name.startsWith(PREFIX)) continue
            val record = WatchLaterRecord.decode(name.removePrefix(PREFIX), value as? String) ?: continue
            if (record.fileId > 0) continue
            val (item, chatTitle) = found[record.key] ?: continue
            if (item.fileId <= 0) continue
            prefs[stringPreferencesKey(name)] = record.copy(
                fileId = item.fileId,
                title = record.title.ifBlank { item.title },
                fileName = record.fileName.ifBlank { item.fileName },
                chatTitle = record.chatTitle.ifBlank { chatTitle },
                mimeType = record.mimeType.ifBlank { item.mimeType },
            ).encode()
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
            .forEach { prefs.remove(keyOf(it)) }
    }

    companion object {
        /** How many videos the list holds before the oldest start dropping off. */
        const val MAX_WATCH_LATER = 1_000

        /** Not the Watched list's "w_": the two share a file and must never read each other's lines. */
        private const val PREFIX = "l_"

        internal fun keyOf(chatId: Long, messageId: Long) =
            stringPreferencesKey(PREFIX + SettingsStore.progressKey(chatId, messageId))

        /** A video's line by its message, a show's by its chat and key ([WatchLaterRecord.id]). */
        private fun keyOf(record: WatchLaterRecord) = stringPreferencesKey(PREFIX + record.id)
    }
}
