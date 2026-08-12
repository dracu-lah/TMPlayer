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
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * One downloaded video, as the screen knows it: the record that names it and what it holds on disk.
 *
 * [bytes] is TDLib's figure rather than the size the message advertised, so a download that was
 * interrupted half way says how much of the disk it is actually using and not how much it wanted.
 */
private data class DownloadRow(
    val record: ResumeRecord,
    val bytes: Long,
    val complete: Boolean,
)

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

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var confirmingClearAll by remember { mutableStateOf(false) }
    // Which row the viewer pressed Delete on, and therefore what the dialog is about to remove.
    // Held rather than a bare boolean: the dialog names the video, and a video deleted without
    // being named is the one thing this screen must never do by accident.
    var confirmingDelete by remember { mutableStateOf<DownloadRow?>(null) }
    // Which finished downloads are ticked, by the key their row is drawn with. Ids rather than
    // rows, because the list behind them is rebuilt from TDLib whenever anything finishes and a
    // held row would go stale the moment it mattered.
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
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
    val chosen = remember(picked, rows) { rows.filter { it.record.fileId in picked } }

    // A selection belongs to the tab it was made on. Carried across, its bar would sit under a
    // list of downloads it says nothing about.
    LaunchedEffect(tab) { if (tab != COMPLETED) leavePicking() }

    BackHandler(enabled = picking) { leavePicking() }
    // Nothing is known until the first pass over TDLib has finished, and an empty list drawn in
    // the meantime reads as "you have downloaded nothing" rather than as "still counting".
    var counted by remember { mutableStateOf(false) }

    suspend fun refresh(known: List<ResumeRecord>) {
        cacheBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
        disk = DiskSpace.read(context)
        rows = known.mapNotNull { record ->
            val bytes = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
            if (bytes <= 0) return@mapNotNull null
            val availability = runCatching { Td.localFileAvailability(record.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            if (availability == LocalFileAvailability.Missing) return@mapNotNull null
            DownloadRow(record, bytes, availability == LocalFileAvailability.Complete)
        }
        counted = true
    }

    LaunchedEffect(history) { refresh(history) }
    // A finished download writes its record and disappears from the queue in the same breath, so
    // the sizes on this screen are re-measured whenever the queue changes rather than only when a
    // new record appears: without it a video that completed while the screen was open moved from
    // the top section to the bottom one still claiming the bytes it had when it started.
    LaunchedEffect(active.size) { refresh(history) }

    fun share(these: List<DownloadRow>) {
        scope.launch {
            val files = these.mapNotNull { row ->
                val path = runCatching { Td.localFilePath(row.record.fileId) }.getOrNull()
                path?.let { it to row.record.title }
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

    fun delete(row: DownloadRow) {
        scope.launch {
            runCatching { Td.deleteFile(row.record.fileId) }
            // Only forget the row once the file has actually gone. Forgetting it regardless would
            // leave bytes on the disk with nothing on this screen pointing at them, which is the
            // one state this screen exists to prevent.
            val left = runCatching { Td.localDownloadedBytes(row.record.fileId) }.getOrDefault(0L)
            if (left <= 0) settings.forgetDownload(row.record.chatId, row.record.messageId)
            refresh(history)
        }
    }

    fun deleteMany(these: List<DownloadRow>) {
        scope.launch {
            for (row in these) {
                runCatching { Td.deleteFile(row.record.fileId) }
                val left = runCatching { Td.localDownloadedBytes(row.record.fileId) }.getOrDefault(0L)
                if (left <= 0) settings.forgetDownload(row.record.chatId, row.record.messageId)
            }
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
                        TextButton(
                            onClick = { picked = rows.map { it.record.fileId }.toSet() },
                            enabled = chosen.size < rows.size,
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
                    if ((rows.isNotEmpty() || cacheBytes >= UNLISTED_FLOOR) && tab == COMPLETED) {
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
                        downloadBytes = rows.sumOf { it.bytes },
                        cacheBytes = cacheBytes,
                        freeBytes = disk.freeBytes,
                        totalBytes = disk.totalBytes,
                    )
                }
                items(rows, key = { "${it.record.chatId}_${it.record.messageId}" }) { row ->
                    DownloadCard(
                        row = row,
                        picking = picking,
                        checked = row.record.fileId in picked,
                        onPlay = { onPlay(row.record) },
                        onShare = { share(listOf(row)) },
                        onDelete = { confirmingDelete = row },
                        onToggle = {
                            picked = if (row.record.fileId in picked) {
                                picked - row.record.fileId
                            } else {
                                picked + row.record.fileId
                            }
                        },
                        // A hold is how a list of anything on a phone starts a selection, and it
                        // saves walking to the top bar for the first tick.
                        onHold = {
                            picking = true
                            picked = picked + row.record.fileId
                        },
                    )
                }
                if (counted && rows.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxHeight(0.6f)) {
                            BigEmpty(
                                "Nothing downloaded yet. Videos you keep are held here until you " +
                                    "delete them.",
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
            title = { Text("Delete this download?") },
            text = {
                Text(
                    "\"${row.record.title}\" frees ${StreamStats.formatBytes(row.bytes)}. Nothing " +
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
                Text(
                    "This frees ${StreamStats.formatBytes(maxOf(rows.sumOf { it.bytes }, cacheBytes))}. " +
                        "Nothing is removed from Telegram, so you can download any of them again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClearAll = false
                    scope.launch {
                        runCatching { Td.clearMediaCache() }
                        settings.forgetAllDownloads()
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

/** What the downloads cost, against what the phone has, above the list they belong to. */
@Composable
private fun StorageSummary(
    downloadBytes: Long,
    cacheBytes: Long,
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
                "${StreamStats.formatBytes(downloadBytes)} in downloads",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // The cache figure is the wider one: it counts the pictures and thumbnails the
                // browse screens keep as well, which the list below deliberately does not show.
                "${StreamStats.formatBytes(freeBytes)} free of " +
                    "${StreamStats.formatBytes(totalBytes)}  ·  " +
                    "${StreamStats.formatBytes(cacheBytes)} of storage used by TMPlayer",
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
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onHold: () -> Unit,
) {
    val details = listOfNotNull(
        StreamStats.formatBytes(row.bytes).takeIf { row.bytes > 0 },
        MediaMapper.formatDuration(row.record.durationSec).ifBlank { null },
        row.record.chatTitle.ifBlank { null },
        // A part-downloaded file still occupies its bytes, and that is the row a viewer looking
        // for space is most likely to want gone.
        if (row.complete) null else "Part downloaded",
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
                    row.record.title,
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
        // Hidden while picking: the card is a tick target then, and a Delete button inside
        // something the viewer is tapping to select is a mistake waiting to be made.
        if (!picking) {
            CardActions {
                PrimaryAction("Watch", Icons.Filled.PlayArrow, onPlay)
                SecondaryAction("Share", TmIcons.Share, onShare)
                SecondaryAction("Delete", Icons.Filled.Delete, onDelete, danger = true)
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

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "
