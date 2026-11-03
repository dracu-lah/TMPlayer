package com.tmplayer.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tmplayer.i18n.L
import com.tmplayer.platform.JobFailures
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Moving Android's downloads folder to a volume the viewer chose in Storage settings, or back to
 * the internal default (HX26). Unlike the desktop's move, TDLib's cache and database never move
 * here, so there is no restart to do and nothing of the watch cache to warn about: only the files
 * already in the Downloads folder, each handed to [DownloadFiles.moveIntoDownloads] (a rename on
 * one volume, a chunked, resumable copy across two), while the download queue is held
 * ([OfflineDownloads.pauseAll]) so nothing else writes into either folder meanwhile.
 *
 * Crash safe the way the desktop's move is: the new root and a pending note of what is still to
 * move are written down before the first file goes, so [resumePending] can finish the job on the
 * next launch if the app is killed partway, which covers "the app going to the background" too,
 * since Android may kill a backgrounded process doing heavy IO at any point. A [cancel] stops
 * after the file in progress (whose own `.part` is removed by [DownloadFiles.moveIntoDownloads] if
 * it was mid copy): nothing landed is undone, and pressing Move again, or simply reopening the
 * app, carries on from there, so the same files are never moved twice.
 */
object DownloadRelocation {

    /** A move in progress: bytes of downloads in place so far, of all there are. */
    data class Moving(val doneBytes: Long, val totalBytes: Long)

    private val _moving = MutableStateFlow<Moving?>(null)
    val moving: StateFlow<Moving?> = _moving.asStateFlow()

    /** How a move went, for the toast at the end. [label] is the volume's own name, as Settings shows it. */
    data class Outcome(
        val movedCount: Int,
        val failed: List<String>,
        val downloadsDir: File,
        val label: String,
        /** Why the move stopped before trying every file, or null when it tried them all. */
        val stoppedBy: Stop? = null,
    ) {
        val message: String
            get() = when {
                stoppedBy is Stop.NoSpace -> L.storageAndroidNoSpace(label, sizeText(stoppedBy.neededBytes))
                stoppedBy is Stop.VolumeGone -> L.storageAndroidCardGone(label)
                failed.isEmpty() -> L.storageAndroidMovedTo(label)
                failed.size == 1 -> L.storageMovedButOne(failed.first())
                else -> L.storageMovedButSome(failed.size)
            }
    }

    /** The first failure that makes every later file pointless, so the move stops there (HX57). */
    sealed interface Stop {
        /** The target filled up; [neededBytes] is how much more the rest of the move would take. */
        data class NoSpace(val neededBytes: Long) : Stop

        /** The card or drive (either end of the move) went away. */
        data object VolumeGone : Stop
    }

    private fun sizeText(bytes: Long): String = StorageRelocationPlan.size(bytes)

    /** Whether [e] is the file system saying the disk is full, in the words the JDK and Android use. */
    internal fun isNoSpace(e: IOException): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC") || text.contains("disk full", ignoreCase = true)
    }

    /**
     * The label of the volume a move is waiting for, or null when none is pending or it is
     * reachable (the move then just finishes at the next launch). Shown on the Storage row.
     */
    suspend fun waitingFor(context: Context, settings: SettingsStore): String? {
        val pending = StorageRelocationPlan.Pending.decode(settings.downloadRootPendingNow()) ?: return null
        val root = pending.root.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (AndroidStorage.isUsable(root)) return null
        return AndroidStorage.volumes(context).firstOrNull { it.root?.path == root.path }?.label ?: L.storageAndroidRemovable(1)
    }

    /** Drops a pending move for good; what already moved stays moved and the rest stays where it is. */
    suspend fun forgetPending(settings: SettingsStore) {
        settings.setDownloadRootPending("")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + JobFailures.handler(TAG))
    private val lock = Mutex()

    @Volatile private var activeJob: Job? = null

    /** Whether a move is running right now; the Settings row disables its trigger while this is true. */
    val running: Boolean get() = activeJob != null

    /** The volume in use, or null for the internal default. */
    fun currentRoot(): File? = AndroidStorage.currentRoot.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * Just the folder, nothing of what is already downloaded moved: new downloads go to [root]
     * from now on (null for the internal default), and the ones already on the device stay where
     * they are. HX26's "leave in place", and the one of the two always on offer, since it needs no
     * room for anything but the next download.
     */
    suspend fun useWithoutMoving(settings: SettingsStore, root: File?) {
        settings.setDownloadRoot(root?.path.orEmpty())
        AndroidStorage.currentRoot = root?.path.orEmpty()
    }

    /**
     * Starts moving every download already on the device into [root] (null for the internal
     * default) as well as switching new ones to it, in the background so leaving the screen or
     * turning the phone loses nothing. [onOutcome] and [onCancelled] are posted to the main
     * thread; neither is guaranteed to fire (the app may be killed first), which is exactly why
     * the move is resumable ([resumePending]) rather than relying on either being heard.
     *
     * @return false when one is already running.
     */
    fun start(
        context: Context,
        settings: SettingsStore,
        root: File?,
        label: String,
        onOutcome: (Outcome) -> Unit,
        onCancelled: () -> Unit,
    ): Boolean {
        if (activeJob != null) return false
        val app = context.applicationContext
        val job = scope.launch {
            lock.withLock {
                val outcome = try {
                    run(app, settings, root, label)
                } finally {
                    withContext(NonCancellable) { OfflineDownloads.resumeAll(app) }
                }
                withContext(Dispatchers.Main) { onOutcome(outcome) }
            }
        }
        job.invokeOnCompletion { cause ->
            activeJob = null
            if (cause is CancellationException) Handler(Looper.getMainLooper()).post(onCancelled)
        }
        activeJob = job
        return true
    }

    /**
     * Stops after the file in progress: what already landed stays landed, and the rest finishes
     * next time Move is pressed, or on its own the next time the app opens ([resumePending]).
     */
    fun cancel() {
        activeJob?.cancel()
    }

    /**
     * Finishes a move an earlier run began and did not complete. Called once at launch
     * ([com.tmplayer.App]), off the main thread; needs only the settings store, so it runs before
     * anything else is ready.
     */
    suspend fun resumePending(context: Context, settings: SettingsStore) {
        val pending = StorageRelocationPlan.Pending.decode(settings.downloadRootPendingNow()) ?: return
        if (!lock.tryLock()) return
        try {
            val app = context.applicationContext
            val root = pending.root.takeIf { it.isNotBlank() }?.let(::File)
            // A card that is not there: wait quietly. The row says so and offers to forget the move.
            if (root != null && !AndroidStorage.isUsable(root)) return
            val label = AndroidStorage.volumes(app).firstOrNull { it.root?.path == root?.path }?.label ?: L.storageAndroidInternal
            finish(app, settings, pending, root, label)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun run(context: Context, settings: SettingsStore, root: File?, label: String): Outcome {
        OfflineDownloads.pauseAll(context)
        awaitNothingMoving()
        val oldDownloadsDir = AndroidStorage.downloadsDir(context)
        val pending = StorageRelocationPlan.Pending(root = root?.path.orEmpty(), oldCache = "", oldDownloads = oldDownloadsDir.path)
        settings.setDownloadRootPending(pending.encode())
        settings.setDownloadRoot(root?.path.orEmpty())
        AndroidStorage.currentRoot = root?.path.orEmpty()
        return finish(context, settings, pending, root, label)
    }

    private suspend fun finish(
        context: Context,
        settings: SettingsStore,
        pending: StorageRelocationPlan.Pending,
        root: File?,
        label: String,
    ): Outcome {
        val to = if (root != null) AndroidStorage.downloadsDirOf(root) else File(context.filesDir, AndroidPaths.DOWNLOADS)
        to.mkdirs()
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val toMove = StorageRelocationPlan.toMove(records, to)
        val files = toMove.mapNotNull { record -> record.localPath?.let { record to File(it) } }
        val total = files.sumOf { (_, file) -> file.length() }
        var movedCount = 0
        val failed = mutableListOf<String>()
        var stoppedBy: Stop? = null
        if (files.isNotEmpty()) _moving.value = Moving(0, total)
        try {
            var done = 0L
            for ((record, src) in files) {
                val before = done
                val size = src.length().coerceAtLeast(0)
                try {
                    val target = DownloadFiles.moveIntoDownloads(src, to, src.name) { landed, _ ->
                        _moving.value = Moving(before + landed, total)
                    }
                    settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                    movedCount++
                } catch (e: IOException) {
                    // The record still points at the old place, which still plays from there.
                    Logger.w(TAG, "Could not move ${src.name} to the new downloads folder", e)
                    failed += src.name
                    // A full disk or a pulled card fails every later file the same slow way, so
                    // stop at the first and say why instead of trying them all.
                    stoppedBy = when {
                        isNoSpace(e) -> Stop.NoSpace((total - before - DiskSpace.read(context, to).freeBytes).coerceAtLeast(size))
                        (root != null && !AndroidStorage.isUsable(root)) || !src.exists() -> Stop.VolumeGone
                        else -> null
                    }
                    if (stoppedBy != null) break
                }
                done = before + size
            }
        } finally {
            _moving.value = null
        }
        if (failed.isEmpty()) {
            settings.setDownloadRootPending("")
            removeIfEmpty(File(pending.oldDownloads), to)
        }
        return Outcome(movedCount, failed, to, label, stoppedBy)
    }

    /**
     * Waits for a file [OfflineDownloads] is already moving into the current Downloads folder to
     * land, so the move below never races it for the same file; gives up after a while rather than
     * hang Settings on a move that is itself stuck (the file simply joins [toMove] next time).
     */
    private suspend fun awaitNothingMoving() {
        var waited = 0L
        while (
            OfflineDownloads.active.value.values.any { it.stage == OfflineDownloads.Stage.Moving } &&
            waited < AWAIT_MOVING_TIMEOUT_MS
        ) {
            delay(AWAIT_MOVING_STEP_MS)
            waited += AWAIT_MOVING_STEP_MS
        }
    }

    /** The old Downloads folder, if the move left it empty. Anything in it, and it stays. */
    private fun removeIfEmpty(old: File, to: File) {
        if (StorageRelocationPlan.inside(to, old) || StorageRelocationPlan.inside(old, to)) return
        if (old.isDirectory && old.list()?.isEmpty() == true) old.delete()
    }

    private const val TAG = "DownloadRelocation"
    private const val AWAIT_MOVING_STEP_MS = 200L
    private const val AWAIT_MOVING_TIMEOUT_MS = 30_000L
}
