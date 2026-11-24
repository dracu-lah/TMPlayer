package com.tmplayer.desktop

import com.tmplayer.data.FolderCopy
import com.tmplayer.data.FolderCopyIo
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Save to folder on the desktop: copies of finished downloads in a folder picked with the OS's own
 * dialog ([com.tmplayer.desktop.os.FolderPicker]). The steps are the phone's (a plan for the
 * confirmation, the copy with progress and Cancel, the result) and live here rather than on the
 * Downloads page, so moving to another page while a large batch copies does not stop it; the page
 * draws [state] whenever it is open.
 *
 * Written with NIO, which takes Windows' long paths (past 260 characters) as they are, and a name
 * already in the folder is never written over (FolderCopy.freeName).
 */
object DesktopFolderExport {

    /** A row the viewer chose: its record, its title, and the file when it is whole on this computer. */
    class Candidate(val record: ResumeRecord, val title: String, val file: File?)

    class Item(val source: FolderCopyIo.Source, val record: ResumeRecord, val sizeBytes: Long, val newest: Long)

    /**
     * What the viewer is asked to confirm, and the profile it was made for ([stamp], HX88 review
     * round 2): the dialog names that profile's videos, so it is never confirmed, or even shown, under
     * another one. 'Remove' would otherwise delete the first profile's originals under the second.
     */
    class Offer(val dir: File, val plan: FolderCopy.Plan<Item>, val stamp: com.tmplayer.data.AccountStamp = com.tmplayer.data.Accounts.stamp)

    /** True while an offer made for [stamp] belongs to the profile open now. */
    internal fun offerIsMine(stamp: com.tmplayer.data.AccountStamp, now: com.tmplayer.data.AccountStamp = com.tmplayer.data.Accounts.stamp): Boolean = stamp == now

    data class State(
        val offer: Offer? = null,
        val preparing: Boolean = false,
        val progress: FolderCopy.Progress? = null,
        val outcome: FolderCopy.Outcome? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("DesktopFolderExport"))
    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    init {
        // A switch takes the first profile's question and answer off the screen: the next profile's Downloads page shows none of it.
        com.tmplayer.data.Accounts.onSwitch { if (!running.get()) _state.value = State() }
    }

    /** The folder saved to last this session: where the picker opens, and the selection's warning. */
    @Volatile var lastDir: File? = null
        private set

    val busy: Boolean get() = running.get() || _state.value.offer != null || _state.value.preparing

    /** The folder picked: sorts the rows, adds up the sizes, reads the room there, and asks. */
    suspend fun prepare(dir: File, chosen: List<Candidate>) {
        _state.value = State(preparing = true)
        val offer = runCatching { plan(dir, chosen) }.getOrElse { error ->
            Logger.w(TAG, "Save to folder could not be prepared", error)
            _state.value = State(outcome = FolderCopy.Outcome(folder = label(dir), copied = 0, stop = FolderCopy.Stop.FolderLost, notReached = chosen.size))
            return
        }
        _state.value = State(offer = offer)
    }

    private suspend fun plan(dir: File, chosen: List<Candidate>): Offer =
        withContext(Dispatchers.IO) {
            val sorted = FolderCopy.sort(
                chosen,
                whole = { it.file?.isFile == true },
                canBeSaved = { Td.maySave(it.record.chatId, it.record.messageId) },
            )
            val items = sorted.copy.map { candidate ->
                val file = candidate.file!!
                Item(
                    FolderCopyIo.Source(file, candidate.record.fileName ?: candidate.title, candidate.title, "", keyOf(candidate.record)),
                    candidate.record,
                    file.length(),
                    candidate.record.updatedAt,
                )
            }
            lastDir = dir
            Offer(dir, FolderCopy.plan(items, label(dir), freeBytes(dir), sorted.forbidden, sorted.unfinished, { it.sizeBytes }, { it.newest }))
        }

    fun dismissOffer() {
        if (!running.get()) _state.value = State()
    }

    /** Starts the confirmed copy, or false when one is already going. */
    fun start(settings: SettingsStore, offer: Offer, items: List<Item>, remove: Boolean): Boolean {
        // An offer made under another profile is dropped, never run (a switch lands between asking and confirming).
        if (!offerIsMine(offer.stamp)) {
            if (!running.get()) _state.value = State()
            return false
        }
        if (!running.compareAndSet(false, true)) return false
        cancel.set(false)
        val plan = offer.plan
        _state.value = State(progress = FolderCopy.Progress(0, items.size, items.firstOrNull()?.source?.title.orEmpty(), 0f, 0, items.sumOf { it.sizeBytes }))
        // The profile whose downloads these are (HX88, review D4): every record written is its own,
        // and a switch, a sign out or Remove all accounts stops the copy between two files and
        // waits for it ([com.tmplayer.data.BeforeSwitch]).
        val owner = com.tmplayer.data.Accounts.stamp
        val job = scope.launch(com.tmplayer.data.AccountOwner(owner), start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var outcome = runCatching {
                FolderCopyIo.copyAll(DirFolder(offer.dir), items.map { it.source }, plan.forbidden, plan.unfinished, { cancel.get() }) {
                    _state.value = State(progress = it)
                }
            }.getOrElse { error ->
                Logger.w(TAG, "Save to folder failed", error)
                FolderCopy.Outcome(
                    folder = label(offer.dir), copied = 0, forbidden = plan.forbidden, unfinished = plan.unfinished,
                    failed = listOf(FolderCopy.Failure(items.firstOrNull()?.source?.title.orEmpty(), com.tmplayer.data.Failures.humanise(error))),
                )
            }
            val leftOut = plan.items.size - items.size
            if (leftOut > 0) outcome = outcome.copy(notReached = outcome.notReached + leftOut)
            if (remove) {
                outcome = removeSaved(outcome, items.associate { it.source.key to it.record }, owner) { DownloadIndex.delete(settings, it) }
            }
            _state.value = State(outcome = outcome)
            running.set(false)
        }
        val hook: suspend () -> Unit = {
            cancel.set(true)
            kotlinx.coroutines.withTimeoutOrNull(SWITCH_WAIT_MS) { job.join() }
        }
        com.tmplayer.data.BeforeSwitch.add(hook) { com.tmplayer.i18n.L.profilesWaitingExport }
        job.invokeOnCompletion {
            com.tmplayer.data.BeforeSwitch.remove(hook)
            running.set(false)
        }
        job.start()
        return true
    }

    fun cancel() = cancel.set(true)

    fun consume() {
        if (!running.get() && _state.value.outcome != null) _state.value = State()
    }

    /** The last folder's name and the room left in it now, for the selection's warning. */
    fun lastFolderFree(): Pair<String, Long>? {
        val dir = lastDir ?: return null
        return freeBytes(dir)?.let { label(dir) to it }
    }

    /**
     * "Remove from TMPlayer's downloads after saving": each original whose copy was checked whole
     * goes the way Delete on the Downloads page takes it (the file, then the record), so the page,
     * the badges and the space figures agree; Continue watching keeps its place. A file the player
     * holds open on Windows will not delete and is counted as kept. Only while [owner] is still the
     * open profile, asked before each file (HX88, invariant 3): after a switch every original stays.
     */
    internal suspend fun removeSaved(
        outcome: FolderCopy.Outcome,
        records: Map<String, ResumeRecord>,
        owner: com.tmplayer.data.AccountStamp,
        mayAct: (com.tmplayer.data.AccountStamp) -> Boolean = { com.tmplayer.data.OwnedSessions.mayAct(it) },
        delete: suspend (ResumeRecord) -> Boolean,
    ): FolderCopy.Outcome {
        var removed = 0
        var kept = 0
        for (key in outcome.verifiedKeys) {
            val record = records[key] ?: continue
            if (mayAct(owner) && runCatching { delete(record) }.getOrDefault(false)) removed++ else kept++
        }
        kept += (outcome.copied - outcome.verifiedKeys.size).coerceAtLeast(0)
        return outcome.copy(moved = removed, notMoved = kept)
    }

    fun keyOf(record: ResumeRecord): String = "${record.chatId}:${record.messageId}"

    /** The longest a switch waits for the copy in hand. */
    private const val SWITCH_WAIT_MS = 60_000L

    private fun label(dir: File): String = dir.name.ifBlank { dir.path }

    private fun freeBytes(dir: File): Long? {
        var at: File? = dir
        while (at != null && !at.exists()) at = at.parentFile
        return at?.usableSpace?.takeIf { it > 0 }
    }

    /** A plain directory, as java.nio reaches it on Windows, Linux and macOS. */
    private class DirFolder(private val picked: File) : FolderCopyIo.Folder<File> {
        // A folder deep enough to pass the 260 characters of Windows is reached with the long path
        // prefix (WP-52); the name the viewer sees stays the plain one.
        private val dir = com.tmplayer.desktop.os.LongPaths.io(picked)
        override val label: String = picked.name.ifBlank { com.tmplayer.desktop.os.LongPaths.shown(picked) }

        override fun writable(): Boolean {
            if (!dir.isDirectory) throw NoSuchFileException(dir.path)
            return Files.isWritable(dir.toPath())
        }

        override fun existing(): Collection<String> = dir.list()?.toList() ?: throw NoSuchFileException(dir.path)

        override fun freeBytes(): Long? = dir.usableSpace.takeIf { it > 0 }

        // FAT32 (a USB stick or an SD card as most come) holds nothing of 4 GB or more; said per
        // video before writing rather than found after most of it has been copied.
        override fun maxFileBytes(): Long? =
            runCatching { Files.getFileStore(dir.toPath()).type() }.getOrNull()
                ?.takeIf(FolderCopy::isFat)?.let { FolderCopy.FAT32_MAX_BYTES }

        override fun create(name: String, mimeType: String): File {
            val file = com.tmplayer.desktop.os.LongPaths.io(File(picked, name))
            // CREATE_NEW: never over a file that appeared since the names were read.
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW).close()
            return file
        }

        override fun open(handle: File): OutputStream =
            Files.newOutputStream(handle.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)

        override fun size(handle: File): Long? = handle.takeIf { it.isFile }?.length()

        override fun discard(handle: File) {
            Files.deleteIfExists(handle.toPath())
        }

        override fun lost(error: Throwable): Boolean =
            !dir.isDirectory || error is AccessDeniedException ||
                (error is FileSystemException && error !is java.nio.file.FileAlreadyExistsException && !Files.isWritable(dir.toPath()))
    }

    private const val TAG = "DesktopFolderExport"
}
