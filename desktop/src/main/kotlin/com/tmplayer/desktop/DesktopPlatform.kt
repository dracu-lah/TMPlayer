package com.tmplayer.desktop

import com.tmplayer.data.DiskInfo
import com.tmplayer.desktop.os.UserDirs
import com.tmplayer.platform.CacheDirRename
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.platform.Paths
import net.harawata.appdirs.AppDirsFactory
import java.io.File

/** Where the three kinds of file TMPlayer keeps on the viewer's behalf go, for one storage root. */
data class StorageLayout(
    /** TDLib's files directory. Evictable. */
    val cacheDir: File,
    /** The viewer's downloads. Never evicted. */
    val downloadsDir: File,
    /** Self update packages while they are checked and installed. */
    val updatesDir: File,
)

/**
 * Per user directories in each OS's own place (XDG on Linux, AppData on Windows, Library on
 * macOS). The TDLib session is data; its cache is cache; settings are config; downloads go in the
 * OS's Downloads folder, under `TMPlayer`.
 *
 * The viewer may choose a storage location instead ([storageRoot], `storage_root` in
 * `desktop.properties`), and then the cache, the downloads and the update staging all live under
 * `<root>/TMPlayer/`. The session and the settings never move. The root is read once, here, before
 * TDLib starts; moving it while running is [useStorageRoot] followed by `Td.restart(DesktopPaths)`.
 */
object DesktopPaths : Paths {
    private const val APP = "TMPlayer"
    private val dirs = AppDirsFactory.getInstance()

    val dataDir: File = File(dirs.getUserDataDir(APP, null, null)).apply { mkdirs() }

    /** The OS's per user cache directory for TMPlayer, which holds the default [StorageLayout.cacheDir]. */
    val cacheDir: File = File(dirs.getUserCacheDir(APP, null, null)).apply { mkdirs() }
    val configDir: File = File(dirs.getUserConfigDir(APP, null, null)).apply { mkdirs() }

    /** Where libmpv and its friends are unpacked once, instead of a fresh temp folder per launch. */
    val nativeDir: File = File(dataDir, "natives").apply { mkdirs() }

    override val databaseDir: File = File(dataDir, "tdlib").apply { mkdirs() }

    val settingsFile: File get() = File(configDir, com.tmplayer.data.SettingsStore.FILE_NAME)

    /** The file [DesktopPrefs] keeps the desktop's own settings in. */
    val prefsFile: File get() = File(configDir, "desktop.properties")

    /**
     * The default cache, renamed from `tdlib-files` the first time this runs after an upgrade.
     * Lazy, so the rename happens on first use, which is TDLib starting.
     */
    private val defaultCacheDir: File by lazy {
        CacheDirRename.adopt(File(cacheDir, CacheDirRename.LEGACY_NAME), File(cacheDir, CacheDirRename.NAME))
    }

    /** `<OS Downloads>/TMPlayer`, asked of the OS once. */
    private val defaultDownloadsDir: File by lazy { File(UserDirs.downloads(), APP) }

    /** The folder the viewer chose, or null for the default folders. */
    @Volatile
    var storageRoot: File? = DesktopPrefs.read(prefsFile).storageRoot.takeIf { it.isNotBlank() }?.let(::File)
        private set

    /**
     * Points every getter here at [root] (or back at the defaults with null). Only the in memory
     * value: the caller writes `storage_root` to [DesktopPrefs] and restarts TDLib.
     */
    fun useStorageRoot(root: File?) {
        storageRoot = root
    }

    /**
     * The folders for [root]: `<root>/TMPlayer/{cache,downloads,updates}` for a chosen one, and the
     * OS's own places for null.
     */
    fun layout(root: File? = storageRoot): StorageLayout = if (root != null) {
        val base = File(root, APP)
        StorageLayout(
            cacheDir = File(base, CacheDirRename.NAME),
            downloadsDir = File(base, "downloads"),
            updatesDir = File(base, "updates"),
        )
    } else {
        StorageLayout(
            cacheDir = defaultCacheDir,
            downloadsDir = defaultDownloadsDir,
            updatesDir = File(cacheDir, "updates"),
        )
    }

    override val filesDir: File get() = layout().cacheDir.apply { mkdirs() }

    /** Not created here: a viewer who never downloads should not find an empty folder. */
    override val downloadsDir: File get() = layout().downloadsDir

    val updatesDir: File get() = layout().updatesDir

    override fun disk(): DiskInfo = DiskInfo.of(filesDir)
}

fun desktopDeviceInfo(): DeviceInfo = DeviceInfo(
    model = "Desktop",
    systemVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
    appVersion = BuildInfo.VERSION,
)

fun desktopCredentials(): Credentials = Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH)
