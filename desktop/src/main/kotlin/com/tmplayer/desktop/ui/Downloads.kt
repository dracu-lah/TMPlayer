package com.tmplayer.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.rememberToast
import java.io.File
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * What is coming down and what is already kept: the shared [OfflineDownloads] queue fetched by the
 * desktop's in-process runner, and the finished list from the settings store.
 */
@Composable
fun DownloadsPage(state: ShellState) {
    val active by OfflineDownloads.active.collectAsState()
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val queue = active.values.sortedBy { it.order }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "Downloads",
            "Videos kept on this computer, to watch without waiting",
            actions = {
                if (queue.any { it.busy }) {
                    TextButton(onClick = { OfflineDownloads.pauseAll(state.downloads) }) { Text("Pause all") }
                } else if (queue.isNotEmpty()) {
                    TextButton(onClick = { OfflineDownloads.resumeAll(state.downloads) }) { Text("Resume all") }
                }
            },
        )
        if (queue.isEmpty() && history.isEmpty()) {
            Centred {
                Text(
                    "Nothing downloaded yet. Right click a video and choose Download to keep it here.",
                    color = Tone.muted,
                )
            }
            return@Column
        }
        val list = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            ) {
                if (queue.isNotEmpty()) item(key = "h-active") { Section("In progress") }
                items(queue, key = { "a${it.fileId}" }) { row -> ActiveRow(state, row) }
                if (history.isNotEmpty()) item(key = "h-done") { Section("On this computer") }
                items(history, key = { "d${it.chatId}:${it.messageId}" }) { record ->
                    KeptRow(
                        record = record,
                        onPlay = {
                            state.noteChatTitle(record.chatId, record.chatTitle)
                            state.openPlayer(record.toMediaItem(), startFromBeginning = false)
                        },
                        onShowInFolder = {
                            scope.launch {
                                val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                                    .getOrDefault(record.fileId)
                                val path = runCatching { Td.localFilePath(id) }.getOrNull()
                                if (path == null) toast("The file is not on this computer any more") else OpenExternal.reveal(File(path))
                            }
                        },
                        onRemove = {
                            scope.launch {
                                // The saved id dies with the TDLib session; ask again from the message.
                                val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                                    .getOrDefault(record.fileId)
                                runCatching { Td.deleteFile(id) }
                                state.settings.forgetDownload(record.chatId, record.messageId)
                            }
                        },
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = Tone.muted,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActiveRow(state: ShellState, row: OfflineDownloads.Progress) {
    val status = when (row.stage) {
        OfflineDownloads.Stage.Running -> buildString {
            append(StreamStats.formatBytes(row.downloadedBytes)).append(" of ").append(StreamStats.formatBytes(row.totalBytes))
            if (row.bytesPerSecond > 0) append("  ·  ").append(StreamStats.formatSpeed(row.bytesPerSecond))
            row.remainingSeconds?.let { append("  ·  ").append(StreamStats.formatEta(it)) }
        }
        OfflineDownloads.Stage.Queued -> "Waiting its turn"
        OfflineDownloads.Stage.Paused -> "Paused at ${StreamStats.formatBytes(row.downloadedBytes)}"
        OfflineDownloads.Stage.Offline -> "Waiting for a connection"
        OfflineDownloads.Stage.NoWifi -> "Waiting for Wi-Fi"
        OfflineDownloads.Stage.Failed -> row.failure ?: "Stopped"
    }
    Row(
        Modifier.fillMaxWidth().widthIn(max = 960.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { row.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.stage == OfflineDownloads.Stage.Failed) Tone.danger else Tone.muted,
            )
        }
        if (row.busy) {
            IconButton(onClick = { OfflineDownloads.pause(state.downloads, row.fileId) }) {
                Icon(TmIcons.Pause, contentDescription = "Pause")
            }
        } else {
            IconButton(onClick = { OfflineDownloads.resume(state.downloads, row.fileId) }) {
                Icon(
                    if (row.stage == OfflineDownloads.Stage.Failed) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                    contentDescription = if (row.stage == OfflineDownloads.Stage.Failed) "Try again" else "Resume",
                )
            }
        }
        IconButton(onClick = { OfflineDownloads.cancel(state.downloads, row.fileId) }) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
    }
}

@Composable
private fun KeptRow(record: ResumeRecord, onPlay: () -> Unit, onShowInFolder: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onPlay)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Tone.accent)
        Column(Modifier.weight(1f)) {
            Text(record.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${record.chatTitle}  ·  ${MediaMapper.formatSize(record.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShowInFolder) { Icon(TmIcons.Folder, contentDescription = "Show in folder") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove download") }
    }
}
