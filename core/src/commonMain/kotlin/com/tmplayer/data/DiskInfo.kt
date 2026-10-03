package com.tmplayer.data

/** Free and total bytes on the volume TDLib actually writes to. */
data class DiskInfo(val freeBytes: Long, val totalBytes: Long) {
    companion object {
        /** What a screen shows before its first measurement has come back off the disk. */
        val EMPTY = DiskInfo(0, 0)
    }
}
