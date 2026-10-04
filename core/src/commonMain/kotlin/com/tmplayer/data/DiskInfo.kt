package com.tmplayer.data

import java.io.File

/** Free and total bytes on the volume TDLib actually writes to. */
data class DiskInfo(val freeBytes: Long, val totalBytes: Long) {
    companion object {
        /** What a screen shows before its first measurement has come back off the disk. */
        val EMPTY = DiskInfo(0, 0)

        /**
         * The volume [dir] is on, or would be on: a directory not made yet is measured at its
         * nearest parent that exists. [EMPTY] when nothing on the way can be read.
         */
        fun of(dir: File): DiskInfo = runCatching {
            var probe: File? = dir.absoluteFile
            while (probe != null && !probe.exists()) probe = probe.parentFile
            if (probe == null) EMPTY else DiskInfo(freeBytes = probe.usableSpace, totalBytes = probe.totalSpace)
        }.getOrDefault(EMPTY)
    }
}
