package com.tmplayer.data

import java.io.File
import java.util.Locale

/** Free and total bytes on the volume TDLib actually writes to. */
data class DiskInfo(val freeBytes: Long, val totalBytes: Long) {
    companion object {
        /** What a screen shows before its first measurement has come back off the disk. */
        val EMPTY = DiskInfo(0, 0)

        /**
         * "12.3 GB free", the figure a Download action carries so nobody starts a two gigabyte
         * fetch onto a phone with one gigabyte left. Counted in 1024s like every other size in the
         * app, so it agrees with the free figure on the Downloads screen.
         *
         * Null for a measurement that has not come back, or came back as nothing: "0 MB free"
         * from a disk that was never read would be a false alarm.
         */
        fun freeLabel(freeBytes: Long): String? = when {
            freeBytes <= 0 -> null
            freeBytes >= 100 * GB -> "${freeBytes / GB} GB free"
            freeBytes >= GB -> String.format(Locale.US, "%.1f GB free", freeBytes / GB.toDouble())
            freeBytes >= MB -> "${freeBytes / MB} MB free"
            else -> "Less than 1 MB free"
        }

        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB

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
