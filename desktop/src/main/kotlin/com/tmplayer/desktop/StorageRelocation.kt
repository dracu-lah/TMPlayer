package com.tmplayer.desktop

import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moving TMPlayer's files to a storage location the viewer chose, or back to the default folders
 * (B2.3). The decisions are [StorageRelocationPlan]'s; this is the work, in this order:
 *
 * 0. Before anything is touched: the new folder is there and can be written to, and the downloads
 *    to carry across fit (needed plus a gigabyte spare against what is free there now, since the
 *    confirm may be minutes old). A refusal changes nothing.
 * 1. Everything that reads TDLib's files stops: the queue, the player, the watch cache sweep.
 * 2. TDLib's cache is emptied through TDLib, so its database agrees it is empty. Copying the cache
 *    would be useless: TDLib does not know files copied into a new directory, and the stray sweep
 *    would delete them.
 * 3. The new root is written down, with a pending note of what is still to do, before anything
 *    moves; then the folders and the `.tmplayer` marker are made, TDLib is restarted over the new
 *    cache folder, and the old cache folder is deleted.
 * 4. When the viewer ticked it, every download moves into the new Downloads folder, the index
 *    updated as each one lands, so a crash part way leaves an index that is right about every
 *    file. It stops at the first full disk or vanished drive (every later file would fail the same
 *    slow way), between files when the viewer cancels, and never leaves a half copy: the copy goes
 *    to a `.part` file that is removed on any failure. A file another program has locked is
 *    reported and tried again next launch, not fatal. Downloads left behind keep playing where
 *    they are; [moveRemaining] carries them later.
 * 5. The old Downloads folder goes if it is empty; nothing else is ever removed.
 *
 * @param layout the folders for a root, or for the default folders with null.
 * @param host the parts that touch the running app, so this can be tested over fakes.
 * @param freeSpace free bytes on the drive a folder is on, or would be on.
 * @param usable whether a chosen root can hold TMPlayer's folders now.
 * @param sameDrive whether two paths are on one drive, so a move between them is a rename, not a copy.
 * @param mover moves one file into the Downloads folder, with no partial file left on failure.
 */
class StorageRelocation(
    private val settings: SettingsStore,
    private val prefs: DesktopPrefs,
    private val host: Host,
    private val platformLayout: (File?) -> StorageLayout = { DesktopPaths.layout(it) },
    private val notifier: () -> TransferNotifier = { NoTransferNotifier },
    private val _moving: MutableStateFlow<Moving?> = MutableStateFlow(null),
    private val freeSpace: (File) -> Long = { DiskInfo.of(it).freeBytes },
    private val usable: (File) -> Boolean = StorageRootCheck::usable,
    private val sameDrive: (File, File) -> Boolean = DownloadFiles::sameStore,
    private val mover: suspend (File, File, String, (Long, Long) -> Unit) -> File =
        { src, dir, name, progress -> DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress) },
    /** The profile a move works for, taken when it starts (HX88, invariant 2). */
    private val owner: () -> com.tmplayer.data.AccountStamp = { com.tmplayer.data.Accounts.stamp },
    /** Whether the profile a move works for is still the open one, no switch under way (invariant 3). */
    private val mayAct: (com.tmplayer.data.AccountStamp) -> Boolean = { com.tmplayer.data.OwnedSessions.mayAct(it) },
    /** Every profile's folder name (null: the adopted one), for the caches a move leaves behind. */
    private val profileFolders: () -> List<String?> = {
        com.tmplayer.data.Accounts.book?.list?.value?.profiles?.map { it.folder }.orEmpty()
    },
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

    /** Why a move ended before it tried every file. */
    sealed interface Stop {
        /** The new drive filled up; [neededBytes] is about how much more the rest would take. */
        data class NoSpace(val neededBytes: Long) : Stop

        /** The drive, either end of the move, went away. */
        data object VolumeGone : Stop

        /** The viewer pressed Cancel. */
        data object Cancelled : Stop
    }

    /** Why one file did not move, in the words a person would use. */
    enum class Reason { InUse, TooLong, Other }

    /**
     * How a move went, for the toast at the end and, when something was left, the report.
     *
     * @param failed the names that did not move (locked, or the copy failed), [reasons] saying why;
     *   tried again at the next launch.
     * @param total the downloads the move set out to carry (links not counted).
     * @param skippedLinks files that are links to somewhere else, left where they are.
     * @param missing records whose file is no longer on the disk at all.
     */
    data class Outcome(
        val downloadsDir: File,
        val moved: Int,
        val failed: List<String>,
        val stop: Stop? = null,
        val total: Int = moved + failed.size,
        val reasons: Map<String, Reason> = emptyMap(),
        val skippedLinks: List<String> = emptyList(),
        val missing: Int = 0,
    ) {
        private val folder: String get() = (downloadsDir.parentFile ?: downloadsDir).toString()

        val message: String
            get() = when {
                stop is Stop.Cancelled -> L.storageMoveCancelled(moved, total)
                stop is Stop.NoSpace -> L.storageMoveDriveFull(folder, StorageRelocationPlan.size(stop.neededBytes), moved, total)
                stop is Stop.VolumeGone -> L.storageMoveDriveGone(folder, moved, total)
                failed.isEmpty() -> L.storageMovedTo(folder)
                failed.size == 1 -> L.storageMovedButOne(failed.first())
                else -> L.storageMovedButSome(failed.size)
            }

        /** Downloads that did not go and stay where they were. */
        val left: Int get() = (total - moved).coerceAtLeast(0)

        /** Whether the toast is too little: the drive stopped it, or something failed or was skipped. */
        val needsReport: Boolean
            get() = (stop != null && stop !is Stop.Cancelled) || failed.isNotEmpty() || skippedLinks.isNotEmpty()

        /** What worked, what was skipped and why, one sentence a line. */
        fun report(): List<String> = buildList {
            if (stop != null) add(message)
            add(L.storageReportMoved(moved))
            failed.take(REPORT_NAMES).forEach { name ->
                val why = when (reasons[name]) {
                    Reason.InUse -> L.storageReasonInUse
                    Reason.TooLong -> L.storageReasonTooLong
                    else -> L.storageReasonOther
                }
                add(L.storageReportFailed(name, why))
            }
            if (failed.size > REPORT_NAMES) add(L.storageReportMore(failed.size - REPORT_NAMES))
            skippedLinks.take(REPORT_NAMES).forEach { add(L.storageReportLink(it)) }
            if (missing > 0) add(L.storageReportMissing(missing))
            if (left > 0 && stop == null) add(L.storageReportLeft(left))
        }
    }

    /** A move in progress: bytes of downloads in place so far, of all there are. */
    data class Moving(val doneBytes: Long, val totalBytes: Long)

    /** Non null while downloads are moving, for the Settings row's progress. */
    val moving: StateFlow<Moving?> = _moving.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var cancelled = false

    @Volatile
    private var copying: Job? = null

    /** Set by [stopForSwitch]: the move stops before its next file, so a switch can go ahead. */
    @Volatile
    private var switchStop = false

    /**
     * A [com.tmplayer.data.BeforeSwitch] hook (HX88, review D2): a move under way stops before its
     * next file (the one in hand lands and is recorded for its owner) and the switch waits for it,
     * at most [waitMs]. A move that outlasts the wait still writes nothing for the next profile:
     * each file is checked against its owner first.
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

    /** What a switch says it waits for while a move runs, or null for nothing. */
    fun waitingLabel(): String? = if (lock.isLocked) L.profilesWaitingMoving else null

    /** The storage location in use, or null for the default folders. */
    fun currentRoot(): File? = prefs.now.storageRoot.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * Stops the downloads' move after the file in hand: its copy is cut, its half file removed,
     * and what already moved stays moved. Nothing happens when no downloads are moving.
     */
    fun cancel() {
        if (_moving.value == null) return
        cancelled = true
        copying?.cancel()
    }

    /** The move written down and waiting, for the Settings row; null when none. */
    fun pending(): StorageRelocationPlan.Pending? = StorageRelocationPlan.Pending.decode(prefs.now.storageRootPending)

    /**
     * Stops waiting for a move that cannot finish (its drive is not there). Downloads stay where
     * they are and keep playing. The storage location goes back to the default folders, which
     * are what this run uses anyway, so it no longer points at a drive that is not there; and the
     * old cache the move would have deleted goes now, unless it is one of the folders in use.
     * Nothing else is removed.
     */
    suspend fun forgetPending() = lock.withLock {
        val pending = StorageRelocationPlan.Pending.decode(prefs.now.storageRootPending)
        prefs.update { it.copy(storageRoot = "", storageRootPending = "") }
        if (pending != null && pending.oldCache.isNotBlank()) {
            withContext(NonCancellable) { deleteOldCache(File(pending.oldCache), layout(null, owner().folder)) }
        }
    }

    /**
     * Moves everything to [root] (null for the default folders). The viewer has confirmed by now,
     * and [StorageRelocationPlan.validate] has allowed it. [moveDownloads] is their tick: off
     * leaves the downloads where they are, playing from there.
     *
     * @throws IOException before anything was touched: another move is running, the folder
     *   cannot be used, or the downloads no longer fit.
     */
    suspend fun move(root: File?, moveDownloads: Boolean = true): Outcome {
        if (!lock.tryLock()) throw IOException(L.storageMoveBusy)
        try {
            val by = owner()
            val oldRoot = currentRoot()
            val from = layout(oldRoot, by.folder)
            val to = layout(root, by.folder)
            preflight(root, to, moveDownloads)
            host.pause()
            try {
                // As the profile that asked (HX88): a record written after a switch is dropped,
                // except a moved file's, which is addressed to it ([record]).
                return withContext(NonCancellable + com.tmplayer.data.AccountOwner(by)) {
                    host.clearCache()
                    val pending = StorageRelocationPlan.Pending(
                        root = root?.path.orEmpty(),
                        oldCache = from.cacheDir.path,
                        oldDownloads = from.downloadsDir.path,
                        moveDownloads = moveDownloads,
                    )
                    prefs.update { it.copy(storageRoot = root?.path.orEmpty(), storageRootPending = pending.encode()) }
                    prepare(root, to)
                    host.restart(root)
                    deleteOldCache(from.cacheDir, to)
                    deleteOtherCaches(oldRoot, root, by.folder, to)
                    finish(pending, root, to)
                }
            } finally {
                withContext(NonCancellable) { host.resume() }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Carries the downloads still outside the Downloads folder in (after a stop, a cancel, or a
     * move made without them), the same way [move] does and with the same checks.
     */
    suspend fun moveRemaining(): Outcome {
        if (!lock.tryLock()) throw IOException(L.storageMoveBusy)
        try {
            val root = currentRoot()
            val by = owner()
            val to = layout(root, by.folder)
            preflight(root, to, moveDownloads = true)
            host.pause()
            try {
                return withContext(NonCancellable + com.tmplayer.data.AccountOwner(by)) { finish(null, root, to) }
            } finally {
                withContext(NonCancellable) { host.resume() }
            }
        } finally {
            lock.unlock()
        }
    }

    /** How many downloads are outside the Downloads folder now, and what they take. */
    suspend fun outside(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val away = StorageRelocationPlan.toMove(records, layout(currentRoot(), owner().folder).downloadsDir)
        away.size to away.sumOf { File(it.localPath.orEmpty()).length() }
    }

    /**
     * Finishes a move an earlier run began and did not complete. Called once at launch; needs the
     * settings store and nothing of TDLib's, so it can run while TDLib is still starting.
     *
     * Waits (returns null, the note kept) while the new location cannot be used, such as a drive
     * not plugged in: making its folders then would put them on whatever disk holds the empty
     * mount point.
     *
     * @return null when nothing was pending, or it is waiting for its drive.
     */
    suspend fun resumePending(): Outcome? = lock.withLock {
        val pending = StorageRelocationPlan.Pending.decode(prefs.now.storageRootPending) ?: return null
        val root = pending.root.takeIf { it.isNotBlank() }?.let(::File)
        if (root != null && !usable(root)) return null
        val by = owner()
        val to = layout(root, by.folder)
        withContext(NonCancellable + com.tmplayer.data.AccountOwner(by)) {
            prepare(root, to)
            if (mayAct(by)) sweepPartFiles(to)
            deleteOldCache(File(pending.oldCache), to)
            finish(pending, root, to)
        }
    }

    /**
     * Half copies a kill left in the new Downloads folder: `<name>.part` for a download of ours
     * that has not landed yet. Only those names; anything else in the folder is not ours to touch.
     */
    private suspend fun sweepPartFiles(to: StorageLayout) = withContext(Dispatchers.IO) {
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        StorageRelocationPlan.toMove(records, to.downloadsDir).forEach { record ->
            val name = File(record.localPath.orEmpty()).name
            val ours = setOf(name, DownloadFiles.freeName(to.downloadsDir, name).name)
            for (candidate in ours) runCatching { Files.deleteIfExists(File(to.downloadsDir, candidate + DownloadFiles.PART_SUFFIX).toPath()) }
        }
    }

    /** The checks before a move touches anything. */
    private suspend fun preflight(root: File?, to: StorageLayout, moveDownloads: Boolean) = withContext(Dispatchers.IO) {
        if (root != null && !usable(root)) throw IOException(L.storageCannotWrite)
        if (!moveDownloads) return@withContext
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val stores = HashMap<File?, Boolean>()
        val crossing = StorageRelocationPlan.toMove(records, to.downloadsDir).sumOf { record ->
            val src = File(record.localPath.orEmpty())
            val same = stores.getOrPut(src.absoluteFile.parentFile) { sameDrive(src, to.downloadsDir) }
            if (same || isLink(src)) 0L else src.length()
        }
        val free = freeSpace(to.downloadsDir)
        if (!StorageRelocationPlan.fits(crossing, free)) {
            throw IOException(
                L.storageDriveTooSmall(
                    StorageRelocationPlan.size(free),
                    StorageRelocationPlan.size(crossing + StorageRelocationPlan.SPARE_BYTES),
                ),
            )
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

    /**
     * The caches the other profiles had at the old root (HX88, review D2): the root moves for every
     * profile, so theirs would otherwise stay there for good, never swept or purged. None of their
     * clients runs while another profile is open, and TDLib fetches again whatever it misses. Never
     * anything the new layout of any profile uses, and never a profile's downloads folder.
     *
     * Decided (HX88 low, 2026-11-23): this stays a delete, not a keep. A cache left at the old root
     * is unreachable (the profile's client starts over the new cache folder, and TDLib does not
     * know files it did not put there), so nothing would ever sweep or purge it, and a move made to
     * empty the old drive would leave it full. Losing it costs a refetch of what the profile
     * watched, never a download. See `other profiles' caches go with the old root` in the test.
     */
    private suspend fun deleteOtherCaches(oldRoot: File?, root: File?, openFolder: String?, to: StorageLayout) {
        for (folder in profileFolders().distinct()) {
            if (folder == openFolder) continue
            val old = layout(oldRoot, folder).cacheDir
            val theirs = layout(root, folder)
            if (listOf(theirs.cacheDir, theirs.downloadsDir, theirs.updatesDir).any { StorageRelocationPlan.inside(it, old) || StorageRelocationPlan.inside(old, it) }) continue
            deleteOldCache(old, to)
        }
    }

    /**
     * Whether the new location is still there. For a chosen root that is its TMPlayer folder,
     * which a drive pulled mid move takes with it (a mount point left behind is an empty folder).
     */
    private fun reachable(root: File?, to: StorageLayout): Boolean =
        if (root == null) to.downloadsDir.parentFile?.isDirectory != false else StorageRelocationPlan.appFolder(root).isDirectory

    private fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun reasonOf(e: Throwable, target: File): Reason {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            text.contains("used by another", ignoreCase = true) || text.contains("access is denied", ignoreCase = true) ||
                text.contains("being used", ignoreCase = true) -> Reason.InUse
            text.contains("too long", ignoreCase = true) || (IS_WINDOWS && target.path.length > WINDOWS_PATH_LIMIT) -> Reason.TooLong
            else -> Reason.Other
        }
    }

    /**
     * The folders of [root] as the profile on [folder] has them (HX88): its own cache and
     * downloads, so a move carries the open profile's and never another's.
     */
    private fun layout(root: File?, folder: String?): StorageLayout = platformLayout(root).forProfile(folder)

    /**
     * A moved file's record, written for the profile the move works for even after a switch
     * ([com.tmplayer.data.AccountAddress], HX88): it never points at the original that is gone.
     */
    private suspend fun addressed(): kotlin.coroutines.CoroutineContext =
        kotlinx.coroutines.currentCoroutineContext()[com.tmplayer.data.AccountOwner]?.stamp
            ?.let { com.tmplayer.data.AccountAddress(it.folder) } ?: kotlin.coroutines.EmptyCoroutineContext

    private suspend fun finish(pending: StorageRelocationPlan.Pending?, root: File?, to: StorageLayout): Outcome {
        // The records read are the open profile's: only while that is still the move's owner, and
        // checked again before each file (HX88, review D2: a switch beside the launch picker).
        val by = kotlinx.coroutines.currentCoroutineContext()[com.tmplayer.data.AccountOwner]?.stamp
        fun ownerOpen() = !switchStop && (by == null || mayAct(by))
        if (!ownerOpen()) {
            Logger.i(TAG, "The downloads' move stopped: its profile is no longer the open one")
            return Outcome(downloadsDir = to.downloadsDir, moved = 0, failed = emptyList(), stop = Stop.Cancelled, total = 0)
        }
        var records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        // A file that landed in the new folder with its record not written (a kill, a failed write):
        // point the record at it before anything else, or it would be taken for a missing file.
        val adopted = StorageRelocationPlan.adoptable(records, to.downloadsDir)
        if (adopted.isNotEmpty()) {
            adopted.forEach { (record, file) ->
                runCatching { withContext(addressed()) { settings.setDownloadPath(record.chatId, record.messageId, file.absolutePath) } }
                    .onFailure { Logger.w(TAG, "Could not record ${file.name}", it) }
            }
            records = runCatching { settings.downloadsNow() }.getOrDefault(records)
        }
        val wanted = pending?.moveDownloads ?: true
        val candidates = if (wanted) StorageRelocationPlan.toMove(records, to.downloadsDir) else emptyList()
        val links = candidates.filter { isLink(File(it.localPath.orEmpty())) }
        val moving = candidates - links.toSet()
        val total = moving.sumOf { File(it.localPath.orEmpty()).length() }
        val id = NOTIFY_ID
        val note = notifier()
        cancelled = false
        var done = 0L
        var moved = 0
        var gone = StorageRelocationPlan.missing(records).size
        var stop: Stop? = null
        val failed = mutableListOf<String>()
        val reasons = mutableMapOf<String, Reason>()
        if (moving.isNotEmpty()) {
            com.tmplayer.desktop.os.ProfileOutside.asOwner(by) { note.begin(id, TransferNotifier.Kind.Relocate, L.storageMovingDownloads) }
            _moving.value = Moving(0, total)
        }
        try {
            for (record in moving) {
                val src = File(record.localPath.orEmpty())
                if (cancelled || !ownerOpen()) {
                    stop = Stop.Cancelled
                    break
                }
                if (!reachable(root, to)) {
                    stop = Stop.VolumeGone
                    break
                }
                val before = done
                val size = src.length().coerceAtLeast(0)
                val job = Job()
                copying = job
                // The name the mover will give the file, so a move that landed can be found even if
                // Cancel is heard on the way back from the mover.
                val expected = DownloadFiles.freeName(to.downloadsDir, src.name)
                var recorded = false
                var landed = false
                // The record is written as part of the move, under NonCancellable, once the file has
                // landed: a Cancel then can no longer leave a file in the new folder that the index
                // does not know.
                suspend fun record(target: File) = withContext(NonCancellable + addressed()) {
                    settings.setDownloadPath(record.chatId, record.messageId, target.absolutePath)
                    recorded = true
                }
                try {
                    withContext(job) {
                        val target = mover(src, to.downloadsDir, src.name) { landed, _ ->
                            _moving.value = Moving(before + landed, total)
                            note.progress(id, before + landed, total, null)
                        }
                        landed = true
                        record(target)
                    }
                    moved++
                } catch (e: CancellationException) {
                    if (!cancelled) throw e
                    // Heard after the file landed but before it was recorded: finish the record.
                    if (!recorded && !src.exists() && expected.isFile) {
                        runCatching { record(expected) }
                    }
                    if (recorded) moved++
                    stop = Stop.Cancelled
                } catch (e: Exception) {
                    // Typically Windows: "The process cannot access the file" while a player elsewhere
                    // has it. The record still points at the old place, which still plays.
                    Logger.w(TAG, "Could not move ${src.name}", e)
                    when {
                        // The file landed but its record could not be written (the settings store failed):
                        // it is found and recorded at the next move or launch, which the note kept says.
                        landed && !recorded -> {
                            failed += src.name
                            reasons[src.name] = Reason.Other
                        }
                        // A full disk fails every later file the same slow way: stop and say how much more.
                        StorageRelocationPlan.isNoSpace(e) -> {
                            val remaining = total - before
                            stop = Stop.NoSpace((remaining - freeSpace(to.downloadsDir)).coerceAtLeast(size))
                        }
                        !reachable(root, to) || (!src.exists() && src.absoluteFile.parentFile?.isDirectory != true) ->
                            stop = Stop.VolumeGone
                        // Gone since the list was read (removed meanwhile): not a failure of the move.
                        !src.exists() -> gone++
                        else -> {
                            failed += src.name
                            reasons[src.name] = reasonOf(e, File(to.downloadsDir, src.name))
                        }
                    }
                } finally {
                    copying = null
                }
                if (stop != null) break
                done = before + size
            }
        } finally {
            _moving.value = null
        }
        // Held until the next launch only for a file that would not go. A stop is the viewer's or the
        // drive's, and the rest is offered from Settings, not moved behind their back.
        if (stop != null || failed.isEmpty()) {
            prefs.update { it.copy(storageRootPending = "") }
            if (stop == null && wanted && pending != null) removeIfEmpty(File(pending.oldDownloads), to)
        }
        val outcome = Outcome(
            downloadsDir = to.downloadsDir,
            moved = moved,
            failed = failed,
            stop = stop,
            total = moving.size,
            reasons = reasons,
            skippedLinks = links.map { File(it.localPath.orEmpty()).name },
            missing = gone,
        )
        if (moving.isNotEmpty()) {
            com.tmplayer.desktop.os.ProfileOutside.asOwner(by) {
                when {
                    stop is Stop.Cancelled -> note.cancel(id)
                    stop != null || failed.isNotEmpty() -> note.fail(id, L.storageSomeDidNotMove, outcome.message, retryable = true)
                    else -> note.complete(id, L.downloadsMoved, outcome.message, TransferNotifier.OpenTarget.Folder(to.downloadsDir.path))
                }
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
        const val REPORT_NAMES = 6
        const val WINDOWS_PATH_LIMIT = 259

        /** The longest a switch waits for a move's file in hand, never for ever. */
        const val SWITCH_WAIT_MS = 60_000L
        val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
