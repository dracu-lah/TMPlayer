package com.tmplayer.desktop

import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The desktop's reading of the downloads index: the `dl_` records in the settings store, each of
 * which says where its file is once it has been moved into the Downloads folder.
 *
 * Everything that asks "is this video here to play" asks this before TDLib, because a download is
 * no longer in TDLib's cache at all: it is a file in a folder TMPlayer owns, and plays from there
 * whether or not Telegram is reachable.
 */
object DownloadIndex {

    /** What a record's file is doing, which decides how its row reads and what it offers. */
    enum class FileState {
        /** In the Downloads folder, whole. */
        Present,

        /**
         * The record names a file that is not there, or is not the size it was. Shown as "File
         * missing" with Remove, never dropped quietly: the viewer should learn that a clean up tool
         * or an unplugged drive took it.
         */
        Missing,

        /** A download from before downloads had a folder, still wherever TDLib's cache put it. */
        Legacy,
    }

    /** Whether [record]'s file is where it says and the size it was, by [exists] and [length]. */
    fun state(
        record: ResumeRecord,
        exists: (File) -> Boolean = File::isFile,
        length: (File) -> Long = File::length,
    ): FileState {
        val path = record.localPath ?: return FileState.Legacy
        val file = File(path)
        if (!exists(file)) return FileState.Missing
        if (record.sizeBytes > 0 && length(file) != record.sizeBytes) return FileState.Missing
        return FileState.Present
    }

    /** The file of the download for this message, if there is one and it is whole. */
    suspend fun fileFor(settings: SettingsStore, chatId: Long, messageId: Long): File? = withContext(Dispatchers.IO) {
        val record = runCatching { settings.downloadRecord(chatId, messageId) }.getOrNull() ?: return@withContext null
        if (state(record) != FileState.Present) return@withContext null
        File(record.localPath!!)
    }

    /**
     * Deletes one download: the file, then the record. A legacy one is in TDLib's cache, so TDLib
     * deletes it there. The record goes even when the file was already missing; that is what
     * Remove on a "File missing" row means.
     *
     * @return false when the file is still on disk afterwards (locked by another program), and
     *   then the record is kept, so the bytes stay accounted for.
     */
    suspend fun delete(settings: SettingsStore, record: ResumeRecord): Boolean = withContext(Dispatchers.IO) {
        val path = record.localPath
        if (path != null) {
            val file = File(path)
            if (file.exists() && !file.delete() && file.exists()) {
                Logger.w(TAG, "Could not delete ${file.name}")
                return@withContext false
            }
        } else {
            val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
            runCatching { Td.deleteFile(id) }
        }
        runCatching { settings.forgetDownload(record.chatId, record.messageId) }
        true
    }

    /** What the downloads with a file of their own take on disk, measured rather than recorded. */
    fun bytesOnDisk(records: List<ResumeRecord>): Long =
        records.sumOf { r -> r.localPath?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L }

    /**
     * Where a legacy record's file is in TDLib's cache: complete, part way, or not there. Asked per
     * row, since only TDLib knows.
     */
    suspend fun legacyAvailability(record: ResumeRecord): LocalFileAvailability {
        val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
        return runCatching { Td.localFileAvailability(id) }.getOrDefault(LocalFileAvailability.Missing)
    }

    // ---- files in the folder that the index does not name (B3.3) --------------------------------

    private val _unlisted = MutableStateFlow<List<File>>(emptyList())

    /**
     * Files in the Downloads folder that no record names, from the last [scan]: left by an earlier
     * install, dropped in by hand, or adopted with a storage location TMPlayer used before.
     */
    val unlisted: StateFlow<List<File>> = _unlisted.asStateFlow()

    /** Looks through [dir] for files the index does not name, and publishes them to [unlisted]. */
    suspend fun scan(settings: SettingsStore, dir: File = DesktopPaths.downloadsDir): List<File> = withContext(Dispatchers.IO) {
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        unlistedIn(dir, records).also { _unlisted.value = it }
    }

    /**
     * The files directly in [dir] that none of [records] points at. Copies still in progress
     * (`.part`), hidden files and TMPlayer's own marker are not videos anybody put there.
     */
    fun unlistedIn(dir: File, records: List<ResumeRecord>): List<File> {
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        val known = records.mapNotNull { it.localPath }.map(::canonical).toSet()
        return files
            .filter { !it.name.startsWith(".") && !it.name.endsWith(DownloadFiles.PART_SUFFIX) }
            .filter { it.name != StorageRelocationPlan.MARKER && canonical(it.path) !in known }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Takes an unlisted file into the index as a download of no chat: it plays from the folder and
     * is deleted like any other, but cannot be fetched again from Telegram, since nothing says where
     * it came from. The message id is a hash of the path, which keeps two such files apart.
     */
    suspend fun keep(settings: SettingsStore, file: File) {
        settings.noteDownload(itemFor(file), chatTitle = "", localPath = file.absolutePath)
        _unlisted.value = _unlisted.value - file
    }

    /** Deletes an unlisted file from the folder. */
    suspend fun discard(file: File): Boolean = withContext(Dispatchers.IO) {
        val gone = !file.exists() || file.delete()
        if (gone) _unlisted.value = _unlisted.value - file
        gone
    }

    /** An unlisted file as the item the player and the index expect: chat 0, a path hash for an id. */
    fun itemFor(file: File) = MediaItem(
        chatId = 0L,
        messageId = file.absolutePath.hashCode().toLong() and 0x7FFFFFFFL,
        fileId = 0,
        title = file.name,
        sizeBytes = file.length(),
        durationSec = 0,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = file.name,
        onDevice = true,
    )

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    private const val TAG = "DownloadIndex"
}
