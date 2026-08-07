package com.tmplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("tmplayer")

private val CACHE_LIMIT_GB = intPreferencesKey("cache_limit_gb")

/** Where playback stopped, so the next launch can offer to continue. */
private fun resumeKey(chatId: Long, messageId: Long) =
    longPreferencesKey("resume_${chatId}_$messageId")

class SettingsStore(private val context: Context) {

    val cacheLimitGb = context.prefs.data.map { it[CACHE_LIMIT_GB] ?: DEFAULT_CACHE_LIMIT_GB }

    suspend fun cacheLimitBytes(): Long = cacheLimitGb.first().toLong() * BYTES_PER_GB

    suspend fun setCacheLimitGb(value: Int) {
        context.prefs.edit { it[CACHE_LIMIT_GB] = value }
    }

    suspend fun resumePosition(chatId: Long, messageId: Long): Long =
        context.prefs.data.first()[resumeKey(chatId, messageId)] ?: 0L

    suspend fun saveResumePosition(chatId: Long, messageId: Long, positionMs: Long) {
        context.prefs.edit { prefs ->
            val key = resumeKey(chatId, messageId)
            // Under a minute in, or basically finished — there is nothing worth resuming.
            if (positionMs < MIN_RESUME_MS) prefs.remove(key) else prefs[key] = positionMs
        }
    }

    suspend fun clearResumePosition(chatId: Long, messageId: Long) {
        context.prefs.edit { it.remove(resumeKey(chatId, messageId)) }
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT_GB = 1
        val CACHE_LIMIT_CHOICES = listOf(1, 2, 4)
        const val BYTES_PER_GB = 1024L * 1024L * 1024L
        const val MIN_RESUME_MS = 60_000L
        /** Anything within this of the end counts as watched. */
        const val END_MARGIN_MS = 30_000L
    }
}
