package com.tmplayer.platform

import com.tmplayer.data.DiskInfo
import java.io.File

/**
 * Where TDLib keeps its session and its files, and how much room the disk under them has.
 *
 * On Android both directories sit under the app's `filesDir`; on the desktop the database goes in
 * the per-user data directory and the files in the cache one.
 */
interface Paths {
    /** TDLib's database: the signed-in session. Losing it signs the viewer out. */
    val databaseDir: File

    /** TDLib's downloaded files: the cache, and the videos kept for offline. */
    val filesDir: File

    /** Free and total bytes on the volume [filesDir] lives on, or [DiskInfo.EMPTY] if unreadable. */
    fun disk(): DiskInfo
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
