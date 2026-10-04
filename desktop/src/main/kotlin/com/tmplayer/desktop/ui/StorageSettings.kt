package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val desktop by state.extras.prefs.state.collectAsState()
    val moving by DesktopStorage.relocation.moving.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var figures by remember { mutableStateOf<StorageFigures?>(null) }
    var dialog by remember { mutableStateOf<StorageDialog?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { figures = measure(state) }

    Group("Storage")
    StorageCard(figures)

    val root = desktop.storageRoot.takeIf { it.isNotBlank() }?.let(::File)
    Setting(
        "Storage location",
        when {
            moving != null -> "Moving downloads, ${StorageRelocationPlan.size(moving!!.doneBytes)} of ${StorageRelocationPlan.size(moving!!.totalBytes)}"
            busy != null -> busy!!
            root != null -> StorageRelocationPlan.appFolder(root).path
            else -> "Default folders: downloads in ${DesktopPaths.layout(null).downloadsDir.path}"
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = busy == null && moving == null, onClick = {
                scope.launch {
                    val picked = FolderPicker.pick("Choose where TMPlayer keeps its files", root ?: DesktopPaths.downloadsDir.parentFile)
                        ?: return@launch
                    dialog = check(state, picked)
                }
            }) { Text("Change") }
            if (root != null) {
                OutlinedButton(enabled = busy == null && moving == null, onClick = {
                    scope.launch { dialog = check(state, null) }
                }) { Text("Reset") }
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
        "Cache limit",
        "$limitGb GB. Played videos stay until the cache passes this; the least recently played go first",
    ) {
        Row {
            Stepper(
                onLess = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, -1)) },
                onMore = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, 1)) },
            )
            if (desktop.cacheLimitBytes != DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) {
                TextButton(onClick = { setLimit(state, DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) }) { Text("Reset") }
            }
        }
    }

    val f = figures
    Setting(
        "Cached videos",
        when {
            f == null -> "Working it out\u2026"
            f.cachedCount == 0 -> "None. Playing a video keeps it here, up to the cache limit"
            else -> "${videos(f.cachedCount)}, ${StorageRelocationPlan.size(f.cachedBytes)}. Each can be saved to Downloads"
        },
    ) {
        OutlinedButton(onClick = { state.go(Destination.Downloads) }) { Text("Show") }
    }
    state.extras.watchCache?.let {
        Setting("Clear cache", "Deletes every cached video. Downloads are not touched") {
            OutlinedButton(onClick = { dialog = StorageDialog.ClearCache }) { Text("Clear") }
        }
    }
    Setting("Clear pictures and previews", "Thumbnails and pictures TMPlayer fetches again as you browse") {
        OutlinedButton(onClick = {
            scope.launch {
                runCatching { Td.clearPicturesAndPreviews() }
                refresh++
                toast("Pictures and previews cleared")
            }
        }) { Text("Clear") }
    }
    Setting("Clear everything except downloads", "Cached videos, pictures and previews. Downloads stay") {
        OutlinedButton(onClick = { dialog = StorageDialog.ClearAllButDownloads }) { Text("Clear", color = Tone.danger) }
    }
    Setting("Scan the downloads folder", "Find files in ${DesktopPaths.downloadsDir.path} that are not in TMPlayer's list") {
        OutlinedButton(onClick = {
            scope.launch {
                val found = DownloadIndex.scan(settings)
                if (found.isEmpty()) {
                    toast("Every file in the folder is in TMPlayer's list")
                } else {
                    toast(if (found.size == 1) "1 file is not in TMPlayer's list" else "${found.size} files are not in TMPlayer's list")
                    state.go(Destination.Downloads)
                }
            }
        }) { Text("Scan") }
    }

    when (val d = dialog) {
        null -> Unit
        is StorageDialog.Refused -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Choose another folder") },
            text = { Text(d.reason) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
        )
        is StorageDialog.Confirm -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.body)
                    d.warning?.let { Text(it, color = Tone.caution) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    busy = "Clearing the cache and restarting Telegram"
                    scope.launch {
                        val outcome = runCatching { DesktopStorage.relocation.move(d.root) }
                        busy = null
                        refresh++
                        outcome.onSuccess { toast(it.message) }.onFailure { toast("The move stopped: ${it.message}") }
                        if (d.adopt && outcome.isSuccess) {
                            val found = DownloadIndex.scan(settings)
                            if (found.isNotEmpty()) {
                                toast("${found.size} files there are not in TMPlayer's list. Keep or delete them in Downloads")
                            }
                        }
                    }
                }) { Text(if (d.adopt) "Use what is already there" else "Move") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        StorageDialog.ClearCache -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Clear the cache?") },
            text = { Text("Deletes every cached video. Downloads are not touched, and nothing is removed from Telegram.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        val freed = runCatching { state.extras.watchCache?.clearAll() ?: 0L }.getOrDefault(0L)
                        refresh++
                        toast(if (freed > 0) "${StorageRelocationPlan.size(freed)} freed" else "No cached videos to clear")
                    }
                }) { Text("Clear", color = Tone.danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        StorageDialog.ClearAllButDownloads -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Clear everything except downloads?") },
            text = {
                Text(
                    "Cached videos, pictures and previews all go; TMPlayer fetches them again as you browse. " +
                        "Downloads are not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        runCatching { state.extras.watchCache?.clearAll() }
                        // TDLib's own clear takes everything in its directory, which is safe only
                        // once no download is left in there from before they had a folder.
                        val migrated = runCatching { settings.downloadsMigratedNow() }.getOrDefault(false)
                        if (migrated) runCatching { Td.clearEverythingCached() } else runCatching { Td.clearPicturesAndPreviews() }
                        refresh++
                        toast("Cleared. Downloads are as they were")
                    }
                }) { Text("Clear", color = Tone.danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

/** The figures in one card: what each kind of file takes, then the drives' free space. */
@Composable
private fun StorageCard(figures: StorageFigures?) {
    Surface(shape = MaterialTheme.shapes.medium, color = Tone.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (figures == null) {
                Text("Working it out\u2026", color = Tone.muted)
                return@Column
            }
            Text(
                "${StorageRelocationPlan.size(figures.downloadsBytes)} in Downloads  ·  " +
                    "${StorageRelocationPlan.size(figures.cachedBytes)} cached  ·  " +
                    "${StorageRelocationPlan.size(figures.picturesBytes)} pictures and previews",
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
                "The default folders' drive has ${StorageRelocationPlan.size(free)} free. " +
                    "The downloads alone need ${StorageRelocationPlan.size(downloadsBytes + StorageRelocationPlan.SPARE_BYTES)}.",
            )
        }
    }
    val (title, body) = StorageRelocationPlan.confirmText(
        where = picked?.path ?: "the default folders",
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
        "${StorageRelocationPlan.size(disk.freeBytes)} free of ${StorageRelocationPlan.size(disk.totalBytes)} on $label"
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

private fun videos(n: Int) = if (n == 1) "1 video" else "$n videos"

private const val GB = 1024L * 1024 * 1024
