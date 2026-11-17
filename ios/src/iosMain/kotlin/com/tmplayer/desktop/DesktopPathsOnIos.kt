package com.tmplayer.desktop

import androidx.compose.ui.platform.IosAppContext
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.DiskInfo
import java.io.File

/**
 * The desktop's download runner (DesktopDownloads.kt, compiled for iOS as upstream wrote it) asks
 * these for its defaults: on iOS they are the app's own folders, as Android's AndroidPaths gives them.
 */
object DesktopPaths {
    val downloadsDir: File get() = AndroidPaths(IosAppContext).downloadsDir
    fun disk(): DiskInfo = AndroidPaths(IosAppContext).disk()
}
