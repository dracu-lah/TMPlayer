package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.desktop.DesktopStorage
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.os.FolderPicker
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** What the Storage card says, measured off the disk and TDLib. */
internal data class StorageFigures(
    val downloadsBytes: Long,
    val downloadCount: Int,
    val cachedBytes: Long,
    val cachedCount: Int,
    val picturesBytes: Long,
    /** One line per drive involved: "120 GB free of 500 GB on /home". */
    val drives: List<String>,
)

/**
 * The Storage group of Settings (B6, D1): the card, where the files live and the button that moves
 * them, the cache's limit, the cached videos, the clear buttons and the folder scan.
 */
@Composable
internal fun StorageGroup(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val desktop by state.extras.prefs.state.collectAsState()
    val moving by DesktopStorage.relocationProgress.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var figures by remember { mutableStateOf<StorageFigures?>(null) }
    var dialog by remember { mutableStateOf<StorageDialog?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { figures = measure(state) }

    Group(s.storageTitle)
    StorageCard(figures)

    val root = desktop.storageRoot.takeIf { it.isNotBlank() }?.let(::File)
    Setting(
        s.storageLocation,
        when {
            moving != null -> s.storageMovingProgress(StorageRelocationPlan.size(moving!!.doneBytes), StorageRelocationPlan.size(moving!!.totalBytes))
            busy != null -> busy!!
            root != null -> StorageRelocationPlan.appFolder(root).path
            else -> s.storageDefaultFolders(DesktopPaths.layout(null).downloadsDir.path)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = busy == null && moving == null, onClick = {
                scope.launch {
                    val picked = FolderPicker.pick(s.storagePickTitle, root ?: DesktopPaths.downloadsDir.parentFile)
                        ?: return@launch
                    dialog = check(state, picked)
                }
            }) { Text(s.commonChange) }
            if (root != null) {
                OutlinedButton(enabled = busy == null && moving == null, onClick = {
                    scope.launch { dialog = check(state, null) }
                }) { Text(s.commonReset) }
            }
        }
    }
    moving?.let { m ->
        LinearProgressIndicator(
            progress = { if (m.totalBytes > 0) (m.doneBytes.toFloat() / m.totalBytes).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val limitGb = desktop.cacheLimitBytes / GB
    Setting(
        s.storageCacheLimit,
        s.storageCacheLimitDetail(s.formatSizeGb(s.messages.formatter.number(limitGb))),
    ) {
        Row {
            Stepper(
                onLess = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, -1)) },
                onMore = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, 1)) },
            )
            if (desktop.cacheLimitBytes != DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) {
                TextButton(onClick = { setLimit(state, DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) }) { Text(s.commonReset) }
            }
        }
    }

    val f = figures
    Setting(
        s.storageCachedVideos,
        when {
            f == null -> s.storageMeasuring
            f.cachedCount == 0 -> s.storageCachedNone
            else -> s.storageCachedSummary(f.cachedCount, StorageRelocationPlan.size(f.cachedBytes))
        },
    ) {
        OutlinedButton(onClick = { state.go(Destination.Downloads) }) { Text(s.commonShow) }
    }
    state.extras.watchCache?.let {
        Setting(s.storageClearCache, s.storageClearCacheDetail) {
            OutlinedButton(onClick = { dialog = StorageDialog.ClearCache }) { Text(s.commonClear) }
        }
    }
    Setting(s.storageClearPictures, s.storageClearPicturesDetail) {
        OutlinedButton(onClick = {
            scope.launch {
                runCatching { Td.clearPicturesAndPreviews() }
                refresh++
                toast(s.storagePicturesCleared)
            }
        }) { Text(s.commonClear) }
    }
    Setting(s.storageClearAll, s.storageClearAllDetail) {
        OutlinedButton(onClick = { dialog = StorageDialog.ClearAllButDownloads }) { Text(s.commonClear, color = Tone.danger) }
    }
    Setting(s.storageScan, s.storageScanDetail(DesktopPaths.downloadsDir.path)) {
        OutlinedButton(onClick = {
            scope.launch {
                val found = DownloadIndex.scan(settings)
                if (found.isEmpty()) {
                    toast(s.storageScanClean)
                } else {
                    toast(s.storageScanFound(found.size))
                    state.go(Destination.Downloads)
                }
            }
        }) { Text(s.storageScanButton) }
    }

    when (val d = dialog) {
        null -> Unit
        is StorageDialog.Refused -> TmAlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(s.storageChooseAnother) },
            text = { Text(d.reason) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(s.commonOk) } },
        )
        is StorageDialog.Confirm -> ConfirmDialog(
            title = d.title,
            message = d.body,
            detail = d.warning,
            confirmLabel = if (d.adopt) s.storageAdopt else s.storageMove,
            destructive = false,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                busy = s.storageRestarting
                scope.launch {
                    val outcome = runCatching { DesktopStorage.relocation.move(d.root) }
                    busy = null
                    refresh++
                    outcome.onSuccess { toast(it.message) }.onFailure { toast(s.storageMoveStopped(it.message.orEmpty())) }
                    if (d.adopt && outcome.isSuccess) {
                        val found = DownloadIndex.scan(settings)
                        if (found.isNotEmpty()) {
                            toast(s.storageAdoptFound(found.size))
                        }
                    }
                }
            },
        )
        StorageDialog.ClearCache -> ConfirmDialog(
            title = s.storageClearCacheTitle,
            message = s.storageClearCacheMessage,
            detail = s.storageClearCacheNote,
            confirmLabel = s.commonClear,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    val freed = runCatching { state.extras.watchCache?.clearAll() ?: 0L }.getOrDefault(0L)
                    refresh++
                    toast(if (freed > 0) s.storageFreed(StorageRelocationPlan.size(freed)) else s.storageNothingToClear)
                }
            },
        )
        StorageDialog.ClearAllButDownloads -> ConfirmDialog(
            title = s.storageClearAllTitle,
            message = s.storageClearAllMessage,
            detail = s.storageClearAllNote,
            confirmLabel = s.commonClear,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    runCatching { state.extras.watchCache?.clearAll() }
                    // TDLib's own clear takes everything in its directory, which is safe only
                    // once no download is left in there from before they had a folder.
                    val migrated = runCatching { settings.downloadsMigratedNow() }.getOrDefault(false)
                    if (migrated) runCatching { Td.clearEverythingCached() } else runCatching { Td.clearPicturesAndPreviews() }
                    refresh++
                    toast(s.storageClearedAll)
                }
            },
        )
    }
}

/** The figures in one card: what each kind of file takes, then the drives' free space. */
@Composable
private fun StorageCard(figures: StorageFigures?) {
    val s = LocalStrings.current
    Surface(shape = MaterialTheme.shapes.medium, color = Tone.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (figures == null) {
                Text(s.storageMeasuring, color = Tone.muted)
                return@Column
            }
            Text(
                s.storageCardSummary(
                    StorageRelocationPlan.size(figures.downloadsBytes),
                    StorageRelocationPlan.size(figures.cachedBytes),
                    StorageRelocationPlan.size(figures.picturesBytes),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            figures.drives.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
        }
    }
}

/** The dialogs the Storage group can raise. */
internal sealed interface StorageDialog {
    data class Refused(val reason: String) : StorageDialog
    data class Confirm(val root: File?, val title: String, val body: String, val warning: String?, val adopt: Boolean) : StorageDialog
    data object ClearCache : StorageDialog
    data object ClearAllButDownloads : StorageDialog
}

private fun setLimit(state: ShellState, bytes: Long) {
    val before = state.extras.prefs.now.cacheLimitBytes
    state.extras.prefs.update { it.copy(cacheLimitBytes = bytes) }
    // A lower limit applies now, not at the next video played.
    if (bytes < before) {
        state.extras.watchCache?.let { cache -> com.tmplayer.platform.Background.scope.launch { runCatching { cache.evictOverCap(bytes) } } }
    }
}

/**
 * Checks a picked folder (null: back to the default folders) and words the answer: a refusal, or
 * the confirm dialog for the move.
 */
private suspend fun check(state: ShellState, picked: File?): StorageDialog = withContext(Dispatchers.IO) {
    val settings = state.settings
    val currentRoot = DesktopStorage.relocation.currentRoot()
    val current = DesktopPaths.layout(currentRoot)
    val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    val target = DesktopPaths.layout(picked)
    val moving = StorageRelocationPlan.toMove(records, target.downloadsDir)
    val downloadsBytes = moving.sumOf { File(it.localPath!!).length() }
    val cacheBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)

    var adopt = false
    var warning: String? = null
    if (picked != null) {
        val verdict = StorageRelocationPlan.validate(
            candidate = inspect(picked),
            currentFolders = listOf(current.cacheDir, current.downloadsDir),
            currentRoot = currentRoot,
            downloadsBytes = if (sameDrive(current.downloadsDir, picked)) 0 else downloadsBytes,
        )
        when (verdict) {
            is StorageRelocationPlan.Verdict.Refused -> return@withContext StorageDialog.Refused(verdict.reason)
            is StorageRelocationPlan.Verdict.Allowed -> {
                adopt = verdict.adopt
                warning = verdict.warning
            }
        }
    } else {
        val free = DiskInfo.of(target.downloadsDir).freeBytes
        if (free in 1 until downloadsBytes + StorageRelocationPlan.SPARE_BYTES && !sameDrive(current.downloadsDir, target.downloadsDir)) {
            return@withContext StorageDialog.Refused(
                L.storageDefaultDriveTooSmall(
                    StorageRelocationPlan.size(free),
                    StorageRelocationPlan.size(downloadsBytes + StorageRelocationPlan.SPARE_BYTES),
                ),
            )
        }
    }
    val (title, body) = StorageRelocationPlan.confirmText(
        where = picked?.path ?: L.storageTheDefaultFolders,
        newDownloads = target.downloadsDir.path,
        newCache = target.cacheDir.path,
        downloadCount = moving.size,
        downloadsBytes = downloadsBytes,
        cacheBytes = cacheBytes,
        adopt = adopt,
    )
    StorageDialog.Confirm(picked, title, body, warning, adopt)
}

private fun sameDrive(a: File, b: File) = com.tmplayer.data.DownloadFiles.sameStore(a, b)

/** What the disk says about a picked folder, without leaving anything behind in it. */
private fun inspect(root: File): StorageRelocationPlan.Candidate {
    val base = StorageRelocationPlan.appFolder(root)
    val existed = base.exists()
    val writable = runCatching {
        if (!base.isDirectory && !base.mkdirs()) return@runCatching false
        val probe = File.createTempFile(".tmplayer-", ".probe", base)
        probe.delete()
        true
    }.getOrDefault(false)
    if (!existed) runCatching { base.delete() }
    return StorageRelocationPlan.Candidate(
        root = root,
        writable = writable,
        usableBytes = DiskInfo.of(root).freeBytes,
        storeType = runCatching { Files.getFileStore(root.toPath()).type() }.getOrDefault(""),
        markerExists = File(base, StorageRelocationPlan.MARKER).exists(),
    )
}

/** The card's figures. TDLib's total less the cached videos is pictures, previews and the rest. */
private suspend fun measure(state: ShellState): StorageFigures = withContext(Dispatchers.IO) {
    val settings = state.settings
    val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    val downloads = DownloadIndex.bytesOnDisk(records)
    val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
    val cachedBytes = cached.sumOf { r ->
        val id = runCatching { Td.currentFileId(r.chatId, r.messageId, r.fileId) }.getOrDefault(r.fileId)
        runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L)
    }
    val tdTotal = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
    val dirs = listOf(DesktopPaths.downloadsDir, DesktopPaths.layout().cacheDir)
    val drives = dirs.map { volumeOf(it) }.distinct().map { (label, dir) ->
        val disk = DiskInfo.of(dir)
        L.storageDriveFree(StorageRelocationPlan.size(disk.freeBytes), StorageRelocationPlan.size(disk.totalBytes), label)
    }
    StorageFigures(
        downloadsBytes = downloads,
        downloadCount = records.size,
        cachedBytes = cachedBytes,
        cachedCount = cached.size,
        picturesBytes = (tdTotal - cachedBytes).coerceAtLeast(0),
        drives = drives,
    )
}

/**
 * The drive [dir] is on, named as its owner would: `E:\` on Windows, the mount point elsewhere,
 * found by walking up while the file store stays the same.
 */
private fun volumeOf(dir: File): Pair<String, File> {
    var probe: File = dir.absoluteFile
    while (!probe.exists()) probe = probe.parentFile ?: break
    val store = runCatching { Files.getFileStore(probe.toPath()) }.getOrNull() ?: return probe.path to probe
    var top = probe
    while (true) {
        val parent = top.parentFile ?: break
        if (runCatching { Files.getFileStore(parent.toPath()) }.getOrNull() != store) break
        top = parent
    }
    return top.path to top
}

private const val GB = 1024L * 1024 * 1024
