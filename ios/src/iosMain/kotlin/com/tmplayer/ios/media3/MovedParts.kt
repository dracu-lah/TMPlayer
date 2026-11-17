package com.tmplayer.ios.media3

import android.net.Uri
import com.tmplayer.data.MovedContainer
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * TDLib keeps the full path of a file it has part of, and iOS gives the app a new folder when it is
 * updated or installed again (the container's name in the path changes), so after one TDLib can say
 * that hundreds of megabytes of a video are on the disk at a path that is not there any more.
 * Android's reader believes it, as it should on Android, where the folder never moves, and waits a
 * minute for bytes that never come before "Telegram stopped sending".
 *
 * So before a video is read, each Telegram file in it that TDLib has part of at a path that is gone
 * is dropped from TDLib's cache, and the download starts afresh as for a video never played. The
 * part itself moved with the app's folder, where nothing names it any more, so it is deleted there
 * too rather than left taking room. A file the viewer is downloading is left alone, as Android
 * leaves it (`Td.dropStalledDownload`): it is not the stream's to cancel.
 */
object MovedParts {
    private const val TAG = "MovedParts"
    private const val TD_FILE = "tdfile"

    /** Drops what TDLib only thinks it has of [uri]'s files. Blocks: called on the engine's reading thread. */
    fun clear(uri: Uri) {
        val ids = fileIds(uri)
        if (ids.isEmpty()) return
        try {
            runBlocking { ids.forEach { clearFile(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "could not check the files of $uri", e)
        }
    }

    private fun fileIds(uri: Uri): List<Int> = when (uri.scheme?.lowercase()) {
        TD_FILE -> listOfNotNull(uri.authority?.toIntOrNull())
        com.tmplayer.player.JOINED_SCHEME -> uri.getQueryParameters("p").mapNotNull { piece ->
            Uri.parse(piece).takeIf { it.scheme?.lowercase() == TD_FILE }?.authority?.toIntOrNull()
        }
        else -> emptyList()
    }

    /** [clear] for one Telegram file, for a reader that opens files by id (the engine's VideoStream). */
    suspend fun clearFile(fileId: Int) {
        val td = runCatching { Td.client }.getOrNull() ?: return
        val file = td.getFile(fileId).valueOrNull ?: return
        val local = file.local
        val path = local.path
        if (path.isBlank() || local.downloadedSize <= 0 || File(path).exists()) return
        if (OfflineDownloads.isDownloading(fileId)) {
            Logger.w(TAG, "file $fileId: TDLib's part is in a folder that is gone, but it is being downloaded; left alone")
            return
        }
        Logger.i(TAG, "file $fileId: TDLib's part of ${local.downloadedSize} bytes is in a folder that is gone; asking for it afresh")
        td.deleteFile(fileId)
        // The part itself moved with the app's folder: TDLib, which forgot it just now, could not
        // delete it at the old path, and nothing else names it, so it goes here or takes room for good.
        val left = MovedContainer.moved(path)?.let(::File)?.takeIf { it.isFile } ?: return
        if (!left.delete() && left.exists()) Logger.w(TAG, "file $fileId: the part left in the app's folder would not delete")
    }
}
