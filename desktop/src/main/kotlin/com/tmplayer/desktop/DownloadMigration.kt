package com.tmplayer.desktop

import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TdFiles
import com.tmplayer.data.Td
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Moves the downloads from before downloads had a folder (B3.4): `dl_` records with no path, whose
 * files are still in TDLib's cache where any clean up could take them.
 *
 * Runs after sign in on every launch until it has nothing left to do, then marks itself done in
 * the settings store. Each file is recorded at its new path as it lands, so a run cut short by a
 * crash, a kill or a player holding one of the files simply carries on next time. A record whose
 * file is only part way there stays as it is: the Downloads page offers to finish it, and the queue
 * moves it once complete. One whose file is gone is left for the page to show as missing.
 *
 * @param files TDLib's side, behind the same seam the watch cache uses, so this can be tested.
 * @param availability whether TDLib has the whole file for an id.
 * @param isOpen whether a player has a file open; such a file is left for the next run.
 */
class DownloadMigration(
    private val settings: SettingsStore,
    private val downloadsDir: () -> File = { DesktopPaths.downloadsDir },
    private val files: TdFiles = TdFiles.Live,
    private val availability: suspend (Int) -> LocalFileAvailability = { Td.localFileAvailability(it) },
    private val isOpen: (Int) -> Boolean = { com.tmplayer.desktop.player.ActiveStreams.isOpen(it) },
    private val notifier: () -> TransferNotifier = { NoTransferNotifier },
) {
    /** What a run did, for the toast at the end. */
    data class Outcome(val moved: Int, val left: Int, val folder: File)

    private val lock = Mutex()

    /**
     * Moves every complete legacy download into the Downloads folder.
     *
     * @return null when there was nothing to move, otherwise how many went and how many are left
     *   for another run (held by a player, or a move that failed).
     */
    suspend fun run(): Outcome? = lock.withLock {
        if (runCatching { settings.downloadsMigratedNow() }.getOrDefault(true)) return null
        val legacy = runCatching { settings.downloadsNow() }.getOrDefault(emptyList()).filter { it.localPath == null }
        val dir = downloadsDir()

        // Which of them can move now: resolved against this session, and whole.
        val ready = mutableListOf<Pair<ResumeRecord, Pair<Int, File>>>()
        for (record in legacy) {
            val id = runCatching { files.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
            if (runCatching { availability(id) }.getOrNull() != LocalFileAvailability.Complete) continue
            val path = runCatching { files.localPathAnyway(id) }.getOrNull() ?: continue
            ready += record to (id to File(path))
        }
        if (ready.isEmpty()) {
            runCatching { settings.markDownloadsMigrated() }
            return null
        }

        val total = ready.sumOf { it.second.second.length() }
        var done = 0L
        var moved = 0
        var left = 0
        val note = notifier()
        note.begin(NOTIFY_ID, TransferNotifier.Kind.Migrate, L.downloadsMigratingToFolder(ready.size))
        for ((record, resolved) in ready) {
            val (id, src) = resolved
            if (isOpen(id)) {
                left++
                continue
            }
            val before = done
            try {
                val target = DownloadFiles.moveIntoDownloads(src, dir, DownloadFiles.safeName(record.title)) { landed, _ ->
                    note.progress(NOTIFY_ID, before + landed, total, null)
                }
                settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                runCatching { files.deleteFile(id) }
                moved++
            } catch (e: CancellationException) {
                note.cancel(NOTIFY_ID)
                throw e
            } catch (e: IOException) {
                Logger.w(TAG, "Could not move ${record.title} into Downloads; trying again next launch", e)
                left++
            }
            done = before + src.length().coerceAtLeast(0)
        }
        if (left == 0) runCatching { settings.markDownloadsMigrated() }
        val body = L.downloadsMigrated(moved, dir.toString())
        if (moved > 0) {
            note.complete(NOTIFY_ID, L.downloadsMoved, body, TransferNotifier.OpenTarget.Folder(dir.absolutePath))
        } else {
            note.cancel(NOTIFY_ID)
        }
        Outcome(moved, left, dir)
    }

    companion object {
        private const val TAG = "DownloadMigration"
        private const val NOTIFY_ID = (1L shl 34) + 1
    }
}
