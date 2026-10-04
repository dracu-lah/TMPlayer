package com.tmplayer.data

import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import java.io.File
import java.io.IOException

/**
 * Moves the downloads made before downloads had a folder of their own into it, once.
 *
 * Up to 1.19 a download was a file TDLib had finished, left in TDLib's cache with a `dl_` record
 * and no path. Those records are found here after sign in, and every one whose file is whole is
 * moved into the Downloads folder in the same order [DownloadFinisher] moves a new one: the file,
 * then the record's path, then the cache record, then TDLib. A record whose file is only partly
 * there stays as it is: the Downloads screen shows it as "Part downloaded" with Resume, and the
 * queue moves it when it finishes. A record with nothing on the disk stays too, and shows as
 * missing, so the viewer is told rather than finding it gone.
 *
 * Done once per install ([SettingsStore.downloadsMigratedNow]). A move that failed leaves the flag
 * unset, so the next launch tries the ones that are left; the ones that moved already have a path
 * and are not looked at again.
 */
class LegacyDownloads(
    private val settings: SettingsStore,
    private val downloadsDir: () -> File,
    private val td: TdFiles = TdFiles.Live,
    private val completePath: suspend (Int) -> String? = { Td.localFilePath(it) },
    private val notifier: TransferNotifier = NoTransferNotifier,
) {
    /** What a pass did. */
    data class Result(val moved: Int, val partial: Int, val failed: Int)

    /** [migrate], unless an earlier launch already finished it. Null when there was nothing to do. */
    suspend fun migrateOnce(): Result? {
        if (runCatching { settings.downloadsMigratedNow() }.getOrDefault(false)) return null
        return migrate()
    }

    /** One pass over every record without a path. Sets the done flag when nothing failed. */
    suspend fun migrate(): Result {
        val legacy = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
            .filter { it.localPath == null }

        // Resolved first, all of them, so the notification can say how many are moving before
        // the first one does.
        val whole = mutableListOf<Pair<ResumeRecord, Pair<Int, String>>>()
        var partial = 0
        for (record in legacy) {
            val fileId = runCatching { td.currentFileId(record.chatId, record.messageId, record.fileId) }
                .getOrDefault(record.fileId)
            val path = runCatching { completePath(fileId) }.getOrNull()
            when {
                path != null -> whole += record to (fileId to path)
                runCatching { td.localDownloadedBytes(fileId) }.getOrDefault(0L) > 0 -> partial++
            }
        }

        var moved = 0
        var failed = 0
        if (whole.isNotEmpty()) {
            notifier.begin(NOTIFICATION_ID, TransferNotifier.Kind.Migrate, title(whole.size))
        }
        for ((index, entry) in whole.withIndex()) {
            val (record, located) = entry
            val (fileId, path) = located
            val source = File(path)
            try {
                val target = DownloadFiles.moveIntoDownloads(
                    source,
                    downloadsDir(),
                    DownloadFiles.safeName(record.title, source.name),
                )
                settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                runCatching { td.deleteFile(fileId) }
                moved++
            } catch (_: IOException) {
                failed++
            }
            notifier.progress(NOTIFICATION_ID, (index + 1).toLong(), whole.size.toLong(), null)
        }
        if (whole.isNotEmpty()) {
            if (failed == 0) {
                notifier.complete(
                    NOTIFICATION_ID,
                    "Downloads moved",
                    if (moved == 1) "1 download is in $FOLDER." else "$moved downloads are in $FOLDER.",
                    TransferNotifier.OpenTarget.DownloadsScreen,
                )
            } else {
                notifier.fail(
                    NOTIFICATION_ID,
                    "Some downloads did not move",
                    "$failed of ${whole.size} are still in the cache. TMPlayer tries again next time.",
                    retryable = true,
                )
            }
        }
        if (failed == 0) runCatching { settings.markDownloadsMigrated() }
        return Result(moved = moved, partial = partial, failed = failed)
    }

    companion object {
        /** What the viewer is told the folder is called. */
        const val FOLDER = "TMPlayer's Downloads folder"

        /** The aggregate notification's id, clear of the download service's 4200 range. */
        const val NOTIFICATION_ID = 4100L

        fun title(count: Int): String =
            if (count == 1) "Moving 1 download into $FOLDER" else "Moving $count downloads into $FOLDER"

        /** The toast at the end, or null when nothing moved and there is nothing to say. */
        fun toast(result: Result?): String? = when {
            result == null || result.moved == 0 -> null
            result.moved == 1 -> "1 download moved into $FOLDER."
            else -> "${result.moved} downloads moved into $FOLDER."
        }
    }
}
