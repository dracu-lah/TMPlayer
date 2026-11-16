package com.tmplayer.online

import java.io.File

/**
 * The viewer's intro marks ([UserSkipMarks]) in one small JSON file in the app's private storage.
 * A write goes to a temporary file first and is renamed over the old one, so a power cut leaves
 * the last good copy; a file that cannot be read or written is no marks, never a failure.
 */
class FileSkipMarks(private val file: File) : UserSkipMarks.Persistence {

    override fun read(): String? = runCatching { if (file.isFile && file.length() <= MAX_BYTES) file.readText() else null }.getOrNull()

    override fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 4_000_000L
    }
}
