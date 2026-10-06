package com.tmplayer.online

import com.tmplayer.data.MediaName
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * A downloaded subtitle turned into what both players read: text, as UTF-8, in a format they
 * understand. Online files arrive in whatever code page their uploader saved them in, and SubDL's
 * arrive zipped, sometimes with a whole season inside.
 */
object SubtitleText {

    /** The text formats both players take as they stand. */
    val FORMATS = setOf("srt", "ass", "ssa", "vtt")

    /** The largest subtitle worth keeping: a feature film's are tens of kilobytes. */
    const val MAX_BYTES = 4 * 1024 * 1024

    data class Text(val extension: String, val text: String)

    /** UTF-8 when the bytes are valid UTF-8, and the Western Windows code page when they are not. */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
        return text.trimStart('﻿')
    }

    /** The extension of [fileName], in lower case, or srt when it names no format we take. */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in FORMATS } ?: "srt"

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * The subtitle inside a zip: the one whose name has the wanted [season] and [episode] when
     * those are known, else the first. Null when there is no subtitle in a format we take.
     */
    fun fromArchive(bytes: ByteArray, season: Int? = null, episode: Int? = null): Text? {
        val found = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in FORMATS) continue
                // Read by hand: InputStream.readNBytes is newer than the oldest Android we run on.
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (out.size() <= MAX_BYTES) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
                if (out.size() <= MAX_BYTES) found += name to out.toByteArray()
            }
        }
        if (found.isEmpty()) return null
        val pick = if (episode != null) {
            found.firstOrNull { (name, _) ->
                val parsed = MediaName.parse(name)
                parsed.episode == episode && (season == null || parsed.season == null || parsed.season == season)
            } ?: found.first()
        } else {
            found.first()
        }
        return Text(extensionOf(pick.first), decode(pick.second))
    }

    /** A downloaded body, zipped or not, as subtitle text. */
    fun from(bytes: ByteArray, fileName: String, season: Int? = null, episode: Int? = null): Text? = when {
        bytes.size > MAX_BYTES -> null
        isZip(bytes) -> runCatching { fromArchive(bytes, season, episode) }.getOrNull()
        else -> decode(bytes).takeIf { it.isNotBlank() }?.let { Text(extensionOf(fileName), it) }
    }
}
