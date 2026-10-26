package com.tmplayer.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.Dp
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.PosterArt
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import kotlinx.coroutines.flow.first
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import com.tmplayer.desktop.DesktopFolderExport
import com.tmplayer.desktop.DownloadIndex
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.i18n.L
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads, as one page (B6), read top to bottom (user, 2026-10-22): what TMPlayer uses and what
 * is free, with the one Free up space, whose review is the only place anything here is cleared in
 * bulk; what is coming down (the shared [OfflineDownloads] queue, shown only while there is one);
 * what is kept in the Downloads folder (the index, a show's episodes grouped under it), with its
 * order; the files found in the folder that the index does not name, to keep or delete; and,
 * folded away at the foot, what playing left in the cache, each with a Save to Downloads.
 *
 * A row plays on a click; its other actions are behind its More button and a right click, the
 * same list. Kept rows can be picked with the tick on their poster for Save to folder or Delete on
 * several; once one is picked a click picks rather than plays. Remove after watching is a setting,
 * in Settings' Storage.
 */
/** The "Remove after watching" switch's words, which Settings' Storage shows. */
internal val REMOVE_AFTER_WATCHING: String get() = L.downloadsRemoveAfterWatching
internal val REMOVE_AFTER_WATCHING_DETAIL: String get() = L.downloadsRemoveAfterWatchingDetail

@Composable
fun DownloadsPage(state: ShellState, downloadsDir: File = DesktopPaths.downloadsDir) {
    val s = LocalStrings.current
    val active by OfflineDownloads.active.collectAsState()
    // The kids lock (CP57) leaves out the chats it does not allow here too: a download from one
    // of them would otherwise be a way round it.
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsState()
    val allHistory by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val allCached by state.settings.cachedVideos.collectAsState(initial = emptyList())
    val history = remember(allHistory, allowedChats) { com.tmplayer.data.KidsLock.records(allHistory, allowedChats) }
    val cachedRecords = remember(allCached, allowedChats) { com.tmplayer.data.KidsLock.records(allCached, allowedChats) }
    val unlisted by DownloadIndex.unlisted.collectAsState()
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
    // The other prompts on this page: a stray file to delete.
    var confirmUnlisted by remember { mutableStateOf<File?>(null) }
    // The storage card's figures, and Free up space: its review, a run under way, the line it left.
    var figures by remember { mutableStateOf<StorageFigures?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh, history.size, cached.size) { figures = runCatching { measureStorage(state) }.getOrNull() }
    // Up at once when the player sent the viewer here for room ("Not enough space").
    var freeingUp by remember { mutableStateOf(state.downloadsFreeUp) }
    // Asked for room: the device counts as short, so Low storage mode is offered while it is off.
    val askedForRoom = remember { state.downloadsFreeUp }
    LaunchedEffect(Unit) { state.downloadsFreeUp = false }
    var freeUpBusy by remember { mutableStateOf(false) }
    var lowStorageTurned by remember { mutableStateOf(false) }
    val lowStorageOn by state.settings.lowStorageMode.collectAsState(initial = true)
    // The downloads that stopped part way or are still arriving, with what each holds here.
    val unfinished = queue.filter { it.downloadedBytes > 0 }
    // Recently played is folded away at the foot of the page until it is opened.
    var cachedOpen by remember { mutableStateOf(state.downloadsOpenRecent) }
    // Where the Recently played heading sits, for Settings' Recently played row to land on.
    val cachedHeadingAt = remember { intArrayOf(-1) }
    BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }

    // Save to folder: the question, the progress and the result live in DesktopFolderExport, so
    // a batch keeps going while the viewer is on another page and is drawn again on return.
    val folder by DesktopFolderExport.state.collectAsState()
    var removeAfterSaving by remember { mutableStateOf(false) }
    val pickedRecords = remember(picked, history) { history.filter { it.key in picked } }
    val pickedBytes = pickedRecords.sumOf { it.sizeBytes }
    var lastFolderFree by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(picked.isNotEmpty(), folder.outcome) {
        if (picked.isNotEmpty()) {
            lastFolderFree = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DesktopFolderExport.lastFolderFree() }
        }
    }
    val selectAll = { picked = history.map { it.key }.toSet() }

    // Free up space's review, from what the page has measured: the groups in the order the sheet
    // lists them. A download still coming down is listed as kept, so the figures add up. The
    // desktop's unlisted files stay in their own section, where Keep is the other answer.
    val reviewSections = run {
        val coming = unfinished.map { it.fileId }.toSet()
        val recent = cached.filter { (cachedBytes[it.key] ?: 0L) > 0 && it.fileId !in coming }
        val keptNow = history
            .filter { DownloadIndex.state(it) != DownloadIndex.FileState.Missing && legacy[it.key] != LocalFileAvailability.Missing }
            .sortedByDescending { it.sizeBytes }
        listOf(
            com.tmplayer.data.FreeUpReview.Section(
                com.tmplayer.data.FreeUpReview.Group.Unfinished,
                unfinished.sumOf { it.downloadedBytes },
                items = unfinished.map { p ->
                    val busy = p.stage == OfflineDownloads.Stage.Running || p.stage == OfflineDownloads.Stage.Moving
                    com.tmplayer.data.FreeUpReview.Item(
                        p.fileId.toString(),
                        correctedTitle(p.request.chatId, p.request.messageId, p.title),
                        p.downloadedBytes,
                        if (busy) com.tmplayer.data.FreeUpReview.Lock.Downloading else null,
                    )
                },
            ),
            com.tmplayer.data.FreeUpReview.Section(
                com.tmplayer.data.FreeUpReview.Group.Recent,
                recent.sumOf { cachedBytes[it.key] ?: 0L },
                count = recent.size,
                items = recent.map {
                    com.tmplayer.data.FreeUpReview.Item(it.key, correctedTitle(it.chatId, it.messageId, it.title), cachedBytes[it.key] ?: 0L)
                },
            ),
            com.tmplayer.data.FreeUpReview.Section(com.tmplayer.data.FreeUpReview.Group.Pictures, figures?.picturesBytes ?: 0L),
            com.tmplayer.data.FreeUpReview.Section(
                com.tmplayer.data.FreeUpReview.Group.Downloads,
                keptNow.sumOf { it.sizeBytes },
                items = keptNow.map { com.tmplayer.data.FreeUpReview.Item(it.key, correctedTitle(it.chatId, it.messageId, it.title), it.sizeBytes) },
            ),
        )
    }

    /**
     * Free up space: what the review ticked, each through the one way this page removes that kind
     * of thing. A download that started again since the sheet was drawn, or a file the player
     * holds, is left and counted as left. Once started it runs to the end even if the page goes.
     */
    fun freeUp(picks: com.tmplayer.data.FreeUpReview.Picks) {
        if (freeUpBusy) return
        val sections = reviewSections
        val records = history
        val recents = cached
        freeingUp = false
        freeUpBusy = true
        scope.launch {
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                var freed = 0L
                var skipped = 0
                for (section in sections) {
                    val ticked = com.tmplayer.data.FreeUpReview.picked(picks, section)
                    when (section.group) {
                        com.tmplayer.data.FreeUpReview.Group.Unfinished -> ticked.forEach { item ->
                            val now = OfflineDownloads.active.value.values.firstOrNull { it.fileId.toString() == item.key }
                            when {
                                now == null -> Unit
                                now.stage == OfflineDownloads.Stage.Running || now.stage == OfflineDownloads.Stage.Moving -> skipped++
                                else -> {
                                    dropUnfinished(state, now.fileId)
                                    freed += item.bytes
                                }
                            }
                        }
                        com.tmplayer.data.FreeUpReview.Group.Recent -> ticked.forEach { item ->
                            val record = recents.firstOrNull { it.key == item.key } ?: return@forEach
                            val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
                            runCatching { Td.deleteFile(id) }
                            // Only forgotten once the bytes have gone: TDLib keeps a file a player has open.
                            if (runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L) > 0) {
                                skipped++
                            } else {
                                state.settings.forgetCachedVideo(record.chatId, record.messageId)
                                freed += item.bytes
                            }
                        }
                        com.tmplayer.data.FreeUpReview.Group.Downloads -> ticked.forEach { item ->
                            val record = records.firstOrNull { it.key == item.key } ?: return@forEach
                            if (runCatching { DownloadIndex.delete(state.settings, record) }.getOrDefault(false)) freed += item.bytes else skipped++
                        }
                        com.tmplayer.data.FreeUpReview.Group.Pictures -> if (com.tmplayer.data.FreeUpReview.wholePicked(picks, section)) {
                            if (runCatching { Td.clearPicturesAndPreviews() }.isSuccess) freed += section.bytes
                            runCatching { com.tmplayer.online.OnlineMetadata.current?.purgeImages() }
                        }
                        com.tmplayer.data.FreeUpReview.Group.Unmatched -> Unit
                    }
                }
                com.tmplayer.data.FreeUpReview.Outcome(freed, skipped)
            }
            // A download that went leaves the selection too.
            picked = picked - com.tmplayer.data.FreeUpReview.downloadsPicked(picks, sections).map { it.key }.toSet()
            refresh++
            freeUpBusy = false
            // A toast, not a line inside the card.
            toast(com.tmplayer.ui.components.freeUpResult(outcome))
        }
    }

    /**
     * Save to folder for one download or the selection: the OS's folder dialog, then the
     * confirmation with the sizes and the room there, the copy with its progress, and the result.
     */
    fun saveToFolder(records: List<ResumeRecord>) {
        if (records.isEmpty()) return
        if (DesktopFolderExport.busy) {
            toast(s.downloadsSaveToFolderBusy)
            return
        }
        scope.launch {
            if (records.size == 1 && !Td.maySave(records[0].chatId, records[0].messageId)) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            val start = DesktopFolderExport.lastDir ?: downloadsDir.parentFile
            val dir = com.tmplayer.desktop.os.FolderPicker.pick(s.downloadsSaveToFolderPick, start) ?: return@launch
            // Asked again now: the list can have changed while the dialog was up.
            val now = state.settings.downloadsNow().map { it.chatId to it.messageId }.toSet()
            val candidates = records.filter { (it.chatId to it.messageId) in now }.map { record ->
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when (DownloadIndex.state(record)) {
                        DownloadIndex.FileState.Present -> record.localPath?.let(::File)
                        DownloadIndex.FileState.Missing -> null
                        DownloadIndex.FileState.Legacy ->
                            if (DownloadIndex.legacyAvailability(record) == LocalFileAvailability.Complete) {
                                runCatching { Td.localFilePath(Td.currentFileId(record.chatId, record.messageId, record.fileId)) }
                                    .getOrNull()?.let(::File)
                            } else {
                                null
                            }
                    }
                }
                DesktopFolderExport.Candidate(record, com.tmplayer.data.VideoCorrections.titleFor(record.chatId, record.messageId, record.title), file)
            }
            if (candidates.isEmpty()) return@launch
            DesktopFolderExport.prepare(dir, candidates)
        }
    }
    // The episodes of one show together in watching order, other videos newest first.
    // The page's order, remembered once: newest, by name, or the largest first, to make room.
    val downloadsSort by state.settings.downloadsSort.collectAsState(initial = com.tmplayer.data.DownloadShelf.Sort.Newest)
    val shelf = remember(history, downloadsSort) {
        com.tmplayer.data.DownloadShelf.arrange(history, { it.toMediaItem() }, { it.key }, { it.updatedAt }, sort = downloadsSort)
    }
    val list = rememberLazyListState()

    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // Ctrl+A (Cmd+A on a Mac) picks every download while a selection is on; with none,
            // the key is left to whatever has focus.
            val chord = event.isCtrlPressed || event.isMetaPressed
            if (picked.isNotEmpty() && chord && event.key == Key.A && event.type == KeyEventType.KeyDown) {
                selectAll()
                true
            } else {
                false
            }
        },
    ) {
        PageHeader(
            s.navDownloads,
            if (picked.isEmpty()) {
                s.downloadsKeptIn(downloadsDir.path)
            } else {
                // The running total, and a warning when the folder saved to last has less room.
                val warning = lastFolderFree?.takeIf { pickedBytes > it.second }?.let { (name, free) ->
                    s.downloadsSaveToFolderOverLast(free = MediaMapper.formatSize(free), folder = name)
                }
                listOfNotNull(s.downloadsSelected(count = picked.size), MediaMapper.formatSize(pickedBytes), warning).joinToString("  ·  ")
            },
            actions = {
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { picked = emptySet() }) { Text(s.commonClearSelection) }
                    TextButton(onClick = selectAll, enabled = picked.size < history.size) { Text(s.downloadsSelectAll) }
                    OutlinedButton(onClick = { saveToFolder(pickedRecords) }) {
                        Icon(TmIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.downloadsSaveToFolder)
                    }
                    OutlinedButton(onClick = { confirm = history.filter { it.key in picked } }) {
                        Text(s.downloadsDeleteSelected(picked.size), color = Tone.danger)
                    }
                }
                // Open folder and Select all sit on the Downloaded heading instead, on the list's
                // own edge: up here they stood at the window's edge, far past a capped list.
            },
        )
        val keptRow: @Composable (ResumeRecord) -> Unit = { record ->
            KeptRow(
                record = record,
                fileState = DownloadIndex.state(record),
                legacy = legacy[record.key],
                picked = record.key in picked,
                picking = picked.isNotEmpty(),
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
                onSaveToFolder = { saveToFolder(listOf(record)) },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val margin = pageMargin()
            val bottom = com.tmplayer.ui.components.LocalBottomChrome.current
            Box(Modifier.fillMaxSize().padding(start = margin - ROW_INSET)) {
                // Every item's words start [ROW_INSET] into the list, so the list itself starts
                // that much short of the page margin: the words line up under the heading, and
                // a row's hover fill reaches out past them as the settings rows' does.
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottom),
                    modifier = Modifier
                        .padding(end = (margin - ROW_INSET).coerceAtLeast(0.dp))
                        .widthIn(max = LIST_MAX + ROW_INSET * 2)
                        .fillMaxHeight()
                        .homeEndScrolls(list)
                        .remembersRemoteItem(),
                ) {
                    var at = 0
                    // What TMPlayer uses, what is free, and the one Free up space: first, so the
                    // page answers "where did my space go" before it lists anything.
                    item(key = "storage") {
                        Box(Modifier.padding(horizontal = ROW_INSET).widthIn(max = OVERVIEW_MAX)) {
                            StorageCard(
                                figures,
                                nothingToFree = com.tmplayer.data.FreeUpReview.nothingToFree(reviewSections),
                                busy = freeUpBusy,
                            ) { if (!freeUpBusy) freeingUp = true }
                        }
                    }
                    at += 1
                    // Only while something is on its way, with the one control for all of it.
                    if (queue.isNotEmpty()) {
                        item(key = "h-active") {
                            Section(queueHeading(queue)) {
                                if (queue.size > 1) {
                                    if (queue.any { it.busy && it.stage != OfflineDownloads.Stage.Moving }) {
                                        TextButton(onClick = { OfflineDownloads.pauseAll(state.downloads) }) { Text(s.downloadsPauseAll) }
                                    } else if (queue.any { !it.busy }) {
                                        TextButton(onClick = { OfflineDownloads.resumeAll(state.downloads) }) { Text(s.downloadsResumeAll) }
                                    }
                                }
                            }
                        }
                        items(queue, key = { "a${it.fileId}" }) { row -> ActiveRow(state, row, onFreeUp = { freeingUp = true }) }
                        at += 1 + queue.size
                    }

                    item(key = "h-done") {
                        Section(if (history.isEmpty()) s.downloadsDownloaded else s.downloadsTabDownloadedCount(history.size)) {
                            if (picked.isEmpty()) {
                                TextButton(onClick = { OpenExternal.open(downloadsDir.apply { mkdirs() }) }) { Text(s.downloadsOpenFolder) }
                                // Starts a selection with everything in it, for Save to folder or
                                // Delete. Delete all went: this and Delete, or the review, say it.
                                if (history.isNotEmpty()) TextButton(onClick = selectAll) { Text(s.downloadsSelectAll) }
                            }
                        }
                    }
                    at += 1
                    if (history.size > 1) {
                        item(key = "t-sort") {
                            Box(Modifier.padding(vertical = 4.dp)) {
                                ChoiceChips(
                                    com.tmplayer.data.DownloadShelf.Sort.entries.map { sort ->
                                        Choice(
                                            sort.name,
                                            when (sort) {
                                                com.tmplayer.data.DownloadShelf.Sort.Newest -> s.downloadsSortNewest
                                                com.tmplayer.data.DownloadShelf.Sort.Name -> s.downloadsSortName
                                                com.tmplayer.data.DownloadShelf.Sort.Largest -> s.downloadsSortLargest
                                            },
                                            when (sort) {
                                                com.tmplayer.data.DownloadShelf.Sort.Newest -> TmIcons.Clock
                                                com.tmplayer.data.DownloadShelf.Sort.Name -> TmIcons.TextSize
                                                com.tmplayer.data.DownloadShelf.Sort.Largest -> TmIcons.Download
                                            },
                                            selected = sort == downloadsSort,
                                        ) { scope.launch { state.settings.setDownloadsSort(sort) } }
                                    },
                                )
                            }
                        }
                        at += 1
                    }
                    if (history.isEmpty()) {
                        item(key = "e-done") {
                            Empty(s.downloadsEmptyDone(downloadsDir.path), s.browseGoToChats) {
                                state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                            }
                        }
                        at += 1
                    }
                    shelf.forEach { entry ->
                        when (entry) {
                            is com.tmplayer.data.DownloadShelf.Entry.Single -> {
                                item(key = "d${entry.key}") { keptRow(entry.row) }
                                at += 1
                            }
                            is com.tmplayer.data.DownloadShelf.Entry.Show -> {
                                item(key = "s${entry.key}") {
                                    ShowHeading(
                                        entry.title,
                                        listOf(
                                            if (entry.pieces) s.gridPartsCount(entry.episodes.size) else s.seriesEpisodesCount(entry.episodes.size),
                                            MediaMapper.formatSize(entry.episodes.sumOf { it.sizeBytes }),
                                        ).joinToString("  ·  "),
                                    )
                                }
                                items(entry.episodes, key = { "d${it.key}" }) { record -> keptRow(record) }
                                at += 1 + entry.episodes.size
                            }
                        }
                    }

                    if (unlisted.isNotEmpty()) {
                        item(key = "h-unlisted") { Section(s.downloadsUnlisted) }
                        items(unlisted, key = { "u${it.path}" }) { file ->
                            UnlistedRow(
                                file = file,
                                onKeep = { scope.launch { DownloadIndex.keep(state.settings, file) } },
                                onDelete = { confirmUnlisted = file },
                            )
                        }
                        at += 1 + unlisted.size
                    }

                    // Not downloads, so folded away at the foot until somebody opens it.
                    cachedHeadingAt[0] = at
                    item(key = "h-cached") {
                        Box(Modifier.padding(top = 12.dp)) {
                            Setting(
                                if (cached.isEmpty()) s.downloadsCached else s.downloadsTabCachedCount(cached.size),
                                s.downloadsCachedDetail(
                                    MediaMapper.formatSize(desktop.cacheLimitBytes.takeIf { it > 0 } ?: DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES),
                                ),
                                icon = TmIcons.Clock,
                                onClick = { cachedOpen = !cachedOpen },
                            ) {
                                Icon(
                                    if (cachedOpen) TmIcons.ChevronUp else TmIcons.ChevronDown,
                                    contentDescription = if (cachedOpen) s.commonHide else s.commonShow,
                                    tint = Tone.muted,
                                )
                            }
                        }
                    }
                    if (cachedOpen && cached.isEmpty()) {
                        item(key = "e-cached") { Empty(s.downloadsEmptyCacheLimit) }
                    }
                    if (cachedOpen) items(cached, key = { "c${it.key}" }) { record ->
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
                                    // Not said: the row moves up to Downloading.
                                    OfflineDownloads.start(state.downloads, record.toMediaItem().copy(fileId = id), record.chatTitle)
                                }
                            },
                        )
                    }
                }
                ScrollToTop(list)
                // The hold to scroll handle on a long list, the scrollbar on a short one.
                FastScroller(list) {
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }

    // Opened from Settings' Recently played row: that section, unfolded, in view.
    LaunchedEffect(Unit) {
        if (!state.downloadsOpenRecent) return@LaunchedEffect
        state.downloadsOpenRecent = false
        // The lists arrive after the first frame, and the heading's place with them: wait for
        // them, or the page lands where the heading was while the lists were still empty.
        runCatching { state.settings.downloadHistory.first() }
        runCatching { state.settings.cachedVideos.first() }
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (cachedHeadingAt[0] > 0) runCatching { list.scrollToItem(cachedHeadingAt[0]) }
    }

    // Opened from the low storage notification or Settings' Free up space: the review, already up.
    LaunchedEffect(state.downloadsOpenFreeUp) {
        if (!state.downloadsOpenFreeUp) return@LaunchedEffect
        state.downloadsOpenFreeUp = false
        freeingUp = true
    }

    if (freeingUp) {
        val f = figures
        val short = com.tmplayer.data.FreeUpSpace.isShort(f?.diskFreeBytes ?: -1, f?.diskTotalBytes ?: 0, askedForRoom = askedForRoom)
        com.tmplayer.ui.components.FreeUpReviewDialog(
            sections = reviewSections,
            onFree = { picks -> freeUp(picks) },
            onDismiss = { freeingUp = false },
            lowStorage = if (short && (!lowStorageOn || lowStorageTurned)) {
                com.tmplayer.ui.components.LowStorageOffer(
                    mode = s.settingsLowStorage,
                    windowBytes = com.tmplayer.player.LowStorageWindow.WINDOW_BYTES,
                    on = lowStorageTurned,
                ) {
                    lowStorageTurned = true
                    scope.launch { runCatching { state.settings.setLowStorageMode(true) } }
                }
            } else {
                null
            },
        )
    }

    confirmUnlisted?.let { file ->
        ConfirmDialog(
            title = s.confirmUnlistedTitle,
            message = s.confirmUnlistedMessage(file.name),
            detail = MediaMapper.formatSize(file.length()),
            confirmLabel = s.commonDelete,
            onDismiss = { confirmUnlisted = null },
            onConfirm = {
                confirmUnlisted = null
                scope.launch {
                    if (!DownloadIndex.discard(file)) toast(s.downloadsDeleteFailed(file.name))
                }
            },
        )
    }

    folder.offer?.let { offer ->
        com.tmplayer.ui.components.FolderCopyConfirmDialog(
            plan = offer.plan,
            remove = removeAfterSaving,
            onRemove = { removeAfterSaving = it },
            onSave = { items ->
                if (!DesktopFolderExport.start(state.settings, offer, items, removeAfterSaving)) toast(s.downloadsSaveToFolderBusy)
                removeAfterSaving = false
            },
            onCancel = {
                DesktopFolderExport.dismissOffer()
                removeAfterSaving = false
            },
        )
    }
    if (folder.preparing) com.tmplayer.ui.components.FolderCopyPreparingDialog()
    folder.progress?.let { com.tmplayer.ui.components.FolderCopyProgressDialog(it, onCancel = { DesktopFolderExport.cancel() }) }
    folder.outcome?.takeIf { !it.clean }?.let {
        com.tmplayer.ui.components.FolderCopyResultDialog(it, onClose = { DesktopFolderExport.consume() })
    }
    // A whole run is a toast; the selection is left once anything was saved.
    LaunchedEffect(folder.outcome) {
        val outcome = folder.outcome ?: return@LaunchedEffect
        if (outcome.copied > 0) picked = emptySet()
        if (outcome.clean) {
            toast(outcome.headline + (if (outcome.moved > 0) ". " + s.downloadsSaveToFolderMoved(count = outcome.moved) else ""))
            DesktopFolderExport.consume()
        }
    }

    confirm?.let { doomed ->
        val one = doomed.size == 1
        val oneName = if (one) com.tmplayer.ui.browse.rememberVideoName(remember(doomed) { doomed.first().toMediaItem() }) else null
        ConfirmDialog(
            title = s.downloadsDeleteTitle(doomed.size),
            message = if (one) s.downloadsDeleteOneMessage(oneName ?: doomed.first().title) else s.downloadsDeleteManyMessage,
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

/** A waiting or stopped download's line, with what it holds here when it holds anything. */
private fun unfinishedAlso(line: String, row: OfflineDownloads.Progress): String =
    if (row.downloadedBytes > 0) line + "  ·  " + L.downloadsUnfinishedOnDevice(MediaMapper.formatSize(row.downloadedBytes)) else line

/**
 * Stops a download and deletes the part it had fetched, unless the same file is the recently
 * played video, whose bytes those are too.
 */
private suspend fun dropUnfinished(state: ShellState, fileId: Int) {
    OfflineDownloads.cancel(state.downloads, fileId)
    runCatching { Td.cancelDownload(fileId) }
    val cachedIds = runCatching { state.settings.cachedVideosNow() }.getOrDefault(emptyList())
        .map { runCatching { Td.currentFileId(it.chatId, it.messageId, it.fileId) }.getOrDefault(it.fileId) }
    if (fileId !in cachedIds) runCatching { Td.deleteFile(fileId) }
}

/** How many downloads are coming down or moving, for the badge on the side bar and the rail. */
@Composable
internal fun rememberDownloadsInFlight(): Int {
    // The count only: the map changes with every download's progress, and reading it here
    // recomposed the whole bar each time, where the count changes as a download starts or ends.
    val active = OfflineDownloads.active.collectAsState()
    val inFlight by remember(active) { androidx.compose.runtime.derivedStateOf { active.value.values.count { it.busy } } }
    return inFlight
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

/** The Downloading heading, the phone's words: what the queue is doing as a whole. */
private fun queueHeading(queue: List<OfflineDownloads.Progress>): String {
    val waiting = queue.count { it.stage == OfflineDownloads.Stage.Queued }
    val running = queue.any { it.stage == OfflineDownloads.Stage.Running }
    return when {
        running && waiting > 0 -> L.downloadsHeadingRunningWaiting(waiting)
        running -> L.downloadsHeadingRunning
        queue.all { it.stage == OfflineDownloads.Stage.Offline } -> L.downloadsWaitingConnection
        queue.all { it.stage == OfflineDownloads.Stage.Paused } -> L.downloadsPaused
        else -> L.downloadsDownloading
    }
}

@Composable
private fun Section(title: String, first: Boolean = false, actions: @Composable () -> Unit = {}) {
    Row(
        // The button's own 12 dp of padding leaves its word on the rows' icons' outer edge.
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = ROW_INSET, end = ROW_INSET - 12.dp, top = if (first) 0.dp else 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** A show's name over its downloaded episodes, with how many there are and what they take. */
@Composable
private fun ShowHeading(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(start = ROW_INSET, end = ROW_INSET, top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }
}

/** An empty section: the phone's download mark and its words, on the card's fill. */
@Composable
private fun Empty(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(Corner.Large),
        color = Tone.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            com.tmplayer.ui.components.SettingIconChip(TmIcons.Download)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
            // The way on from an empty list: the chats, where every video has its Download.
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(16.dp))
                androidx.compose.material3.OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** One entry of a row's right-click menu. */
private class RowMenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val action: () -> Unit)

/**
 * The frame every video on the page is drawn in: no card at rest, the step above the page under
 * the pointer and the keyboard (the Chats rows' look), a click on it plays when [onClick] is given,
 * and a right click (or Ctrl+click on a Mac) opens [menu], the row's buttons with their names.
 * With [more] the same menu also hangs off a More button at the row's end, for whoever does not
 * think to right click, in place of a row of icon buttons (user, 2026-10-22).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoRow(
    onClick: (() -> Unit)?,
    menu: List<RowMenuEntry>,
    start: Dp = ROW_INSET,
    more: Boolean = false,
    content: @Composable RowScope.(lit: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val ring = keyboardFocused(interactions)
    var open by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (hovered || ring) Tone.surface else Color.Transparent)
                .then(if (ring) Modifier.border(Focus.Edge, Tone.accent, shape) else Modifier)
                .hoverable(interactions)
                .onPointerEvent(PointerEventType.Press) { event ->
                    val macContext = ON_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                    if (menu.isNotEmpty() && (event.buttons.isSecondaryPressed || macContext)) open = true
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                // The icon buttons at the end carry 12 dp of room round their icons.
                .padding(start = start, end = ROW_INSET - 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content(hovered || ring)
            if (more && menu.isNotEmpty()) TipIconButton(TmIcons.MoreVert, LocalStrings.current.commonMoreOptions, { open = true })
        }
        com.tmplayer.ui.components.TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label, color = if (entry.danger) Tone.danger else Color.Unspecified) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null, tint = if (entry.danger) Tone.danger else Tone.muted) },
                    onClick = {
                        open = false
                        entry.action()
                    },
                )
            }
        }
    }
}

/**
 * A video's small 2:3 poster, the one History's and a chat's rows wear (the phone's download cards
 * too): the provider's poster where the title is matched, else its picture, or the generated
 * cover with no name on it, since the title is beside it.
 */
@Composable
private fun Thumb(item: MediaItem, pick: Pick? = null) {
    val cover = rememberCover(item)
    val shown = remember(cover) { cover.copy(title = "") }
    val shape = RoundedCornerShape(Corner.Small)
    Box(
        Modifier
            .width(THUMB_WIDTH)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .then(if (pick?.picked == true) Modifier.border(2.dp, Tone.accent, shape) else Modifier),
    ) {
        val meta = com.tmplayer.ui.online.rememberMeta(item, showOnly = true)
        PosterArt(item.miniThumbnail, item.thumbnailFileId, shown, meta?.posterUrl, Modifier.fillMaxSize(), compactCover = true)
        if (pick != null && pick.shown) {
            val s = LocalStrings.current
            // The tick, on the poster as a photo grid has it: there under the pointer and once
            // anything is picked, so the column of boxes is not on every row at rest.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (pick.picked) Tone.accent else Color.Black.copy(alpha = 0.45f))
                    .border(1.5.dp, if (pick.picked) Tone.accent else Color.White, CircleShape)
                    .toggleable(value = pick.picked, role = Role.Checkbox, onValueChange = pick.onPick)
                    .semantics { contentDescription = s.downloadsSelect },
                contentAlignment = Alignment.Center,
            ) {
                if (pick.picked) Icon(TmIcons.Check, contentDescription = null, tint = Tone.onAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A poster's tick: whether the row is picked, whether the tick shows, and what a press on it does. */
private class Pick(val picked: Boolean, val shown: Boolean, val onPick: (Boolean) -> Unit)

/** A row's words: the title over its details. */
@Composable
private fun RowScope.Words(title: String, detail: String, detailColor: Color = Tone.muted, extra: @Composable () -> Unit = {}) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra()
    }
}

/** The file's own name under a row's details: quieter still, one line, cut in the middle. */
@Composable
private fun FileNameLine(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted.copy(alpha = 0.75f),
        maxLines = 1,
        // The end of a file name is its extension and release tags; the middle goes when it does not fit.
        overflow = TextOverflow.MiddleEllipsis,
    )
}

@Composable
private fun ActiveRow(state: ShellState, row: OfflineDownloads.Progress, onFreeUp: (() -> Unit)? = null) {
    val s = LocalStrings.current
    val status = when (row.stage) {
        OfflineDownloads.Stage.Running -> buildString {
            append(s.downloadsProgress(StreamStats.formatBytes(row.downloadedBytes), StreamStats.formatBytes(row.totalBytes)))
            if (row.bytesPerSecond > 0) append("  ·  ").append(StreamStats.formatSpeed(row.bytesPerSecond))
            row.remainingSeconds?.let { append("  ·  ").append(StreamStats.formatEta(it)) }
        }
        OfflineDownloads.Stage.Queued -> unfinishedAlso(s.downloadsQueued, row)
        OfflineDownloads.Stage.Paused -> unfinishedAlso(s.downloadsPaused, row)
        OfflineDownloads.Stage.Offline -> unfinishedAlso(s.downloadsWaitingConnection, row)
        OfflineDownloads.Stage.NoWifi -> unfinishedAlso(s.downloadsWaitingWifi, row)
        OfflineDownloads.Stage.Moving ->
            if (row.heldByPlayer) s.downloadsFinishesWhenStopped else s.downloadsMovingIn
        OfflineDownloads.Stage.Failed -> row.failure ?: s.downloadsStopped
    }
    val failed = row.stage == OfflineDownloads.Stage.Failed
    val fraction = if (row.stage == OfflineDownloads.Stage.Moving) row.moveFraction ?: 0f else row.fraction ?: 0f
    val pause = { OfflineDownloads.pause(state.downloads, row.fileId) }
    val resume = { OfflineDownloads.resume(state.downloads, row.fileId) }
    val scope = rememberCoroutineScope()
    // Cancel deletes what it had fetched too: a part file left behind is space nothing names.
    val cancel: () -> Unit = { scope.launch { dropUnfinished(state, row.fileId) } }
    // Refused for room: Free up space first, since Try again finds the same full drive. Refused
    // because the chat forbids keeping a copy: no Try again at all, as on the phone.
    val noSpace = failed && onFreeUp != null && com.tmplayer.data.isNoSpaceFailure(row.failure)
    val savable = row.failure != com.tmplayer.data.ContentProtection.NOT_SAVABLE
    val menu = buildList {
        when {
            row.stage == OfflineDownloads.Stage.Moving -> Unit
            row.busy -> add(RowMenuEntry(s.playerPause, TmIcons.Pause, action = pause))
            failed -> {
                if (noSpace) add(RowMenuEntry(s.downloadsFreeUpSpace, TmIcons.Delete, action = onFreeUp!!))
                if (savable) add(RowMenuEntry(s.commonTryAgain, TmIcons.Refresh, action = resume))
            }
            else -> add(RowMenuEntry(s.downloadsResume, TmIcons.Play, action = resume))
        }
        add(RowMenuEntry(s.commonCancel, TmIcons.Close, danger = true, action = cancel))
    }
    val name = com.tmplayer.ui.browse.rememberVideoName(remember(row.request) { row.request.item() })
    VideoRow(onClick = null, menu = menu) {
        Thumb(row.request.item().copy(title = correctedTitle(row.request.chatId, row.request.messageId, row.title)))
        Words(
            name,
            status,
            detailColor = if (failed) Tone.danger else Tone.muted,
        ) {
            if (!failed) {
                LinearProgressIndicator(
                    progress = { fraction },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = Tone.surfaceHigh,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape),
                )
            }
        }
        when {
            row.stage == OfflineDownloads.Stage.Moving -> Unit
            row.busy -> TipIconButton(TmIcons.Pause, s.playerPause, pause)
            failed -> {
                if (noSpace) TipIconButton(TmIcons.Delete, s.downloadsFreeUpSpace, onFreeUp!!)
                if (savable) TipIconButton(TmIcons.Refresh, s.commonTryAgain, resume)
            }
            else -> TipIconButton(TmIcons.Play, s.downloadsResume, resume)
        }
        TipIconButton(TmIcons.Close, if (failed) s.commonDismiss else s.commonCancel, cancel)
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
    /** Something on the page is picked: a click then picks rather than plays, and every tick shows. */
    picking: Boolean,
    onPick: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onShowInFolder: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onSaveToFolder: () -> Unit,
) {
    val s = LocalStrings.current
    val missing = fileState == DownloadIndex.FileState.Missing ||
        (fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Missing)
    val partial = fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Partial
    val playable = !missing && !partial
    val detail = buildString {
        append(MediaMapper.formatSize(record.sizeBytes))
        if (record.durationSec > 0) append("  ·  ").append(MediaMapper.formatDuration(record.durationSec))
        if (record.chatTitle.isNotBlank()) append("  ·  ").append(record.chatTitle)
        when {
            missing -> append("  ·  ").append(s.downloadsFileMissing)
            partial -> append("  ·  ").append(s.downloadsPartDownloaded)
            fileState == DownloadIndex.FileState.Legacy && legacy == LocalFileAvailability.Complete -> append("  ·  ").append(s.downloadsMovingSoon)
        }
    }
    val present = fileState == DownloadIndex.FileState.Present
    val menu = buildList {
        if (!picked) add(RowMenuEntry(s.downloadsSelect, TmIcons.Checklist) { onPick(true) })
        when {
            missing -> add(RowMenuEntry(s.commonRemove, TmIcons.Delete, danger = true, action = onRemove))
            partial -> add(RowMenuEntry(s.downloadsResume, TmIcons.Download, action = onResume))
            else -> {
                add(RowMenuEntry(s.navWatch, TmIcons.Play, action = onPlay))
                if (present) add(RowMenuEntry(s.commonOpenElsewhere, TmIcons.OpenExternal, action = onOpenElsewhere))
                add(RowMenuEntry(s.downloadsShowInFolder, TmIcons.Folder, action = onShowInFolder))
                add(RowMenuEntry(s.downloadsSaveToFolder, TmIcons.FolderOpen, action = onSaveToFolder))
                add(RowMenuEntry(s.downloadsDeleteDownload, TmIcons.Delete, danger = true, action = onRemove))
            }
        }
    }
    val click = when {
        picking -> ({ onPick(!picked) })
        playable -> onPlay
        else -> null
    }
    // The clean name the rest of the app uses, with the file's own name on a quieter line under it.
    val item = remember(record) { record.toMediaItem() }
    val name = com.tmplayer.ui.browse.rememberVideoName(item)
    val fileName = remember(item, name) { com.tmplayer.ui.browse.fileNameLine(item, name) }
    // A whole download plays on a click and keeps the rest behind More (and a right click): four
    // icon buttons on every row were most of the page's clutter. A row with one thing to do (gone
    // from the folder, or part downloaded) keeps that one word.
    VideoRow(onClick = click, menu = menu, more = playable) { lit ->
        Thumb(
            item.copy(title = correctedTitle(record.chatId, record.messageId, record.title)),
            Pick(picked, shown = lit || picking, onPick = onPick),
        )
        Words(
            name,
            detail,
            detailColor = if (missing) Tone.danger else Tone.muted,
        ) { fileName?.let { FileNameLine(it) } }
        when {
            missing -> TextButton(onClick = onRemove) { Text(s.commonRemove) }
            partial -> TextButton(onClick = onResume) { Text(s.downloadsResume) }
            else -> Unit
        }
    }
}

@Composable
private fun UnlistedRow(file: File, onKeep: () -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    val menu = listOf(
        RowMenuEntry(s.downloadsKeep, TmIcons.Check, action = onKeep),
        RowMenuEntry(s.commonDelete, TmIcons.Delete, danger = true, action = onDelete),
    )
    VideoRow(onClick = null, menu = menu) {
        Words(file.name, s.downloadsUnlistedDetail(MediaMapper.formatSize(file.length())))
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
) {
    val s = LocalStrings.current
    val size = bytes?.takeIf { it > 0 }?.let(MediaMapper::formatSize) ?: MediaMapper.formatSize(record.sizeBytes)
    // Watch and Save to Downloads, the two things only this list does. Clearing a recently played
    // video is Free up space's review, with the rest of what playing left behind.
    val menu = buildList {
        add(RowMenuEntry(s.navWatch, TmIcons.Play, action = onPlay))
        if (!queued) add(RowMenuEntry(s.downloadsSave, TmIcons.Download, action = onSave))
    }
    val item = remember(record) { record.toMediaItem() }
    val name = com.tmplayer.ui.browse.rememberVideoName(item)
    val fileName = remember(item, name) { com.tmplayer.ui.browse.fileNameLine(item, name) }
    VideoRow(onClick = onPlay, menu = menu) {
        Thumb(item.copy(title = correctedTitle(record.chatId, record.messageId, record.title)))
        Words(
            name,
            listOf(size, record.chatTitle).filter { it.isNotBlank() }.joinToString("  ·  "),
        ) { fileName?.let { FileNameLine(it) } }
        if (queued) {
            Text(s.downloadsSaving, style = MaterialTheme.typography.bodySmall, color = Tone.muted, modifier = Modifier.padding(end = 12.dp))
        } else {
            TextButton(onClick = onSave) {
                Icon(TmIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.downloadsSave, maxLines = 1)
            }
        }
    }
}

/**
 * Where every row's words start inside the list, the settings rows' own figure, so the remove after
 * watching switch, the section names and the rows under them all begin on one line.
 */
private val ROW_INSET = SETTING_INSET

/** A row's poster: the width of History's and a chat's row posters on the desktop. */
private val THUMB_WIDTH = 40.dp

/** The widest the list grows: past it a title and its buttons drift too far apart to read as one row. */
private val LIST_MAX = 920.dp

/**
 * The widest the storage card at the top grows: its ring, two lines and one button read as one
 * thing only while they stay together, not spread across a 920 dp list.
 */
private val OVERVIEW_MAX = 560.dp

private val ON_MAC = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
