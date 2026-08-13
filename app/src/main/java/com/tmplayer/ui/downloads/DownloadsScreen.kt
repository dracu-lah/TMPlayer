package com.tmplayer.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * One video on this device, as the screen knows it: what names it, and what it holds on disk.
 *
 * [bytes] is what is actually on the disk rather than the size the message advertised, so a video
 * that stopped half way says how much room it is really taking and not how much it wanted.
 * [totalBytes] is what it wanted, kept beside it so a part-loaded row can say "710 MB of 1.4 GB"
 * instead of leaving the viewer to wonder why the figures on this screen do not add up.
 *
 * Every row is a download the viewer asked for by name. Videos left behind by playing something are
 * the cache, not downloads, and are never listed here: they belong with the other reclaimable space
 * on the Settings screen. Mixing the two would have one button deleting two different things.
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
) {
    /** Part loaded and not being fetched by anything: bytes sitting there for no one. */
    val partial: Boolean get() = !complete && bytes > 0
}

/**
 * Everything this phone has downloaded or is waiting for, what it costs, and the way to be rid of it.
 *
 * The television has no screen like this and does not need one: it keeps a single video at a time
 * and replaces it on the next play, so there is never a list to manage. A phone keeps every
 * download until it is told otherwise, which is only a fair deal if the viewer can see them.
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val toast = rememberToast()
    val history by settings.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())
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
    var tab by rememberSaveable { mutableStateOf(COMPLETED) }
    var landedOnATab by rememberSaveable { mutableStateOf(false) }
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
        // Against this session's id, not the one saved with the row. A saved id stops resolving
        // after a restart, and a row measured from it reads as zero bytes and disappears.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (bytes <= 0) return null
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        if (availability == LocalFileAvailability.Missing) return null
        return DownloadRow(
            key = "kept_${record.chatId}_${record.messageId}",
            title = record.title,
            bytes = bytes,
            totalBytes = record.sizeBytes,
            complete = availability == LocalFileAvailability.Complete,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
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

            Measured(keptRows, measuredSplit, readDisk)
        }

        rows = measured.rows
        split = measured.split
        disk = measured.disk
        counted = true
    }

    // One trigger, not three. These keys all change together when a download finishes, and three
    // separate effects would interleave three passes of the audit above, each writing `rows` when
    // it finished, so the slowest could land last and put stale figures back on screen.
    LaunchedEffect(history, cached.map { it.fileId }, active.size) { refresh(history) }
    fun share(these: List<DownloadRow>) {
        scope.launch {
            val files = these.mapNotNull { row ->
                val record = row.record ?: return@mapNotNull null
                val path = runCatching { Td.localFilePath(record.fileId) }.getOrNull()
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
        runCatching { Td.deleteFile(record.fileId) }
        val left = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
        if (left > 0) return@withContext
        settings.forgetDownload(record.chatId, record.messageId)
    }

    fun delete(row: DownloadRow) {
        scope.launch {
            removeOne(row)
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
                    IconButton(onClick = { if (picking) leavePicking() else onBack() }) {
                        Icon(
                            if (picking) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (picking) "Leave the selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (picking) {
                        // Everything the list is showing, and never more than that.
                        TextButton(
                            onClick = { picked = shown.map { it.key }.toSet() },
                            enabled = chosen.size < shown.size,
                        ) {
                            Text("Select all")
                        }
                        return@TopAppBar
                    }
                    if (rows.isNotEmpty() && tab == COMPLETED) {
                        TextButton(onClick = { picking = true }) { Text("Select") }
                    }
                    // Offered on the rows and nothing else: a screen headed "Downloads" must not
                    // carry a button that empties the cache and the previews too.
                    if (rows.isNotEmpty() && tab == COMPLETED) {
                        TextButton(onClick = { confirmingClearAll = true }) {
                            Text("Delete all")
                        }
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
                Tab(
                    selected = tab == ONGOING,
                    onClick = { tab = ONGOING },
                    text = {
                        Text(if (active.isEmpty()) "Ongoing" else "Ongoing (${active.size})")
                    },
                )
                Tab(
                    selected = tab == COMPLETED,
                    onClick = { tab = COMPLETED },
                    text = {
                        Text(if (rows.isEmpty()) "Completed" else "Completed (${rows.size})")
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
                                    "Nothing downloading. Tick videos in a chat and they queue up " +
                                        "here, one at a time.",
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
                items(shown, key = { it.key }) { row ->
                    DownloadCard(
                        row = row,
                        picking = picking,
                        checked = row.key in picked,
                        onPlay = { row.record?.let(onPlay) },
                        onShare = { share(listOf(row)) },
                        onDelete = { confirmingDelete = row },
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
                                "Nothing downloaded yet. Videos you keep are held here until " +
                                    "you delete them.",
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
                Text("Delete this download?")
            },
            text = {
                Text(
                    "\"${row.title}\" frees ${StreamStats.formatBytes(row.bytes)}. Nothing " +
                        "is removed from Telegram, so you can download it again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    delete(row)
                    confirmingDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Keep it") }
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
                TextButton(onClick = {
                    confirmingDeleteMany = false
                    deleteMany(chosen)
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteMany = false }) { Text("Keep them") }
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
                TextButton(onClick = {
                    confirmingClearAll = false
                    scope.launch {
                        // The downloads, one at a time, and nothing else. Each is only forgotten
                        // once its bytes have actually gone.
                        for (row in rows) removeOne(row)
                        refresh(history)
                    }
                }) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearAll = false }) { Text("Keep them") }
            },
        )
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
            TextButton(onClick = onBulk) { Text(bulk) }
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
                "${StreamStats.formatBytes(split.downloadBytes)} in downloads",
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
                    "TMPlayer is also holding " +
                        StreamStats.formatBytes(split.cachedBytes + split.otherBytes) +
                        " of playback cache and previews. Clear it in Settings.",
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(
                if (onClick == null && onHold == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
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
    FilledTonalButton(onClick = onClick, modifier = Modifier.weight(1f)) {
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
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
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
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
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
    onHold: () -> Unit,
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
                PrimaryAction("Watch", Icons.Filled.PlayArrow, onPlay)
                SecondaryAction("Share", TmIcons.Share, onShare)
                IconOnlyAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
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
                OfflineDownloads.Stage.Failed ->
                    PrimaryAction("Try again", Icons.Filled.Refresh, onResume)
                // It will start itself the moment the signal is back, so the useful offer is the
                // other one: hold it, and do not.
                OfflineDownloads.Stage.Offline, OfflineDownloads.Stage.NoWifi ->
                    PrimaryAction("Pause", TmIcons.Pause, onPause)
                // Nothing to pause that has not started. Cancel below fills the row on its own.
                OfflineDownloads.Stage.Queued -> Unit
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

/** The two tabs, as indices, because that is what [TabRow] counts in. */
private const val ONGOING = 0
private const val COMPLETED = 1

/** "1 download", "3 downloads": counted, so a dialog does not have to say "download(s)". */
private fun Int.videos(noun: String): String =
    if (this == 1) "1 $noun" else "$this ${noun}s"

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "
