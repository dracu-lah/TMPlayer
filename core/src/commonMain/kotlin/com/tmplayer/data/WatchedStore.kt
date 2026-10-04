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
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import java.io.File

/**
 * A video the viewer has finished, or said they have.
 *
 * Carries the same description a [ResumeRecord] does, so the "Watched" list can open the player
 * without the chat it came from being loaded.
 */
data class WatchedRecord(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    val chatTitle: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val watchedAt: Long,
    /** True when the viewer pressed "Mark as watched", false when the player saw the end. */
    val manual: Boolean,
) {
    val key: String get() = SettingsStore.progressKey(chatId, messageId)

    fun toMediaItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = title,
        sizeBytes = sizeBytes,
        durationSec = durationSec,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = title,
    )

    internal fun encode(): String = listOf(
        watchedAt.toString(),
        if (manual) "m" else "a",
        fileId.toString(),
        title.replace(SEP, ' '),
        chatTitle.replace(SEP, ' '),
        sizeBytes.toString(),
        durationSec.toString(),
    ).joinToString(SEP.toString())

    companion object {
        /** Unit separator, for the reason [ResumeRecord] uses it: titles contain everything else. */
        private const val SEP = '\u001F'
        private const val FIELDS = 7

        fun of(item: MediaItem, chatTitle: String, watchedAt: Long, manual: Boolean) = WatchedRecord(
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            title = item.title,
            chatTitle = chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec,
            watchedAt = watchedAt,
            manual = manual,
        )

        /** Rebuilds a record, or null for a line this build cannot trust. */
        internal fun decode(ids: String, encoded: String?): WatchedRecord? {
            if (encoded.isNullOrEmpty()) return null
            val idParts = ids.split('_')
            if (idParts.size != 2) return null
            val chatId = idParts[0].toLongOrNull() ?: return null
            val messageId = idParts[1].toLongOrNull() ?: return null
            val parts = encoded.split(SEP)
            // A later build may append fields; everything up to here still reads the same.
            if (parts.size < FIELDS) return null
            return WatchedRecord(
                chatId = chatId,
                messageId = messageId,
                watchedAt = parts[0].toLongOrNull() ?: return null,
                manual = parts[1] == "m",
                fileId = parts[2].toIntOrNull() ?: 0,
                title = parts[3],
                chatTitle = parts[4],
                sizeBytes = parts[5].toLongOrNull() ?: 0L,
                durationSec = parts[6].toIntOrNull() ?: 0,
            )
        }
    }
}

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

    private fun <T> read(transform: (Preferences) -> T): Flow<T> = store.data
        .map(transform)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Every watched video keyed by [SettingsStore.progressKey], for the ticks on the grid. */
    val watched: Flow<Map<String, WatchedRecord>> = read { prefs ->
        decodeAll(prefs).associateBy { it.key }
    }

    /** The "Watched" list: most recently finished first. */
    val history: Flow<List<WatchedRecord>> = read { prefs ->
        decodeAll(prefs).sortedByDescending { it.watchedAt }
    }

    suspend fun isWatched(chatId: Long, messageId: Long): Boolean =
        store.data.first()[keyOf(chatId, messageId)] != null

    /**
     * Records [record] as watched. A manual mark is never downgraded to an automatic one: if the
     * viewer said so first, the player finishing it later keeps their word and the new time.
     */
    suspend fun markWatched(record: WatchedRecord) {
        store.edit { prefs ->
            val key = keyOf(record.chatId, record.messageId)
            val before = WatchedRecord.decode(record.key, prefs[key])
            val kept = if (before?.manual == true && !record.manual) record.copy(manual = true) else record
            prefs[key] = kept.encode()
            evictOldest(prefs)
        }
    }

    suspend fun markUnwatched(chatId: Long, messageId: Long) {
        store.edit { it.remove(keyOf(chatId, messageId)) }
    }

    /** Empties the list. Keys are collected first: the map cannot be walked while it is written. */
    suspend fun clear() {
        store.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }
            for (key in doomed) prefs.remove(key)
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
            PreferenceDataStoreFactory.createWithPath(produceFile = { file.absolutePath.toPath() })
    }
}
