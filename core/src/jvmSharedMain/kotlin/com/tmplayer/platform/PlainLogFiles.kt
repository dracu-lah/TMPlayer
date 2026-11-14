package com.tmplayer.platform

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.Volatile

/**
 * [LogFiles] on plain files, for Android and the desktop: `<name>.log` and `<name>.1.log` in
 * [dir]. Not a Windows-only or Android-only API, so one class serves both.
 *
 * One lock for the whole process guards every instance, so two instances over the same folder (a
 * service and an activity both opening the log) still take turns. Another process appending is
 * not locked out, but each write is one `write` call on a file opened for appending, which the
 * system keeps whole; a rotation racing it at worst loses a line.
 *
 * If the run before was killed in the middle of a line, the first append afterwards starts a new
 * line first, so the cut one never swallows the next.
 */
class PlainLogFiles(private val dir: File, name: String = "tmplayer-app") : LogFiles {

    private val current = File(dir, "$name.log")
    private val previous = File(dir, "$name.1.log")

    @Volatile
    private var tailChecked = false

    override fun <T> exclusive(block: () -> T): T = synchronized(LOCK) { block() }

    override fun currentSize(): Long = current.length()

    override fun append(text: String) {
        dir.mkdirs()
        var out = text
        if (!tailChecked) {
            if (endsMidLine(current)) out = "\n$out"
            tailChecked = true
        }
        FileOutputStream(current, true).use { it.write(out.toByteArray(Charsets.UTF_8)) }
    }

    override fun rotate() {
        if (!current.exists()) return
        previous.delete()
        if (!current.renameTo(previous)) {
            // A rename can fail across odd mounts: copy, then empty the current file.
            current.copyTo(previous, overwrite = true)
            current.delete()
        }
        tailChecked = true
    }

    override fun readPrevious(): String = read(previous)

    override fun readCurrent(): String = read(current)

    override fun deleteAll() {
        current.delete()
        previous.delete()
        tailChecked = false
    }

    private fun read(file: File): String = if (file.isFile) file.readText(Charsets.UTF_8) else ""

    private fun endsMidLine(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use {
            it.seek(it.length() - 1)
            it.read() != '\n'.code
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
