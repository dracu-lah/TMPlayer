package com.tmplayer.platform

import com.tmplayer.data.DiskInfo
import java.io.File

/**
 * Where TDLib keeps its session and its cache, where the viewer's downloads go, and how much room
 * the disks under them have.
 *
 * On Android all three sit under the app's `filesDir`; on the desktop the database goes in the
 * per-user data directory, the cache in the cache one, and downloads in the system's Downloads
 * folder unless the viewer has chosen a storage location of their own.
 */
interface Paths {
    /** TDLib's database: the signed-in session. Losing it signs the viewer out. */
    val databaseDir: File

    /**
     * TDLib's files directory: the cache. Anything in here may be deleted by rule, by TDLib's own
     * clean up or by TMPlayer's; a download is moved out of it into [downloadsDir] once complete.
     */
    val filesDir: File

    /**
     * Where downloads are kept: TMPlayer's own, flat, and never deleted except by the viewer.
     * May not exist yet; whatever first writes a download there creates it.
     */
    val downloadsDir: File

    /** Free and total bytes on the volume [filesDir] lives on, or [DiskInfo.EMPTY] if unreadable. */
    fun disk(): DiskInfo

    /**
     * Free and total bytes on the volume [downloadsDir] is on, measured at its nearest existing
     * parent while it has not been created, or [DiskInfo.EMPTY] if unreadable.
     */
    fun downloadsDisk(): DiskInfo = DiskInfo.of(downloadsDir)
}

/** What TDLib tells Telegram about the device, shown in the viewer's list of active sessions. */
data class DeviceInfo(
    val model: String,
    val systemVersion: String,
    val appVersion: String,
)

/**
 * The Telegram API id and hash. Each app injects its own from its build configuration; a build
 * without them compiles and says so at sign in, which is what a fork or a CI build gets.
 */
data class Credentials(
    val apiId: Int,
    val apiHash: String,
) {
    val present: Boolean get() = apiId != 0
}
