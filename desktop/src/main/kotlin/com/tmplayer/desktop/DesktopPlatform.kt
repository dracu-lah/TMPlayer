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
) {
    /**
     * These folders as the profile on [folder] has them (HX88): its own cache and downloads beside
     * these, the update staging shared; unchanged for the profile adopted in place (null).
     */
    fun forProfile(folder: String?): StorageLayout = if (folder == null) {
        this
    } else {
        copy(
            cacheDir = com.tmplayer.data.AccountDirs.beside(cacheDir, folder),
            downloadsDir = com.tmplayer.data.AccountDirs.beside(downloadsDir, folder),
        )
    }
}

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

    /** The storage location saved in Settings, whether or not it can be used this run. */
    private val chosenRoot: File? = runCatching { DesktopPrefs.read(prefsFile).storageRoot }.getOrNull()
        ?.takeIf { it.isNotBlank() }?.let { File(com.tmplayer.desktop.os.LongPaths.clean(it)) }

    /**
     * The folder in use, or null for the default folders. A chosen location that cannot be used
     * at launch (a drive unplugged or gone, a folder removed or read only, #35) is left out for this
     * run, so TDLib starts over the defaults instead of failing to sign in with the drive's error
     * every time. The saved choice is kept and nothing is deleted: with the drive back, the next
     * launch uses it again. [unusableRoot] says which one was skipped.
     */
    @Volatile
    var storageRoot: File? = chosenRoot?.takeIf { StorageRootCheck.usable(it) }
        private set

    /** The chosen location skipped at launch because it could not be used, or null. See [storageRoot]. */
    val unusableRoot: File? = chosenRoot?.takeIf { storageRoot == null }

    private val unusableRootTold = java.util.concurrent.atomic.AtomicBoolean(false)

    /** [unusableRoot] the first time it is asked for in this run, then null, so the window says so once. */
    fun takeUnusableRootNotice(): File? = unusableRoot?.takeIf { unusableRootTold.compareAndSet(false, true) }

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
            // A folder too deep to hold a typical file name under 260 characters is reached with the long path prefix (WP-52).
            downloadsDir = com.tmplayer.desktop.os.LongPaths.io(File(base, "downloads"), reserve = com.tmplayer.desktop.os.LongPaths.NAME_RESERVE),
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

    /**
     * The open profile's downloads folder (HX88): [downloadsDir] for the profile adopted in place,
     * `<downloads>-<folder>` beside it for another. Where a download is written, listed or offered.
     */
    val profileDownloadsDir: File get() = downloadsDirFor(com.tmplayer.data.Accounts.active.folder)

    /** The downloads folder of the profile on [folder] (null: the adopted one), from its own folder name. */
    fun downloadsDirFor(folder: String?): File = folder?.let { com.tmplayer.data.AccountDirs.beside(downloadsDir, it) } ?: downloadsDir

    /** TDLib's files folder of the profile on [folder], the one its cache sweep walks (invariant 4). */
    fun filesDirFor(folder: String?): File = profileFilesDir(this, folder)

    /**
     * [layout] as the profile on [folder] has it: its own cache and downloads folders beside the
     * platform's, the update staging shared. What a storage move carries for the open profile only.
     */
    fun layoutFor(root: File?, folder: String?): StorageLayout = layout(root).forProfile(folder)

    /**
     * Where the trailer player (Chromium, about 350 to 470 MB unpacked) may live, preferred first:
     * `<root>/TMPlayer/browser` for a chosen storage location that is usable this run, then
     * `<data>/browser` beside the session. Not created here: nothing is written before the viewer
     * agrees to the download (trailer/EngineInstaller). A chosen location that is unplugged at
     * launch ([unusableRoot]) is not offered, and an install found in either place is used.
     */
    fun browserPlaces(): List<File> =
        listOfNotNull(storageRoot?.let { File(File(it, APP), "browser") }, File(dataDir, "browser"))

    override fun disk(): DiskInfo = DiskInfo.of(filesDir)
}

/**
 * TDLib's files folder of the profile on [folder] over the platform's [base] folders (HX88): the
 * platform's own for the profile adopted in place, `<files>-<folder>` beside it for another.
 */
internal fun profileFilesDir(base: Paths, folder: String?): File =
    com.tmplayer.data.AccountDirs.pathsFor(base, com.tmplayer.data.Profile(id = "sweep", folder = folder)).filesDir

fun desktopDeviceInfo(): DeviceInfo = DeviceInfo(
    model = "Desktop", // i18n-ok: the device name Telegram lists, not UI
    systemVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
    appVersion = BuildInfo.VERSION,
)

fun desktopCredentials(): Credentials = Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH)

/**
 * Whether a chosen storage location can hold TMPlayer's folders now (#35): the folder itself is
 * there, `<root>/TMPlayer` is there or can be made, and a file can be written in it.
 *
 * The chosen folder itself is never created: a missing one means a drive that is not plugged in or
 * not mounted, and making it would put the cache on whatever disk holds the empty mount point.
 * The probe file is removed straight away. Never throws.
 */
object StorageRootCheck {
    fun usable(root: File): Boolean = runCatching {
        if (!root.isDirectory) return false
        val base = com.tmplayer.data.StorageRelocationPlan.appFolder(root)
        if (!base.isDirectory && !base.mkdirs()) return false
        val probe = File.createTempFile(".tmplayer-probe", null, base)
        probe.delete()
        true
    }.getOrDefault(false)
}
