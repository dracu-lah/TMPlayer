package com.tmplayer.ui.browse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.KeepNextPlanner
import com.tmplayer.data.KeepNextRunner
import com.tmplayer.data.KeepNextShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LowStorageOffers
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.start
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Putting videos on the download queue, shared by every place that offers it: a chat's grid and its
 * multi-select, and the detail panel wherever it opens (a chat, Home, the all-chats search).
 */

/**
 * Queues [chosen], having first asked the disk whether they fit, and returns what to tell the
 * viewer, or null when there was nothing to queue.
 *
 * Every route in must come through this plan: calling [OfflineDownloads.start] directly skips the
 * watch cache eviction in [planDownloads], and the service's late check then refuses the video on
 * raw free space alone. A video whose chat restricts saving content is dropped here too.
 */
internal suspend fun queueDownloads(
    context: Context,
    settings: SettingsStore,
    ticked: List<MediaItem>,
    chatTitle: (MediaItem) -> String,
): String? {
    val savable = ticked.filter { it.canBeSaved }
    if (savable.isEmpty()) return null
    // A second press while the first is still being planned (a double press, or Download season
    // over an episode just asked for) leaves those videos to the first, whose sentence answers
    // both: planned twice, the watch cache was given up twice and the viewer told twice.
    val claimed = QueueingDownloads.claim(savable.map { it.fileId })
    val chosen = savable.filter { it.fileId in claimed }
    if (chosen.isEmpty()) return null
    var handedOver = false
    try {
        return planAndQueue(context, settings, chosen, chatTitle).also { handedOver = true }
    } finally {
        if (!handedOver) QueueingDownloads.release(claimed)
    }
}

/** [queueDownloads] once the videos are its own to plan. */
private suspend fun planAndQueue(
    context: Context,
    settings: SettingsStore,
    chosen: List<MediaItem>,
    chatTitle: (MediaItem) -> String,
): String {
    // Android 13 counts a download's progress notification as one the viewer has to have agreed
    // to. Asked here rather than at first launch, because here is the one moment the request
    // explains itself.
    askForNotifications(context)
    // Off the main thread for the whole of the decision: it walks every download record with a
    // TDLib round trip each, a statvfs() for the free space and a stat() per ticked video, which on
    // the drawing thread would freeze the listing for its whole length.
    val (taken, message) = withContext(Dispatchers.Default) {
        // Already in Downloads: nothing to do. Whole in the cache: straight to the queue, which
        // moves it into Downloads without fetching a byte, so the disk has no say.
        val inDownloads = LocalDownloads.presentIds(settings)
        val coming = OfflineDownloads.active.value
        val (wholeHere, toPlan) = chosen
            .filter { it.id !in inDownloads && !coming.containsKey(it.fileId) }
            .partition { runCatching { Td.isFileCached(it.fileId) }.getOrDefault(false) }
        val (planTaken, planMessage) = planDownloads(context, settings, toPlan, chatTitle)
        val all = wholeHere + planTaken
        all to when {
            all.isEmpty() && toPlan.isEmpty() && chosen.size == 1 -> L.gridAlreadyDownloadedOne
            all.isEmpty() && toPlan.isEmpty() -> L.gridAlreadyDownloadedMany
            toPlan.isEmpty() && all.size == 1 -> L.gridSavingOne
            toPlan.isEmpty() -> L.gridSavingMany(all.size)
            else -> planMessage
        }
    }
    taken.forEach { OfflineDownloads.start(context, it, chatTitle(it)) }
    QueueingDownloads.releaseOnceQueued(chosen.map { it.fileId }.toSet(), taken)
    // A refusal costs the progress in the shade: the line says so, once the system has nothing left to ask.
    return if (taken.isEmpty()) message else withBlockedLine(context, message)
}

/**
 * The files a press asked for that the queue does not show yet: from the press, through the plan
 * against the disk, until the download service has noted them (a moment after it is started).
 * A detail page draws its button as taken meanwhile, and [queueDownloads] lets a second press on
 * the same file go, so a double press plans and queues it once.
 */
internal object QueueingDownloads {
    private val _fileIds = MutableStateFlow<Set<Int>>(emptySet())
    val fileIds: StateFlow<Set<Int>> = _fileIds.asStateFlow()

    /** Takes the ones of [ids] nobody is planning yet, and returns those. */
    fun claim(ids: Collection<Int>): Set<Int> {
        var taken = emptySet<Int>()
        _fileIds.update { now ->
            taken = ids.toSet() - now
            now + taken
        }
        return taken
    }

    fun release(ids: Set<Int>) = _fileIds.update { it - ids }

    /**
     * Lets go of [claimed] at once except for the [queued] videos, which are held until the queue
     * shows them, or for a few seconds at most should the service never say (Android refusing to
     * start it is noted as a failed row, which counts as shown). Without the wait the button went
     * back to Download for a moment between the plan and the service.
     */
    fun releaseOnceQueued(claimed: Set<Int>, queued: List<MediaItem>) {
        val waiting = queued.map { it.fileId }.toSet()
        release(claimed - waiting)
        if (waiting.isEmpty()) return
        // A video kept as byte pieces is on the queue as its pieces, not as itself.
        val shownBy = queued.map { item -> if (item.isJoined) item.pieces.map { it.fileId } else listOf(item.fileId) }
        com.tmplayer.App.backgroundScope.launch {
            withTimeoutOrNull(SHOWN_WITHIN_MS) {
                OfflineDownloads.active.first { active -> shownBy.all { ids -> ids.any { it in active } } }
            }
            release(waiting)
        }
    }

    private const val SHOWN_WITHIN_MS = 5_000L
}

/**
 * "Download season" or "Download unwatched" from a show's page: one copy per episode, as
 * [SeasonDownloadRequest] chooses them, skipping every episode already downloaded or on the queue
 * in any of its copies, then [queueDownloads] for the room and the sentence, as a ticked selection
 * gets. An episode list with nothing left to fetch says so in the selection's words too.
 */
internal suspend fun queueSeason(
    context: Context,
    settings: SettingsStore,
    request: SeasonDownloadRequest,
    chatTitle: (MediaItem) -> String,
): String? {
    val chosen = withContext(Dispatchers.Default) {
        val inDownloads = LocalDownloads.presentIds(settings)
        val coming = OfflineDownloads.active.value
        request.copies { it.id in inDownloads || coming.containsKey(it.fileId) }
    }
    if (chosen.isEmpty()) return L.gridAlreadyDownloadedMany
    return queueDownloads(context, settings, chosen, chatTitle)
}

/**
 * The videos that need fetching, planned against the disk: which fit, and what the watch cache
 * gives up to make room. Returns the ones to queue and what to tell the viewer.
 */
private suspend fun planDownloads(
    context: Context,
    settings: SettingsStore,
    chosen: List<MediaItem>,
    chatTitle: (MediaItem) -> String,
): Pair<List<MediaItem>, String> {
    if (chosen.isEmpty()) return emptyList<MediaItem>() to ""
    return run {
        // Only the watch cache is on offer here. A video left behind by a press of Play
        // should not be the reason a video somebody ticked is refused; the videos they
        // downloaded on purpose are not touched either way.
        val cachedRecords = runCatching { settings.cachedVideosNow() }
            .getOrDefault(emptyList())
        // Measured and later deleted through the id the message answers with now, not the
        // one saved with the record: a saved id from an earlier session measures as zero
        // and deletes nothing. See [WatchCache.evictAllBut].
        val owned = cachedRecords.mapNotNull { record ->
            val fileId = runCatching {
                Td.currentFileId(record.chatId, record.messageId, record.fileId)
            }.getOrDefault(record.fileId)
            val bytes = runCatching { Td.localDownloadedBytes(fileId) }
                .getOrDefault(0L)
            if (bytes <= 0) null else CacheShelf.Held(fileId, bytes, record.updatedAt) to record
        }
        val cached = owned.map { it.first }
        val owners = owned.associate { it.first.fileId to it.second }
        val coming = OfflineDownloads.active.value
        val candidates = chosen.map { item ->
            CacheShelf.Candidate(
                fileId = item.fileId,
                sizeBytes = item.sizeBytes,
                partialBytes = runCatching { Td.localDownloadedBytes(item.fileId) }
                    .getOrDefault(0L),
                alreadyHere = coming.containsKey(item.fileId) ||
                    runCatching { Td.isFileCached(item.fileId) }.getOrDefault(false),
            )
        }
        // The disk decides here, and nothing else. Ticking three videos is a viewer asking
        // for three videos, so all three go on the queue and the only thing that can turn
        // one away is there being no room for it.
        val batch = CacheShelf.planBatch(
            candidates = candidates,
            cached = cached,
            freeBytes = DiskSpace.read(context).freeBytes,
        )
        // Spent before the first byte is fetched, so the room the plan counted on is
        // actually there by the time the queue starts.
        if (batch.reclaimFileIds.isNotEmpty()) {
            for (fileId in batch.reclaimFileIds) {
                runCatching { Td.deleteFile(fileId) }
                val record = owners[fileId] ?: continue
                val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
                if (left <= 0) {
                    runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                }
            }
        }
        if (batch.outOfSpace) offerLowStorage(context, settings, chosen, candidates, batch, chatTitle)
        val taken = batch.fits.map { chosen[it] }
        taken to batchMessage(batch, chosen.size, big = taken.singleOrNull()?.takeIf { it.sizeBytes >= BIG_DOWNLOAD_BYTES }, freeBytes = DiskSpace.read(context).freeBytes)
    }
}

/**
 * A download refused for room still needs room for the whole file, whatever the mode. When Low
 * storage mode is off and the first refused video would stream in it, the viewer is asked whether
 * to switch and watch it instead. Every time, not once: the refusal message stands either way.
 */
private suspend fun offerLowStorage(
    context: Context,
    settings: SettingsStore,
    chosen: List<MediaItem>,
    candidates: List<CacheShelf.Candidate>,
    batch: CacheShelf.Batch,
    chatTitle: (MediaItem) -> String,
) {
    if (runCatching { settings.lowStorageModeNow() }.getOrDefault(true)) return
    val refused = candidates.indices.firstOrNull { it !in batch.fits && it !in batch.alreadyHere } ?: return
    val candidate = candidates[refused]
    // Measured again: the cache the batch gave up is gone by now, so this is the room there is.
    val plan = CacheShelf.plan(
        targetFileId = candidate.fileId,
        targetSizeBytes = candidate.sizeBytes,
        targetPartialBytes = candidate.partialBytes,
        alreadyCached = false,
        cached = emptyList(),
        freeBytes = DiskSpace.read(context).freeBytes,
        streaming = true,
    )
    if ((plan as? CacheShelf.Plan.NotEnoughSpace)?.fitsInLowStorage != true) return
    val item = chosen[refused]
    LowStorageOffers.ask(LowStorageOffers.Ask(item, chatTitle(item), keep = true))
}

/** From this size one download says what it is and how much room is left, as it starts. */
private const val BIG_DOWNLOAD_BYTES = 1L shl 30

/**
 * What to say about a selection the device could not take whole.
 *
 * The count is quoted rather than hidden behind "some could not be downloaded", because a viewer
 * who ticked ten and got seven needs to know which number to act on. [wanted] is everything ticked,
 * including whatever was on the device already: those are subtracted here, so the sentence counts
 * the videos that actually needed fetching.
 */
private fun batchMessage(batch: CacheShelf.Batch, wanted: Int, big: MediaItem? = null, freeBytes: Long = 0L): String {
    val taken = batch.fits.size
    val needed = wanted - batch.alreadyHere.size
    // Room is the only thing that can refuse a tick, and by this point the watch cache has already
    // been handed over, so there is genuinely nothing left to give.
    val noRoom = L.gridNoRoom
    return when {
        needed == 0 && wanted == 1 -> L.gridAlreadyDownloadedOne
        needed == 0 -> L.gridAlreadyDownloadedMany
        taken == 0 -> L.gridNoneFit(room = noRoom)
        taken < needed -> L.gridSomeFit(taken = taken, needed = needed, room = noRoom)
        // One video over a gigabyte says its size and the room left (Raju 5.2): the press that costs the most says so.
        taken == 1 && big != null && freeBytes > 0 -> L.gridDownloadingOneBig(
            size = Translator.messages.formatter.bytes(big.sizeBytes),
            free = Translator.messages.formatter.bytes(freeBytes),
        )
        taken == 1 -> L.gridDownloadingOne
        // They are fetched one at a time, so what the viewer is told is what they will see on the
        // Downloads screen: one coming down and the rest waiting their turn.
        else -> L.gridQueuedMany(taken)
    }
}

/**
 * Asks for the notification permission on the versions that have one, when the system will still
 * show its question ([NotificationAsk]; the answer is read there, through the Activity Result API).
 *
 * A refusal is not treated as a failure: the download still runs, it simply runs without a bar to
 * watch, and the line under it says so. Nothing here waits on the answer, because the fetch is the
 * thing the viewer pressed.
 */
internal fun askForNotifications(context: Context) = com.tmplayer.data.NotificationAsk.request(context)

/**
 * [message] with what a refusal costs, when notifications cannot reach the viewer and the system
 * has no question left to put (WP-38): the progress shows in Downloads only.
 */
internal fun withBlockedLine(context: Context, message: String): String =
    if (com.tmplayer.data.NotificationAsk.pending || com.tmplayer.data.BackgroundChecks.canNotify(context)) {
        message
    } else {
        "$message\n${L.notifyBlockedCardLine}"
    }

/**
 * Hands the downloaded file to another app, either to send or to play.
 *
 * Through a `FileProvider`, because TDLib keeps its files inside this app's own storage and a
 * `file://` path handed across a process boundary is a `FileUriExposedException` on every Android
 * this app runs on. The grant is read-only and lasts as long as the other app's task.
 */
internal suspend fun shareVideo(context: Context, item: MediaItem, send: Boolean) {
    val path = LocalDownloads.shareablePath(SettingsStore(context), item.chatId, item.messageId, item.fileId)
    if (path.isNullOrBlank()) {
        // Not a state the menu should be able to reach, since these two entries only appear for a
        // file already on the phone. Said out loud anyway: silence here is indistinguishable from
        // a press that missed.
        Log.w(SHARE_TAG, "No local file for ${item.fileId}")
        com.tmplayer.ui.components.showMessage(context, L.gridShareNotHere)
        return
    }
    val uri = runCatching {
        com.tmplayer.data.LocalVideo.handoutUri(context, path) ?: error("Nothing to hand out")
    }.onFailure { Log.w(SHARE_TAG, "Cannot share $path", it) }.getOrNull() ?: run {
        com.tmplayer.ui.components.showMessage(context, L.gridShareFailed)
        return
    }

    val mime = item.mimeType.ifBlank { "video/*" }
    val intent = if (send) {
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, item.title)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // The chooser is deliberate on both: ACTION_VIEW without one lands in whatever app once won
    // the default, which for a video on most phones is this one, and that is a loop.
    val chooser = Intent.createChooser(intent, if (send) L.commonShare else L.gridOpenWith)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure {
        Log.w(SHARE_TAG, "No app took the video", it)
        com.tmplayer.ui.components.showMessage(context, L.gridNoAppOpens)
    }
}

private const val SHARE_TAG = "ShareVideo"

/**
 * Smart downloads on the phone and tablet (WP-83): [KeepNextRunner] wired to this app. A pass runs
 * when a video is marked watched, when the connection changes, and when a show's switch changes,
 * a few seconds after the last of a burst; never by itself otherwise, so an idle phone or a TV
 * stick pays nothing. Each pass runs on [com.tmplayer.data.Accounts.profileScope] for the open
 * profile, which a switch cancels: the triggers are listened to again for the next profile, and
 * what the last one's shows said is forgotten.
 *
 * What it reads costs no Telegram listing: a show's episodes are the chat's kept listing
 * ([com.tmplayer.data.PageQueries]), so a show whose chat this device has not listed is skipped
 * until it is opened. Queueing goes through [queueDownloads] like a press, so the disk is asked
 * again, the watch cache gives way first and a chat that restricts saving is dropped there too.
 * Offline or on mobile data nothing is queued: smart downloads are Wi-Fi only.
 */
internal object KeepNextDownloads {

    @Volatile
    private var runner: KeepNextRunner? = null

    private val empty = MutableStateFlow<Map<String, KeepNextRunner.Report>>(emptyMap())

    /** What each show's last pass said, for the Downloads row (WP-84); empty before the first pass. */
    val reports: StateFlow<Map<String, KeepNextRunner.Report>> get() = runner?.reports ?: empty

    private val idle = MutableStateFlow(false)

    /** A pass runs now, for the Downloads row's status line (WP-84). */
    val running: StateFlow<Boolean> get() = runner?.running ?: idle

    /** Asks for a pass now (a show's switch turned on), folded into one already running. */
    private val asked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun ask() {
        asked.tryEmit(Unit)
    }

    /** Once, from the application: the runner, and its triggers on the open profile's scope. */
    fun start(context: Context) {
        if (runner != null) return
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val watched = com.tmplayer.data.WatchedStore(app)
        val made = KeepNextRunner(host(app, settings, watched))
        runner = made
        listen(made, settings, watched)
        com.tmplayer.data.Accounts.onSwitch {
            made.forget()
            listen(made, settings, watched)
        }
    }

    @OptIn(FlowPreview::class)
    private fun listen(made: KeepNextRunner, settings: SettingsStore, watched: com.tmplayer.data.WatchedStore) {
        com.tmplayer.data.Accounts.profileScope.launch {
            try {
                merge(
                    watched.watched.map { it.size }.distinctUntilChanged().drop(1).map { },
                    com.tmplayer.data.NetworkMonitor.status.drop(1).map { },
                    com.tmplayer.data.NetworkMonitor.metered.drop(1).map { },
                    settings.keepNextShows.drop(1).map { },
                    asked,
                )
                    .debounce(TRIGGER_SETTLE_MS)
                    .collect {
                        try {
                            made.pass()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(KEEP_NEXT_TAG, "Keep next pass failed", e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(KEEP_NEXT_TAG, "Keep next stopped listening", e)
            }
        }
    }

    private fun host(app: Context, settings: SettingsStore, watched: com.tmplayer.data.WatchedStore) = object : KeepNextRunner.Host {
        override val stamp get() = com.tmplayer.data.Accounts.stamp

        override suspend fun shows() = settings.keepNextShowsNow()

        override suspend fun episodes(show: KeepNextShow): KeepNextRunner.Listed? = withContext(Dispatchers.Default) {
            val series = keptSeries(show) ?: return@withContext null
            val finished = runCatching { watched.watched.first() }.getOrDefault(emptyMap())
            val inDownloads = LocalDownloads.presentIds(settings)
            val coming = OfflineDownloads.active.value
            val episodes = KeepNextPlanner.episodesOf(
                series,
                finished = { com.tmplayer.data.ProgressKeys.of(it.chatId, it.messageId) in finished },
                copyOf = {
                    when {
                        it.id in inDownloads -> KeepNextPlanner.Copy.Here
                        coming.containsKey(it.fileId) -> KeepNextPlanner.Copy.Queued
                        else -> KeepNextPlanner.Copy.None
                    }
                },
                keptForYou = { false },
            )
            KeepNextRunner.Listed(series.title, episodes)
        }

        override suspend fun conditions(): KeepNextRunner.Conditions {
            val offline = com.tmplayer.data.NetworkMonitor.status.value == com.tmplayer.data.NetworkStatus.Offline
            val free = withContext(Dispatchers.IO) { runCatching { DiskSpace.read(app).freeBytes }.getOrDefault(0L) }
            // Offline counts as "not on Wi-Fi": the queue would only wait, so nothing is added.
            return KeepNextRunner.Conditions(freeBytes = free, metered = offline || com.tmplayer.data.NetworkMonitor.metered.value)
        }

        override suspend fun queue(show: KeepNextShow, title: String, items: List<MediaItem>): Int {
            queueDownloads(app, settings, items) { title }
            return items.size
        }

        override suspend fun sleep(ms: Long) = delay(ms)

        override fun log(line: String) {
            Log.i(KEEP_NEXT_TAG, line)
        }
    }

    /** [show] as the chat's kept listing groups it, or null when the chat is not listed here or the show is gone. */
    fun keptSeries(show: KeepNextShow): com.tmplayer.data.Series? {
        val cache = com.tmplayer.data.PageQueries.cache
        val grid = cache.data<com.tmplayer.data.ChatGrid>(com.tmplayer.data.QueryKey.ChatMedia(show.chatId, 0))
            ?: com.tmplayer.data.PageQueries.lastTopic(show.chatId)?.let {
                cache.data<com.tmplayer.data.ChatGrid>(com.tmplayer.data.QueryKey.ChatMedia(show.chatId, it))
            }
            ?: return null
        return com.tmplayer.data.SeriesShelf.arrange(grid.items)
            .firstNotNullOfOrNull { (it as? com.tmplayer.data.ShelfEntry.Show)?.series?.takeIf { s -> s.key == show.showKey } }
    }

    /** A burst of triggers (several marks, a network flapping) settles into one pass. */
    private const val TRIGGER_SETTLE_MS = 5_000L
    private const val KEEP_NEXT_TAG = "KeepNext"
}
