package com.tmplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
 * Everything this phone has downloaded, what it costs, and the way to get rid of it.
 *
 * The television has no screen like this and does not need one: it keeps a single video at a time
 * and replaces it on the next play, so there is never a list to manage. A phone keeps every
 * download until it is told otherwise, which is only a fair deal if the viewer can see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onPlay: (ResumeRecord) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val history by settings.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var confirmingClearAll by remember { mutableStateOf(false) }
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

    Scaffold(
        containerColor = Tone.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Offered on what TDLib is holding rather than on what this list shows. An
                    // install that downloaded videos before the index existed has bytes and no
                    // rows, and hiding the button there left the space unreachable.
                    if (rows.isNotEmpty() || cacheBytes >= UNLISTED_FLOOR) {
                        TextButton(onClick = { confirmingClearAll = true }) {
                            Text("Delete all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    StorageSummary(
                        downloadBytes = rows.sumOf { it.bytes },
                        cacheBytes = cacheBytes,
                        freeBytes = disk.freeBytes,
                        totalBytes = disk.totalBytes,
                    )
                }
                items(rows, key = { "${it.record.chatId}_${it.record.messageId}" }) { row ->
                    DownloadListItem(
                        row = row,
                        onPlay = { onPlay(row.record) },
                        onDelete = { delete(row) },
                    )
                }
                if (counted && rows.isEmpty()) {
                    item {
                        // Given a height of its own: BigEmpty fills what it is given, and inside a
                        // LazyColumn that is nothing, so it collapsed under the card above it.
                        Box(Modifier.fillParentMaxHeight(0.7f)) {
                        BigEmpty(
                            "Nothing downloaded yet. Videos you play are kept here until you " +
                                "delete them.",
                            icon = TmIcons.Download,
                        )
                        }
                    }
                }
            }
        }
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

/** A downloaded video: what it is, what it takes, and the bin that gets it back. */
@Composable
private fun DownloadListItem(row: DownloadRow, onPlay: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(row.record.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val duration = MediaMapper.formatDuration(row.record.durationSec)
            Text(
                listOfNotNull(
                    StreamStats.formatBytes(row.bytes).takeIf { row.bytes > 0 },
                    duration.ifEmpty { null },
                    row.record.chatTitle.ifBlank { null },
                    // A part-downloaded file still occupies its bytes, and that is the row a
                    // viewer looking for space is most likely to want gone.
                    if (row.complete) null else "Part downloaded",
                ).joinToString("  ·  "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${row.record.title}",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    )
}
