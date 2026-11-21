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
    /** The Downloads folder of the profile on a folder name (HX88, invariant 4: the owner's). */
    private val downloadsDir: (String?) -> File = { DesktopPaths.downloadsDirFor(it) },
    private val files: TdFiles = TdFiles.Live,
    private val availability: suspend (Int) -> LocalFileAvailability = { Td.localFileAvailability(it) },
    private val isOpen: (Int) -> Boolean = { com.tmplayer.desktop.player.ActiveStreams.isOpen(it) },
    private val notifier: () -> TransferNotifier = { NoTransferNotifier },
    /** The profile a run works for, taken when it starts (HX88, invariant 2). */
    private val owner: () -> com.tmplayer.data.AccountStamp = { com.tmplayer.data.Accounts.stamp },
    /** Whether that profile is still the open one, no switch under way (invariant 3). */
    private val mayAct: (com.tmplayer.data.AccountStamp) -> Boolean = { com.tmplayer.data.OwnedSessions.mayAct(it) },
) {
    /** What a run did, for the toast at the end. */
    data class Outcome(val moved: Int, val left: Int, val folder: File)

    private val lock = Mutex()

    /** Set by [stopForSwitch]: the run stops before its next file. */
    @Volatile
    private var switchStop = false

    /**
     * A [com.tmplayer.data.BeforeSwitch] hook (HX88, review D4): a run under way stops before its
     * next file and the switch waits for the one in hand, at most [waitMs].
     */
    suspend fun stopForSwitch(waitMs: Long = SWITCH_WAIT_MS) {
        if (!lock.isLocked) return
        switchStop = true
        try {
            kotlinx.coroutines.withTimeoutOrNull(waitMs) { lock.withLock { } }
        } finally {
            switchStop = false
        }
    }

    /**
     * Moves every complete legacy download into the Downloads folder.
     *
     * @return null when there was nothing to move, otherwise how many went and how many are left
     *   for another run (held by a player, or a move that failed).
     */
    suspend fun run(): Outcome? = lock.withLock {
        val by = owner()
        // As the profile it began for: a record written after a switch is dropped, except a moved
        // file's, which is addressed to it below.
        kotlinx.coroutines.withContext(com.tmplayer.data.AccountOwner(by)) { runFor(by) }
    }

    private suspend fun runFor(by: com.tmplayer.data.AccountStamp): Outcome? {
        fun ownerOpen() = !switchStop && mayAct(by)
        if (!ownerOpen()) return null
        if (runCatching { settings.downloadsMigratedNow() }.getOrDefault(true)) return null
        val legacy = runCatching { settings.downloadsNow() }.getOrDefault(emptyList()).filter { it.localPath == null }
        val dir = downloadsDir(by.folder)

        // Which of them can move now: resolved against this session, and whole.
        val ready = mutableListOf<Pair<ResumeRecord, Pair<Int, File>>>()
        for (record in legacy) {
            val id = runCatching { files.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
            if (runCatching { availability(id) }.getOrNull() != LocalFileAvailability.Complete) continue
            val path = runCatching { files.localPathAnyway(id) }.getOrNull() ?: continue
            ready += record to (id to File(path))
        }
        if (!ownerOpen()) return null
        if (ready.isEmpty()) {
            runCatching { settings.markDownloadsMigrated() }
            return null
        }

        val total = ready.sumOf { it.second.second.length() }
        var done = 0L
        var moved = 0
        var left = 0
        val note = notifier()
        com.tmplayer.desktop.os.ProfileOutside.asOwner(by) { note.begin(NOTIFY_ID, TransferNotifier.Kind.Migrate, L.downloadsMigratingToFolder(ready.size)) }
        for ((record, resolved) in ready) {
            val (id, src) = resolved
            // Its profile left (a switch, a sign out): the rest waits for its next launch.
            if (!ownerOpen()) {
                left++
                continue
            }
            if (isOpen(id)) {
                left++
                continue
            }
            val before = done
            try {
                val target = DownloadFiles.moveIntoDownloads(src, dir, DownloadFiles.safeName(record.title)) { landed, _ ->
                    note.progress(NOTIFY_ID, before + landed, total, null)
                }
                // The file is in the owner's folder now: its record is the owner's even after a switch.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + com.tmplayer.data.AccountAddress(by.folder)) {
                    settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                }
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                // TDLib's copy is told gone only on the owner's own client (invariant 3).
                if (mayAct(by)) runCatching { files.deleteFile(id) }
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
            com.tmplayer.desktop.os.ProfileOutside.asOwner(by) { note.complete(NOTIFY_ID, L.downloadsMoved, body, TransferNotifier.OpenTarget.Folder(dir.absolutePath)) }
        } else {
            note.cancel(NOTIFY_ID)
        }
        return Outcome(moved, left, dir)
    }

    companion object {
        private const val TAG = "DownloadMigration"
        private const val NOTIFY_ID = (1L shl 34) + 1
        private const val SWITCH_WAIT_MS = 60_000L
    }
}
