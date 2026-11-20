package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.JobFailures
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The last stage of a download: taking the finished file out of TDLib's cache and into the
 * Downloads folder, where nothing but the viewer deletes it.
 *
 * Run by [DownloadService] once TDLib says the file is whole, and by nothing else, so a video
 * reaches the Downloads list one way only. Kept free of the service, with TDLib and the player
 * behind parameters, so the order of what it does can be tested against real directories.
 *
 * The order is the point. The file moves first; the record is written with its new path only once
 * it is there; the cache record goes after that; TDLib is told last. A crash at any step leaves a
 * state the next attempt finishes from: before the move the file is still whole in the cache, and
 * after it the record already points at the Downloads folder. A record that cannot be written once
 * the file is in place is logged and the download counts as done: the file is the user's, and a
 * Try again would only find the cache empty (or make a second copy in a picked folder).
 *
 * @param downloadsDir where downloads go, asked each time in case the storage location moved.
 * @param completePath where TDLib has a file, only once all of it is there.
 * @param isPlaying whether a player has the file open, which holds the move: a player streaming
 *   the file would lose it, and moving an open file is refused outright on some file systems.
 * @param maySave whether Telegram still lets this message be kept, asked just before the move.
 * @param tree the folder the viewer picked for downloads (HX57), when one is chosen and can be
 *   reached right now; null otherwise, and the file goes to [downloadsDir] as it always did, which
 *   is also where a download lands while a picked folder cannot be reached.
 */
class DownloadFinisher(
    private val settings: SettingsStore,
    private val downloadsDir: () -> File,
    private val td: TdFiles = TdFiles.Live,
    private val completePath: suspend (Int) -> String? = { Td.localFilePath(it) },
    private val isPlaying: (Int) -> Boolean = WatchCache::isPlaying,
    private val maySave: suspend (chatId: Long, messageId: Long) -> Boolean = { chat, message ->
        Td.maySave(chat, message)
    },
    private val pollMs: Long = POLL_MS,
    private val tree: () -> TreeTarget? = { null },
    private val noteDownload: suspend (MediaItem, chatTitle: String, path: String) -> Unit =
        { item, chat, path -> settings.noteDownload(item, chat, path) },
) {
    /** A picked folder as the finisher writes to it; [DownloadService] wires the real one. */
    interface TreeTarget {
        /** The folder's name for a message. */
        val label: String

        /** Room left on the folder's drive, or null when it cannot be asked. */
        fun freeBytes(): Long?

        /** Copies [source] in under [name], checked whole, and returns the new document's string. */
        suspend fun put(source: File, name: String, onProgress: (done: Long, total: Long) -> Unit): String
    }

    /** How a finish ended. */
    sealed interface Outcome {
        /** In the Downloads folder as [file], and recorded there. */
        data class Moved(val file: File) : Outcome

        /** In the picked folder as the document [uri], and recorded there. */
        data class MovedToTree(val uri: String) : Outcome

        /** Still in the cache, untouched, with [reason] for the row. Try again repeats the move. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Waits for any player to let go of the file, then moves it into the Downloads folder.
     *
     * [onHeld] is called once if the move has to wait, so the notification can say "Finishes when
     * playback stops"; [onProgress] hears the bytes in place so far. The row in [OfflineDownloads]
     * is kept in [OfflineDownloads.Stage.Moving] throughout.
     */
    suspend fun finish(
        request: DownloadRequest,
        onHeld: () -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Outcome = try {
        finishUnsafe(request, onHeld, onProgress)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // HX69: this used to catch IOException only, so a SecurityException from a revoked folder,
        // an IllegalStateException from a provider or an OutOfMemoryError from a copy went on to
        // the service's scope and ended the process. Every failure now ends as a row that says
        // what happened, with the file still whole in the cache for Try again.
        failed(request, failure)
    }

    private suspend fun finishUnsafe(
        request: DownloadRequest,
        onHeld: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Outcome {
        val fileId = request.fileId
        if (isPlaying(fileId)) {
            OfflineDownloads.moving(fileId, heldByPlayer = true)
            onHeld()
            while (isPlaying(fileId)) delay(pollMs)
        }
        OfflineDownloads.moving(fileId)

        // Asked again at the last moment, since a channel can turn on "restrict saving content"
        // while its video sits in the queue. Refused, the file stays where it is: TDLib's cache,
        // which treats it like any watched video and evicts it in time.
        if (!maySave(request.chatId, request.messageId)) {
            return Outcome.Failed(ContentProtection.NOT_SAVABLE)
        }

        val path = runCatching { completePath(fileId) }.getOrNull()
            ?: return Outcome.Failed(NOT_FOUND)
        val source = File(path)
        val name = DownloadFiles.safeName(request.title, request.fileName.ifBlank { source.name })
        tree()?.let { picked -> return finishIntoTree(request, fileId, source, name, picked, onProgress) }
        val target = DownloadFiles.moveIntoDownloads(source, downloadsDir(), name) { done, total ->
            OfflineDownloads.moving(fileId, movedBytes = done)
            onProgress(done, total)
        }

        // Recorded at the size it landed at, which is what the file is checked against later:
        // the size a message advertises can be an estimate.
        record(request.item().copy(sizeBytes = target.length()), request, target.absolutePath)
        runCatching { SupportReminder.downloadFinished() }
        // Played before it was downloaded: the cache's record of it would have the next play
        // delete a file that is no longer the cache's.
        runCatching { settings.forgetCachedVideo(request.chatId, request.messageId) }
        // Gone from the cache path already; this keeps TDLib's own figures honest rather than
        // waiting for it to notice. Nothing asks TDLib to stream this file again: the index is
        // asked first everywhere.
        // Only for the profile whose file it is (HX88).
        if (runCatching { OwnedSessions.ensure() }.isSuccess) runCatching { td.deleteFile(fileId) }
        return Outcome.Moved(target)
    }

    /**
     * Writes the download's record, once more if the store fails the first time. A second failure
     * is logged and swallowed, since the file is already in place and reporting the download as
     * failed would point Try again at a cache that no longer holds it.
     */
    private suspend fun record(item: MediaItem, request: DownloadRequest, path: String) {
        try {
            noteDownload(item, request.chatTitle, path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (first: Exception) {
            delay(pollMs)
            try {
                noteDownload(item, request.chatTitle, path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (second: Exception) {
                JobFailures.handle("DownloadFinisher.record", second)
            }
        }
    }

    /**
     * The row's sentence for [failure], and the log of it. The move that failed has already taken
     * its own part file away ([DownloadFiles.moveIntoDownloads], [DownloadTree.put]), so what is
     * left is the whole file in the cache and a card that offers Try again.
     *
     * @param inTree whether the failed copy was into a picked folder.
     * @param sizeBytes the size the picked folder was asked to take.
     * @param label the picked folder's name for the full-drive sentence, null when it is unknown.
     */
    private fun failed(
        request: DownloadRequest,
        failure: Throwable,
        inTree: Boolean = false,
        sizeBytes: Long = 0L,
        label: String? = null,
    ): Outcome.Failed {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val reason = when {
            failure is OutOfMemoryError -> MOVE_FAILED_MEMORY
            chain.any { it is SecurityException } -> MOVE_FAILED_PERMISSION
            chain.any { it is IOException && DownloadRelocation.isNoSpace(it) } ->
                if (!label.isNullOrBlank()) L.storageAndroidNoSpace(label, StorageRelocationPlan.size(sizeBytes)) else L.errorsNoSpace
            failure is FileNotFoundException && !inTree -> NOT_FOUND
            failure is IOException -> MOVE_FAILED
            else -> MOVE_FAILED_UNEXPECTED
        }
        if (reason == MOVE_FAILED_UNEXPECTED) {
            // Not a disk, a folder or the memory refusing, so a bug or a device quirk: kept for
            // Send logs and, when the viewer allows it, reported. Nothing here ends the process.
            JobFailures.handle("DownloadFinisher", failure)
        } else {
            runCatching { Logger.w(TAG, "Could not finish ${request.fileId}: ${failure.message}", failure) }
        }
        return Outcome.Failed(reason)
    }

    /**
     * [finish]'s second half when downloads go to a picked folder: the copy is written and checked
     * before anything else changes, the record then holds the document, and only then are the
     * cache's file and TDLib's copy let go. A failure of the copy leaves the file whole in the cache
     * for Try again, and the folder without a partial copy; a record that cannot be written after
     * the copy is logged and the copy counts as done (see [record]).
     */
    private suspend fun finishIntoTree(
        request: DownloadRequest,
        fileId: Int,
        source: File,
        name: String,
        picked: TreeTarget,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Outcome {
        val size = source.length()
        val free = runCatching { picked.freeBytes() }.getOrNull()
        if (free != null && free < size) {
            return Outcome.Failed(L.storageAndroidNoSpace(picked.label, StorageRelocationPlan.size(size - free)))
        }
        val uri = try {
            picked.put(source, name) { done, total ->
                OfflineDownloads.moving(fileId, movedBytes = done)
                onProgress(done, total)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A source that vanished mid copy is the file missing, not the folder.
            if (failure is FileNotFoundException && !source.exists()) return Outcome.Failed(NOT_FOUND)
            return failed(request, failure, inTree = true, sizeBytes = size, label = runCatching { picked.label }.getOrNull())
        }
        record(request.item().copy(sizeBytes = size), request, uri)
        runCatching { SupportReminder.downloadFinished() }
        runCatching { settings.forgetCachedVideo(request.chatId, request.messageId) }
        // Only for the profile whose file it is (HX88).
        if (runCatching { OwnedSessions.ensure() }.isSuccess) runCatching { td.deleteFile(fileId) }
        runCatching { source.delete() }
        return Outcome.MovedToTree(uri)
    }

    companion object {
        /** How often a held move looks to see whether the player has gone. */
        const val POLL_MS = 500L

        val NOT_FOUND: String get() = L.downloadsFinishedFileMissing
        val MOVE_FAILED: String get() = L.downloadsMoveFailed
        val MOVE_FAILED_MEMORY: String get() = L.downloadsMoveFailedMemory
        val MOVE_FAILED_PERMISSION: String get() = L.downloadsMoveFailedPermission
        val MOVE_FAILED_UNEXPECTED: String get() = L.downloadsMoveFailedUnexpected
        private const val TAG = "DownloadFinisher"
    }
}
