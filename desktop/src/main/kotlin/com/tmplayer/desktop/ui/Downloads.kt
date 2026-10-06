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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Td
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.i18n.L
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads, in three parts (B6): what is coming down (the shared [OfflineDownloads] queue fetched
 * by the desktop's in-process runner), what is kept in the Downloads folder (the index), and what
 * playing left in the cache, each with a Save to Downloads of its own. Files found in the folder
 * that the index does not name are listed between the last two, to keep or delete.
 *
 * Kept rows can be picked with their boxes for Delete selected; Delete all clears the lot, after a
 * question, since a download is the one thing TMPlayer never takes back on its own.
 */
/** The "Remove after watching" switch's words. */
internal val REMOVE_AFTER_WATCHING: String get() = L.downloadsRemoveAfterWatching
internal val REMOVE_AFTER_WATCHING_DETAIL: String get() = L.downloadsRemoveAfterWatchingDetail

@Composable
fun DownloadsPage(state: ShellState, downloadsDir: File = DesktopPaths.downloadsDir) {
    val s = LocalStrings.current
    val active by OfflineDownloads.active.collectAsState()
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val cachedRecords by state.settings.cachedVideos.collectAsState(initial = emptyList())
    val unlisted by DownloadIndex.unlisted.collectAsState()
    val removeAfterWatching by state.settings.removeAfterWatching.collectAsState(initial = false)
    val desktop by state.extras.prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val queue = active.values.sortedBy { it.order }
    // The cached videos the index already names are downloads, whatever the cache's records say.
    val kept = remember(history) { history.map { it.chatId to it.messageId }.toSet() }
    val cached = cachedRecords.filter { (it.chatId to it.messageId) !in kept }

    // Legacy rows ask TDLib where their file is; the answers arrive as they come.
    val legacy = remember { mutableStateMapOf<String, LocalFileAvailability>() }
    LaunchedEffect(history) {
        history.filter { it.localPath == null }.forEach { record ->
            legacy[record.key] = DownloadIndex.legacyAvailability(record)
        }
    }
    val cachedBytes = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(cached) {
        cached.forEach { record ->
            val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
            cachedBytes[record.key] = runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L)
        }
    }

    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf<List<ResumeRecord>?>(null) }
    BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            s.navDownloads,
            s.downloadsKeptIn(downloadsDir.path),
            actions = {
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { picked = emptySet() }) { Text(s.commonClearSelection) }
                    OutlinedButton(onClick = { confirm = history.filter { it.key in picked } }) {
                        Text(s.downloadsDeleteSelected(picked.size), color = Tone.danger)
                    }
                } else {
                    if (queue.any { it.busy && it.stage != OfflineDownloads.Stage.Moving }) {
                        TextButton(onClick = { OfflineDownloads.pauseAll(state.downloads) }) { Text(s.downloadsPauseAll) }
                    } else if (queue.any { !it.busy }) {
                        TextButton(onClick = { OfflineDownloads.resumeAll(state.downloads) }) { Text(s.downloadsResumeAll) }
                    }
                    TextButton(onClick = { OpenExternal.open(downloadsDir.apply { mkdirs() }) }) { Text(s.downloadsOpenFolder) }
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { confirm = history }) { Text(s.downloadsDeleteAll, color = Tone.danger) }
                    }
                }
            },
        )
        val list = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            ) {
                item(key = "h-active") { Section(s.downloadsDownloading) }
                if (queue.isEmpty()) {
                    item(key = "e-active") { Empty(s.downloadsEmptyActive) }
                }
                items(queue, key = { "a${it.fileId}" }) { row -> ActiveRow(state, row) }

                item(key = "h-done") { Section(s.downloadsDownloaded) }
                item(key = "t-remove") {
                    Setting(REMOVE_AFTER_WATCHING, REMOVE_AFTER_WATCHING_DETAIL) {
                        Switch(
                            checked = removeAfterWatching,
                            onCheckedChange = { on -> scope.launch { state.settings.setRemoveAfterWatching(on) } },
                        )
                    }
                }
                if (history.isEmpty()) {
                    item(key = "e-done") { Empty(s.downloadsEmptyDone(downloadsDir.path)) }
                }
                items(history, key = { "d${it.key}" }) { record ->
                    KeptRow(
                        record = record,
                        fileState = DownloadIndex.state(record),
                        legacy = legacy[record.key],
                        picked = record.key in picked,
                        onPick = { on -> picked = if (on) picked + record.key else picked - record.key },
                        onPlay = {
                            state.noteChatTitle(record.chatId, record.chatTitle)
                            state.openPlayer(record.toMediaItem(), startFromBeginning = false)
                        },
                        onShowInFolder = {
                            scope.launch {
                                val file = record.localPath?.let(::File)
                                    ?: runCatching { Td.localFilePath(Td.currentFileId(record.chatId, record.messageId, record.fileId)) }
                                        .getOrNull()?.let(::File)
                                if (file == null || !file.exists()) toast(s.downloadsFileGone) else OpenExternal.reveal(file)
                            }
                        },
                        onOpenElsewhere = { record.localPath?.let { OpenExternal.open(File(it)) } },
                        onResume = {
                            scope.launch {
                                val item = record.toMediaItem().let { item ->
                                    val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                                        .getOrDefault(record.fileId)
                                    item.copy(fileId = id)
                                }
                                OfflineDownloads.start(state.downloads, item, record.chatTitle)
                            }
                        },
                        onRemove = { confirm = listOf(record) },
                    )
                }

                if (unlisted.isNotEmpty()) {
                    item(key = "h-unlisted") { Section(s.downloadsUnlisted) }
                    items(unlisted, key = { "u${it.path}" }) { file ->
                        UnlistedRow(
                            file = file,
                            onKeep = { scope.launch { DownloadIndex.keep(state.settings, file) } },
                            onDelete = {
                                scope.launch {
                                    if (!DownloadIndex.discard(file)) toast(s.downloadsDeleteFailed(file.name))
                                }
                            },
                        )
                    }
                }

                item(key = "h-cached") { Section(s.downloadsCached) }
                item(key = "l-cached") {
                    Text(
                        s.downloadsCachedDetail(
                            MediaMapper.formatSize(desktop.cacheLimitBytes.takeIf { it > 0 } ?: DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.muted,
                    )
                }
                if (cached.isEmpty()) {
                    item(key = "e-cached") { Empty(s.downloadsEmptyCacheLimit) }
                }
                items(cached, key = { "c${it.key}" }) { record ->
                    CachedRow(
                        record = record,
                        bytes = cachedBytes[record.key],
                        queued = active.values.any { it.request.chatId == record.chatId && it.request.messageId == record.messageId },
                        onPlay = {
                            state.noteChatTitle(record.chatId, record.chatTitle)
                            state.openPlayer(record.toMediaItem(), startFromBeginning = false)
                        },
                        onSave = {
                            scope.launch {
                                // A cached record does not say whether its chat allows keeping a
                                // copy, so Telegram is asked before the file leaves the cache.
                                if (!Td.maySave(record.chatId, record.messageId)) {
                                    toast(ContentProtection.NOT_SAVABLE)
                                    return@launch
                                }
                                val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                                    .getOrDefault(record.fileId)
                                OfflineDownloads.start(state.downloads, record.toMediaItem().copy(fileId = id), record.chatTitle)
                                toast(s.downloadsSavingTitle(record.title))
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                                    .getOrDefault(record.fileId)
                                runCatching { Td.deleteFile(id) }
                                state.settings.forgetCachedVideo(record.chatId, record.messageId)
                            }
                        },
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }

    confirm?.let { doomed ->
        val one = doomed.size == 1
        ConfirmDialog(
            title = s.downloadsDeleteTitle(doomed.size),
            message = if (one) s.downloadsDeleteOneMessage(doomed.first().title) else s.downloadsDeleteManyMessage,
            detail = s.downloadsDeleteDetail,
            confirmLabel = s.commonDelete,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                picked = picked - doomed.map { it.key }.toSet()
                scope.launch {
                    val locked = doomed.count { !DownloadIndex.delete(state.settings, it) }
                    if (locked > 0) toast(s.downloadsLocked(locked))
                }
            },
        )
    }
}

private val ResumeRecord.key: String get() = "$chatId:$messageId"

/** How many downloads are coming down or moving, for the badge on the side bar and the rail. */
@Composable
internal fun rememberDownloadsInFlight(): Int {
    val active by OfflineDownloads.active.collectAsState()
    return active.values.count { it.busy }
}

/**
 * A toast in the window for every download that lands in the Downloads folder (B7): the one
 * signal that is never lost, whatever the OS shows or does not.
 */
@Composable
internal fun DownloadToasts(state: ShellState) {
    val s = LocalStrings.current
    val toast = rememberToast()
    val runner = state.downloads as? com.tmplayer.desktop.DesktopDownloadRunner ?: return
    LaunchedEffect(runner) {
        runner.finished.collect { toast(s.downloadsFinished(it.title)) }
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
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ActiveRow(state: ShellState, row: OfflineDownloads.Progress) {
    val s = LocalStrings.current
    val status = when (row.stage) {
        OfflineDownloads.Stage.Running -> buildString {
            append(s.downloadsProgress(StreamStats.formatBytes(row.downloadedBytes), StreamStats.formatBytes(row.totalBytes)))
            if (row.bytesPerSecond > 0) append("  ·  ").append(StreamStats.formatSpeed(row.bytesPerSecond))
            row.remainingSeconds?.let { append("  ·  ").append(StreamStats.formatEta(it)) }
        }
        OfflineDownloads.Stage.Queued -> s.downloadsQueued
        OfflineDownloads.Stage.Paused -> s.downloadsPausedAt(StreamStats.formatBytes(row.downloadedBytes))
        OfflineDownloads.Stage.Offline -> s.downloadsWaitingConnection
        OfflineDownloads.Stage.NoWifi -> s.downloadsWaitingWifi
        OfflineDownloads.Stage.Moving ->
            if (row.heldByPlayer) s.downloadsFinishesWhenStopped else s.downloadsMovingIn
        OfflineDownloads.Stage.Failed -> row.failure ?: s.downloadsStopped
    }
    val fraction = if (row.stage == OfflineDownloads.Stage.Moving) row.moveFraction ?: 0f else row.fraction ?: 0f
    Row(
        Modifier.fillMaxWidth().widthIn(max = 960.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.stage == OfflineDownloads.Stage.Failed) Tone.danger else Tone.muted,
            )
        }
        when {
            row.stage == OfflineDownloads.Stage.Moving -> Unit
            row.busy -> IconButton(onClick = { OfflineDownloads.pause(state.downloads, row.fileId) }) {
                Icon(TmIcons.Pause, contentDescription = s.playerPause)
            }
            else -> IconButton(onClick = { OfflineDownloads.resume(state.downloads, row.fileId) }) {
                Icon(
                    if (row.stage == OfflineDownloads.Stage.Failed) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                    contentDescription = if (row.stage == OfflineDownloads.Stage.Failed) s.commonTryAgain else s.downloadsResume,
                )
            }
        }
        IconButton(onClick = { OfflineDownloads.cancel(state.downloads, row.fileId) }) {
            Icon(Icons.Filled.Close, contentDescription = s.commonCancel)
        }
    }
}

/**
 * One download. Whole in the folder: plays, shows in its folder, opens in another app. Named by
 * the index but gone: "File missing" and Remove. From before downloads had a folder: plays from the
 * cache, and offers Resume when only part of it is there.
 */
@Composable
private fun KeptRow(
    record: ResumeRecord,
    fileState: DownloadIndex.FileState,
    legacy: LocalFileAvailability?,
    picked: Boolean,
    onPick: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onShowInFolder: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalStrings.current
    val missing = fileState == DownloadIndex.FileState.Missing ||
        (fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Missing)
    val partial = fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Partial
    val playable = !missing && !partial
    val detail = buildString {
        if (record.chatTitle.isNotBlank()) append(record.chatTitle).append("  ·  ")
        append(MediaMapper.formatSize(record.sizeBytes))
        when {
            missing -> append("  ·  ").append(s.downloadsFileMissing)
            partial -> append("  ·  ").append(s.downloadsPartDownloaded)
            fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Complete -> append("  ·  ").append(s.downloadsMovingSoon)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(if (playable) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = picked, onCheckedChange = onPick)
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (playable) Tone.accent else Tone.muted)
        Column(Modifier.weight(1f)) {
            Text(record.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (missing) Tone.danger else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            missing -> TextButton(onClick = onRemove) { Text(s.commonRemove) }
            partial -> TextButton(onClick = onResume) { Text(s.downloadsResume) }
            else -> {
                if (fileState == DownloadIndex.FileState.Present) {
                    TextButton(onClick = onOpenElsewhere) { Text(s.commonOpenElsewhere) }
                }
                IconButton(onClick = onShowInFolder) { Icon(TmIcons.Folder, contentDescription = s.downloadsShowInFolder) }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = s.downloadsDeleteDownload) }
            }
        }
    }
}

@Composable
private fun UnlistedRow(file: File, onKeep: () -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().widthIn(max = 960.dp).padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                s.downloadsUnlistedDetail(MediaMapper.formatSize(file.length())),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
            )
        }
        TextButton(onClick = onKeep) { Text(s.downloadsKeep) }
        TextButton(onClick = onDelete) { Text(s.commonDelete, color = Tone.danger) }
    }
}

@Composable
private fun CachedRow(
    record: ResumeRecord,
    bytes: Long?,
    queued: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
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
            val size = bytes?.takeIf { it > 0 }?.let(MediaMapper::formatSize) ?: MediaMapper.formatSize(record.sizeBytes)
            Text(
                listOf(record.chatTitle, size).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (queued) {
            Text(s.downloadsSaving, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        } else {
            TextButton(onClick = onSave) { Text(s.downloadsSave) }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = s.downloadsDeleteCached) }
    }
}
