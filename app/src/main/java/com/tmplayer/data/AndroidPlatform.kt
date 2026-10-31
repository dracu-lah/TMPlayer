package com.tmplayer.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tmplayer.BuildConfig
import com.tmplayer.platform.CacheDirRename
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.platform.LogSink
import com.tmplayer.platform.Paths
import java.io.File

// The Android answers to what `:core` asks of the platform it runs on. Each is the value the app
// used before the core was lifted out of it, so nothing a viewer has on disk moves, save the name
// of the cache directory, which [AndroidPaths] changes once.

/** Logcat, with the tags the shared code has always used. */
object AndroidLogSink : LogSink {
    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }
}

/**
 * Everything under `filesDir`: TDLib's session in `tdlib`, its cache in `cache` and the viewer's
 * downloads in `downloads`. Internal storage is one volume, so moving a finished download out of
 * the cache is always a rename.
 *
 * The cache was called `tdlib-files` up to 1.19. The first [AndroidPaths] built in a process
 * renames it, once, before anything can hand it to TDLib (see [CacheDirRename] for why the old
 * name is left as a link); every other place that needs the cache's location asks this class.
 */
class AndroidPaths(context: Context) : Paths {
    private val app = context.applicationContext
    override val databaseDir: File = File(app.filesDir, "tdlib")
    override val filesDir: File = cacheDir(app)

    /**
     * Asked fresh each time: a download may be moving to, or already living on, the volume
     * chosen in Storage settings ([AndroidStorage], HX26) rather than the internal default.
     * TDLib's own folders above never do; only this one moves.
     */
    override val downloadsDir: File get() = AndroidStorage.downloadsDir(app)
    override fun disk(): DiskInfo = DiskSpace.read(app, filesDir)
    override fun downloadsDisk(): DiskInfo = DiskSpace.read(app, downloadsDir)

    companion object {
        const val DOWNLOADS = "downloads"

        @Volatile
        private var adopted: File? = null

        /** TDLib's files directory, renamed from its old name the first time this is asked. */
        fun cacheDir(context: Context): File = adopted ?: synchronized(this) {
            adopted ?: CacheDirRename.adopt(
                legacy = File(context.applicationContext.filesDir, CacheDirRename.LEGACY_NAME),
                current = File(context.applicationContext.filesDir, CacheDirRename.NAME),
            ).also { adopted = it }
        }
    }
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

    @Volatile
    private var openedAt: File? = null

    /**
     * The one store for this process. Opened again only if the app's files folder is another one,
     * which never happens in the app; Robolectric gives each test its own folder and deletes it
     * after, and the store of the first test in a run was left writing into a folder that no
     * longer existed.
     */
    fun get(context: Context): DataStore<Preferences> {
        val file = File(context.applicationContext.filesDir, "datastore/${SettingsStore.FILE_NAME}")
        store?.takeIf { openedAt == file }?.let { return it }
        return synchronized(this) {
            store?.takeIf { openedAt == file } ?: SettingsStore.openDataStore(file).also {
                store = it
                openedAt = file
            }
        }
    }
}

/** Every screen builds its own [SettingsStore] from a context; they all share [SettingsFile]. */
fun SettingsStore(context: Context): SettingsStore = SettingsStore(SettingsFile.get(context))

/**
 * The watched list's own DataStore, opened once per process for the reason [SettingsFile] is:
 * `filesDir/datastore/watched.preferences_pb`, beside the settings and never inside them.
 */
private object WatchedFile {
    @Volatile
    private var store: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = store ?: synchronized(this) {
        store ?: WatchedStore.openDataStore(
            File(context.applicationContext.filesDir, "datastore/${WatchedStore.FILE_NAME}"),
        ).also { store = it }
    }
}

/** Every screen builds its own [WatchedStore] from a context; they all share [WatchedFile]. */
fun WatchedStore(context: Context): WatchedStore = WatchedStore(WatchedFile.get(context))

/** Watch later, which lives in [WatchedFile] beside the Watched list (see [WatchedStore.watchLater]). */
fun WatchLaterStore(context: Context): WatchLaterStore = WatchedStore(context).watchLater

/**
 * The bookmarks' own DataStore, opened once per process for the reason [WatchedFile] is:
 * `filesDir/datastore/bookmarks.preferences_pb`, beside the Watched list.
 */
private object BookmarkFile {
    @Volatile
    private var store: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = store ?: synchronized(this) {
        store ?: BookmarkStore.openDataStore(
            File(context.applicationContext.filesDir, "datastore/${BookmarkStore.FILE_NAME}"),
        ).also { store = it }
    }
}

/** Every screen builds its own [BookmarkStore] from a context; they all share [BookmarkFile]. */
fun BookmarkStore(context: Context): BookmarkStore = BookmarkStore(BookmarkFile.get(context))
