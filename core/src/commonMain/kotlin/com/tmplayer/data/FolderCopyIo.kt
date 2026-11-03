package com.tmplayer.data

import com.tmplayer.i18n.L
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The copying half of [FolderCopy], shared by the phone (a folder from the Storage Access
 * Framework) and the desktop (a plain directory): one loop over the videos, each written under a
 * free name and checked for size once written, with progress, a cancel that stops between
 * buffers, and a stop on a full drive or a folder that can no longer be written. A copy left part
 * written by any of those is deleted, so nothing half made stays in the folder. Blocking: the
 * caller runs it on [kotlinx.coroutines.Dispatchers.IO].
 */
object FolderCopyIo {

    /**
     * One finished download to copy: the file on the disk, the name it goes by, its title for the
     * viewer, and [key], the caller's own name for it (which a Move deletes by).
     */
    class Source(
        val displayName: String,
        val title: String,
        val mimeType: String,
        val key: String,
        /** The name on the disk, used when [displayName] gives nothing usable. */
        val fileName: String,
        private val lengthOrNull: () -> Long?,
        private val opener: () -> InputStream,
    ) {
        /** A video that is a plain file (desktop, TDLib's cache, an app folder). */
        constructor(file: File, displayName: String, title: String, mimeType: String, key: String = file.path) :
            this(displayName, title, mimeType, key, file.name, { if (file.isFile) file.length() else null }, { file.inputStream() })

        /** Its size, or null when it is gone. */
        fun length(): Long? = runCatching { lengthOrNull() }.getOrNull()

        fun exists(): Boolean = length() != null

        fun open(): InputStream = opener()
    }

    /** Where the copies go, as each platform reaches it. [H] is its handle on one new file. */
    interface Folder<H> {
        /** The folder's name, for the result's "saved to". */
        val label: String

        /** False for a folder that cannot be written at all, asked once before the first copy. */
        fun writable(): Boolean = true

        /** The names already in the folder, so a copy never takes one. */
        fun existing(): Collection<String>

        /** Room left on the folder's drive, where the platform can say; null when it cannot. */
        fun freeBytes(): Long? = null

        /** The largest file the folder's drive takes (FAT32's 4 GB), where known. */
        fun maxFileBytes(): Long? = null

        /** A new, empty file named [name] in the folder. */
        fun create(name: String, mimeType: String): H

        fun open(handle: H): OutputStream

        /** The written copy's size, to check it against the original; null when the folder will not say. */
        fun size(handle: H): Long?

        /** Removes a copy left part written by a cancel or a failure. */
        fun discard(handle: H)

        /** A copy written whole: where a folder hides new files until they are done (the gallery), shown now. */
        fun finish(handle: H) {}

        /** Whether [error] means the folder can no longer be written at all (gone, or its permission taken back). */
        fun lost(error: Throwable): Boolean
    }

    fun <H> copyAll(
        folder: Folder<H>,
        sources: List<Source>,
        forbidden: Int,
        unfinished: Int,
        cancelled: () -> Boolean,
        onProgress: (FolderCopy.Progress) -> Unit,
    ): FolderCopy.Outcome {
        val total = sources.size
        val failed = ArrayList<FolderCopy.Failure>()
        val saved = ArrayList<String>()
        val verified = ArrayList<String>()
        fun outcome(stop: FolderCopy.Stop? = null, notReached: Int = 0) =
            FolderCopy.Outcome(folder.label, saved.size, forbidden, unfinished, failed.toList(), stop, notReached, saved.toList(), verified.toList())

        val taken = try {
            if (!folder.writable()) return outcome(FolderCopy.Stop.ReadOnly, total)
            folder.existing().mapTo(HashSet()) { it.lowercase() }
        } catch (error: Exception) {
            return outcome(if (folder.lost(error)) FolderCopy.Stop.FolderLost else stopFor(error) ?: FolderCopy.Stop.FolderLost, total)
        }
        val maxBytes = runCatching { folder.maxFileBytes() }.getOrNull()
        val bytesTotal = sources.sumOf { it.length() ?: 0L }
        var bytesBefore = 0L
        for ((index, source) in sources.withIndex()) {
            if (index > 0) bytesBefore += sources[index - 1].length() ?: 0L
            if (cancelled()) return outcome(FolderCopy.Stop.Cancelled, total - index)
            onProgress(FolderCopy.Progress(index, total, source.title, 0f, bytesBefore, bytesTotal))
            // Asked again here, not when the list was made: the selection can change under a
            // long batch (a download deleted, or removed after watching).
            val length = source.length()
            if (length == null) {
                failed += FolderCopy.Failure(source.title, L.downloadsFileGone)
                continue
            }
            if (maxBytes != null && length > maxBytes) {
                failed += FolderCopy.Failure(source.title, L.downloadsSaveToFolderTooLarge)
                continue
            }
            // A drive that says it has too little room stops the batch before a byte is written.
            val free = runCatching { folder.freeBytes() }.getOrNull()
            if (free != null && free < length) return outcome(FolderCopy.Stop.NoSpace, total - index)

            val name = FolderCopy.freeName(FolderCopy.nameFor(source.displayName, source.fileName)) { it.lowercase() in taken }
            taken += name.lowercase()
            val handle = try {
                folder.create(name, source.mimeType.ifBlank { "video/*" })
            } catch (error: Exception) {
                when (val stop = classify(folder, error)) {
                    null -> failed += FolderCopy.Failure(source.title, reasonFor(error))
                    else -> return outcome(stop, total - index)
                }
                continue
            }
            val whole = length.coerceAtLeast(1L)
            var shown = -1
            val finished = try {
                folder.open(handle).use { out ->
                    source.open().use { input ->
                        pipe(input, out, cancelled) { bytes ->
                            // A step per percent, not per buffer: the dialog redraws on each.
                            val percent = (bytes * 100 / whole).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress(FolderCopy.Progress(index, total, source.title, percent / 100f, bytesBefore + bytes, bytesTotal))
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                runCatching { folder.discard(handle) }
                when (val stop = classify(folder, error)) {
                    null -> failed += FolderCopy.Failure(source.title, reasonFor(error))
                    else -> return outcome(stop, total - index)
                }
                continue
            }
            if (!finished) {
                runCatching { folder.discard(handle) }
                return outcome(FolderCopy.Stop.Cancelled, total - index)
            }
            when (runCatching { folder.size(handle) }.getOrNull()) {
                null -> {
                    runCatching { folder.finish(handle) }
                    saved += source.title
                }
                length -> {
                    runCatching { folder.finish(handle) }
                    saved += source.title
                    verified += source.key
                }
                // Short or long: not the video. Removed, and said, rather than left looking whole.
                else -> {
                    runCatching { folder.discard(handle) }
                    failed += FolderCopy.Failure(source.title, L.downloadsSaveToFolderIncomplete)
                }
            }
        }
        onProgress(FolderCopy.Progress(total, total, sources.lastOrNull()?.title.orEmpty(), 1f, bytesTotal, bytesTotal))
        return outcome()
    }

    /**
     * Copies [input] into [output] a buffer at a time, asking [cancelled] between buffers so a large
     * video stops at once, and telling [onBytes] how much has gone. False when it was cancelled.
     */
    fun pipe(input: InputStream, output: OutputStream, cancelled: () -> Boolean, onBytes: (Long) -> Unit): Boolean {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            if (cancelled()) return false
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            onBytes(total)
        }
        output.flush()
        return true
    }

    /** Whether an error ends the batch (and why), or only this one video (null). */
    private fun <H> classify(folder: Folder<H>, error: Throwable): FolderCopy.Stop? =
        if (runCatching { folder.lost(error) }.getOrDefault(false)) FolderCopy.Stop.FolderLost else stopFor(error)

    /** A full drive stops the batch: every video after this one would fail the same way. */
    private fun stopFor(error: Throwable): FolderCopy.Stop? =
        if (messages(error).any(FolderCopy::isNoSpace)) FolderCopy.Stop.NoSpace else null

    /** One video's failure in the viewer's words, FAT32's limit named as such. */
    private fun reasonFor(error: Throwable): String =
        if (messages(error).any(FolderCopy::isTooLarge)) L.downloadsSaveToFolderTooLarge else Failures.humanise(error)

    private fun messages(error: Throwable): Sequence<String?> = generateSequence(error) { it.cause }.take(8).map { it.message }

    private const val BUFFER = 1 shl 18
}
