package com.tmplayer.data

import kotlinx.coroutines.delay
import java.io.File
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
 * after it the record already points at the Downloads folder.
 *
 * @param downloadsDir where downloads go, asked each time in case the storage location moved.
 * @param completePath where TDLib has a file, only once all of it is there.
 * @param isPlaying whether a player has the file open, which holds the move: a player streaming
 *   the file would lose it, and moving an open file is refused outright on some file systems.
 * @param maySave whether Telegram still lets this message be kept, asked just before the move.
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
) {
    /** How a finish ended. */
    sealed interface Outcome {
        /** In the Downloads folder as [file], and recorded there. */
        data class Moved(val file: File) : Outcome

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
        val target = try {
            DownloadFiles.moveIntoDownloads(source, downloadsDir(), name) { done, total ->
                OfflineDownloads.moving(fileId, movedBytes = done)
                onProgress(done, total)
            }
        } catch (failure: IOException) {
            return Outcome.Failed(MOVE_FAILED)
        }

        // Recorded at the size it landed at, which is what the file is checked against later:
        // the size a message advertises can be an estimate.
        val item = request.item().copy(sizeBytes = target.length())
        settings.noteDownload(item, request.chatTitle, target.absolutePath)
        // Played before it was downloaded: the cache's record of it would have the next play
        // delete a file that is no longer the cache's.
        runCatching { settings.forgetCachedVideo(request.chatId, request.messageId) }
        // Gone from the cache path already; this keeps TDLib's own figures honest rather than
        // waiting for it to notice. Nothing asks TDLib to stream this file again: the index is
        // asked first everywhere.
        runCatching { td.deleteFile(fileId) }
        return Outcome.Moved(target)
    }

    companion object {
        /** How often a held move looks to see whether the player has gone. */
        const val POLL_MS = 500L

        const val NOT_FOUND = "The finished file could not be found. Try again."
        const val MOVE_FAILED = "Could not move it into Downloads. Try again."
    }
}
