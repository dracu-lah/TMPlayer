package com.tmplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The download index, read on Android: which videos are in TMPlayer's Downloads folder, where, and
 * whether the file is still there.
 *
 * Everything that asks "is this video on the device" asks here before it asks TDLib. A download
 * whose record has a path is a plain file this app owns: it plays from that path, it is shared
 * from that path, and it is deleted by deleting that path. TDLib was told to let go of it when it
 * was moved, so asking TDLib about it would only answer "nothing here".
 */
object LocalDownloads {

    /** What a download record amounts to on the disk, which decides how its row is drawn. */
    enum class FileState {
        /** In the Downloads folder, the size it was recorded at. */
        Present,

        /**
         * Recorded with a path, but the file is gone or shorter than it was: a cleanup app, a
         * file manager, or a drive that is no longer there. Shown as "File missing" with Remove,
         * never dropped quietly, so the viewer learns something took it.
         */
        Missing,

        /**
         * From before downloads had a folder: the file, if any, is still in TDLib's cache, and
         * TDLib is the one to ask about it.
         */
        Legacy,
    }

    /**
     * [FileState] from the record and what the disk says, kept apart from the disk so it can be
     * tested. [length] is null when there is no file at the path.
     */
    fun stateOf(localPath: String?, length: Long?, recordedSize: Long): FileState = when {
        localPath == null -> FileState.Legacy
        length == null || length <= 0 -> FileState.Missing
        // Shorter than recorded is a file cut off or replaced. Longer is let through: the size a
        // message advertises can be an estimate, and a whole file is not missing for exceeding it.
        recordedSize > 0 && length < recordedSize -> FileState.Missing
        else -> FileState.Present
    }

    /** [stateOf] against the real disk. */
    fun stateOf(record: ResumeRecord): FileState {
        val path = record.localPath ?: return FileState.Legacy
        val file = File(path)
        return stateOf(path, if (file.isFile) file.length() else null, record.sizeBytes)
    }

    /** The file of a download that is present, or null for anything else. */
    fun presentFile(record: ResumeRecord?): File? {
        val path = record?.localPath ?: return null
        return File(path).takeIf { stateOf(record) == FileState.Present }
    }

    /** The downloaded file for one message, if it is in the Downloads folder. */
    suspend fun fileFor(settings: SettingsStore, chatId: Long, messageId: Long): File? =
        withContext(Dispatchers.IO) {
            presentFile(runCatching { settings.downloadRecord(chatId, messageId) }.getOrNull())
        }

    /** [MediaItem.id] of every present download, for the grid to tell Downloaded from Cached. */
    suspend fun presentIds(settings: SettingsStore): Set<String> = withContext(Dispatchers.IO) {
        runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
            .filter { presentFile(it) != null }
            .map { "${it.chatId}:${it.messageId}" }
            .toSet()
    }

    /** What the files in the Downloads folder come to, measured on the disk. */
    suspend fun indexedBytes(settings: SettingsStore): Long = withContext(Dispatchers.IO) {
        runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
            .sumOf { record -> record.localPath?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L }
    }

    /**
     * A path for one video that another app can be handed: the download's own when it has one,
     * otherwise TDLib's complete cached copy, resolved against this session first. Null when
     * neither has the whole video.
     */
    suspend fun shareablePath(settings: SettingsStore, chatId: Long, messageId: Long, fileId: Int): String? {
        fileFor(settings, chatId, messageId)?.let { return it.absolutePath }
        val current = runCatching { Td.currentFileId(chatId, messageId, fileId) }.getOrDefault(fileId)
        return runCatching { Td.localFilePath(current) }.getOrNull()
    }

    /**
     * Deletes one indexed download: the file at its path, then the record. A file that will not go
     * keeps its record, so nothing is left on the disk that the app cannot name. A missing file has
     * nothing to delete and its record simply goes.
     *
     * @return whether the record went.
     */
    suspend fun delete(settings: SettingsStore, record: ResumeRecord): Boolean = withContext(Dispatchers.IO) {
        val path = record.localPath ?: return@withContext false
        val file = File(path)
        if (file.exists() && !file.delete() && file.exists()) return@withContext false
        runCatching { settings.forgetDownload(record.chatId, record.messageId) }.isSuccess
    }

    /**
     * Deletes one download of either kind, for "Remove after watching": a file in the Downloads
     * folder through [delete], and one from before downloads had a folder through TDLib, the way
     * the Downloads screen removes it. The record goes only once the bytes have.
     *
     * @return whether it went.
     */
    suspend fun deleteAnyKind(settings: SettingsStore, record: ResumeRecord): Boolean {
        if (record.localPath != null) return delete(settings, record)
        return withContext(Dispatchers.IO) {
            val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                .getOrDefault(record.fileId)
            runCatching { Td.deleteFile(fileId) }
            if (runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L) > 0) return@withContext false
            runCatching { settings.forgetDownload(record.chatId, record.messageId) }.isSuccess
        }
    }

    /**
     * Every download in the Downloads folder, deleted with its record, for "Also delete my
     * downloads" when signing out.
     *
     * @return how many went.
     */
    suspend fun deleteAll(settings: SettingsStore): Int {
        var removed = 0
        for (record in runCatching { settings.downloadsNow() }.getOrDefault(emptyList())) {
            if (record.localPath != null && delete(settings, record)) removed++
        }
        return removed
    }
}
