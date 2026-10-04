package com.tmplayer.data

import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Taking a finished file out of TDLib's cache and putting it in the Downloads folder, under a name
 * a person can read and every file system will accept.
 *
 * TDLib has no "keep this file" flag: anything in its files directory is cache, and its own GC or
 * TMPlayer's sweep may take it. A download is therefore a file moved out of there once it is
 * complete. On one volume that is a rename and costs nothing; across volumes it is a copy, done so
 * that a crash part way never leaves a file in Downloads that looks whole and is not.
 *
 * Shared by the Android service, the desktop runner and the desktop's storage relocation, and
 * kept free of TDLib so it can be tested against real directories.
 */
object DownloadFiles {

    /**
     * How much is copied per call when the move has to copy.
     *
     * Small enough that progress moves visibly on a slow USB drive, and a long way under the 2 GiB
     * that one `transferTo` call has historically been unable to cross on some platforms.
     */
    const val CHUNK_BYTES = 64L * 1024 * 1024

    /**
     * The longest name written, in bytes of UTF-8.
     *
     * NTFS, exFAT and ext4 all stop at 255; the margin is room for a " (2)" and a ".part".
     */
    const val MAX_NAME_BYTES = 200

    /** What a copy in progress is called, beside where the finished file will be. */
    const val PART_SUFFIX = ".part"

    /** The name used when neither the title nor the file name has anything usable left in it. */
    const val FALLBACK_NAME = "video"

    /**
     * Moves [src] into [downloadsDir] as [name], or as "[name] (2)" and so on when that is taken.
     *
     * When both are on one file store the file is renamed in place, atomically, and [onProgress]
     * hears once, with the whole size. Otherwise it is copied to `<name>.part` in chunks of
     * [CHUNK_BYTES], with [onProgress] told after each, flushed to the device, checked for size,
     * renamed to its final name and only then is [src] deleted. A failure or a cancellation part
     * way removes the part file and leaves [src] where it was, so the move can simply be asked for
     * again.
     *
     * @param name a name from [safeName]; it is not cleaned again here.
     * @param onProgress bytes in place so far, and the total.
     * @return the file as it now is, in [downloadsDir].
     * @throws IOException when [src] is missing or the move fails; [src] is untouched then.
     */
    suspend fun moveIntoDownloads(
        src: File,
        downloadsDir: File,
        name: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (!src.isFile) throw FileNotFoundException("Nothing to move at ${src.absolutePath}")
        if (!downloadsDir.isDirectory && !downloadsDir.mkdirs() && !downloadsDir.isDirectory) {
            throw IOException("Could not create ${downloadsDir.absolutePath}")
        }
        val total = src.length()
        val target = freeName(downloadsDir, name)

        if (sameStore(src, downloadsDir)) {
            try {
                Files.move(src.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                onProgress(total, total)
                return@withContext target
            } catch (_: AtomicMoveNotSupportedException) {
                // One store as far as Java can tell, but not one the OS will rename across: a bind
                // mount, or a FUSE file system that does not do renames. Copying still works.
            }
        }

        val part = File(downloadsDir, target.name + PART_SUFFIX)
        try {
            copyInChunks(src, part, total, onProgress)
            if (part.length() != total) {
                throw IOException("Copied ${part.length()} of $total bytes of ${src.name}")
            }
            renameInPlace(part, target)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(part.toPath()) }
            throw failure
        }
        // The download is whole and in place by now, so a source that will not go is logged rather
        // than thrown: failing here would report a finished download as a failed one. TDLib's own
        // delete, which the caller makes next, takes it anyway.
        if (!runCatching { Files.deleteIfExists(src.toPath()) }.getOrDefault(false) && src.exists()) {
            Logger.w(TAG, "Moved ${src.name} into Downloads but could not delete the original")
        }
        target
    }

    private suspend fun copyInChunks(src: File, part: File, total: Long, onProgress: (Long, Long) -> Unit) {
        FileChannel.open(src.toPath(), StandardOpenOption.READ).use { input ->
            FileChannel.open(
                part.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                var position = 0L
                var stalls = 0
                while (position < total) {
                    currentCoroutineContext().ensureActive()
                    val sent = input.transferTo(position, minOf(CHUNK_BYTES, total - position), output)
                    if (sent <= 0) {
                        // transferTo may legitimately return nothing once; a file that keeps
                        // returning nothing has shrunk under the copy, and looping on it would hang.
                        if (++stalls > MAX_STALLS) throw IOException("${src.name} stopped at $position of $total bytes")
                        continue
                    }
                    stalls = 0
                    position += sent
                    onProgress(position, total)
                }
                output.force(true)
            }
        }
    }

    /** Renames within one directory, atomically where the file system allows it. */
    private fun renameInPlace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    /**
     * Whether [a] and [b] are on the same file store, so that a move between them is a rename.
     *
     * Either may not exist yet; its nearest existing parent is asked instead. False whenever the
     * answer cannot be had, since assuming a copy is merely slower and assuming a rename can fail.
     */
    fun sameStore(a: File, b: File): Boolean = runCatching {
        val storeA = Files.getFileStore(existingAncestor(a).toPath())
        val storeB = Files.getFileStore(existingAncestor(b).toPath())
        storeA == storeB
    }.getOrDefault(false)

    private fun existingAncestor(file: File): File {
        var current: File? = file.absoluteFile
        while (current != null && !current.exists()) current = current.parentFile
        return current ?: file.absoluteFile
    }

    /**
     * A file name for a download, built from what the video is called.
     *
     * [title] is preferred, being what the viewer saw on the tile; [originalName], the name the
     * file had on Telegram, supplies the extension and stands in when the title is blank. Every
     * character Windows, macOS or Linux refuses in a name is replaced with a space, whitespace is
     * collapsed, leading and trailing dots and spaces go (Windows silently drops the trailing ones,
     * which would make two names the same), a reserved device name such as `CON` or `com1` gets an
     * underscore, and the whole is cut to [MAX_NAME_BYTES] of UTF-8 without splitting a character.
     */
    fun safeName(title: String, originalName: String = ""): String {
        // A title's last dot is as likely to be "S01E02.1080p" as an extension, so one is taken from
        // the title only when it is a container a video actually comes in.
        val extension = extensionOf(originalName)
            ?: extensionOf(title)?.takeIf { it.lowercase() in VIDEO_EXTENSIONS }
        val suffix = extension?.let { ".$it" }.orEmpty()
        val stemSource = title.ifBlank { originalName }
            .let { if (suffix.isNotEmpty() && it.endsWith(suffix, ignoreCase = true)) it.dropLast(suffix.length) else it }
        var stem = clean(stemSource)
        if (stem.isEmpty()) stem = clean(originalName.substringBeforeLast('.', originalName))
        if (stem.isEmpty()) stem = FALLBACK_NAME
        if (stem.substringBefore('.').trimEnd().uppercase() in RESERVED) stem += "_"
        stem = truncateUtf8(stem, MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
            .trimEnd(' ', '.')
            .ifEmpty { FALLBACK_NAME }
        return stem + suffix
    }

    /**
     * [name] in [dir] if nothing is there by that name, else the first free "name (2).ext",
     * "name (3).ext" and so on. A part file left by an interrupted copy counts as taken too, so a
     * new download never writes over one a resumed move is about to finish.
     */
    fun freeName(dir: File, name: String): File {
        fun taken(candidate: String) =
            File(dir, candidate).exists() || File(dir, candidate + PART_SUFFIX).exists()
        if (!taken(name)) return File(dir, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val suffix = name.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$suffix"
            if (!taken(candidate)) return File(dir, candidate)
            n++
        }
    }

    /** The extension, if the name has a plausible one: letters and digits, one to eight of them. */
    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1)
        return extension.takeIf { it.length <= 8 && it.all { c -> c.isLetterOrDigit() && c.code < 128 } }
    }

    private fun clean(raw: String): String = buildString {
        for (c in raw) append(if (c in FORBIDDEN || c.code < 0x20 || c.code == 0x7F) ' ' else c)
    }.replace(WHITESPACE, " ").trim(' ', '.')

    /** Cut to at most [maxBytes] of UTF-8, never through the middle of a character. */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val bytes = text.substring(i, i + chars).toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.append(text, i, i + chars)
            used += bytes
            i += chars
        }
        return out.toString()
    }

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp", "ogv",
    )

    private const val TAG = "DownloadFiles"
    private const val MAX_STALLS = 3
    private const val FORBIDDEN = "<>:\"/\\|?*"
    private val WHITESPACE = Regex("\\s+")

    /** Names Windows keeps for devices, with or without an extension after them. */
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
}
