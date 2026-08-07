package com.tmplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("tmplayer")

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
private val ASK_BEFORE_CLEARING = booleanPreferencesKey("ask_before_clearing")
private val INTRO_SEEN = booleanPreferencesKey("intro_seen")
private val OPEN_LAST_CHAT = booleanPreferencesKey("open_last_chat")
private val DOWNLOAD_FIRST = booleanPreferencesKey("download_before_playing")
private val LAST_CHAT = longPreferencesKey("last_chat")
private val MIN_SIZE = longPreferencesKey("min_size_bytes")
private val MAX_SIZE = longPreferencesKey("max_size_bytes")
private val LOW_SPACE_DISMISSED_FREE = longPreferencesKey("low_space_dismissed_free_bytes")
private val CHAT_LAYOUT = stringPreferencesKey("chat_layout")
private val FILM_LAYOUT = stringPreferencesKey("film_layout")

/** Where playback stopped, so the next launch can offer to continue. */
private fun resumeKey(chatId: Long, messageId: Long) =
    longPreferencesKey("resume_${chatId}_$messageId")

private fun durationKey(chatId: Long, messageId: Long) =
    longPreferencesKey("duration_${chatId}_$messageId")

/** Title, chat and file id, so a half-watched film can be reopened without its chat loaded. */
private fun metaKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("meta_${chatId}_$messageId")

class SettingsStore(private val context: Context) {

    // ---- favourites -------------------------------------------------------------------------

    val favorites: Flow<Set<Long>> = context.prefs.data.map { prefs ->
        prefs[FAVORITES].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun toggleFavorite(chatId: Long): Boolean {
        var nowFavorite = false
        context.prefs.edit { prefs ->
            val current = prefs[FAVORITES].orEmpty().toMutableSet()
            val key = chatId.toString()
            nowFavorite = if (current.remove(key)) false else current.add(key)
            prefs[FAVORITES] = current
        }
        return nowFavorite
    }

    // ---- what opens on launch ---------------------------------------------------------------

    /**
     * Skip the chat list and reopen whichever chat was last watched.
     *
     * On by default: people come back to the same channel evening after evening, and the chat
     * list is a screen they pass through rather than one they want. This follows what the viewer
     * actually did last, so it needs no setting up and it keeps up when their habits change.
     */
    val openLastChat: Flow<Boolean> = context.prefs.data.map { it[OPEN_LAST_CHAT] ?: true }

    suspend fun setOpenLastChat(value: Boolean) {
        context.prefs.edit { it[OPEN_LAST_CHAT] = value }
    }

    /** The chat opened most recently, or zero when there has not been one yet. */
    val lastChatId: Flow<Long> = context.prefs.data.map { it[LAST_CHAT] ?: 0L }

    suspend fun rememberChatOpened(chatId: Long) {
        context.prefs.edit { it[LAST_CHAT] = chatId }
    }

    suspend fun forgetLastChat() {
        context.prefs.edit { it.remove(LAST_CHAT) }
    }

    /**
     * The chat to open immediately, or null to show the chat list.
     *
     * Read straight from disk rather than from a collected flow: this runs once, the moment the
     * chats arrive, and a flow that has not emitted yet would still be reporting its placeholder.
     */
    suspend fun autoOpenTarget(): Long? {
        val prefs = context.prefs.data.first()
        return autoOpenChatId(prefs[LAST_CHAT] ?: 0L, prefs[OPEN_LAST_CHAT] ?: true)
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * Wait for the whole film to arrive before starting it.
     *
     * Off by default, because streaming while it downloads is the point of the app. It is here for
     * a connection too slow or too unsteady to keep up with playback, where waiting once beats
     * being stopped every few minutes.
     */
    val downloadBeforePlaying: Flow<Boolean> = context.prefs.data.map { it[DOWNLOAD_FIRST] ?: false }

    suspend fun setDownloadBeforePlaying(value: Boolean) {
        context.prefs.edit { it[DOWNLOAD_FIRST] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun downloadBeforePlayingNow(): Boolean =
        context.prefs.data.first()[DOWNLOAD_FIRST] ?: false

    // ---- size filter ------------------------------------------------------------------------

    val minSizeBytes: Flow<Long> =
        context.prefs.data.map { it[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN }

    val maxSizeBytes: Flow<Long> =
        context.prefs.data.map { it[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX }

    suspend fun setMinSizeBytes(value: Long) {
        context.prefs.edit { prefs ->
            val max = prefs[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX
            prefs[MIN_SIZE] = SizeFilter.clampMin(value, max)
        }
    }

    suspend fun setMaxSizeBytes(value: Long) {
        context.prefs.edit { prefs ->
            val min = prefs[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN
            prefs[MAX_SIZE] = SizeFilter.clampMax(value, min)
        }
    }

    // ---- card layout ------------------------------------------------------------------------

    /**
     * How the chat sections are arranged.
     *
     * Rows by default: a chat is a name and a picture, and a name is what the viewer is reading.
     */
    val chatLayout: Flow<CardLayout> =
        context.prefs.data.map { CardLayout.decode(it[CHAT_LAYOUT], CardLayout.List) }

    suspend fun setChatLayout(value: CardLayout) {
        context.prefs.edit { it[CHAT_LAYOUT] = value.name }
    }

    /**
     * How a chat's films are arranged.
     *
     * Posters by default: this is the screen where artwork is what the viewer picks from, and four
     * across is a whole shelf of films rather than four rows of file names.
     */
    val filmLayout: Flow<CardLayout> =
        context.prefs.data.map { CardLayout.decode(it[FILM_LAYOUT], CardLayout.Grid) }

    suspend fun setFilmLayout(value: CardLayout) {
        context.prefs.edit { it[FILM_LAYOUT] = value.name }
    }

    // ---- prompts ----------------------------------------------------------------------------

    /** Whether to confirm before dropping the previous film to make room for a new one. */
    val askBeforeClearing: Flow<Boolean> = context.prefs.data.map { it[ASK_BEFORE_CLEARING] ?: true }

    suspend fun setAskBeforeClearing(value: Boolean) {
        context.prefs.edit { it[ASK_BEFORE_CLEARING] = value }
    }

    val introSeen: Flow<Boolean> = context.prefs.data.map { it[INTRO_SEEN] ?: false }

    suspend fun markIntroSeen() {
        context.prefs.edit { it[INTRO_SEEN] = true }
    }

    /**
     * Free space when the viewer last hid the low-space card, or [StorageWarning.NEVER_DISMISSED].
     *
     * Stored as a figure rather than a flag so the card can return once things get worse; see
     * [StorageWarning.shouldShow].
     */
    val lowSpaceDismissedAtFreeBytes: Flow<Long> =
        context.prefs.data.map { it[LOW_SPACE_DISMISSED_FREE] ?: StorageWarning.NEVER_DISMISSED }

    suspend fun dismissLowSpaceWarning(freeBytes: Long) {
        context.prefs.edit { it[LOW_SPACE_DISMISSED_FREE] = freeBytes }
    }

    /**
     * Forget a dismissal once there is comfortable room again.
     *
     * Without this, clearing several gigabytes and later filling them back up would be measured
     * against the old cramped figure, and the warning would stay quiet far past the point where
     * it was useful.
     */
    suspend fun clearLowSpaceDismissal() {
        context.prefs.edit { it.remove(LOW_SPACE_DISMISSED_FREE) }
    }

    // ---- resume -----------------------------------------------------------------------------

    suspend fun resumePosition(chatId: Long, messageId: Long): Long =
        context.prefs.data.first()[resumeKey(chatId, messageId)] ?: 0L

    /**
     * Every stored resume point, keyed by `chatId:messageId`, read in one pass.
     *
     * The grid needs a progress bar on every poster at once; asking DataStore per card would be
     * one disk read per item on a device with very little to spare.
     */
    val watchProgress: Flow<Map<String, WatchPoint>> = context.prefs.data.map { prefs ->
        buildMap {
            for ((key, value) in prefs.asMap()) {
                val name = key.name
                if (!name.startsWith("resume_")) continue
                val ids = name.removePrefix("resume_")
                val positionMs = value as? Long ?: continue
                val durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L
                put(ids, WatchPoint(positionMs, durationMs))
            }
        }
    }

    /**
     * Everything half-watched, most recent first: the "Continue watching" row.
     *
     * Entries without a stored description are skipped rather than guessed at: they were written
     * by a build before the description existed, so there is no title to put on the card.
     */
    val continueWatching: Flow<List<ResumeRecord>> = context.prefs.data.map { prefs ->
        buildList {
            for ((key, value) in prefs.asMap()) {
                val name = key.name
                if (!name.startsWith("resume_")) continue
                val ids = name.removePrefix("resume_")
                val positionMs = value as? Long ?: continue
                val record = ResumeRecord.decode(
                    key = ids,
                    encoded = prefs[stringPreferencesKey("meta_$ids")],
                    positionMs = positionMs,
                    durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
                ) ?: continue
                add(record)
            }
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun saveResumePosition(
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long = 0L,
        description: String? = null,
    ) {
        context.prefs.edit { prefs ->
            val key = resumeKey(chatId, messageId)
            // Under a minute in, or basically finished: there is nothing worth resuming.
            if (positionMs < MIN_RESUME_MS) {
                prefs.remove(key)
                prefs.remove(durationKey(chatId, messageId))
                prefs.remove(metaKey(chatId, messageId))
            } else {
                prefs[key] = positionMs
                if (durationMs > 0) prefs[durationKey(chatId, messageId)] = durationMs
                if (description != null) prefs[metaKey(chatId, messageId)] = description
            }
        }
    }

    /**
     * Forgets every half-watched film in one pass, emptying Continue watching.
     *
     * The keys are collected before anything is removed: [MutablePreferences] is being written to
     * while its own map is walked otherwise.
     */
    suspend fun clearWatchHistory() {
        context.prefs.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { key ->
                val name = key.name
                name.startsWith("resume_") || name.startsWith("duration_") || name.startsWith("meta_")
            }
            for (key in doomed) prefs.remove(key)
        }
    }

    /**
     * Drops half-watched entries that can no longer become a card, and says how many went.
     *
     * A resume position is written the moment playback starts, but the description beside it comes
     * from the film that was playing; a build that predates the description, a write interrupted by
     * the stick killing the app, or a file id that has since been revoked all leave a position on
     * disk that [continueWatching] can only skip. Skipping it every launch keeps a dead entry
     * forever, and it counts against nothing the viewer can see or clear. This is the sweep that
     * gets rid of them, and it uses the same decoder as the tab, so anything it deletes is exactly
     * what the tab could not show.
     */
    suspend fun pruneBrokenHistory(): Int {
        var removed = 0
        context.prefs.edit { prefs ->
            // Collected before anything is removed: [MutablePreferences] is being written to while
            // its own map is walked otherwise.
            val doomed = prefs.asMap().keys
                .map { it.name }
                .filter { it.startsWith("resume_") }
                .mapNotNull { name ->
                    val ids = name.removePrefix("resume_")
                    val positionMs = prefs[longPreferencesKey(name)] ?: return@mapNotNull ids
                    val record = ResumeRecord.decode(
                        key = ids,
                        encoded = prefs[stringPreferencesKey("meta_$ids")],
                        positionMs = positionMs,
                        durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
                    )
                    if (record == null) ids else null
                }

            for (ids in doomed) {
                prefs.remove(longPreferencesKey("resume_$ids"))
                prefs.remove(longPreferencesKey("duration_$ids"))
                prefs.remove(stringPreferencesKey("meta_$ids"))
                removed++
            }
        }
        return removed
    }

    suspend fun clearResumePosition(chatId: Long, messageId: Long) {
        context.prefs.edit {
            it.remove(resumeKey(chatId, messageId))
            it.remove(durationKey(chatId, messageId))
            it.remove(metaKey(chatId, messageId))
        }
    }

    companion object {
        /** Whether launch should skip the chat list, given what is remembered and the setting. */
        fun autoOpenChatId(lastChatId: Long, enabled: Boolean): Long? =
            if (enabled && lastChatId != 0L) lastChatId else null

        const val MIN_RESUME_MS = 60_000L

        /** Anything within this of the end counts as watched. */
        const val END_MARGIN_MS = 30_000L

        fun progressKey(chatId: Long, messageId: Long) = "${chatId}_$messageId"
    }
}

/** How far into a film the viewer got, and how long it runs. */
data class WatchPoint(val positionMs: Long, val durationMs: Long) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
