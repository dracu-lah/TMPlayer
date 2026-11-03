package com.tmplayer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * One downloaded video as it sits on the device (HX57): a file on the disk, or a document in a
 * folder the viewer picked ("Other folder"). The index keeps either as the record's `localPath`
 * (see [StoredLocation]); this is what the rest of the app asks instead of branching on that text.
 *
 * Everything here is safe to call for a video that is gone: a revoked folder permission, a pulled
 * card or a deleted file answers "not there" ([length] null), never an exception, so a missing
 * video shows as missing and plays from Telegram rather than taking a screen down.
 */
sealed interface LocalVideo {

    /** The file on the disk, or null for a document: the one thing a caller that needs a path must check. */
    val file: File?

    /** What a player, a share sheet or an external app opens. */
    fun uri(): Uri

    /** The size in bytes, or null when there is nothing there to read. */
    fun length(): Long?

    fun exists(): Boolean = (length() ?: -1L) >= 0L

    /** The file's name, for a name to give a copy of it. */
    fun name(): String

    /** A new stream over the whole video; the caller closes it. */
    fun open(): InputStream

    /**
     * Up to [length] bytes from [offset], or null when [offset] is at or past the end or the
     * video cannot be read. For the few readers that want a span, not the stream: the subtitle
     * hash, chapters.
     */
    fun readAt(offset: Long, length: Int): ByteArray?

    /** A plain file on the disk: the app's own Downloads folder, or a card's. */
    data class OnDisk(override val file: File) : LocalVideo {
        override fun uri(): Uri = Uri.fromFile(file)
        override fun length(): Long? = if (file.isFile) file.length() else null
        override fun name(): String = file.name
        override fun open(): InputStream = FileInputStream(file)

        override fun readAt(offset: Long, length: Int): ByteArray? = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (offset < 0 || offset >= raf.length()) return@use null
                val count = minOf(length.toLong(), raf.length() - offset).toInt()
                ByteArray(count).also { raf.seek(offset); raf.readFully(it) }
            }
        }.getOrNull()
    }

    /** A document inside a picked folder, reached through [resolver] under the folder's permission. */
    data class InTree(val document: Uri, private val resolver: ContentResolver) : LocalVideo {
        override val file: File? get() = null
        override fun uri(): Uri = document

        override fun length(): Long? = runCatching {
            resolver.query(document, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()

        override fun name(): String = runCatching {
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DownloadFiles.FALLBACK_NAME

        override fun open(): InputStream =
            resolver.openInputStream(document) ?: throw java.io.FileNotFoundException(document.toString())

        override fun readAt(offset: Long, length: Int): ByteArray? = runCatching {
            resolver.openFileDescriptor(document, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    if (offset < 0 || offset >= channel.size()) return@use null
                    val count = minOf(length.toLong(), channel.size() - offset).toInt()
                    val buffer = java.nio.ByteBuffer.allocate(count)
                    channel.position(offset)
                    while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return@use null
                    buffer.array()
                }
            }
        }.getOrNull()

        /** Removes the document; true when it is gone afterwards, including when it already was. */
        fun delete(): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false) || !exists()
    }

    companion object {
        /** The authority the manifest declares for handing a file in the app's own storage to another app. */
        private const val HANDOUT_SUFFIX = ".updates"

        /**
         * What another app (a share sheet, an external player) is given for the video at [path]:
         * a `FileProvider` link for a file on the disk, the document itself for one in the picked
         * folder (the app's grant on that folder lets it pass a read on). Null when the video is
         * gone or empty, so a stale press drops out instead of throwing.
         */
        fun handoutUri(context: Context, path: String): Uri? {
            val video = of(context, path) ?: return null
            if ((video.length() ?: 0L) <= 0L) return null
            return when (video) {
                is OnDisk -> runCatching {
                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + HANDOUT_SUFFIX, video.file)
                }.getOrNull()
                is InTree -> video.document
            }
        }

        /**
         * The video a record's [localPath] names, or null for no path. A document string that does
         * not parse becomes a document that is not there, never a crash.
         */
        fun of(context: Context?, localPath: String?): LocalVideo? {
            if (localPath.isNullOrBlank()) return null
            if (!StoredLocation.isDocument(localPath)) return OnDisk(File(localPath))
            val resolver = context?.applicationContext?.contentResolver ?: AndroidStorage.appContext?.contentResolver
                ?: return null
            val uri = runCatching { Uri.parse(localPath) }.getOrNull() ?: return null
            return InTree(uri, resolver)
        }
    }
}
