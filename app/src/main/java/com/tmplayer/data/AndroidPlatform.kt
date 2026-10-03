package com.tmplayer.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tmplayer.BuildConfig
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.platform.LogSink
import com.tmplayer.platform.Paths
import java.io.File

// The Android answers to what `:core` asks of the platform it runs on. Each is the value the app
// used before the core was lifted out of it, so nothing a viewer has on disk moves.

/** Logcat, with the tags the shared code has always used. */
object AndroidLogSink : LogSink {
    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }
}

/** TDLib under `filesDir`, where it has always been: `tdlib` and `tdlib-files`. */
class AndroidPaths(context: Context) : Paths {
    private val app = context.applicationContext
    override val databaseDir: File = File(app.filesDir, "tdlib")
    override val filesDir: File = File(app.filesDir, "tdlib-files")
    override fun disk(): DiskInfo = DiskSpace.read(app)
}

fun androidDeviceInfo(): DeviceInfo = DeviceInfo(
    model = Build.MODEL ?: "Android TV",
    systemVersion = Build.VERSION.RELEASE ?: "",
    appVersion = BuildConfig.VERSION_NAME,
)

fun buildCredentials(): Credentials = Credentials(
    apiId = BuildConfig.TG_API_ID,
    apiHash = BuildConfig.TG_API_HASH,
)

/** [Td.start] with everything Android supplies. Safe to call from every entry point. */
fun Td.start(context: Context) = start(AndroidPaths(context), androidDeviceInfo(), buildCredentials())

/**
 * The one DataStore for the process, at the path the `preferencesDataStore("tmplayer")` delegate
 * used: `filesDir/datastore/tmplayer.preferences_pb`.
 */
private object SettingsFile {
    @Volatile
    private var store: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = store ?: synchronized(this) {
        store ?: SettingsStore.openDataStore(
            File(context.applicationContext.filesDir, "datastore/${SettingsStore.FILE_NAME}"),
        ).also { store = it }
    }
}

/** Every screen builds its own [SettingsStore] from a context; they all share [SettingsFile]. */
fun SettingsStore(context: Context): SettingsStore = SettingsStore(SettingsFile.get(context))
