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
import androidx.compose.material3.FilterChip
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
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.Td
import com.tmplayer.data.WatchCache
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * Where a video on this device came from, which is the one thing a row has to be honest about.
 *
 * They cost the same disk and they are deleted the same way, so they belong on the same screen. But
 * one of them the viewer chose and the other one merely happened, and a viewer looking at two
 * gigabytes they do not remember asking for is owed the difference in writing.
 */
private enum class Source { Kept, Cache }

/**
 * One video on this device, as the screen knows it: what names it, and what it holds on disk.
 *
 * [bytes] is what is actually on the disk rather than the size the message advertised, so a video
 * that stopped half way says how much room it is really taking and not how much it wanted.
 * [totalBytes] is what it wanted, kept beside it so a part-loaded row can say "710 MB of 1.4 GB"
 * instead of leaving the viewer to wonder why the figures on this screen do not add up.
 *
 * [record] is absent for a video found on the disk with nothing in the app naming it. Those can be
 * measured and deleted but not played or shared: without a record there is no message to ask
 * Telegram about, and a Watch button that cannot watch is worse than no button.
 */
private data class DownloadRow(
    val key: String,
    val title: String,
    val bytes: Long,
    val totalBytes: Long,
    val complete: Boolean,
    val source: Source,
    val record: ResumeRecord? = null,
    val strayPath: String? = null,
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
 * names written on them. The rows used to be list items with a bare icon or a word of text at the
 * right hand end, which on a phone meant a 24 dp target beside a two line title and no way to tell
 * a video that is arriving from one that has arrived. A card has an edge, so it is obvious where
 * one video stops and the next begins, and a button with a word on it cannot be mistaken for
 * another button with a different word on it.
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

    // What watching left behind. None of it is a download and none of it was asked for, but every
    // byte of it is on the disk, and anything taking up room belongs where room is managed.
    val cached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    // Which kinds of row are showing. Both, normally: the filter is for the viewer who came here
    // because the app is two gigabytes and wants to see only the part they did not ask for.
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    // The same measurement the Settings card draws, so the two panels cannot disagree about a
    // disk they are both looking at.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    var confirmingClearAll by remember { mutableStateOf(false) }
    // Which row the viewer pressed Delete on, and therefore what the dialog is about to remove.
    // Held rather than a bare boolean: the dialog names the video, and a video deleted without
    // being named is the one thing this screen must never do by accident.
    var confirmingDelete by remember { mutableStateOf<DownloadRow?>(null) }
    // Which finished downloads are ticked, by the key their row is drawn with. Keys rather than
    // rows, because the list behind them is rebuilt from TDLib whenever anything finishes and a
    // held row would go stale the moment it mattered.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picking by remember { mutableStateOf(false) }
    // Which tab is up. Opens on whichever has something in it: somebody who just queued three
    // films wants the queue, and somebody opening this on a quiet phone wants what they have.
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

    // What the viewer is actually looking at, and what the counts beside the filters say.
    val shown = remember(rows, filter) {
        when (filter) {
            KEPT_ONLY -> rows.filter { it.source == Source.Kept }
            CACHE_ONLY -> rows.filter { it.source == Source.Cache }
            else -> rows
        }
    }
    val keptCount = remember(rows) { rows.count { it.source == Source.Kept } }
    val cacheCount = remember(rows) { rows.count { it.source == Source.Cache } }

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
     * A row is kept as soon as it holds a byte, finished or not. Half a film is half a gigabyte,
     * and a part-loaded video that the screen hid was exactly the space a viewer came here looking
     * for and could not find.
     */
    suspend fun measure(record: ResumeRecord, source: Source): DownloadRow? {
        val bytes = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
        if (bytes <= 0) return null
        val availability = runCatching { Td.localFileAvailability(record.fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        if (availability == LocalFileAvailability.Missing) return null
        return DownloadRow(
            key = "${source.name}_${record.chatId}_${record.messageId}",
            title = record.title,
            bytes = bytes,
            totalBytes = record.sizeBytes,
            complete = availability == LocalFileAvailability.Complete,
            source = source,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
        )
    }

    suspend fun refresh(known: List<ResumeRecord>) {
        disk = DiskSpace.read(context)
        split = runCatching { StorageSplit.measure(context) }.getOrDefault(StorageSplit.EMPTY)
        val keptRows = known.mapNotNull { measure(it, Source.Kept) }
        // A video can be both: watched first, downloaded afterwards. It is the viewer's then, and
        // showing it twice would have them delete it from one row and find it still in the other.
        val keptIds = known.map { it.fileId }.toSet()
        val cachedRows = settings.cachedVideosNow()
            .filter { it.fileId !in keptIds }
            .mapNotNull { measure(it, Source.Cache) }

        // Everything else on the disk. Older versions of this app kept one record for the whole
        // watch cache and overwrote it on every play, so a series watched through left a copy of
        // every episode with only the last of them named. Those files are still here, they are the
        // bulk of what the app is reported to weigh, and until now nothing on this screen could
        // point at a single one of them.
        // Both the rows above and anything the queue is fetching this moment: a download in flight
        // has bytes on the disk under a name of TDLib's choosing, and listing those as orphans
        // would offer the viewer a Delete button on the video they are waiting for.
        val accounted = ((keptRows + cachedRows).mapNotNull { it.record?.fileId } + activeMap.keys)
            .distinct()
            .mapNotNull { runCatching { Td.localPathAnyway(it) }.getOrNull() }
            .toSet()
        val strays = runCatching { WatchCache.strays(context, accounted) }.getOrDefault(emptyList())
            .map { stray ->
                DownloadRow(
                    key = "stray_${stray.path}",
                    title = stray.title,
                    bytes = stray.bytes,
                    totalBytes = 0,
                    // Nothing here knows whether it finished, and claiming either way would be a
                    // guess. The row says what it is instead: bytes, and where they came from.
                    complete = true,
                    source = Source.Cache,
                    strayPath = stray.path,
                    // The file's own name, but only where the title is not already it: two rows
                    // both headed "Cached video" need something to tell them apart, and a row
                    // that already carries the real name does not need it printed twice.
                    chatTitle = if (stray.title == stray.fileName.substringBeforeLast('.')
                            .replace('_', ' ').trim()
                    ) {
                        ""
                    } else {
                        stray.fileName
                    },
                )
            }

        rows = keptRows + cachedRows + strays
        counted = true
    }

    LaunchedEffect(history) { refresh(history) }
    // The cache changes without the download index moving at all: watching something else adds to
    // it and evicts the last of it, and the list has to follow rather than keep quoting the film
    // before it.
    LaunchedEffect(cached.map { it.fileId }) { refresh(history) }
    // A finished download writes its record and disappears from the queue in the same breath, so
    // the sizes on this screen are re-measured whenever the queue changes rather than only when a
    // new record appears: without it a video that completed while the screen was open moved from
    // the top section to the bottom one still claiming the bytes it had when it started.
    LaunchedEffect(active.size) { refresh(history) }

    fun share(these: List<DownloadRow>) {
        scope.launch {
            val files = these.mapNotNull { row ->
                val record = row.record ?: return@mapNotNull null
                val path = runCatching { Td.localFilePath(record.fileId) }.getOrNull()
                path?.let { it to record.title }
            }
            val intent = ShareMedia.intentFor(context, files)
            if (intent == null) {
                // Every one of them turned out to be part downloaded or gone. Saying so beats a
                // share sheet with nothing in it, which reads as the button being broken.
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
     * this screen exists to prevent, and is precisely how the app came to be holding gigabytes it
     * could not name.
     */
    suspend fun removeOne(row: DownloadRow) {
        val path = row.strayPath
        if (path != null) {
            WatchCache.forget(WatchCache.Stray(path, row.bytes, 0))
            return
        }
        val record = row.record ?: return
        runCatching { Td.deleteFile(record.fileId) }
        val left = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
        if (left > 0) return
        when (row.source) {
            Source.Kept -> settings.forgetDownload(record.chatId, record.messageId)
            Source.Cache -> settings.forgetCachedVideo(record.chatId, record.messageId)
        }
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

    /**
     * Promotes a cached video into a download, so the next thing watched cannot take it away.
     *
     * The bytes are already here; this only changes whose they are. Offered because a viewer who
     * finds last night's film on this screen and wants to keep it should not have to download it a
     * second time to say so.
     */
    fun keep(row: DownloadRow) {
        val record = row.record ?: return
        scope.launch {
            settings.noteDownload(record.toMediaItem(), record.chatTitle)
            settings.forgetCachedVideo(record.chatId, record.messageId)
            toast("\"${record.title}\" is yours now. Watching something else will not remove it.")
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
                        // Everything the filter is showing, which is what "all" means with a
                        // filter on: ticking Cached and pressing this must not quietly take in
                        // the downloads the viewer has just asked not to see.
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
                    // Offered on what TDLib is holding rather than on what this list shows. An
                    // install that downloaded videos before the index existed has bytes and no
                    // rows, and hiding the button there left the space unreachable.
                    if ((rows.isNotEmpty() || split.totalBytes >= UNLISTED_FLOOR) && tab == COMPLETED) {
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
            // Fixed above the list rather than scrolled with it. A tab that scrolls away is a tab
            // nobody can get back to without scrolling to the top first, and the whole point of
            // these two is switching between them while reading either.
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
                                // waiting row can say how long the wait is rather than only that
                                // there is one.
                                place = active.filter { it.stage == OfflineDownloads.Stage.Queued }
                                    .indexOfFirst { it.fileId == progress.fileId },
                                onPause = { OfflineDownloads.pause(context, progress.fileId) },
                                onResume = { OfflineDownloads.resume(context, progress.fileId) },
                                onCancel = { OfflineDownloads.cancel(context, progress.fileId) },
                            )
                        }
                    } else {
                        item {
                            // Given a height of its own: BigEmpty fills what it is given, and
                            // inside a LazyColumn that is nothing, so it collapsed to a line.
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
                // Only once there is a mixture worth separating. With downloads alone, or cache
                // alone, three chips are three things to read that all say the same thing.
                if (keptCount > 0 && cacheCount > 0 && !picking) {
                    item(key = "filters") {
                        Filters(
                            filter = filter,
                            keptCount = keptCount,
                            cacheCount = cacheCount,
                            onPick = { filter = it },
                        )
                    }
                }
                items(shown, key = { it.key }) { row ->
                    DownloadCard(
                        row = row,
                        picking = picking,
                        checked = row.key in picked,
                        onPlay = { row.record?.let(onPlay) },
                        onShare = { share(listOf(row)) },
                        onKeep = { keep(row) },
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
                                when (filter) {
                                    KEPT_ONLY -> "Nothing downloaded yet. Videos you keep are " +
                                        "held here until you delete them."
                                    CACHE_ONLY -> "Nothing cached. Playing a video leaves a copy " +
                                        "here so you can carry on watching it."
                                    else -> "Nothing downloaded yet. Videos you keep are held " +
                                        "here until you delete them."
                                },
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
                Text(
                    if (row.source == Source.Cache) "Delete this cached video?" else
                        "Delete this download?",
                )
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
            title = { Text("Delete everything on this device?") },
            text = {
                // The figure TDLib reports, which is every byte the app is holding rather than the
                // sum of the rows. The button empties the lot, so quoting the rows would promise
                // less than it takes: it was doing exactly that on a device where most of the
                // space was cached episodes that never made it onto the list.
                val freed = maxOf(rows.sumOf { it.bytes }, split.totalBytes)
                Text(
                    "This frees ${StreamStats.formatBytes(freed)}: " +
                        "${keptCount.videos("download")} and " +
                        "${cacheCount.videos("cached video")}, along with the previews TMPlayer " +
                        "is holding. Nothing is removed from Telegram, so you can download any " +
                        "of them again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClearAll = false
                    scope.launch {
                        runCatching { Td.clearMediaCache() }
                        // Anything TDLib would not part with, taken off the disk directly. It is
                        // the difference between a button that says 2.30 GB and frees 2.30 GB and
                        // one that says it and frees a third of it.
                        for (row in rows) {
                            val path = row.strayPath ?: continue
                            WatchCache.forget(WatchCache.Stray(path, row.bytes, 0))
                        }
                        settings.forgetAllDownloads()
                        settings.forgetCachedVideo()
                        refresh(emptyList())
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

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
    )
}

/**
 * What is on this device, against what the device has, above the list it belongs to.
 *
 * Three figures, because they are three different things and conflating them is what made the old
 * panel unreadable: what the viewer chose to keep, the one film watching left behind, and
 * everything TMPlayer is holding altogether, which counts the previews and thumbnails the browse
 * screens keep as well.
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
                "TMPlayer has ${StreamStats.formatBytes(split.totalBytes)} on this phone",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            // The three parts, named the same and in the same order as the Settings card, so a
            // viewer reading both is reading one story about their disk rather than two.
            Text(
                // A line for each part that has anything in it. "0 MB in pictures and previews"
                // is a line the viewer has to read before they can ignore it.
                listOfNotNull(
                    "${StreamStats.formatBytes(split.downloadBytes)} in downloads"
                        .takeIf { split.downloadBytes > 0 },
                    "${StreamStats.formatBytes(split.cachedBytes)} cached from playback"
                        .takeIf { split.cachedBytes > 0 },
                    "${StreamStats.formatBytes(split.otherBytes)} in pictures and previews"
                        .takeIf { split.otherBytes > 0 },
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${StreamStats.formatBytes(freeBytes)} free of " +
                    StreamStats.formatBytes(totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * Anything below this is thumbnails and database, not a video worth offering to delete.
 */
private val UNLISTED_FLOOR = 64L * 1024 * 1024

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
        // The same fill the Continue watching cards use, rather than the scheme's surfaceVariant.
        // That one sits a shade above the window on a dark theme, so a screenful of these read as
        // pale panels floating on the background instead of as the app's own cards.
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
 * Delete is the one button on the card that reads perfectly well as a glyph: everybody knows the
 * bin, and spelling it out gave a destructive action the same visual weight as Watch, which is the
 * thing the card is actually for. Dropping to a square also gives Watch and Share the width the
 * label needs, so neither of them ellipsises on a narrow phone.
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
 * Which kinds of video the list is showing, with what each kind costs written on the chip.
 *
 * Shown only where there is a mixture. The counts are the point: a viewer opening this screen
 * because the app weighs two gigabytes wants to know, in one glance, how much of that they chose.
 */
@Composable
private fun Filters(
    filter: Int,
    keptCount: Int,
    cacheCount: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == ALL,
            onClick = { onPick(ALL) },
            label = { Text("All ${keptCount + cacheCount}") },
        )
        FilterChip(
            selected = filter == KEPT_ONLY,
            onClick = { onPick(KEPT_ONLY) },
            label = { Text("Downloads $keptCount") },
        )
        FilterChip(
            selected = filter == CACHE_ONLY,
            onClick = { onPick(CACHE_ONLY) },
            label = { Text("Cached $cacheCount") },
        )
    }
}

/**
 * A finished video: what it is, what it costs, and the three things worth doing with it.
 *
 * While a selection is on, the whole card is one target that ticks and unticks: the buttons would
 * otherwise be three small things to miss on a card the viewer is trying to tick, and a phone's
 * list of anything behaves this way already.
 */
@Composable
private fun DownloadCard(
    row: DownloadRow,
    picking: Boolean,
    checked: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onHold: () -> Unit,
) {
    val details = listOfNotNull(
        // A part-loaded video says both figures. "710 MB" on its own reads as the size of the
        // video, and the viewer is left wondering why it will not play; "710 MB of 1.4 GB" says
        // what is on the disk and what it would take to finish, in the same breath.
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
                // Said before the title, not after it. A viewer scanning this list for the two
                // gigabytes they did not ask for reads down the left edge, and a video they never
                // downloaded sitting on the Downloads screen is owed the explanation first.
                if (row.source == Source.Cache) {
                    Text(
                        "Cached from playback",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
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
                if (row.source == Source.Cache) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (row.record == null) {
                            "Left behind by a video you watched. Safe to delete."
                        } else {
                            "Kept so you can carry on watching. Watching something else " +
                                "replaces it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Hidden while picking: the card is a tick target then, and a Delete button inside
        // something the viewer is tapping to select is a mistake waiting to be made.
        if (!picking) {
            CardActions {
                // Nothing in the app knows which video this file is, so there is nothing to open
                // and nothing to hand another app. Delete is the whole of what it can offer, and
                // it fills the row rather than sitting beside two buttons that do not work.
                if (row.record == null) {
                    SecondaryAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
                    return@CardActions
                }
                PrimaryAction("Watch", Icons.Filled.PlayArrow, onPlay)
                // The cache is the one thing on this screen that goes away by itself, so what it
                // needs is the button that stops that. A download already belongs to the viewer,
                // and Share is the useful third thing to do with one.
                if (row.source == Source.Cache) {
                    SecondaryAction("Keep", TmIcons.Download, onKeep)
                } else {
                    SecondaryAction("Share", TmIcons.Share, onShare)
                }
                IconOnlyAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
            }
        }
    }
}

/**
 * A video still coming down, or waiting to, at the top of the list where the viewer went looking.
 *
 * These used to appear nowhere: the screen read only the finished-downloads record, which the
 * service writes on completion, so pressing download and opening this screen showed an empty list
 * and no sign anything was happening at all. Now every stage a video can be in has a card, a line
 * saying which stage that is, and the buttons that move it to another one.
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
            // A bar for every stage that has one, so a queued video's card is the same shape as
            // the one above it and the list does not jump as each takes its turn. Indeterminate
            // only while something really is moving with no figure to show for it yet.
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
                // It will start itself the moment the signal is back, so what is worth offering is
                // the other answer: hold it, and do not.
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

/** The three filters, in the order their chips are drawn. */
private const val ALL = 0
private const val KEPT_ONLY = 1
private const val CACHE_ONLY = 2

/** "1 download", "3 downloads": counted, so a dialog does not have to say "download(s)". */
private fun Int.videos(noun: String): String =
    if (this == 1) "1 $noun" else "$this ${noun}s"

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "
