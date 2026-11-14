package com.tmplayer.player

import java.io.File

/**
 * The codec (a MIME type such as `video/hevc`) of the last video that played, in a one-line file,
 * so Send logs can list the decoders the device has for it (L5). A MIME type names no title, chat
 * or file. Never throws: a full disk keeps the old line.
 */
object LastVideoCodec {

    /** Longest line kept, and the alphabet a MIME type uses. */
    private const val MAX_CHARS = 64
    private val SAFE = Regex("""^[a-z0-9][a-z0-9.+\-]*/[a-z0-9][a-z0-9.+\-]*$""")

    /** [mime] cleaned to a MIME type, or null when it is not one. */
    fun clean(mime: String?): String? = mime?.trim()?.lowercase()?.take(MAX_CHARS)?.takeIf { SAFE.matches(it) }

    /** Writes [mime] to [file] unless it is already there or is not a MIME type. */
    fun remember(file: File, mime: String?) {
        val clean = clean(mime) ?: return
        runCatching {
            if (file.isFile && file.readText().trim() == clean) return
            file.parentFile?.mkdirs()
            file.writeText(clean)
        }
    }

    /** The MIME type kept, or null when none, or when the file holds anything else. */
    fun read(file: File): String? = runCatching { if (file.isFile) clean(file.readText().take(MAX_CHARS * 2)) else null }.getOrNull()
}
