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

    class Offer(val dir: File, val plan: FolderCopy.Plan<Item>)

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
        if (!running.compareAndSet(false, true)) return false
        cancel.set(false)
        val plan = offer.plan
        _state.value = State(progress = FolderCopy.Progress(0, items.size, items.firstOrNull()?.source?.title.orEmpty(), 0f, 0, items.sumOf { it.sizeBytes }))
        scope.launch {
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
            if (remove) outcome = removeSaved(settings, outcome, items.associate { it.source.key to it.record })
            _state.value = State(outcome = outcome)
            running.set(false)
        }
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
     * holds open on Windows will not delete and is counted as kept.
     */
    private suspend fun removeSaved(settings: SettingsStore, outcome: FolderCopy.Outcome, records: Map<String, ResumeRecord>): FolderCopy.Outcome {
        var removed = 0
        var kept = 0
        for (key in outcome.verifiedKeys) {
            val record = records[key] ?: continue
            if (runCatching { DownloadIndex.delete(settings, record) }.getOrDefault(false)) removed++ else kept++
        }
        kept += (outcome.copied - outcome.verifiedKeys.size).coerceAtLeast(0)
        return outcome.copy(moved = removed, notMoved = kept)
    }

    fun keyOf(record: ResumeRecord): String = "${record.chatId}:${record.messageId}"

    private fun label(dir: File): String = dir.name.ifBlank { dir.path }

    private fun freeBytes(dir: File): Long? {
        var at: File? = dir
        while (at != null && !at.exists()) at = at.parentFile
        return at?.usableSpace?.takeIf { it > 0 }
    }

    /** A plain directory, as java.nio reaches it on Windows, Linux and macOS. */
    private class DirFolder(private val dir: File) : FolderCopyIo.Folder<File> {
        override val label: String = dir.name.ifBlank { dir.path }

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
            val file = File(dir, name)
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
