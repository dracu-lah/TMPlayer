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

    /**
     * Where downloads go (HX57): the app's own folder, a volume's app folder (HX26), or a folder the
     * viewer picked ("Other folder"). [key] is what [SettingsStore.downloadRoot] holds.
     */
    sealed interface Destination {
        val key: String

        data object Internal : Destination {
            override val key: String = ""
        }

        data class Volume(val root: File) : Destination {
            override val key: String get() = root.path
        }

        data class Tree(val uri: android.net.Uri) : Destination {
            override val key: String get() = uri.toString()
        }

        companion object {
            /** A volume's root (null for internal), as [AndroidStorage.Volume] holds it. */
            fun ofRoot(root: File?): Destination = if (root == null) Internal else Volume(root)

            /**
             * The destination a stored value names. A blank, malformed or cloud value is the internal
             * folder, the one place that is always there, never a crash or a half understood path.
             */
            fun parse(value: String?): Destination = when (StoredLocation.kindOf(value)) {
                StoredLocation.Kind.Volume -> Volume(File(value.orEmpty()))
                StoredLocation.Kind.Tree -> DownloadTree.parse(value)?.let(::Tree) ?: Internal
                else -> Internal
            }
        }
    }

    /** How a move went, for the toast at the end. [label] is the volume's own name, as Settings shows it. */
    data class Outcome(
        val movedCount: Int,
        val failed: List<String>,
        /** The folder on the disk moved into; null when the destination is a picked folder. */
        val downloadsDir: File?,
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
    internal fun isNoSpace(e: Throwable): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC") || text.contains("disk full", ignoreCase = true)
    }

    /**
     * The label of the volume a move is waiting for, or null when none is pending or it is
     * reachable (the move then just finishes at the next launch). Shown on the Storage row.
     */
    suspend fun waitingFor(context: Context, settings: SettingsStore): String? {
        val pending = StorageRelocationPlan.Pending.decode(settings.downloadRootPendingNow()) ?: return null
        return when (val destination = Destination.parse(pending.root)) {
            Destination.Internal -> null
            is Destination.Tree ->
                if (DownloadTree.usable(context, destination.uri)) null else DownloadTree.label(context, destination.uri)
            is Destination.Volume ->
                if (AndroidStorage.isUsable(destination.root)) {
                    null
                } else {
                    AndroidStorage.volumes(context).firstOrNull { it.root?.path == destination.root.path }?.label
                        ?: L.storageAndroidRemovable(1)
                }
        }
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

    /** The volume in use, or null for the internal default or a picked folder (which has no path). */
    fun currentRoot(): File? = (Destination.parse(AndroidStorage.currentRoot) as? Destination.Volume)?.root

    /**
     * Just the folder, nothing of what is already downloaded moved: new downloads go to [root]
     * from now on (null for the internal default), and the ones already on the device stay where
     * they are. HX26's "leave in place", and the one of the two always on offer, since it needs no
     * room for anything but the next download.
     */
    suspend fun useWithoutMoving(settings: SettingsStore, root: File?) = useWithoutMoving(settings, Destination.ofRoot(root))

    suspend fun useWithoutMoving(settings: SettingsStore, destination: Destination) {
        settings.setDownloadRoot(destination.key)
        AndroidStorage.currentRoot = destination.key
        AndroidStorage.appContext?.let { runCatching { DownloadTree.releaseUnused(it, settings) } }
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
    ): Boolean = start(context, settings, Destination.ofRoot(root), label, onOutcome, onCancelled)

    fun start(
        context: Context,
        settings: SettingsStore,
        destination: Destination,
        label: String,
        onOutcome: (Outcome) -> Unit,
        onCancelled: () -> Unit,
    ): Boolean {
        if (activeJob != null) return false
        val app = context.applicationContext
        val job = scope.launch {
            lock.withLock {
                val outcome = try {
                    run(app, settings, destination, label)
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
            val destination = Destination.parse(pending.root)
            // A card that is not there, or a folder whose permission was taken back: wait quietly.
            // The row says so and offers to forget the move.
            val reachable = when (destination) {
                Destination.Internal -> true
                is Destination.Volume -> AndroidStorage.isUsable(destination.root)
                is Destination.Tree -> DownloadTree.usable(app, destination.uri)
            }
            if (!reachable) return
            finish(app, settings, pending, destination, labelOf(app, destination))
        } finally {
            lock.unlock()
        }
    }

    private fun labelOf(context: Context, destination: Destination): String = when (destination) {
        Destination.Internal -> L.storageAndroidInternal
        is Destination.Tree -> DownloadTree.label(context, destination.uri)
        is Destination.Volume ->
            AndroidStorage.volumes(context).firstOrNull { it.root?.path == destination.root.path }?.label ?: L.storageAndroidRemovable(1)
    }

    /** The folder on the disk a destination writes into; null for a picked folder, which is written through the system. */
    private fun dirOf(context: Context, destination: Destination): File? = when (destination) {
        Destination.Internal -> File(context.filesDir, AndroidPaths.DOWNLOADS)
        is Destination.Volume -> AndroidStorage.downloadsDirOf(destination.root)
        is Destination.Tree -> null
    }

    /**
     * The downloads that still have to go to [destination]: every record whose video is there
     * (a record whose file has gone is left for the Downloads page to show as missing) and is not
     * in [destination] already, whether it is a file on the disk or a document in a picked folder.
     */
    fun toMove(context: Context, records: List<ResumeRecord>, destination: Destination): List<Pair<ResumeRecord, LocalVideo>> {
        val dir = dirOf(context, destination)
        return records.mapNotNull { record ->
            val video = LocalVideo.of(context, record.localPath) ?: return@mapNotNull null
            if (!video.exists()) return@mapNotNull null
            val inPlace = when (destination) {
                is Destination.Tree -> video is LocalVideo.InTree && StoredLocation.documentIsIn(destination.key, record.localPath.orEmpty())
                else -> video is LocalVideo.OnDisk && dir != null && StorageRelocationPlan.inside(video.file, dir)
            }
            if (inPlace) null else record to video
        }
    }

    private suspend fun run(context: Context, settings: SettingsStore, destination: Destination, label: String): Outcome {
        OfflineDownloads.pauseAll(context)
        awaitNothingMoving()
        val oldDownloadsDir = AndroidStorage.downloadsDir(context)
        val pending = StorageRelocationPlan.Pending(root = destination.key, oldCache = "", oldDownloads = oldDownloadsDir.path)
        settings.setDownloadRootPending(pending.encode())
        settings.setDownloadRoot(destination.key)
        AndroidStorage.currentRoot = destination.key
        return finish(context, settings, pending, destination, label)
    }

    /**
     * Puts one video where [destination] keeps downloads and returns the path to record for it.
     * A file on the disk or a document, into a folder on the disk or a picked folder: the copy is
     * made whole and size checked before the original is let go, and a failure or a cancel leaves
     * no partial video at either end. Throws [IOException] when the move fails; the original is
     * untouched then.
     */
    private suspend fun transfer(
        context: Context,
        video: LocalVideo,
        destination: Destination,
        dir: File?,
        onProgress: (done: Long, total: Long) -> Unit,
    ): String {
        if (destination is Destination.Tree) {
            val length = video.length() ?: throw java.io.FileNotFoundException("Nothing to move at ${video.uri()}")
            val document = DownloadTree.put(context, destination.uri, video.name(), length, { video.open() }, onProgress)
            letGo(video)
            return document.toString()
        }
        val into = dir ?: throw IOException("No folder to move into")
        return when (video) {
            is LocalVideo.OnDisk -> DownloadFiles.moveIntoDownloads(video.file, into, video.file.name, onProgress).absolutePath
            is LocalVideo.InTree -> {
                val moved = DownloadTree.pull(video, into, video.name(), onProgress)
                letGo(video)
                moved.absolutePath
            }
        }
    }

    /** Removes the original of a video that is now whole at its new place; a stubborn one is logged, never a failure. */
    private fun letGo(video: LocalVideo) {
        val gone = when (video) {
            is LocalVideo.OnDisk -> runCatching { video.file.delete() }.getOrDefault(false) || !video.file.exists()
            is LocalVideo.InTree -> video.delete()
        }
        if (!gone) Logger.w(TAG, "Moved a download but could not delete the original at ${video.uri()}")
    }

    private suspend fun finish(
        context: Context,
        settings: SettingsStore,
        pending: StorageRelocationPlan.Pending,
        destination: Destination,
        label: String,
    ): Outcome {
        val to = dirOf(context, destination)
        to?.mkdirs()
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val files = toMove(context, records, destination)
        val total = files.sumOf { (_, video) -> video.length() ?: 0L }
        var movedCount = 0
        val failed = mutableListOf<String>()
        var stoppedBy: Stop? = null
        if (files.isNotEmpty()) _moving.value = Moving(0, total)
        try {
            var done = 0L
            for ((record, src) in files) {
                val before = done
                val size = (src.length() ?: 0L).coerceAtLeast(0)
                try {
                    val newPath = transfer(context, src, destination, to) { landed, _ ->
                        _moving.value = Moving(before + landed, total)
                    }
                    settings.setDownloadPath(record.chatId, record.messageId, newPath)
                    movedCount++
                } catch (e: Exception) {
                    // A cancel is not a failure: it stops the move here, between files.
                    if (e is CancellationException) throw e
                    // The record still points at the old place, which still plays from there.
                    val name = src.name()
                    Logger.w(TAG, "Could not move $name to the new downloads folder", e)
                    failed += name
                    // A full disk or a pulled card fails every later file the same slow way, so
                    // stop at the first and say why instead of trying them all.
                    stoppedBy = when {
                        isNoSpace(e) -> Stop.NoSpace((total - before - freeAt(context, destination, to)).coerceAtLeast(size))
                        !reachable(context, destination) || !src.exists() -> Stop.VolumeGone
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
            if (to != null) removeIfEmpty(File(pending.oldDownloads), to)
        }
        // A folder the viewer gave up keeps its grant only while a download is still in it.
        runCatching { DownloadTree.releaseUnused(context, settings) }
        return Outcome(movedCount, failed, to, label, stoppedBy)
    }

    private fun reachable(context: Context, destination: Destination): Boolean = when (destination) {
        Destination.Internal -> true
        is Destination.Volume -> AndroidStorage.isUsable(destination.root)
        is Destination.Tree -> DownloadTree.usable(context, destination.uri)
    }

    /** Room left at the destination; zero when it cannot be asked, so the message quotes the whole size still to move. */
    private fun freeAt(context: Context, destination: Destination, dir: File?): Long = when (destination) {
        is Destination.Tree -> DownloadTree.freeBytes(destination.uri) ?: 0L
        else -> dir?.let { DiskSpace.read(context, it).freeBytes } ?: 0L
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
