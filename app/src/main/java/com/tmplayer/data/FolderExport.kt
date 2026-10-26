package com.tmplayer.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.tmplayer.R
import com.tmplayer.i18n.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Save to folder on Android: copies of finished downloads in a folder the viewer picked with the
 * system's folder picker (ACTION_OPEN_DOCUMENT_TREE), written through the Storage Access
 * Framework, so the phone's storage, an SD card, a USB drive or a cloud provider all work the same
 * way and no storage permission is needed.
 *
 * Three steps, all held here rather than on the screen so turning the phone or leaving the app
 * loses none of them: [prepare] works out the [FolderCopy.Plan] (what is copied and what skipped,
 * the sizes, the room in the folder) for the confirmation; [start] runs the copy in
 * [FolderExportService], a data sync foreground service with the progress and a Cancel in its
 * notification; and the outcome waits in [state] for the Downloads screen to show. The folder's
 * grant is held from the pick to the end of the run and given back after.
 *
 * Save to gallery runs the same way into Movies/TMPlayer through MediaStore (Android 10 and
 * later, no permission needed for the app's own files): no picker, so [prepare] is called with no
 * tree. From a video's page it starts at once with no confirmation ([saveToGalleryNow]), its
 * progress on the page's button and in the notification, and its end said in a toast and the shade.
 *
 * The rules (names, skips, the result) are [FolderCopy]'s and the loop is [FolderCopyIo]'s; this
 * is the folder as SAF or MediaStore reaches it.
 */
object FolderExport {

    /** A row the viewer chose: its key on the screen, its record, and whether it is whole here. */
    class Candidate(val key: String, val record: ResumeRecord, val fileId: Int, val title: String, val whole: Boolean)

    /** A video that will be copied: the file and names, its record (for a removal), its size and age. */
    class Item(val source: FolderCopyIo.Source, val record: ResumeRecord, val sizeBytes: Long, val newest: Long)

    /** The confirmation's question: the folder (none for the gallery) and the plan for it. */
    class Offer(val tree: Uri?, val plan: FolderCopy.Plan<Item>) {
        val gallery: Boolean get() = tree == null
    }

    /**
     * What the screen draws: the question, the progress while a run goes, or the outcome once it
     * has ended; whether it is the gallery's, and while copying, the keys of the videos in the run
     * (a video's page rings its button by them).
     */
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

    /** The rows a picker was opened for, kept here so a rotation while it is up loses nothing. */
    @Volatile var pendingKeys: List<String>? = null

    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile private var run: Run? = null

    private class Run(
        val tree: Uri?,
        val items: List<Item>,
        val forbidden: Int,
        val unfinished: Int,
        val leftOut: Int,
        val remove: Boolean,
        /** Started from a video's page, which shows no dialogs: its end is a toast and a notification. */
        val detached: Boolean,
    ) {
        val keys: Set<String> = items.mapTo(HashSet()) { it.source.key }
    }

    /** Whether the run going now is to the gallery, and was started with no screen to show its end. */
    internal val runningToGallery: Boolean get() = run?.tree == null
    internal val runningDetached: Boolean get() = run?.detached == true

    /** Screens showing [state]; with none, the end of a run is said in a notification instead. */
    internal val watchers = AtomicInteger(0)

    /** Where the last folder is on the disk, for the selection's "more than is free there" line. */
    @Volatile private var lastPath: File? = null
    @Volatile private var lastLabel: String = ""

    val busy: Boolean get() = running.get() || _state.value.preparing || _state.value.offer != null

    /** A copy is running (not only asked about). */
    val copying: Boolean get() = running.get()

    private const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * The folder picked: holds its grant, sorts the rows into what is copied and what skipped
     * (unfinished, or from a chat that forbids saving, asked of Telegram now), adds up the sizes
     * and reads the room there, then puts the confirmation up.
     */
    suspend fun prepare(context: Context, tree: Uri?, chosen: List<Candidate>, settings: SettingsStore) {
        val app = context.applicationContext
        _state.value = State(preparing = true, gallery = tree == null)
        if (tree != null) runCatching { app.contentResolver.takePersistableUriPermission(tree, GRANT) }
        val offer = runCatching { plan(app, tree, chosen, settings) }.getOrElse { error ->
            // Cancelled is not a lost folder: nothing is said, and nothing is left preparing.
            if (error is kotlinx.coroutines.CancellationException) {
                _state.value = State()
                throw error
            }
            // Never left "preparing" (which would refuse every later Save to folder): said, and the grant given back.
            Log.w(TAG, "Save to folder could not be prepared", error)
            if (tree != null) runCatching { app.contentResolver.releasePersistableUriPermission(tree, GRANT) }
            _state.value = State(
                outcome = FolderCopy.Outcome(folder = "", copied = 0, stop = FolderCopy.Stop.FolderLost, notReached = chosen.size),
                gallery = tree == null,
            )
            return
        }
        _state.value = State(offer = offer, gallery = tree == null)
    }

    /**
     * Save to gallery from a video's page: one video, no confirmation (nothing is removed, and a
     * gallery without room for it stops before a byte is written and says so). Null when the copy
     * has started, otherwise what to say instead: one already running, the file gone or not
     * finished, or its chat not allowing saving.
     */
    suspend fun saveToGalleryNow(context: Context, candidate: Candidate, settings: SettingsStore): String? {
        if (busy) return L.downloadsSaveBusy
        val app = context.applicationContext
        val plan = runCatching { plan(app, null, listOf(candidate), settings).plan }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Save to gallery could not be prepared", error)
            return L.keepExportFailed(Failures.humanise(error))
        }
        return when {
            plan.forbidden > 0 -> ContentProtection.NOT_SAVABLE
            plan.items.isEmpty() -> L.downloadsFileGone
            !start(app, Offer(null, plan), plan.items, remove = false, detached = true) -> L.downloadsSaveBusy
            else -> null
        }
    }

    private suspend fun plan(app: Context, tree: Uri?, chosen: List<Candidate>, settings: SettingsStore): Offer =
        withContext(Dispatchers.IO) {
            val withFiles = chosen.map { candidate ->
                val path = if (candidate.whole) {
                    runCatching { LocalDownloads.shareablePath(settings, candidate.record.chatId, candidate.record.messageId, candidate.fileId) }.getOrNull()
                } else {
                    null
                }
                candidate to path?.let(::File)?.takeIf { it.isFile }
            }
            val sorted = FolderCopy.sort(
                withFiles,
                whole = { it.second != null },
                canBeSaved = { Td.maySave(it.first.record.chatId, it.first.record.messageId) },
            )
            val items = sorted.copy.map { (candidate, file) ->
                Item(
                    FolderCopyIo.Source(file!!, candidate.record.fileName ?: candidate.title, candidate.title, "", candidate.key),
                    candidate.record,
                    file.length(),
                    candidate.record.updatedAt,
                )
            }
            val label = if (tree == null) GALLERY_LABEL else runCatching { folderFor(app, tree)?.label }.getOrNull().orEmpty()
            val free = if (tree == null) statFree(galleryPath()) else freeBytes(tree)
            if (tree != null) {
                pathOf(tree)?.let {
                    lastPath = it
                    lastLabel = label
                }
            }
            Offer(tree, FolderCopy.plan(items, label, free, sorted.forbidden, sorted.unfinished, { it.sizeBytes }, { it.newest }))
        }

    /** Cancel on the confirmation: nothing copied, the grant given back. */
    fun dismissOffer(context: Context) {
        val offer = _state.value.offer
        offer?.tree?.let { tree -> runCatching { context.applicationContext.contentResolver.releasePersistableUriPermission(tree, GRANT) } }
        if (!running.get()) _state.value = State()
    }

    /** The picker failed or was cancelled before a plan existed. */
    fun dropPreparing() {
        if (_state.value.preparing) _state.value = State()
    }

    /** Starts the confirmed run, or returns false when one is already going. */
    fun start(context: Context, offer: Offer, items: List<Item>, remove: Boolean, detached: Boolean = false): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        cancel.set(false)
        val plan = offer.plan
        val next = Run(offer.tree, items, plan.forbidden, plan.unfinished, plan.items.size - items.size, remove, detached)
        run = next
        _state.value = State(
            progress = FolderCopy.Progress(0, items.size, items.firstOrNull()?.source?.title.orEmpty(), 0f, 0, items.sumOf { it.sizeBytes }),
            gallery = offer.gallery,
            keys = next.keys,
        )
        val started = runCatching {
            val intent = Intent(app, FolderExportService::class.java)
            app.startForegroundServiceCompat(intent)
        }.isSuccess
        // No service allowed (Android 15's data sync allowance spent, say): the copy still runs,
        // in the process, for as long as the app is in use.
        if (!started) CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("FolderExport")).launch { run(app) }
        return true
    }

    fun cancel() = cancel.set(true)

    /** The result has been shown: the screen draws nothing for it any more. */
    fun consume() {
        if (!running.get() && _state.value.outcome != null) _state.value = State()
    }

    /**
     * The folder last saved to and the room left there now, for the selection's warning; null
     * when there was none this session or its room cannot be read.
     */
    fun lastFolderFree(): Pair<String, Long>? {
        val path = lastPath ?: return null
        val free = statFree(path) ?: return null
        return lastLabel.ifBlank { path.name } to free
    }

    /** The run itself, on an IO thread. Returns the outcome after any removals. */
    internal suspend fun run(context: Context, onProgress: (FolderCopy.Progress) -> Unit = {}): FolderCopy.Outcome {
        val next = run
        if (next == null) {
            running.set(false)
            return FolderCopy.Outcome(folder = "", copied = 0)
        }
        val resolver = context.contentResolver
        val gallery = next.tree == null
        var outcome = try {
            val folder: FolderCopyIo.Folder<Uri>? = if (next.tree == null) GalleryFolder(resolver) else folderFor(context, next.tree)
            if (folder == null) {
                FolderCopy.Outcome(
                    folder = "", copied = 0, forbidden = next.forbidden, unfinished = next.unfinished,
                    stop = FolderCopy.Stop.FolderLost, notReached = next.items.size,
                )
            } else {
                FolderCopyIo.copyAll(folder, next.items.map { it.source }, next.forbidden, next.unfinished, { cancel.get() }) {
                    _state.value = State(progress = it, gallery = gallery, keys = next.keys)
                    onProgress(it)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Save to folder failed", error)
            FolderCopy.Outcome(
                folder = "", copied = 0, forbidden = next.forbidden, unfinished = next.unfinished,
                failed = listOf(FolderCopy.Failure(next.items.firstOrNull()?.source?.title.orEmpty(), Failures.humanise(error))),
            )
        }
        if (next.leftOut > 0) outcome = outcome.copy(notReached = outcome.notReached + next.leftOut)
        if (next.remove) outcome = removeSaved(context, outcome, next.items.associate { it.source.key to it.record })
        next.tree?.let { tree -> runCatching { resolver.releasePersistableUriPermission(tree, GRANT) } }
        run = null
        // A video's page shows no result dialog: the end is a toast here and a notification from
        // the service, and nothing waits for the Downloads screen to show it later.
        if (next.detached) {
            _state.value = State()
            val said = if (outcome.clean) outcome.headline else (listOf(outcome.headline) + outcome.lines.take(1)).joinToString(". ")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                com.tmplayer.ui.components.showMessage(context, said)
            }
        } else {
            _state.value = State(outcome = outcome, gallery = gallery)
        }
        running.set(false)
        return outcome
    }

    /**
     * "Remove from TMPlayer's downloads after saving": TMPlayer's own copy of each video whose
     * saved copy was checked whole goes, exactly as Delete on the Downloads screen removes it (the
     * file, then the record), so Downloads, the badges and the space figures agree. Where it was
     * watched up to stays in Continue watching, which plays it from the chat from then on.
     */
    private suspend fun removeSaved(context: Context, outcome: FolderCopy.Outcome, records: Map<String, ResumeRecord>): FolderCopy.Outcome {
        val settings = SettingsStore(context)
        var removed = 0
        var kept = 0
        for (key in outcome.verifiedKeys) {
            val record = records[key] ?: continue
            val gone = runCatching {
                if (record.localPath != null) {
                    LocalDownloads.delete(settings, record)
                } else {
                    val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
                    runCatching { Td.deleteFile(fileId) }
                    val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
                    if (left > 0) {
                        false
                    } else {
                        settings.forgetDownload(record.chatId, record.messageId)
                        true
                    }
                }
            }.getOrDefault(false)
            if (gone) removed++ else kept++
        }
        // A copy saved but not checked (the folder would not give its size) keeps its original.
        kept += (outcome.copied - outcome.verifiedKeys.size).coerceAtLeast(0)
        return outcome.copy(moved = removed, notMoved = kept)
    }

    private fun folderFor(context: Context, tree: Uri): SafFolder? {
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return null
        return SafFolder(context.contentResolver, tree, parent)
    }

    /**
     * Where a folder from the phone's own storage or an SD card is on the disk, for its free
     * space: "primary:Movies/Kept" is /storage/emulated/0/Movies/Kept, "1A2B-3C4D:Videos" is
     * /storage/1A2B-3C4D/Videos. Null for any other provider (a cloud drive has no path).
     */
    private fun pathOf(tree: Uri): File? {
        if (tree.authority != EXTERNAL_STORAGE) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        @Suppress("DEPRECATION")
        val base = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage", volume)
        return if (relative.isEmpty()) base else File(base, relative)
    }

    /** The room in the folder [tree] names, or null when it cannot be read (the confirmation then says so). */
    private fun freeBytes(tree: Uri): Long? = pathOf(tree)?.let(::statFree)

    private fun statFree(path: File): Long? {
        // The nearest folder that exists: StatFs needs a real path, and any one on the volume will do.
        var at: File? = path
        while (at != null && !at.exists()) at = at.parentFile
        return runCatching { StatFs((at ?: path).path).availableBytes }.getOrNull()?.takeIf { it >= 0 }
    }

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Where Save to gallery puts its copies, as MediaStore's relative path and as the viewer reads it. */
    private val GALLERY_PATH = Environment.DIRECTORY_MOVIES + "/TMPlayer"
    private val GALLERY_LABEL = GALLERY_PATH

    @Suppress("DEPRECATION")
    private fun galleryPath(): File = File(Environment.getExternalStorageDirectory(), GALLERY_PATH)

    /**
     * Movies/TMPlayer through MediaStore. Each copy is added hidden (IS_PENDING) so the gallery
     * never lists a part written file, and shown by [finish] once it is whole; one left by a
     * cancel or a failure is deleted, and MediaStore clears any a crash leaves behind.
     */
    private class GalleryFolder(private val resolver: ContentResolver) : FolderCopyIo.Folder<Uri> {

        override val label: String = GALLERY_LABEL

        // Only the app's own earlier copies are visible to it, which is all it needs: MediaStore
        // renames any other clash itself.
        override fun existing(): Collection<String> {
            val names = ArrayList<String>()
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$GALLERY_PATH/"),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) cursor.getString(0)?.let(names::add) }
            return names
        }

        override fun freeBytes(): Long? = statFree(galleryPath())

        // The type from the name's extension, as MediaStore otherwise renames the file to match
        // the type it was given.
        override fun create(name: String, mimeType: String): Uri {
            val type = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?.takeIf { it.startsWith("video/") }
                ?: mimeType
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException(name)
        }

        override fun open(handle: Uri): OutputStream =
            resolver.openOutputStream(handle, "w") ?: throw IOException(handle.toString())

        override fun size(handle: Uri): Long? =
            resolver.openFileDescriptor(handle, "r")?.use { it.statSize }?.takeIf { it >= 0 }

        override fun discard(handle: Uri) {
            resolver.delete(handle, null, null)
        }

        override fun finish(handle: Uri) {
            resolver.update(handle, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        override fun lost(error: Throwable): Boolean = error is SecurityException
    }

    private class SafFolder(
        private val resolver: ContentResolver,
        private val tree: Uri,
        private val parent: Uri,
    ) : FolderCopyIo.Folder<Uri> {

        override val label: String by lazy {
            runCatching {
                resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
                // "primary:Movies/Saved" when the provider will not say: the part after the colon.
                ?: DocumentsContract.getTreeDocumentId(tree).let { id -> id.substringAfter(':').ifBlank { id } }
        }

        override fun writable(): Boolean {
            val flags = resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            } ?: throw FileNotFoundException(tree.toString())
            return flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }

        override fun existing(): Collection<String> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            val names = ArrayList<String>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
            } ?: throw FileNotFoundException(tree.toString())
            return names
        }

        // The type from the name's own extension: a provider given a type that does not match it
        // adds an extension of its own ("Film.mkv.mp4"), and a wildcard is refused outright.
        override fun create(name: String, mimeType: String): Uri {
            val type = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            return DocumentsContract.createDocument(resolver, parent, type, name) ?: throw IOException(name)
        }

        override fun open(handle: Uri): OutputStream =
            resolver.openOutputStream(handle, "w") ?: throw IOException(handle.toString())

        override fun size(handle: Uri): Long? =
            resolver.query(handle, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }

        override fun discard(handle: Uri) {
            DocumentsContract.deleteDocument(resolver, handle)
        }

        // A grant taken back (the folder's app uninstalled, the card or drive taken out, the
        // permission revoked) is a SecurityException; a folder deleted under the copy answers
        // "not found" for its tree.
        override fun lost(error: Throwable): Boolean =
            error is SecurityException || error is IllegalArgumentException || error is IllegalStateException ||
                (error is FileNotFoundException && runCatching { !exists(parent) }.getOrDefault(true))

        private fun exists(uri: Uri): Boolean =
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } ?: false
    }

    private const val TAG = "FolderExport"
}

/**
 * Keeps a Save to folder going with the app out of sight: the notification shows which video of
 * how many and its bar, with Cancel, and the service stops itself at the end. Started only by
 * [FolderExport.start].
 */
class FolderExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("FolderExport"))
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FolderExport.cancel()
            return START_NOT_STICKY
        }
        AndroidTransferNotifier.ensureChannel(this)
        val foreground = runCatching {
            val first = notification(FolderExport.state.value.progress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        }.onFailure { Log.w(TAG, "Android would not let Save to folder run in the foreground", it) }.isSuccess
        if (started) return START_NOT_STICKY
        started = true
        val detached = FolderExport.runningDetached
        scope.launch {
            var lastShown = -1
            val outcome = FolderExport.run(applicationContext) { progress ->
                // A step per few percent: a notification posted per buffer is throttled by Android.
                val step = (progress.overall * 1000).toInt()
                if (foreground && step / 5 != lastShown / 5) {
                    lastShown = step
                    runCatching { notifier().notify(NOTIFICATION_ID, notification(progress)) }
                }
            }
            runCatching { stopForegroundRemoving() }
            // Nobody on the Downloads screen to see the result, or a copy started from a video's
            // page, which has no dialog for it: it is said in the shade as well.
            if (detached || FolderExport.watchers.get() == 0) {
                runCatching {
                    notifier().notify(
                        DONE_ID,
                        builder().setContentTitle(outcome.headline)
                            .setContentText(outcome.lines.firstOrNull())
                            .setStyle(Notification.BigTextStyle().bigText(outcome.lines.joinToString("\n")))
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // The system taking the service down mid copy: the loop stops at its next buffer and
        // removes the part copy, rather than writing on with nothing to show it.
        if (FolderExport.copying) FolderExport.cancel()
    }

    private fun notification(progress: FolderCopy.Progress?): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, FolderExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (FolderExport.runningToGallery) L.downloadsSaveToGalleryTitle else L.downloadsSaveToFolderTitle
        return builder()
            .setContentTitle(progress?.line ?: title)
            .setContentText(listOfNotNull(progress?.title, progress?.bytesLine).joinToString("  ·  "))
            .setOngoing(true)
            .setProgress(1000, ((progress?.overall ?: 0f) * 1000).toInt(), progress == null)
            .addAction(Notification.Action.Builder(null, L.commonCancel, cancelIntent).build())
            .build()
    }

    private fun builder(): Notification.Builder =
        notificationBuilder(this, AndroidTransferNotifier.CHANNEL_ID, OldAndroid.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(AndroidTransferNotifier.openDownloads(this))
            .setOnlyAlertOnce(true)

    private fun notifier(): NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val ACTION_CANCEL = "com.tmplayer.folderexport.CANCEL"
        // Clear of DownloadService's (4200 and the 65536 after it) and the player's.
        const val NOTIFICATION_ID = 90_001
        const val DONE_ID = 90_002
        const val TAG = "FolderExportService"
    }
}
