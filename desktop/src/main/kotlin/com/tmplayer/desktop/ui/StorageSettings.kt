package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
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
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DesktopSettings
import com.tmplayer.desktop.DesktopStorage
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.os.FolderPicker
import com.tmplayer.ui.components.jumpTarget
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
    /** TDLib's thumbnails and previews with the online posters: what Clear pictures and posters frees. */
    val picturesBytes: Long,
    /** One line per drive involved: "120 GB free of 500 GB on /home". */
    val drives: List<String>,
    /** What the downloads that have not finished hold, and how many there are. */
    val unfinishedBytes: Long = 0,
    val unfinishedCount: Int = 0,
    /** The drive the Downloads folder is on, which the storage ring is a picture of. */
    val diskTotalBytes: Long = 0,
    val diskFreeBytes: Long = 0,
) {
    /** Nothing at all Free up space could take: its button is off, with a line saying so. */
    val nothingToFree: Boolean
        get() = downloadsBytes + cachedBytes + picturesBytes + unfinishedBytes <= 0

    /** The storage ring's figures. The desktop has no unmatched files of its own to list. */
    fun chart(): com.tmplayer.data.StorageChart.Figures = com.tmplayer.data.StorageChart.Figures(
        downloads = downloadsBytes,
        unfinished = unfinishedBytes,
        recent = cachedBytes,
        unmatched = 0,
        pictures = picturesBytes,
        deviceTotal = diskTotalBytes,
        deviceFree = diskFreeBytes,
    )
}

/**
 * The Storage group of Settings (B6, D1), settings only (user, 2026-10-22): what TMPlayer uses with
 * the one Free up space, whose review opens over Settings and leaves the viewer here; where the
 * files live and the button that moves them; the cache's limit; Remove after watching; Low storage
 * mode; and the folder scan. The rows that cleared one part each (the cached videos, the cache,
 * the pictures, everything but downloads) are groups in Free up space's review.
 */
@Composable
internal fun StorageGroup(state: ShellState, jumps: Map<com.tmplayer.ui.onboarding.SetupStep, com.tmplayer.ui.components.JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val desktop by state.extras.prefs.state.collectAsState()
    val moving by DesktopStorage.relocationProgress.collectAsState()
    val removeAfterWatching by settings.removeAfterWatching.collectAsState(initial = false)
    val lowStorage by settings.lowStorageMode.collectAsState(initial = false)
    var refresh by remember { mutableIntStateOf(0) }
    var figures by remember { mutableStateOf<StorageFigures?>(null) }
    var dialog by remember { mutableStateOf<StorageDialog?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { figures = measureStorage(state) }

    // Free up space's review opens here, over Settings, and the run ends here with its toast: it
    // used to open over the Downloads panel, so the panel was what was left once it was done.
    var reviewing by remember { mutableStateOf(false) }
    var freeUpBusy by remember { mutableStateOf(false) }
    val sources = rememberDownloadSources(state)

    // The page's own heading names it, so the card comes first, as on the phone's Storage page.
    StorageCard(figures, nothingToFree = figures?.nothingToFree == true, busy = freeUpBusy) {
        if (!freeUpBusy) reviewing = true
    }
    if (reviewing) {
        SettingsFreeUpReview(state, sources, figures, onDismiss = { reviewing = false }) { picks, sections ->
            reviewing = false
            freeUpBusy = true
            scope.launch {
                val outcome = runFreeUp(state, picks, sections, sources.history, sources.cached)
                freeUpBusy = false
                refresh++
                toast(com.tmplayer.ui.components.freeUpResult(outcome))
            }
        }
    }

    val root = desktop.storageRoot.takeIf { it.isNotBlank() }?.let(::File)
    val idle = busy == null && moving == null
    Setting(
        s.storageLocation,
        when {
            moving != null -> s.storageMovingProgress(StorageRelocationPlan.size(moving!!.doneBytes), StorageRelocationPlan.size(moving!!.totalBytes))
            busy != null -> busy!!
            root != null -> StorageRelocationPlan.appFolder(root).path
            else -> s.storageDefaultFolders(DesktopPaths.layout(null).downloadsDir.path)
        },
        icon = TmIcons.Folder,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton(s.commonChange, enabled = idle) {
                scope.launch {
                    val picked = FolderPicker.pick(s.storagePickTitle, root ?: DesktopPaths.downloadsDir.parentFile)
                        ?: return@launch
                    dialog = check(state, picked)
                }
            }
            if (root != null) {
                RowButton(s.commonReset, enabled = idle) { scope.launch { dialog = check(state, null) } }
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
        icon = TmIcons.Archive,
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (desktop.cacheLimitBytes != DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) {
                RowButton(s.commonReset) { setLimit(state, DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) }
            }
            Stepper(
                onLess = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, -1)) },
                onMore = { setLimit(state, DesktopSettings.stepCacheLimit(desktop.cacheLimitBytes, 1)) },
            )
        }
    }
    // Here rather than among the downloads it acts on: on is asked first, since from then on files
    // go without a press; off deletes nothing.
    Toggle(REMOVE_AFTER_WATCHING, REMOVE_AFTER_WATCHING_DETAIL, removeAfterWatching, icon = TmIcons.Delete) { on ->
        if (on) dialog = StorageDialog.RemoveAfterWatching else scope.launch { settings.setRemoveAfterWatching(false) }
    }
    // A rolling window of the video on disk, for a drive with less room than the video; the line
    // under it says the cost.
    Toggle(
        s.settingsLowStorage,
        if (lowStorage) s.settingsLowStorageOn else s.settingsLowStorageOff,
        lowStorage,
        Modifier.jumpTarget(jumps[com.tmplayer.ui.onboarding.SetupStep.Storage]),
        icon = TmIcons.Archive,
    ) { on -> scope.launch { settings.setLowStorageMode(on) } }
    // Its notification, beside the mode it is about rather than on the Notifications page (2026-11-01).
    val lowStorageAlert by settings.noticeOn(com.tmplayer.data.Notice.LowStorage).collectAsState(initial = com.tmplayer.data.Notice.LowStorage.defaultOn)
    Toggle(s.settingsLowStorageAlert, s.settingsAlertLowStorageDetail, lowStorageAlert, icon = TmIcons.Bell) { on ->
        scope.launch { settings.setNoticeOn(com.tmplayer.data.Notice.LowStorage, on) }
    }
    Action(s.storageScan, s.storageScanDetail(DesktopPaths.downloadsDir.path), TmIcons.Search, end = RowEnd.None) {
        scope.launch {
            val found = DownloadIndex.scan(settings)
            if (found.isEmpty()) {
                toast(s.storageScanClean)
            } else {
                toast(s.storageScanFound(found.size))
                state.go(Destination.Downloads)
            }
        }
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
        StorageDialog.RemoveAfterWatching -> ConfirmDialog(
            title = s.confirmRemoveAfterWatchingTitle,
            message = s.confirmRemoveAfterWatchingMessage,
            detail = s.confirmRemoveAfterWatchingDetail,
            confirmLabel = s.confirmTurnOn,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch { settings.setRemoveAfterWatching(true) }
            },
        )
    }
}

/**
 * The figures and the one Free up space, as the Downloads page and Settings' Storage both show
 * them: the shared [com.tmplayer.ui.components.StorageOverview] (the small ring, what TMPlayer
 * uses, what is free, the button), then each drive's free space.
 */
@Composable
internal fun StorageCard(
    figures: StorageFigures?,
    nothingToFree: Boolean,
    busy: Boolean = false,
    result: String? = null,
    onFreeUp: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(Corner.Large), color = Tone.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.tmplayer.ui.components.StorageOverview(
                figures = figures?.chart() ?: com.tmplayer.data.StorageChart.Figures(0, 0, 0, 0, 0, 0, 0),
                measured = figures != null,
                nothingToFree = nothingToFree,
                onFreeUp = onFreeUp,
                busy = busy,
                result = result,
            )
            // Each drive TMPlayer writes to, when there are two.
            figures?.drives?.takeIf { it.size > 1 }?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
        }
    }
}

/**
 * Free up space's review for Settings' Storage. Its own composable so the download queue's
 * progress, read here, recomposes only the review while it is up and not the whole group.
 */
@Composable
private fun SettingsFreeUpReview(
    state: ShellState,
    sources: DownloadSources,
    figures: StorageFigures?,
    onDismiss: () -> Unit,
    onFree: (com.tmplayer.data.FreeUpReview.Picks, List<com.tmplayer.data.FreeUpReview.Section>) -> Unit,
) {
    val active by OfflineDownloads.active.collectAsState()
    val unfinished = active.values.sortedBy { it.order }.filter { it.downloadedBytes > 0 }
    val sections = freeUpSections(sources, unfinished, figures?.picturesBytes ?: 0L)
    FreeUpReviewHost(
        state,
        sections = sections,
        figures = figures,
        askedForRoom = false,
        onFree = { picks -> onFree(picks, sections) },
        onDismiss = onDismiss,
    )
}

/** The dialogs the Storage group can raise. */
internal sealed interface StorageDialog {
    data class Refused(val reason: String) : StorageDialog
    data class Confirm(val root: File?, val title: String, val body: String, val warning: String?, val adopt: Boolean) : StorageDialog
    data object RemoveAfterWatching : StorageDialog
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
internal suspend fun measureStorage(state: ShellState): StorageFigures = withContext(Dispatchers.IO) {
    val settings = state.settings
    val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    // The files in the folder the index does not name are listed on the page beside the
    // downloads, so they are counted with them rather than left in nobody's part.
    val downloads = DownloadIndex.bytesOnDisk(records) + DownloadIndex.unlisted.value.sumOf { it.length() }
    // The downloads that have not finished: their part files are TDLib's until they land.
    val unfinished = OfflineDownloads.active.value.values
        .filter { it.stage != OfflineDownloads.Stage.Moving && it.downloadedBytes > 0 }
    val unfinishedIds = unfinished.map { it.fileId }.toSet()
    val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
    val cachedBytes = cached.sumOf { r ->
        val id = runCatching { Td.currentFileId(r.chatId, r.messageId, r.fileId) }.getOrDefault(r.fileId)
        if (id in unfinishedIds) 0L else runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L)
    }
    val unfinishedBytes = unfinished.sumOf { it.downloadedBytes }
    val posters = runCatching { com.tmplayer.online.OnlineMetadata.current?.imageBytes() ?: 0L }.getOrDefault(0L)
    val tdTotal = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
    val home = DiskInfo.of(DesktopPaths.downloadsDir.let { dir -> generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir })
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
        picturesBytes = (tdTotal - cachedBytes - unfinishedBytes).coerceAtLeast(0) + posters,
        drives = drives,
        unfinishedBytes = unfinishedBytes,
        unfinishedCount = unfinished.size,
        diskTotalBytes = home.totalBytes,
        diskFreeBytes = home.freeBytes,
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
