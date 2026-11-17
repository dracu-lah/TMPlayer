package com.tmplayer.data

import android.content.Context
import android.net.Uri
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Save to gallery on iOS: the Photos library, through the same steps as Android's FolderExport
 * (the plan with what is skipped and why, the confirmation, the progress, the outcome, and
 * removing TMPlayer's copy after when asked), so the Downloads screen and a video's page work as
 * upstream wrote them. Only the gallery: a folder of the viewer's choosing goes through the share
 * sheet's Save to Files, since Android's folder picker is not offered on iOS.
 */
object FolderExport {
    private const val TAG = "FolderExport"

    class Candidate(val key: String, val record: ResumeRecord, val fileId: Int, val title: String, val whole: Boolean)

    class Item(val source: FolderCopyIo.Source, val record: ResumeRecord, val sizeBytes: Long, val newest: Long) {
        internal var path: String = ""
    }

    class Offer(val tree: Uri?, val plan: FolderCopy.Plan<Item>) {
        val gallery: Boolean get() = tree == null
    }

    data class State(
        val offer: Offer? = null,
        val preparing: Boolean = false,
        val progress: FolderCopy.Progress? = null,
        val outcome: FolderCopy.Outcome? = null,
        val gallery: Boolean = false,
        val keys: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var pendingKeys: List<String>? = null
    internal val watchers = AtomicInteger(0)
    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    val busy: Boolean get() = running.get() || _state.value.preparing || _state.value.offer != null
    val copying: Boolean get() = running.get()

    private val label: String get() = L.downloadsSaveToGalleryPhotos

    suspend fun prepare(context: Context, tree: Uri?, chosen: List<Candidate>, settings: SettingsStore) {
        _state.value = State(preparing = true, gallery = true)
        val offer = runCatching { plan(context, chosen, settings) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) {
                _state.value = State()
                throw error
            }
            Logger.w(TAG, "Save to Photos could not be prepared", error)
            _state.value = State(
                outcome = FolderCopy.Outcome(folder = label, copied = 0, stop = FolderCopy.Stop.FolderLost, notReached = chosen.size),
                gallery = true,
            )
            return
        }
        _state.value = State(offer = offer, gallery = true)
    }

    /** One video from its page, with no confirmation; null once started, otherwise what to say. */
    suspend fun saveToGalleryNow(context: Context, candidate: Candidate, settings: SettingsStore): String? {
        if (busy) return L.downloadsSaveBusy
        val plan = runCatching { plan(context, listOf(candidate), settings).plan }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            return L.keepExportFailed(Failures.humanise(error))
        }
        return when {
            plan.forbidden > 0 -> ContentProtection.NOT_SAVABLE
            plan.items.isEmpty() -> L.downloadsFileGone
            !start(context, Offer(null, plan), plan.items, remove = false, detached = true) -> L.downloadsSaveBusy
            else -> null
        }
    }

    private suspend fun plan(context: Context, chosen: List<Candidate>, settings: SettingsStore): Offer =
        withContext(Dispatchers.IO) {
            val withFiles = chosen.map { candidate ->
                val path = if (candidate.whole) {
                    runCatching { LocalDownloads.shareablePath(settings, candidate.record.chatId, candidate.record.messageId, candidate.fileId) }.getOrNull()
                } else {
                    null
                }
                Triple(candidate, LocalVideo.of(context, path)?.takeIf { it.exists() }, path)
            }
            val sorted = FolderCopy.sort(
                withFiles,
                whole = { it.second != null },
                canBeSaved = { Td.maySave(it.first.record.chatId, it.first.record.messageId) },
            )
            val items = sorted.copy.mapNotNull { (candidate, video, path) ->
                val local = video ?: return@mapNotNull null
                Item(
                    FolderCopyIo.Source(
                        candidate.record.fileName ?: candidate.title, candidate.title, "", candidate.key,
                        local.name(), { local.length() }, { local.open() },
                    ),
                    candidate.record,
                    local.length() ?: 0L,
                    candidate.record.updatedAt,
                ).also { it.path = path.orEmpty() }
            }
            val free = DiskInfo.of(File(NSHomeDirectory())).freeBytes
            Offer(null, FolderCopy.plan(items, label, free, sorted.forbidden, sorted.unfinished, { it.sizeBytes }, { it.newest }))
        }

    fun dismissOffer(context: Context) {
        if (!running.get()) _state.value = State()
    }

    fun dropPreparing() {
        if (_state.value.preparing) _state.value = State()
    }

    fun start(context: Context, offer: Offer, items: List<Item>, remove: Boolean, detached: Boolean = false): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancel.set(false)
        val plan = offer.plan
        val keys = items.mapTo(HashSet()) { it.source.key }
        _state.value = State(
            progress = FolderCopy.Progress(0, items.size, items.firstOrNull()?.source?.title.orEmpty(), 0f, 0, items.sumOf { it.sizeBytes }),
            gallery = true,
            keys = keys,
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler(TAG)).launch {
            var outcome = run(items, plan.forbidden, plan.unfinished, keys)
            val leftOut = plan.items.size - items.size
            if (leftOut > 0) outcome = outcome.copy(notReached = outcome.notReached + leftOut)
            if (remove) outcome = removeSaved(context, outcome, items.associate { it.source.key to it.record })
            if (detached) {
                _state.value = State()
                val said = if (outcome.clean) outcome.headline else (listOf(outcome.headline) + outcome.lines.take(1)).joinToString(". ")
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.tmplayer.ui.components.showMessage(context, said) }
            } else {
                _state.value = State(outcome = outcome, gallery = true)
            }
            running.set(false)
        }
        return true
    }

    /** One video after another into Photos, asking for permission first (add only). */
    private suspend fun run(items: List<Item>, forbidden: Int, unfinished: Int, keys: Set<String>): FolderCopy.Outcome {
        if (!mayAdd()) {
            return FolderCopy.Outcome(folder = label, copied = 0, forbidden = forbidden, unfinished = unfinished, stop = FolderCopy.Stop.ReadOnly, notReached = items.size)
        }
        val total = items.sumOf { it.sizeBytes }
        var bytesDone = 0L
        val saved = ArrayList<String>()
        val verified = ArrayList<String>()
        val failed = ArrayList<FolderCopy.Failure>()
        for ((index, item) in items.withIndex()) {
            if (cancel.get()) {
                return FolderCopy.Outcome(
                    folder = label, copied = saved.size, forbidden = forbidden, unfinished = unfinished, failed = failed,
                    stop = FolderCopy.Stop.Cancelled, notReached = items.size - index, saved = saved, verifiedKeys = verified,
                )
            }
            _state.value = State(progress = FolderCopy.Progress(index, items.size, item.source.title, index.toFloat() / items.size, bytesDone, total), gallery = true, keys = keys)
            val error = addToPhotos(item.path)
            if (error == null) {
                saved += item.source.title
                verified += item.source.key
            } else {
                failed += FolderCopy.Failure(item.source.title, error)
            }
            bytesDone += item.sizeBytes
        }
        return FolderCopy.Outcome(
            folder = label, copied = saved.size, forbidden = forbidden, unfinished = unfinished, failed = failed,
            saved = saved, verifiedKeys = verified,
        )
    }

    private suspend fun mayAdd(): Boolean = suspendCancellableCoroutine { done ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            done.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
        }
    }

    /** Null when the video is in Photos, otherwise why not. */
    private suspend fun addToPhotos(path: String): String? = suspendCancellableCoroutine { done ->
        val url = NSURL.fileURLWithPath(path)
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            { PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url) },
        ) { success, error ->
            done.resume(if (success) null else error?.localizedDescription ?: L.keepExportFailed(""))
        }
    }

    /** "Remove from TMPlayer's downloads after saving", for each video that reached Photos. */
    private suspend fun removeSaved(context: Context, outcome: FolderCopy.Outcome, records: Map<String, ResumeRecord>): FolderCopy.Outcome {
        val settings = SettingsStore(context)
        for (key in outcome.verifiedKeys) {
            val record = records[key] ?: continue
            runCatching { LocalDownloads.delete(settings, record) }
                .onFailure { Logger.w(TAG, "could not remove a saved download", it) }
        }
        return outcome
    }

    fun cancel() = cancel.set(true)

    fun consume() {
        if (!running.get() && _state.value.outcome != null) _state.value = State()
    }

    /** No folder is ever picked on iOS, so there is no last folder to measure. */
    fun lastFolderFree(): Pair<String, Long>? = null

    internal fun folderFor(context: Context, tree: Uri): FolderCopyIo.Folder<Uri>? = null

    internal fun freeBytes(tree: Uri): Long? = null
}
