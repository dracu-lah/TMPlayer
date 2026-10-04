package com.tmplayer.data

import android.content.Context
import android.os.StatFs

object DiskSpace {

    /**
     * Measured on [dir], `filesDir` unless told otherwise, not the external volume: that is where
     * TDLib's database, its cache and the downloads live, and on a TV stick the two can be
     * different sizes. A [dir] not created yet is measured at its nearest existing parent.
     */
    fun read(context: Context, dir: java.io.File = context.filesDir): DiskInfo = runCatching {
        var probe: java.io.File? = dir.absoluteFile
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val stat = StatFs((probe ?: context.filesDir).absolutePath)
        DiskInfo(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrDefault(DiskInfo(0, 0))
}
