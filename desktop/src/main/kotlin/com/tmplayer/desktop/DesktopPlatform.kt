package com.tmplayer.desktop

import com.tmplayer.data.DiskInfo
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.platform.Paths
import net.harawata.appdirs.AppDirsFactory
import java.io.File

/**
 * Per user directories in each OS's own place (XDG on Linux, AppData on Windows, Library on
 * macOS). The TDLib session is data; its downloaded files are cache; settings are config.
 */
object DesktopPaths : Paths {
    private const val APP = "TMPlayer"
    private val dirs = AppDirsFactory.getInstance()

    val dataDir: File = File(dirs.getUserDataDir(APP, null, null)).apply { mkdirs() }
    val cacheDir: File = File(dirs.getUserCacheDir(APP, null, null)).apply { mkdirs() }
    val configDir: File = File(dirs.getUserConfigDir(APP, null, null)).apply { mkdirs() }

    /** Where libmpv and its friends are unpacked once, instead of a fresh temp folder per launch. */
    val nativeDir: File = File(dataDir, "natives").apply { mkdirs() }

    override val databaseDir: File = File(dataDir, "tdlib").apply { mkdirs() }
    override val filesDir: File = File(cacheDir, "tdlib-files").apply { mkdirs() }

    override fun disk(): DiskInfo = runCatching {
        DiskInfo(freeBytes = filesDir.usableSpace, totalBytes = filesDir.totalSpace)
    }.getOrDefault(DiskInfo.EMPTY)

    val settingsFile: File get() = File(configDir, com.tmplayer.data.SettingsStore.FILE_NAME)
}

fun desktopDeviceInfo(): DeviceInfo = DeviceInfo(
    model = "Desktop",
    systemVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
    appVersion = BuildInfo.VERSION,
)

fun desktopCredentials(): Credentials = Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH)
