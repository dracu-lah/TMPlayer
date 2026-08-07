package com.tmplayer.data

import android.content.Context
import android.os.StatFs

/** Free and total bytes on the volume TDLib actually writes to. */
data class DiskInfo(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

object DiskSpace {

    /**
     * Measured on `filesDir`, not the external volume: that is where TDLib's database and cache
     * live, and on a TV stick the two can be different sizes.
     */
    fun read(context: Context): DiskInfo = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        DiskInfo(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrDefault(DiskInfo(0, 0))
}
