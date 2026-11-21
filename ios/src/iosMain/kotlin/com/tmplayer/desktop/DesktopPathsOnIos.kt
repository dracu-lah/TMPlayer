package com.tmplayer.desktop

import androidx.compose.ui.platform.IosAppContext
import com.tmplayer.data.AccountDirs
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.DiskInfo
import java.io.File

/**
 * The desktop's download runner (DesktopDownloads.kt, compiled for iOS as upstream wrote it) asks
 * these for its defaults: on iOS they are the app's own folders, as Android's AndroidPaths gives them.
 */
object DesktopPaths {
    val downloadsDir: File get() = AndroidPaths(IosAppContext).downloadsDir

    /**
     * The downloads folder of the profile on [folder] (HX88): the app's own for the profile adopted
     * in place (null), `downloads-<folder>` beside it for another, as on Android and the desktop.
     */
    fun downloadsDirFor(folder: String?): File = folder?.let { AccountDirs.beside(downloadsDir, it) } ?: downloadsDir

    fun disk(): DiskInfo = AndroidPaths(IosAppContext).disk()
}
