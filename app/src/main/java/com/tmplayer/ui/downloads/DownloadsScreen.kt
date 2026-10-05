package com.tmplayer.ui.downloads

import com.tmplayer.ui.nav.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.tmplayer.data.LegacyDownloads
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.start
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.cancel
import com.tmplayer.data.pause
import com.tmplayer.data.pauseAll
import com.tmplayer.data.resume
import com.tmplayer.data.resumeAll
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.launch

/**
 * One video on this device, as the screen knows it: what names it, and what it holds on disk.
 *
 * [bytes] is what is actually on the disk rather than the size the message advertised, so a video
 * that stopped half way says how much room it is really taking and not how much it wanted.
 * [totalBytes] is what it wanted, kept beside it so a part-loaded row can say "710 MB of 1.4 GB"
 * instead of leaving the viewer to wonder why the figures on this screen do not add up.
 *
 * Downloads and cached videos are both rows, in different tabs, and [cached] says which: a
 * download is in the Downloads folder until the viewer deletes it, and a cached video is what
 * playing left behind, which the next play may take. [state] is where a download's file stands
 * against the index; [fileId] is the TDLib id resolved against this session, for the rows TDLib
 * still holds.
 */
private data class DownloadRow(
    val key: String,
    val title: String,
    val bytes: Long,
    val totalBytes: Long,
    val complete: Boolean,
    val record: ResumeRecord,
    val chatTitle: String = "",
    val durationSec: Int = 0,
    val state: LocalDownloads.FileState = LocalDownloads.FileState.Present,
    val cached: Boolean = false,
    val fileId: Int = record.fileId,
) {
    /** Part loaded and not being fetched by anything: bytes sitting there for no one. */
    val partial: Boolean get() = !complete && bytes > 0

    /** Recorded as a download, with nothing left of it on the disk. */
    val missing: Boolean get() = !cached && state == LocalDownloads.FileState.Missing
}

/**
 * Everything this device has downloaded or is waiting for, what it costs, and the way to be rid
 * of it.
 *
 * Both form factors keep this list. The one-video-at-a-time arrangement on a television belongs
 * to the watch cache, which playing fills and the next play replaces; a download the viewer asked
 * for by name is kept on a TV exactly as it is on a phone, until it is deleted here. Keeping every
 * download until told otherwise is only a fair deal if the viewer can see them, so a phone reaches
 * this screen from the drawer and a television from the rail, and every control on it has to be
 * findable with a D-pad as well as a thumb.
 *
 * Every row is a card, and every card carries its buttons underneath it at full width with their
 * names written on them: a card has an edge, so it is obvious where one video stops and the next
 * begins, and a named button is a thumb-sized target that cannot be mistaken for its neighbour.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onPlay: (ResumeRecord) -> Unit,
    onBack: () -> Unit,
    /** Opens on the cached videos rather than the downloads, for Settings' Cached videos row. */
    openOnCached: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val toast = rememberToast()
    val history by settings.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val removeAfterWatching by settings.removeAfterWatching.collectAsStateWithLifecycle(initialValue = false)
    // What is arriving and what is behind it, which the finished-downloads record knows nothing
    // about until the file has landed. In the order the videos were asked for, which is the order
    // they will arrive in, so the list does not reshuffle itself as the figures move.
    val activeMap by OfflineDownloads.active.collectAsStateWithLifecycle()
    val active = remember(activeMap) { activeMap.values.sortedBy { it.order } }

    // Each waiting video's place in the queue, worked out once for the list rather than once for
    // every row in it. See where it is read, below.
    val queuePlaces = remember(active) {
        active.filter { it.stage == OfflineDownloads.Stage.Queued }
            .withIndex()
            .associate { (index, progress) -> progress.fileId to index }
    }

    // What watching left behind. None of it is a download and none of it was asked for, but every
    // byte of it is on the disk, and anything taking up room belongs where room is managed.
    val cached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var cachedRows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    // Empty until the first measurement lands, never read during composition: reading the disk is
    // a blocking statvfs().
    var disk by remember { mutableStateOf(DiskInfo.EMPTY) }
    // The same measurement the Settings card draws, so the two panels cannot disagree about a
    // disk they are both looking at.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    var confirmingClearAll by remember { mutableStateOf(false) }
    // Which row the viewer pressed Delete on, and therefore what the dialog is about to remove.
    // The row rather than a bare boolean, because the dialog has to name the video: nothing here
    // may be deleted without being named first.
    var confirmingDelete by remember { mutableStateOf<DownloadRow?>(null) }
    // Which finished downloads are ticked, by the key their row is drawn with. Keys rather than
    // rows: the list behind them is rebuilt from TDLib whenever anything finishes, so a held row
    // would go stale the moment it mattered.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picking by remember { mutableStateOf(false) }
    // Which tab is up. Opens on whichever has something in it: somebody who just queued three
    // videos wants the queue, and somebody opening this on a quiet phone wants what they have.
    var tab by rememberSaveable { mutableStateOf(if (openOnCached) CACHED else COMPLETED) }
    var landedOnATab by rememberSaveable { mutableStateOf(openOnCached) }
    LaunchedEffect(active.isNotEmpty()) {
        if (landedOnATab) return@LaunchedEffect
        if (active.isNotEmpty()) tab = ONGOING
        landedOnATab = true
    }
    var confirmingDeleteMany by remember { mutableStateOf(false) }

    fun leavePicking() {
        picking = false
        picked = emptySet()
    }

    // Only the rows that are still on the list. A selection that outlived its videos would offer
    // to delete or share things that are no longer there.
    val chosen = remember(picked, rows) { rows.filter { it.key in picked } }

    // Every row, because every row is a download.
    val shown = rows

    // A selection belongs to the tab it was made on. Carried across, its bar would sit under a
    // list of downloads it says nothing about.
    LaunchedEffect(tab) { if (tab != COMPLETED) leavePicking() }

    BackHandler(enabled = picking) { leavePicking() }
    // Nothing is known until the first pass over TDLib has finished, and an empty list drawn in
    // the meantime reads as "you have downloaded nothing" rather than as "still counting".
    var counted by remember { mutableStateOf(false) }

    /**
     * Measures a record against the disk, or drops it when there is nothing there any more.
     *
     * A row is kept as soon as it holds a byte, finished or not: half a video is still half a
     * gigabyte, and hiding it hides exactly the space the viewer came here looking for.
     */
    suspend fun measure(record: ResumeRecord): DownloadRow? {
        val row = DownloadRow(
            key = "kept_${record.chatId}_${record.messageId}",
            title = record.title,
            bytes = 0,
            totalBytes = record.sizeBytes,
            complete = false,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
        )
        // The index first: a download with a path is a file in the Downloads folder, measured on
        // the disk, and TDLib knows nothing about it any more.
        when (val state = LocalDownloads.stateOf(record)) {
            LocalDownloads.FileState.Present -> {
                val bytes = java.io.File(record.localPath!!).length()
                return row.copy(bytes = bytes, complete = true, state = state)
            }
            LocalDownloads.FileState.Missing -> return row.copy(state = state)
            LocalDownloads.FileState.Legacy -> Unit
        }
        // From before downloads had a folder: still in TDLib's cache, asked of TDLib. Against
        // this session's id, not the one saved with the row, which stops resolving after a restart.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        // Nothing left of it: said, not hidden, so the viewer learns something took it.
        if (bytes <= 0 || availability == LocalFileAvailability.Missing) {
            return row.copy(state = LocalDownloads.FileState.Missing, fileId = fileId)
        }
        return row.copy(
            bytes = bytes,
            complete = availability == LocalFileAvailability.Complete,
            state = LocalDownloads.FileState.Legacy,
            fileId = fileId,
        )
    }

    /**
     * A video playing left behind, measured through TDLib, or null when nothing of it is left.
     * One that is also a download is the download's, and is listed there instead.
     */
    suspend fun measureCached(record: ResumeRecord, downloaded: Set<Pair<Long, Long>>): DownloadRow? {
        if ((record.chatId to record.messageId) in downloaded) return null
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (bytes <= 0) return null
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        return DownloadRow(
            key = "cached_${record.chatId}_${record.messageId}",
            title = record.title,
            bytes = bytes,
            totalBytes = record.sizeBytes,
            complete = availability == LocalFileAvailability.Complete,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
            cached = true,
            fileId = fileId,
        )
    }

    /**
     * Re-measures the disk and rebuilds every row.
     *
     * All of it on [Dispatchers.IO]. Callers are `LaunchedEffect`s, which resume on the Main
     * dispatcher, and one pass is a `statvfs()`, several TDLib round trips per record and a
     * `walkTopDown` of five media directories with a `length()` on every file in them: left on
     * Main that is a frozen screen on entry and again every time a download finishes.
     *
     * The state is written once, at the end, back on the caller's dispatcher.
     */
    suspend fun refresh(known: List<ResumeRecord>) {
        data class Measured(
            val rows: List<DownloadRow>,
            val cached: List<DownloadRow>,
            val split: StorageSplit,
            val disk: DiskInfo,
        )

        val measured = withContext(Dispatchers.IO) {
            val readDisk = DiskSpace.read(context)
            val measuredSplit = runCatching { StorageSplit.measure(context) }
                .getOrDefault(StorageSplit.EMPTY)
            // Every record measured at once rather than one after another: they are independent
            // questions of TDLib, and fifty of them fifty waits deep reads as a hang.
            val keptRows = known
                .map { async { measure(it) } }
                .awaitAll()
                .filterNotNull()
            val downloadedMessages = known.map { it.chatId to it.messageId }.toSet()
            val cachedNow = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .map { async { measureCached(it, downloadedMessages) } }
                .awaitAll()
                .filterNotNull()

            Measured(keptRows, cachedNow, measuredSplit, readDisk)
        }

        rows = measured.rows
        cachedRows = measured.cached
        split = measured.split
        disk = measured.disk
        counted = true
    }

    // One trigger, not three. These keys all change together when a download finishes, and three
    // separate effects would interleave three passes of the audit above, each writing `rows` when
    // it finished, so the slowest could land last and put stale figures back on screen.
    LaunchedEffect(history, cached.map { it.fileId }, active.size) { refresh(history) }
    fun share(ticked: List<DownloadRow>) {
        scope.launch {
            // The records never knew whether their chat restricts saving content, and a channel can
            // turn it on after the fact, so Telegram is asked at the moment of handing them on.
            val these = ticked.filter { Td.maySave(it.record.chatId, it.record.messageId) }
            if (these.isEmpty()) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            val files = these.mapNotNull { row ->
                val record = row.record
                // The download's own file when it has one; otherwise TDLib's, through the id this
                // session knows it by rather than the one saved with the row.
                val path = LocalDownloads.shareablePath(settings, record.chatId, record.messageId, row.fileId)
                path?.let { it to record.title }
            }
            val intent = ShareMedia.intentFor(context, files)
            if (intent == null) {
                // All of them part downloaded or gone. Saying so beats an empty share sheet,
                // which reads as the button being broken.
                toast("Nothing to share yet. These videos are not fully downloaded.")
                return@launch
            }
            runCatching { context.startActivity(intent) }
                .onFailure { toast("No app on this phone can take a video.") }
            leavePicking()
        }
    }

    /**
     * Removes one video, whichever of the three kinds it is.
     *
     * The record is only forgotten once the file has actually gone. Forgetting it regardless would
     * leave bytes on the disk with nothing on this screen pointing at them, which is the one state
     * this screen exists to prevent.
     */
    suspend fun removeOne(row: DownloadRow): Unit = withContext(Dispatchers.IO) {
        val record = row.record
        // A download in the folder is deleted there, through the index; TDLib does not have it.
        if (!row.cached && record.localPath != null) {
            LocalDownloads.delete(settings, record)
            return@withContext
        }
        // The same resolution [measure] reads with: the saved id stops answering after a restart,
        // and a delete through it removes nothing while the row still leaves the list.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        runCatching { Td.deleteFile(fileId) }
        val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (left > 0) return@withContext
        if (row.cached) {
            settings.forgetCachedVideo(record.chatId, record.messageId)
        } else {
            settings.forgetDownload(record.chatId, record.messageId)
        }
    }

    /**
     * Puts a video into the download queue: a cached one, which moves into Downloads straight away,
     * or a part downloaded one, which finishes and then moves.
     */
    fun saveToDownloads(row: DownloadRow) {
        scope.launch {
            // Asked here for the same reason [share] asks: a cached video may come from a chat that
            // lets it be watched and nothing more, and the cache record cannot say so.
            if (!Td.maySave(row.record.chatId, row.record.messageId)) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            OfflineDownloads.start(context, row.record.toMediaItem().copy(fileId = row.fileId), row.chatTitle)
            toast(if (row.cached) "Saving ${row.title} to Downloads" else "Resuming ${row.title}")
            if (row.cached) tab = ONGOING
        }
    }

    fun delete(row: DownloadRow) {
        scope.launch {
            removeOne(row)
            refresh(history)
        }
    }

    /** A row whose file is gone: there is nothing to confirm deleting, only a record to drop. */
    fun removeMissing(row: DownloadRow) {
        scope.launch {
            withContext(Dispatchers.IO) { settings.forgetDownload(row.record.chatId, row.record.messageId) }
            toast("Removed ${row.title} from Downloads")
            refresh(history)
        }
    }

    fun deleteMany(these: List<DownloadRow>) {
        scope.launch {
            for (row in these) removeOne(row)
            leavePicking()
            refresh(history)
        }
    }

    Scaffold(
        containerColor = Tone.background,
        // A television crops its outer few percent, and this screen is stock Material laid out to
        // the panel's edge, so on a TV the whole of it moves inside the overscan margin.
        modifier = if (isTouch()) {
            Modifier
        } else {
            Modifier.background(Tone.background).padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (picking) {
                            if (chosen.isEmpty()) "Select videos" else "${chosen.size} selected"
                        } else {
                            "Downloads"
                        },
                    )
                },
                navigationIcon = {
                    // While picking, the arrow leaves the selection rather than the screen: that
                    // is what Back does here, and the two must not disagree.
                    val backFocus = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { if (picking) leavePicking() else onBack() },
                        interactionSource = backFocus,
                        modifier = Modifier.tvFocusRing(backFocus, CircleShape),
                    ) {
                        Icon(
                            if (picking) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (picking) "Leave the selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (picking) {
                        // Everything the list is showing, and never more than that.
                        TextAction("Select all", enabled = chosen.size < shown.size) {
                            picked = shown.map { it.key }.toSet()
                        }
                        return@TopAppBar
                    }
                    if (rows.isNotEmpty() && tab == COMPLETED) {
                        TextAction("Select") { picking = true }
                    }
                    // Offered on the rows and nothing else: a screen headed "Downloads" must not
                    // carry a button that empties the cache and the previews too.
                    if (rows.isNotEmpty() && tab == COMPLETED) {
                        TextAction("Delete all") { confirmingClearAll = true }
                    }
                },
            )
        },
        bottomBar = {
            // Only while picking, and only with something picked: a bar of disabled buttons is a
            // bar that has to be read before it can be ignored.
            if (!picking || chosen.isEmpty()) return@Scaffold
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryAction("Share", TmIcons.Share) { share(chosen) }
                    SecondaryAction(
                        label = "Delete",
                        icon = Icons.Filled.Delete,
                        onClick = { confirmingDeleteMany = true },
                        danger = true,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Fixed above the list rather than scrolled with it: switching between the two has to
            // be possible from anywhere in either.
            TabRow(selectedTabIndex = tab) {
                // A tab is the one widget here whose shape really is the full rectangle it is
                // given, so the ring follows that rather than pretending it is a pill.
                val ongoingFocus = remember { MutableInteractionSource() }
                Tab(
                    selected = tab == ONGOING,
                    onClick = { tab = ONGOING },
                    interactionSource = ongoingFocus,
                    modifier = Modifier.tvFocusRing(ongoingFocus, RectangleShape),
                    text = {
                        Text(if (active.isEmpty()) "Downloading" else "Downloading (${active.size})")
                    },
                )
                val completedFocus = remember { MutableInteractionSource() }
                Tab(
                    selected = tab == COMPLETED,
                    onClick = { tab = COMPLETED },
                    interactionSource = completedFocus,
                    modifier = Modifier.tvFocusRing(completedFocus, RectangleShape),
                    text = {
                        Text(if (rows.isEmpty()) "Downloaded" else "Downloaded (${rows.size})")
                    },
                )
                val cachedFocus = remember { MutableInteractionSource() }
                Tab(
                    selected = tab == CACHED,
                    onClick = { tab = CACHED },
                    interactionSource = cachedFocus,
                    modifier = Modifier.tvFocusRing(cachedFocus, RectangleShape),
                    text = {
                        Text(
                            if (cachedRows.isEmpty()) "Cached from playback" else "Cached from playback (${cachedRows.size})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (tab == ONGOING) {
                    if (active.isNotEmpty()) {
                        item {
                            QueueHeading(
                                title = queueHeading(active),
                                // Only worth offering on a real queue. With one video the card's
                                // own button says the same thing an inch further down the screen.
                                bulk = if (active.size > 1) {
                                    if (active.any { it.busy }) "Pause all" else "Resume all"
                                } else {
                                    null
                                },
                                onBulk = {
                                    if (active.any { it.busy }) {
                                        OfflineDownloads.pauseAll(context)
                                    } else {
                                        OfflineDownloads.resumeAll(context)
                                    }
                                },
                            )
                        }
                        items(active, key = { "active_${it.fileId}" }) { progress ->
                            ActiveDownloadCard(
                                progress = progress,
                                // Where it stands among the videos still to be fetched, so a
                                // waiting row can say how long the wait is.
                                //
                                // Looked up from the map built once above rather than worked out
                                // per row: the progress ticker rebuilds this list once a second
                                // for as long as anything is downloading.
                                place = queuePlaces[progress.fileId] ?: -1,
                                onPause = { OfflineDownloads.pause(context, progress.fileId) },
                                onResume = { OfflineDownloads.resume(context, progress.fileId) },
                                onCancel = { OfflineDownloads.cancel(context, progress.fileId) },
                            )
                        }
                    } else {
                        item {
                            // Given a height of its own: BigEmpty fills what it is given, and
                            // inside a LazyColumn that is nothing, so it would collapse to a line.
                            Box(Modifier.fillParentMaxHeight(0.7f)) {
                                BigEmpty(
                                    "Nothing downloading. Choose Download on a video and it " +
                                        "queues here.",
                                    icon = TmIcons.Download,
                                )
                            }
                        }
                    }
                    return@LazyColumn
                }

                if (tab == CACHED) {
                    item {
                        Text(
                            "Played recently. The next video you play replaces it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(cachedRows, key = { it.key }) { row ->
                        DownloadCard(
                            row = row,
                            picking = false,
                            checked = false,
                            onPlay = { onPlay(row.record) },
                            onShare = { saveToDownloads(row) },
                            onDelete = { confirmingDelete = row },
                            onToggle = {},
                            onHold = null,
                        )
                    }
                    if (counted && cachedRows.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxHeight(0.6f)) {
                                BigEmpty(
                                    "Nothing cached. Playing a video keeps it here until the next one.",
                                    icon = TmIcons.Download,
                                )
                            }
                        }
                    }
                    return@LazyColumn
                }

                item {
                    StorageSummary(
                        split = split,
                        freeBytes = disk.freeBytes,
                        totalBytes = disk.totalBytes,
                    )
                }
                item {
                    RemoveAfterWatchingRow(removeAfterWatching) { on ->
                        scope.launch { settings.setRemoveAfterWatching(on) }
                    }
                }
                items(shown, key = { it.key }) { row ->
                    DownloadCard(
                        row = row,
                        picking = picking,
                        checked = row.key in picked,
                        onPlay = {
                            // A part downloaded one carries on downloading; anything else plays.
                            if (row.partial) saveToDownloads(row) else onPlay(row.record)
                        },
                        onShare = { share(listOf(row)) },
                        onDelete = { if (row.missing) removeMissing(row) else confirmingDelete = row },
                        onToggle = {
                            picked = if (row.key in picked) picked - row.key else picked + row.key
                        },
                        // A hold is how a list of anything on a phone starts a selection, and it
                        // saves walking to the top bar for the first tick.
                        onHold = {
                            picking = true
                            picked = picked + row.key
                        },
                    )
                }
                if (counted && shown.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxHeight(0.6f)) {
                            BigEmpty(
                                "Nothing downloaded yet. Downloads are kept in " +
                                    "${LegacyDownloads.FOLDER} until you delete them.",
                                icon = TmIcons.Download,
                            )
                        }
                    }
                }
            }
        }
    }

    confirmingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = {
                Text(if (row.cached) "Delete this cached video?" else "Delete this download?")
            },
            text = {
                Text(
                    "\"${row.title}\" frees ${StreamStats.formatBytes(row.bytes)}. Nothing " +
                        "is removed from Telegram, so you can " +
                        (if (row.cached) "play it again." else "download it again."),
                )
            },
            confirmButton = {
                TextAction("Delete") {
                    delete(row)
                    confirmingDelete = null
                }
            },
            dismissButton = {
                TextAction("Keep it") { confirmingDelete = null }
            },
        )
    }

    if (confirmingDeleteMany) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteMany = false },
            title = {
                Text(
                    if (chosen.size == 1) {
                        "Delete this download?"
                    } else {
                        "Delete ${chosen.size} downloads?"
                    },
                )
            },
            text = {
                Text(
                    "This frees ${StreamStats.formatBytes(chosen.sumOf { it.bytes })}. Nothing " +
                        "is removed from Telegram, so you can download them again.",
                )
            },
            confirmButton = {
                TextAction("Delete") {
                    confirmingDeleteMany = false
                    deleteMany(chosen)
                }
            },
            dismissButton = {
                TextAction("Keep them") { confirmingDeleteMany = false }
            },
        )
    }

    if (confirmingClearAll) {
        AlertDialog(
            onDismissRequest = { confirmingClearAll = false },
            title = { Text("Delete every download?") },
            text = {
                // The rows, and only the rows: the figure quoted here has to be what this button
                // will actually delete. Cache and previews are counted and cleared in Settings.
                val freed = rows.sumOf { it.bytes }
                Text(
                    "This deletes ${rows.size.videos("download")} and frees " +
                        "${StreamStats.formatBytes(freed)}. Nothing is removed from Telegram, so " +
                        "you can download any of them again.",
                )
            },
            confirmButton = {
                TextAction("Delete all") {
                    confirmingClearAll = false
                    scope.launch {
                        // The downloads, one at a time, and nothing else. Each is only forgotten
                        // once its bytes have actually gone.
                        for (row in rows) removeOne(row)
                        refresh(history)
                    }
                }
            },
            dismissButton = {
                TextAction("Keep them") { confirmingClearAll = false }
            },
        )
    }
}

/**
 * The ring that says where the remote is standing, on the stock Material widgets this screen is
 * built from.
 *
 * The app's own television controls answer focus with a filled row plus [focusRing]; the Material
 * buttons, tabs and icon buttons here own their fill and answer it with a state layer a few
 * percent of alpha deep, which from a sofa is no answer at all. So on a television this draws the
 * theme's accent ring around whichever widget holds focus, in that widget's own shape, and on a
 * phone it is a no-op: there is no roving focus to mark, and the screen must not change.
 *
 * [focusRing] on its own is not enough, because it deliberately draws nothing in the dark theme:
 * its callers swap their fill to the focus colour, which in the dark is a bright block that would
 * swallow a same-colour ring. Nothing here changes fill, so the dark theme gets the same 2.dp
 * accent ring drawn by hand.
 *
 * [interactions] must also be handed to the widget itself, through its interactionSource
 * parameter, so the state collected here is the widget's own focus rather than a parallel guess.
 */
@Composable
private fun Modifier.tvFocusRing(
    interactions: MutableInteractionSource,
    shape: Shape,
): Modifier {
    if (isTouch()) return this
    val focused by interactions.collectIsFocusedAsState()
    return this
        .focusRing(focused, shape)
        .then(
            if (focused && LocalDarkTheme.current) {
                Modifier.border(2.dp, Tone.accent, shape)
            } else {
                Modifier
            },
        )
}

/**
 * "Remove after watching": one row, the whole of it the switch, so a remote lands on one thing and
 * OK flips it. The deleting itself happens in [com.tmplayer.data.RemoveAfterWatching], which runs
 * for the life of the process.
 */
@Composable
private fun RemoveAfterWatchingRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .tvFocusRing(interactions, shape)
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Remove after watching", style = MaterialTheme.typography.titleMedium)
            Text(
                "Delete a download once it is marked watched. Cached videos are not affected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Drawn, not pressed: the row is the control, so the switch takes no focus of its own.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * [TextButton], with the ring a remote needs.
 *
 * Every plain text action on this screen goes through here: the top bar, the queue heading and
 * the dialog buttons, so none of them can be the one control focus disappears on. The dialogs
 * matter most: a viewer who cannot see whether Delete or Keep holds focus is one press away from
 * deleting the wrong thing. On a phone this is exactly the stock button.
 */
@Composable
private fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = Modifier.tvFocusRing(interactions, CircleShape),
    ) {
        Text(label)
    }
}

/** What the top section is called, which depends on whether anything is actually moving. */
private fun queueHeading(active: List<OfflineDownloads.Progress>): String {
    val waiting = active.count { it.stage == OfflineDownloads.Stage.Queued }
    return when {
        active.any { it.stage == OfflineDownloads.Stage.Running } && waiting > 0 ->
            "Downloading now, $waiting waiting"
        active.any { it.stage == OfflineDownloads.Stage.Running } -> "Downloading now"
        active.all { it.stage == OfflineDownloads.Stage.NoWifi } -> "Waiting for Wi-Fi"
        active.all { it.stage == OfflineDownloads.Stage.Offline } -> "Waiting for a connection"
        active.all { it.stage == OfflineDownloads.Stage.Paused } -> "Paused"
        else -> "In the queue"
    }
}

/** A section heading with the one control that acts on the whole of the section beside it. */
@Composable
private fun QueueHeading(title: String, bulk: String?, onBulk: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bulk != null) {
            TextAction(bulk, onClick = onBulk)
        }
    }
}

/**
 * What the downloads on this device cost, against what the device has.
 *
 * Downloads only: a panel totalling all three kinds of space above a list showing one of them
 * would have to be explained. The cache and the previews are named in the last line, which points
 * at Settings, where the full breakdown and the buttons that clear each part live.
 */
@Composable
private fun StorageSummary(
    split: StorageSplit,
    freeBytes: Long,
    totalBytes: Long,
) {
    val usedFraction = if (totalBytes > 0) {
        ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${StreamStats.formatBytes(split.downloadBytes)} in Downloads",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${StreamStats.formatBytes(freeBytes)} free of " +
                    StreamStats.formatBytes(totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Named, not hidden: somebody comparing this against Android's own figure for the app
            // needs to know where the rest of it went, and where the buttons for it are.
            if (split.cachedBytes > 0 || split.otherBytes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "TMPlayer also has " + StreamStats.formatBytes(split.cachedBytes) + " cached and " +
                        StreamStats.formatBytes(split.otherBytes) + " of pictures and previews. " +
                        "Clear them in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(Corner.Small)),
            )
        }
    }
}


/**
 * The shell every row on this screen is drawn in: a filled card with an edge and its own space.
 *
 * Both kinds of row use it, so a video arriving and a video that has arrived are plainly the same
 * sort of thing at different stages, and neither can be confused with the storage panel above them.
 */
@Composable
private fun RowCard(
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    onHold: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    // A clickable card is a focusable card, and on a television that is a trap: directional
    // search will not step from a focused parent onto the buttons drawn inside it, so a card
    // that takes D-pad focus in browse mode swallows the remote one row above Watch, with no
    // ring to say where it went. So the card only takes D-pad focus while a press on it means
    // something a remote wants, toggling its tick in a selection, and it wears the ring then;
    // the rest of the time the remote lands straight on the buttons, and the hold-to-select
    // shortcut stays what it always was, a touch gesture, with the Select button covering it.
    val dpadFocusable = onClick != null || isTouch()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .tvFocusRing(interactions, RoundedCornerShape(Corner.Large))
            .then(
                if (onClick == null && onHold == null) {
                    Modifier
                } else {
                    Modifier
                        .focusProperties { canFocus = dpadFocusable }
                        .combinedClickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            onClick = { onClick?.invoke() ?: onHold?.invoke() },
                            onLongClick = onHold,
                        )
                },
            ),
        shape = RoundedCornerShape(Corner.Large),
        // The same fill the Continue watching cards use, not the scheme's surfaceVariant: that
        // one sits a shade above the window on a dark theme, so a screenful reads as pale panels
        // floating on the background rather than as the app's own cards.
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
        border = BorderStroke(
            if (accent != null) 2.dp else 1.dp,
            accent ?: MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/**
 * The buttons under a card: side by side, equal, full width, and each with its name on it.
 *
 * A single button still fills the row rather than sitting at one end, because a target the width
 * of the card is one a thumb finds without looking.
 */
@Composable
private fun CardActions(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PrimaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SecondaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colour)
        Spacer(Modifier.width(8.dp))
        Text(label, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An action with no word on it, sized as a square rather than sharing the row's width.
 *
 * For Delete, which reads well enough as a bin and should not carry the same visual weight as
 * Watch. The square also leaves Watch and Share the width their labels need, so neither of them
 * ellipsises on a narrow phone.
 */
@Composable
private fun IconOnlyAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.size(48.dp).tvFocusRing(interactions, CircleShape),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = colour)
    }
}

/**
 * A finished video: what it is, what it costs, and the three things worth doing with it.
 *
 * While a selection is on, the whole card is one target that ticks and unticks, which is how a
 * phone's list of anything behaves and keeps three small buttons out of a tick target.
 */
@Composable
private fun DownloadCard(
    row: DownloadRow,
    picking: Boolean,
    checked: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onHold: (() -> Unit)?,
) {
    val details = listOfNotNull(
        // A part-loaded video says both figures: "710 MB" alone reads as the size of the video,
        // while "710 MB of 1.4 GB" says what is on the disk and what would finish it.
        if (row.partial && row.totalBytes > row.bytes) {
            "${StreamStats.formatBytes(row.bytes)} of ${StreamStats.formatBytes(row.totalBytes)}"
        } else {
            StreamStats.formatBytes(row.bytes).takeIf { row.bytes > 0 }
        },
        MediaMapper.formatDuration(row.durationSec).ifBlank { null },
        row.chatTitle.ifBlank { null },
        // A part-loaded file still occupies its bytes, and that is the row a viewer looking for
        // space is most likely to want gone.
        if (row.partial) "Part downloaded" else null,
        if (row.missing) "File missing" else null,
    ).joinToString(DOT)

    RowCard(
        accent = if (checked) MaterialTheme.colorScheme.primary else null,
        onClick = if (picking) onToggle else null,
        onHold = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picking) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Hidden while picking: a Delete button inside something the viewer is tapping to select
        // is a mistake waiting to be made.
        if (!picking) {
            CardActions {
                when {
                    // Something took the file. The record is all that is left to deal with.
                    row.missing -> SecondaryAction("Remove", Icons.Filled.Delete, onDelete, danger = true)
                    row.cached -> {
                        PrimaryAction("Watch", Icons.Filled.PlayArrow, onPlay)
                        // The share slot's place: what a cached video wants is keeping.
                        SecondaryAction("Save to Downloads", TmIcons.Download, onShare)
                        IconOnlyAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
                    }
                    row.partial -> {
                        PrimaryAction("Resume", Icons.Filled.Refresh, onPlay)
                        IconOnlyAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
                    }
                    else -> {
                        PrimaryAction("Watch", Icons.Filled.PlayArrow, onPlay)
                        SecondaryAction("Share", TmIcons.Share, onShare)
                        IconOnlyAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
                    }
                }
            }
        }
    }
}

/**
 * A video still coming down, or waiting to, at the top of the list where the viewer went looking.
 *
 * Every stage a video can be in has a card, a line saying which stage that is, and the buttons
 * that move it to another one. Nothing here comes from the finished-downloads record, which the
 * service only writes on completion.
 */
@Composable
private fun ActiveDownloadCard(
    progress: OfflineDownloads.Progress,
    place: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val stage = progress.stage
    val failed = stage == OfflineDownloads.Stage.Failed
    val fraction = progress.fraction
    val size = if (progress.totalBytes > 0) {
        "${StreamStats.formatBytes(progress.downloadedBytes)} of " +
            StreamStats.formatBytes(progress.totalBytes)
    } else {
        StreamStats.formatBytes(progress.downloadedBytes)
    }
    val line = when (stage) {
        OfflineDownloads.Stage.Failed -> progress.failure.orEmpty()
        OfflineDownloads.Stage.Queued -> listOfNotNull(
            if (place <= 0) "Next in the queue" else "${place + 1} in the queue",
            StreamStats.formatBytes(progress.totalBytes).takeIf { progress.totalBytes > 0 },
            // Only worth saying when an earlier attempt left something behind, since a queued
            // video normally has nothing on disk and "0 B so far" is not news.
            "${StreamStats.formatPercent(fraction ?: 0f)} already here"
                .takeIf { progress.downloadedBytes > 0 },
        ).joinToString(DOT)
        OfflineDownloads.Stage.Paused -> listOfNotNull(
            "Paused",
            fraction?.let(StreamStats::formatPercent),
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.NoWifi -> listOfNotNull(
            "Waiting for Wi-Fi",
            fraction?.let(StreamStats::formatPercent),
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Offline -> listOfNotNull(
            "Waiting for a connection",
            fraction?.let(StreamStats::formatPercent),
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Moving ->
            if (progress.heldByPlayer) "Finishes when playback stops" else "Moving into Downloads"
        OfflineDownloads.Stage.Running -> listOfNotNull(
            fraction?.let(StreamStats::formatPercent),
            size,
            StreamStats.formatSpeed(progress.bytesPerSecond)
                .takeIf { progress.bytesPerSecond >= 1024 },
            StreamStats.formatEta(progress.remainingSeconds).ifBlank { null },
        ).joinToString(DOT)
    }

    RowCard(accent = if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else null) {
        Text(
            progress.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!failed) {
            Spacer(Modifier.height(10.dp))
            // A bar for every stage, so the cards are one shape and the list does not jump as
            // each takes its turn. Indeterminate only while something is moving with no figure
            // to show for it yet.
            if (fraction == null && stage == OfflineDownloads.Stage.Running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Small)),
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Small)),
                )
            }
        }
        CardActions {
            when (stage) {
                OfflineDownloads.Stage.Running ->
                    PrimaryAction("Pause", TmIcons.Pause, onPause)
                OfflineDownloads.Stage.Paused ->
                    PrimaryAction("Resume", Icons.Filled.PlayArrow, onResume)
                // Refused because the chat forbids keeping a copy: trying again is refused the
                // same way, so the only offer left is Dismiss below.
                OfflineDownloads.Stage.Failed ->
                    if (progress.failure != ContentProtection.NOT_SAVABLE) {
                        PrimaryAction("Try again", Icons.Filled.Refresh, onResume)
                    }
                // It will start itself the moment the signal is back, so the useful offer is the
                // other one: hold it, and do not.
                OfflineDownloads.Stage.Offline, OfflineDownloads.Stage.NoWifi ->
                    PrimaryAction("Pause", TmIcons.Pause, onPause)
                // Nothing to pause that has not started. Cancel below fills the row on its own.
                OfflineDownloads.Stage.Queued -> Unit
                // Fetched already; the move is short, and stopping it half way gains nothing.
                OfflineDownloads.Stage.Moving -> Unit
            }
            SecondaryAction(
                label = if (failed) "Dismiss" else "Cancel",
                icon = Icons.Filled.Close,
                onClick = onCancel,
                danger = true,
            )
        }
    }
}

/** The three tabs, as indices, because that is what [TabRow] counts in. */
private const val ONGOING = 0
private const val COMPLETED = 1
private const val CACHED = 2

/** "1 download", "3 downloads": counted, so a dialog does not have to say "download(s)". */
private fun Int.videos(noun: String): String =
    if (this == 1) "1 $noun" else "$this ${noun}s"

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "
