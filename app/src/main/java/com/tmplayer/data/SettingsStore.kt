package com.tmplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("tmplayer")

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
private val ASK_BEFORE_CLEARING = booleanPreferencesKey("ask_before_clearing")
private val INTRO_SEEN = booleanPreferencesKey("intro_seen")
private val JUMP_TO_FAVORITE = booleanPreferencesKey("jump_to_favorite")
private val DEFAULT_CHAT = longPreferencesKey("default_chat")

/** Where playback stopped, so the next launch can offer to continue. */
private fun resumeKey(chatId: Long, messageId: Long) =
    longPreferencesKey("resume_${chatId}_$messageId")

private fun durationKey(chatId: Long, messageId: Long) =
    longPreferencesKey("duration_${chatId}_$messageId")

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
     * Skip the chat list and go straight into a favourite when the answer is unambiguous.
     *
     * On by default: most people watch from one channel, and making them re-pick it on every
     * launch is a whole extra screen for no decision. Switchable off for anyone who doesn't.
     */
    val jumpToFavorite: Flow<Boolean> = context.prefs.data.map { it[JUMP_TO_FAVORITE] ?: true }

    suspend fun setJumpToFavorite(value: Boolean) {
        context.prefs.edit { it[JUMP_TO_FAVORITE] = value }
    }

    /** Which favourite to open when there are several. Zero means "no preference". */
    val defaultChatId: Flow<Long> = context.prefs.data.map { it[DEFAULT_CHAT] ?: 0L }

    suspend fun setDefaultChatId(chatId: Long) {
        context.prefs.edit { if (chatId == 0L) it.remove(DEFAULT_CHAT) else it[DEFAULT_CHAT] = chatId }
    }

    /**
     * The chat to open immediately, or null to show the browser.
     *
     * A single favourite is unambiguous. Several favourites need the viewer to have named one,
     * otherwise picking for them would be a guess.
     */
    fun autoOpenChatId(favorites: Set<Long>, jumpEnabled: Boolean, preferred: Long): Long? = when {
        !jumpEnabled -> null
        favorites.isEmpty() -> null
        favorites.size == 1 -> favorites.first()
        preferred in favorites -> preferred
        else -> null
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

    suspend fun saveResumePosition(
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long = 0L,
    ) {
        context.prefs.edit { prefs ->
            val key = resumeKey(chatId, messageId)
            // Under a minute in, or basically finished — there is nothing worth resuming.
            if (positionMs < MIN_RESUME_MS) {
                prefs.remove(key)
                prefs.remove(durationKey(chatId, messageId))
            } else {
                prefs[key] = positionMs
                if (durationMs > 0) prefs[durationKey(chatId, messageId)] = durationMs
            }
        }
    }

    suspend fun clearResumePosition(chatId: Long, messageId: Long) {
        context.prefs.edit {
            it.remove(resumeKey(chatId, messageId))
            it.remove(durationKey(chatId, messageId))
        }
    }

    companion object {
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
