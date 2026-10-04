package com.tmplayer.desktop

import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.platform.Logger
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moving TMPlayer's files to a storage location the viewer chose, or back to the default folders
 * (B2.3). The decisions are [StorageRelocationPlan]'s; this is the work, in this order:
 *
 * 1. Everything that reads TDLib's files stops: the queue, the player, the watch cache sweep.
 * 2. TDLib's cache is emptied through TDLib, so its database agrees it is empty. Copying the cache
 *    would be useless: TDLib does not know files copied into a new directory, and the stray sweep
 *    would delete them.
 * 3. The new root is written down, with a pending note of what is still to do, before anything
 *    moves; then the folders and the `.tmplayer` marker are made, TDLib is restarted over the new
 *    cache folder, and the old cache folder is deleted.
 * 4. Every download moves into the new Downloads folder, the index updated as each one lands, so
 *    a crash part way leaves an index that is right about every file. The pending note is cleared
 *    only when the last one is in, and [resumePending] finishes the job on the next launch if it
 *    never was. A file another program has locked is reported and tried again then, not fatal.
 * 5. The old Downloads folder goes if it is empty; nothing else is ever removed.
 *
 * @param layout the folders for a root, or for the default folders with null.
 * @param host the parts that touch the running app, so this can be tested over fakes.
 */
class StorageRelocation(
    private val settings: SettingsStore,
    private val prefs: DesktopPrefs,
    private val host: Host,
    private val layout: (File?) -> StorageLayout = { DesktopPaths.layout(it) },
    private val notifier: () -> TransferNotifier = { NoTransferNotifier },
    private val _moving: MutableStateFlow<Moving?> = MutableStateFlow(null),
) {
    /** The running app, as a move needs it. */
    interface Host {
        /** Stops the download queue (letting moves into Downloads finish), the player and the sweep. */
        suspend fun pause()

        /** Empties TDLib's cache through TDLib. */
        suspend fun clearCache()

        /** Points the app at [root] (null for the defaults) and restarts TDLib over its cache folder. */
        suspend fun restart(root: File?)

        /** Lets the queue and the sweep carry on. */
        suspend fun resume()
    }

    /** How a move went, for the toast at the end. */
    data class Outcome(
        val downloadsDir: File,
        val moved: Int,
        /** Files that could not be moved this time (locked, or the copy failed); tried again next launch. */
        val failed: List<String>,
    ) {
        val message: String
            get() = when {
                failed.isEmpty() -> "Storage moved to ${downloadsDir.parentFile ?: downloadsDir}"
                failed.size == 1 -> "Storage moved, but ${failed.first()} could not be. TMPlayer tries again next launch."
                else -> "Storage moved, but ${failed.size} downloads could not be. TMPlayer tries again next launch."
            }
    }

    /** A move in progress: bytes of downloads in place so far, of all there are. */
    data class Moving(val doneBytes: Long, val totalBytes: Long)

    /** Non null while downloads are moving, for the Settings row's progress. */
    val moving: StateFlow<Moving?> = _moving.asStateFlow()

    private val lock = Mutex()

    /** The storage location in use, or null for the default folders. */
    fun currentRoot(): File? = prefs.now.storageRoot.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * Moves everything to [root] (null for the default folders). The viewer has confirmed by now,
     * and [StorageRelocationPlan.validate] has allowed it.
     */
    suspend fun move(root: File?): Outcome = lock.withLock {
        val from = layout(currentRoot())
        val to = layout(root)
        host.pause()
        try {
            withContext(NonCancellable) {
                host.clearCache()
                val pending = StorageRelocationPlan.Pending(
                    root = root?.path.orEmpty(),
                    oldCache = from.cacheDir.path,
                    oldDownloads = from.downloadsDir.path,
                )
                prefs.update { it.copy(storageRoot = root?.path.orEmpty(), storageRootPending = pending.encode()) }
                prepare(root, to)
                host.restart(root)
                deleteOldCache(from.cacheDir, to)
                finish(pending, to)
            }
        } finally {
            withContext(NonCancellable) { host.resume() }
        }
    }

    /**
     * Finishes a move an earlier run began and did not complete. Called once at launch; needs the
     * settings store and nothing of TDLib's, so it can run while TDLib is still starting.
     *
     * @return null when nothing was pending.
     */
    suspend fun resumePending(): Outcome? = lock.withLock {
        val pending = StorageRelocationPlan.Pending.decode(prefs.now.storageRootPending) ?: return null
        val root = pending.root.takeIf { it.isNotBlank() }?.let(::File)
        val to = layout(root)
        withContext(NonCancellable) {
            prepare(root, to)
            deleteOldCache(File(pending.oldCache), to)
            finish(pending, to)
        }
    }

    /** The new folders, and the marker that says whose they are. */
    private suspend fun prepare(root: File?, to: StorageLayout) = withContext(Dispatchers.IO) {
        to.cacheDir.mkdirs()
        to.downloadsDir.mkdirs()
        to.updatesDir.mkdirs()
        if (root != null) {
            val marker = File(StorageRelocationPlan.appFolder(root), StorageRelocationPlan.MARKER)
            if (!marker.exists()) {
                runCatching { marker.writeText(markerText()) }
                    .onFailure { Logger.w(TAG, "Could not write ${marker.path}", it) }
            }
        }
    }

    /**
     * The cache folder before the move, with every file TDLib left in it (partials in `temp/`
     * included). Never the new one, and never anything holding the new downloads.
     */
    private suspend fun deleteOldCache(old: File, to: StorageLayout) = withContext(Dispatchers.IO) {
        if (!old.exists()) return@withContext
        val keep = listOf(to.cacheDir, to.downloadsDir, to.updatesDir)
        if (keep.any { StorageRelocationPlan.inside(it, old) || StorageRelocationPlan.inside(old, it) }) return@withContext
        if (!old.deleteRecursively()) Logger.w(TAG, "Some of the old cache at ${old.path} could not be deleted")
    }

    private suspend fun finish(pending: StorageRelocationPlan.Pending, to: StorageLayout): Outcome {
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val moving = StorageRelocationPlan.toMove(records, to.downloadsDir)
        val total = moving.sumOf { File(it.localPath!!).length() }
        val id = NOTIFY_ID
        val note = notifier()
        var done = 0L
        var moved = 0
        val failed = mutableListOf<String>()
        if (moving.isNotEmpty()) {
            note.begin(id, TransferNotifier.Kind.Relocate, "Moving downloads")
            _moving.value = Moving(0, total)
        }
        for (record in moving) {
            val src = File(record.localPath!!)
            val before = done
            try {
                val target = DownloadFiles.moveIntoDownloads(src, to.downloadsDir, src.name) { landed, _ ->
                    _moving.value = Moving(before + landed, total)
                    note.progress(id, before + landed, total, null)
                }
                settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                moved++
            } catch (e: IOException) {
                // Typically Windows: "The process cannot access the file" while a player elsewhere
                // has it. The record still points at the old place, which still plays.
                Logger.w(TAG, "Could not move ${src.name}", e)
                failed += src.name
            }
            done = before + src.length().coerceAtLeast(0)
        }
        _moving.value = null
        if (failed.isEmpty()) {
            prefs.update { it.copy(storageRootPending = "") }
            removeIfEmpty(File(pending.oldDownloads), to)
        }
        val outcome = Outcome(to.downloadsDir, moved, failed)
        if (moving.isNotEmpty()) {
            if (failed.isEmpty()) {
                note.complete(id, "Downloads moved", outcome.message, TransferNotifier.OpenTarget.Folder(to.downloadsDir.path))
            } else {
                note.fail(id, "Some downloads did not move", outcome.message, retryable = true)
            }
        }
        return outcome
    }

    /** The old Downloads folder, if TMPlayer's move left it empty. Anything in it, and it stays. */
    private suspend fun removeIfEmpty(old: File, to: StorageLayout) = withContext(Dispatchers.IO) {
        if (StorageRelocationPlan.inside(to.downloadsDir, old) || StorageRelocationPlan.inside(old, to.downloadsDir)) return@withContext
        if (old.isDirectory && old.list()?.isEmpty() == true) old.delete()
    }

    private fun markerText(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return "TMPlayer storage, created $date. TMPlayer manages the folders here; do not put your own files in them.\n"
    }

    private companion object {
        const val TAG = "StorageRelocation"
        const val NOTIFY_ID = (1L shl 34) + 2
    }
}
