package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import java.io.File
import java.io.IOException

/**
 * How the settings, watched and bookmark stores are opened (HX10): a file that no longer parses
 * (cut short by a full disk or a power cut, or restored from an older backup) starts again empty
 * instead of throwing on every read, which on Android was a crash at every launch; and a disk that
 * cannot be read or written is logged and passed over rather than ending whatever asked. A write
 * that failed leaves the setting as it was, which is what the screen goes on showing.
 */
internal fun openSafeDataStore(file: File): DataStore<Preferences> = SafeDataStore(
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            Logger.w(TAG, "${file.name} could not be read, so it starts again empty", error)
            emptyPreferences()
        },
        produceFile = { file.absolutePath.toPath() },
    ),
)

/** [delegate] with its disk errors caught: see [openSafeDataStore]. */
internal class SafeDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {

    override val data: Flow<Preferences> = delegate.data.catch { error ->
        if (error !is IOException) throw error
        Logger.w(TAG, "settings could not be read", error)
        emit(emptyPreferences())
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        try {
            delegate.updateData(transform)
        } catch (error: IOException) {
            Logger.w(TAG, "settings could not be written", error)
            runCatching { delegate.data.first() }.getOrDefault(emptyPreferences())
        }
}

private const val TAG = "SafeDataStore"
