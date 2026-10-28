package com.tmplayer.ui.downloads

import com.tmplayer.ui.components.keepTopInSight
import androidx.compose.foundation.BorderStroke
import com.tmplayer.ui.theme.Danger
import androidx.tv.material3.Text as TvText
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.tmplayer.ui.browse.BarOverflow
import com.tmplayer.ui.browse.LocalTouchChrome
import com.tmplayer.ui.browse.TouchChipStrip
import com.tmplayer.ui.browse.TouchFilterChip
import com.tmplayer.ui.browse.TouchPage
import com.tmplayer.ui.browse.TouchPageSpec
import com.tmplayer.ui.browse.VideoPosterThumb
import com.tmplayer.ui.components.FloatingBackButton
import com.tmplayer.ui.components.FloatingBarIcon
import com.tmplayer.ui.components.FloatingChrome
import com.tmplayer.ui.components.FloatingPageTitle
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.StickyActionBar
import com.tmplayer.ui.components.rememberCompactChrome
import com.tmplayer.ui.components.usesFloatingChrome
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LegacyDownloads
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.FreeUpSpace
import com.tmplayer.data.FolderCopy
import com.tmplayer.data.FolderExport
import com.tmplayer.ui.components.FolderCopyConfirmDialog
import com.tmplayer.ui.components.FolderCopyProgressDialog
import com.tmplayer.ui.components.FolderCopyResultDialog
import com.tmplayer.data.StorageChart
import com.tmplayer.data.StorageSplit
import com.tmplayer.data.FreeUpReview
import com.tmplayer.ui.components.FreeUpReviewDialog
import com.tmplayer.ui.components.StorageOverview
import com.tmplayer.data.Td
import com.tmplayer.data.cancel
import com.tmplayer.data.pause
import com.tmplayer.data.pauseAll
import com.tmplayer.data.resume
import com.tmplayer.data.resumeAll
import com.tmplayer.data.start
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One video on this device, as the screen knows it: what names it, and what it holds on disk.
 *
 * [bytes] is what is actually on the disk rather than the size the message advertised, so a video
 * that stopped half way says how much room it is really taking and not how much it wanted.
 * [totalBytes] is what it wanted, kept beside it so a part-loaded row can say "710 MB of 1.4 GB"
 * instead of leaving the viewer to wonder why the figures on this screen do not add up.
 *
 * Downloads and cached videos are both rows, in different tabs, and [cached] says which: a
 * download is in the Downloads folder until the viewer deletes it, and a cached video is what
 * playing left behind, which the next play may take. [state] is where a download's file stands
 * against the index; [fileId] is the TDLib id resolved against this session, for the rows TDLib
 * still holds.
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
    val state: LocalDownloads.FileState = LocalDownloads.FileState.Present,
    val cached: Boolean = false,
    val fileId: Int = record.fileId,
) {
    /** Part loaded and not being fetched by anything: bytes sitting there for no one. */
    val partial: Boolean get() = !complete && bytes > 0

    /** Recorded as a download, with nothing left of it on the disk. */
    val missing: Boolean get() = !cached && state == LocalDownloads.FileState.Missing
}

/**
 * Everything this device has downloaded or is waiting for, what it costs, and the way to be rid
 * of it.
 *
 * Both form factors keep this list. The one-video-at-a-time arrangement on a television belongs
 * to the watch cache, which playing fills and the next play replaces; a download the viewer asked
 * for by name is kept on a TV exactly as it is on a phone, until it is deleted here. Keeping every
 * download until told otherwise is only a fair deal if the viewer can see them, so a phone reaches
 * this screen from the drawer and a television from the rail, and every control on it has to be
 * findable with a D-pad as well as a thumb.
 *
 * The page reads top to bottom (user, 2026-10-22): what TMPlayer uses and what is free, with the
 * one Free up space, whose review is the only place anything here is cleared in bulk; what is
 * coming down, while anything is; then the downloads with their order and Select. The settings
 * that used to sit among them (Remove after watching) are in Settings' Storage.
 *
 * Every row is a card: a card has an edge, so it is obvious where one video stops and the next
 * begins. On a phone a download's card plays on a tap and keeps the rest behind More; on a
 * television its buttons stay underneath it with their names written on them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onPlay: (ResumeRecord) -> Unit,
    onBack: () -> Unit,
    /** Opens on the cached videos rather than the downloads, for Settings' Cached videos row. */
    openOnCached: Boolean = false,
    /**
     * Opens with Free up space's review already up: for "Not enough space", and for Settings'
     * Storage, whose Free up space is this page's.
     */
    openFreeUp: Boolean = false,
    /**
     * A video was refused for room ("Not enough space"), so the device counts as short and the
     * review leads with Low storage mode while it is off. False when Settings asked for the review.
     */
    askedForRoom: Boolean = openFreeUp,
    /** The way on from an empty page: the chats, where every video has its Download. */
    onBrowse: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val toast = rememberToast()
    // The kids lock (CP57) leaves out the chats it does not allow here too: a download from one
    // of them would otherwise be a way round it.
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
    val allHistory by settings.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val history = remember(allHistory, allowedChats) { com.tmplayer.data.KidsLock.records(allHistory, allowedChats) }
    // The page's order, remembered once for the page: newest, by name, or the largest first.
    val downloadsSort by settings.downloadsSort.collectAsStateWithLifecycle(initialValue = com.tmplayer.data.DownloadShelf.Sort.Newest)
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
    val allCached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val cached = remember(allCached, allowedChats) { com.tmplayer.data.KidsLock.records(allCached, allowedChats) }

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var cachedRows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    // Empty until the first measurement lands, never read during composition: reading the disk is
    // a blocking statvfs().
    var disk by remember { mutableStateOf(DiskInfo.EMPTY) }
    // The same measurement the Settings card draws, so the two panels cannot disagree about a
    // disk they are both looking at.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
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
    var tab by rememberSaveable { mutableStateOf(if (openOnCached) CACHED else COMPLETED) }
    var landedOnATab by rememberSaveable { mutableStateOf(openOnCached) }
    LaunchedEffect(active.isNotEmpty()) {
        if (landedOnATab) return@LaunchedEffect
        if (active.isNotEmpty()) tab = ONGOING
        landedOnATab = true
    }
    var confirmingDeleteMany by remember { mutableStateOf(false) }

    // A phone and a tablet draw one page: what is downloading (only while something is), what is
    // downloaded, grouped by show, and Recently played folded away at the foot. The television
    // keeps its three tabs (see [TvDownloads]): a remote steps through one short list at a time
    // better than down one long page, and its tabs are the first thing it lands on.
    val onePage = isTouch()
    // Settings' Recently played row opens the page with that section unfolded and in view.
    var cachedOpen by rememberSaveable { mutableStateOf(openOnCached) }
    var freeingUp by remember { mutableStateOf(false) }
    // A Free up space run under way, and the line it left: what it freed, and what it had to leave.
    var freeUpBusy by remember { mutableStateOf(false) }
    var freeUpResult by remember { mutableStateOf<String?>(null) }
    // Turned on from the review: the line there then says it is on rather than vanishing.
    var lowStorageTurned by remember { mutableStateOf(false) }
    // Deletes so far, for the television's page to put the remote back on its tab after each.
    var deleted by remember { mutableStateOf(0) }
    // The row the remote goes to once the row it was on has left the list (deleted, cancelled):
    // the next one down, else the one above, and the tab only when none is left. Asked again with
    // each removal ([rowFocusAsked]), so the same row can be the answer twice running.
    var rowFocusKey by remember { mutableStateOf<String?>(null) }
    var rowFocusAsked by remember { mutableStateOf(0) }
    val rowFocus = remember { FocusRequester() }
    val remote = !isTouch()
    // A row of the list, wearing the requester when it is the one the remote goes to next.
    val focusTarget: @Composable (String, @Composable () -> Unit) -> Unit = { key, card ->
        Box(if (remote && key == rowFocusKey) Modifier.focusRequester(rowFocus) else Modifier) { card() }
    }

    /**
     * Once [gone] has been taken out (the list read again), the remote goes to the row after it
     * in [before], the order the list showed it in, or the one before it; to the tab when none
     * is left. A row that is still there (its file would not delete) keeps the remote itself.
     */
    fun landAfterRemoving(before: List<String>, gone: String, now: Set<String>) {
        if (!remote) return
        val next = rowAfterRemoving(before, gone, now)
        if (next == null) {
            deleted++
        } else {
            rowFocusKey = next
            rowFocusAsked++
        }
    }
    LaunchedEffect(rowFocusAsked) {
        if (rowFocusAsked == 0 || rowFocusKey == null) return@LaunchedEffect
        // The row is composed a frame or two after the list changes; a request before then is lost.
        repeat(ROW_FOCUS_TRIES) {
            withFrameNanos { }
            if (runCatching { rowFocus.requestFocus() }.getOrDefault(false)) {
                rowFocusKey = null
                return@LaunchedEffect
            }
        }
        // Not on screen after all: the tab, as before.
        rowFocusKey = null
        deleted++
    }
    // Initially on, so the line that offers it does not flash in before the setting is read.
    val lowStorageOn by settings.lowStorageMode.collectAsStateWithLifecycle(initialValue = true)
    // Free up space opened by "Not enough space" opens once, when the figures are in.
    var freeUpOpened by rememberSaveable { mutableStateOf(false) }
    // The downloads that stopped part way or are still arriving, with what each holds here: the
    // review's Unfinished group, where the ones still coming down are listed as kept.
    val unfinished = remember(active) {
        active.filter { it.downloadedBytes > 0 }
    }
    val listState = rememberLazyListState()
    // The list's own height, which an empty section's message takes a share of as its least.
    val viewportPx by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val viewport = with(androidx.compose.ui.platform.LocalDensity.current) { viewportPx.toDp() }
    // Where the Recently played heading sits in the list, written as the list is laid out, so
    // the page can scroll to it when it opens on that section.
    val cachedHeadingAt = remember { intArrayOf(-1) }

    fun leavePicking() {
        picking = false
        picked = emptySet()
    }

    // Save to folder. Its steps live in FolderExport, not here, so a rotation or the app going to
    // the background loses none of them: the question, the progress, the result. The screen only
    // draws them, and counts itself as watching so the end is not also said in the shade.
    val folder by FolderExport.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) {
        FolderExport.watchers.incrementAndGet()
        onDispose { FolderExport.watchers.decrementAndGet() }
    }
    var removeAfterSaving by rememberSaveable { mutableStateOf(false) }
    // The folder the picker came back with, handled further down once the rows are counted.
    var pickedTree by remember { mutableStateOf<android.net.Uri?>(null) }
    // Offered where there is a folder picker that works: every phone and tablet. A television's
    // "picker" is a stub that answers Cancel at once (Android TV ships no DocumentsUI), so there
    // the button says why it cannot instead.
    val touch = isTouch()
    val canSaveToFolder = remember(touch) {
        touch && android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
            .resolveActivity(context.packageManager) != null
    }
    // Save to gallery: Movies/TMPlayer through MediaStore, Android 10 and later, on a phone or
    // tablet (a television has no gallery anyone looks at).
    val canSaveToGallery = touch && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    var explainingNoFolder by remember { mutableStateOf(false) }
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree == null) {
            FolderExport.pendingKeys = null
            FolderExport.dropPreparing()
        } else {
            pickedTree = tree
        }
    }

    // Only the rows that are still on the list. A selection that outlived its videos would offer
    // to delete or share things that are no longer there.
    val chosen = remember(picked, rows) { rows.filter { it.key in picked } }
    // The selection's running total, and a warning when it is more than the room left in the
    // folder saved to last (read off the main thread: it is a statfs()).
    val chosenBytes = remember(chosen) { chosen.sumOf { it.bytes } }
    var lastFolderFree by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(picking, folder.outcome) {
        if (picking) lastFolderFree = withContext(Dispatchers.IO) { FolderExport.lastFolderFree() }
    }
    val chosenSize = if (chosen.isEmpty()) null else Translator.messages.formatter.bytes(chosenBytes)
    val chosenWarning = lastFolderFree?.takeIf { chosen.isNotEmpty() && chosenBytes > it.second }?.let { (name, free) ->
        L.downloadsSaveToFolderOverLast(free = Translator.messages.formatter.bytes(free), folder = name)
    }

    // Every row, because every row is a download.
    val shown = rows

    // A selection belongs to the tab it was made on. Carried across, its bar would sit under a
    // list of downloads it says nothing about.
    LaunchedEffect(tab) { if (!onePage && tab != COMPLETED) leavePicking() }

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
        val row = DownloadRow(
            key = "kept_${record.chatId}_${record.messageId}",
            title = com.tmplayer.data.VideoCorrections.titleFor(record.chatId, record.messageId, record.title),
            bytes = 0,
            totalBytes = record.sizeBytes,
            complete = false,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
        )
        // The index first: a download with a path is a file in the Downloads folder, measured on
        // the disk, and TDLib knows nothing about it any more.
        when (val state = LocalDownloads.stateOf(record)) {
            LocalDownloads.FileState.Present -> {
                val bytes = java.io.File(record.localPath!!).length()
                return row.copy(bytes = bytes, complete = true, state = state)
            }
            LocalDownloads.FileState.Missing -> return row.copy(state = state)
            LocalDownloads.FileState.Legacy -> Unit
        }
        // From before downloads had a folder: still in TDLib's cache, asked of TDLib. Against
        // this session's id, not the one saved with the row, which stops resolving after a restart.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        // Nothing left of it: said, not hidden, so the viewer learns something took it.
        if (bytes <= 0 || availability == LocalFileAvailability.Missing) {
            return row.copy(state = LocalDownloads.FileState.Missing, fileId = fileId)
        }
        return row.copy(
            bytes = bytes,
            complete = availability == LocalFileAvailability.Complete,
            state = LocalDownloads.FileState.Legacy,
            fileId = fileId,
        )
    }

    /**
     * A video playing left behind, measured through TDLib, or null when nothing of it is left.
     * One that is also a download is the download's, and is listed there instead.
     */
    suspend fun measureCached(record: ResumeRecord, downloaded: Set<Pair<Long, Long>>): DownloadRow? {
        if ((record.chatId to record.messageId) in downloaded) return null
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (bytes <= 0) return null
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        return DownloadRow(
            key = "cached_${record.chatId}_${record.messageId}",
            title = com.tmplayer.data.VideoCorrections.titleFor(record.chatId, record.messageId, record.title),
            bytes = bytes,
            totalBytes = record.sizeBytes,
            complete = availability == LocalFileAvailability.Complete,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
            cached = true,
            fileId = fileId,
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
            val cached: List<DownloadRow>,
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
            val downloadedMessages = known.map { it.chatId to it.messageId }.toSet()
            val cachedNow = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .map { async { measureCached(it, downloadedMessages) } }
                .awaitAll()
                .filterNotNull()

            Measured(keptRows, cachedNow, measuredSplit, readDisk)
        }

        rows = measured.rows
        cachedRows = measured.cached
        split = measured.split
        disk = measured.disk
        counted = true
    }

    // One trigger, not three. These keys all change together when a download finishes, and three
    // separate effects would interleave three passes of the audit above, each writing `rows` when
    // it finished, so the slowest could land last and put stale figures back on screen.
    LaunchedEffect(history, cached.map { it.fileId }, active.size) { refresh(history) }
    LaunchedEffect(counted) {
        if (!counted || !openFreeUp || freeUpOpened) return@LaunchedEffect
        freeUpOpened = true
        freeingUp = true
    }
    fun share(ticked: List<DownloadRow>) {
        scope.launch {
            // The records never knew whether their chat restricts saving content, and a channel can
            // turn it on after the fact, so Telegram is asked at the moment of handing them on.
            val these = ticked.filter { Td.maySave(it.record.chatId, it.record.messageId) }
            if (these.isEmpty()) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            val files = these.mapNotNull { row ->
                val record = row.record
                // The download's own file when it has one; otherwise TDLib's, through the id this
                // session knows it by rather than the one saved with the row.
                val path = LocalDownloads.shareablePath(settings, record.chatId, record.messageId, row.fileId)
                path?.let { it to record.title }
            }
            val intent = ShareMedia.intentFor(context, files)
            if (intent == null) {
                // All of them part downloaded or gone. Saying so beats an empty share sheet,
                // which reads as the button being broken.
                toast(L.downloadsNothingToShare)
                return@launch
            }
            runCatching { context.startActivity(intent) }
                .onFailure { toast(L.downloadsNoShareApp) }
            leavePicking()
        }
    }

    /**
     * Removes one video, whichever of the three kinds it is.
     *
     * The record is only forgotten once the file has actually gone. Forgetting it regardless would
     * leave bytes on the disk with nothing on this screen pointing at them, which is the one state
     * this screen exists to prevent.
     *
     * @return whether it went, so a file that would not delete is said rather than left on the
     *   list without a word.
     */
    suspend fun removeOne(row: DownloadRow): Boolean = withContext(Dispatchers.IO) {
        val record = row.record
        // A download in the folder is deleted there, through the index; TDLib does not have it.
        if (!row.cached && record.localPath != null) {
            return@withContext LocalDownloads.delete(settings, record)
        }
        // The same resolution [measure] reads with: the saved id stops answering after a restart,
        // and a delete through it removes nothing while the row still leaves the list.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        runCatching { Td.deleteFile(fileId) }
        val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (left > 0) return@withContext false
        if (row.cached) {
            settings.forgetCachedVideo(record.chatId, record.messageId)
        } else {
            settings.forgetDownload(record.chatId, record.messageId)
        }
        true
    }

    /**
     * Puts a video into the download queue: a cached one, which moves into Downloads straight away,
     * or a part downloaded one, which finishes and then moves.
     */
    fun saveToDownloads(row: DownloadRow) {
        scope.launch {
            // Asked here for the same reason [share] asks: a cached video may come from a chat that
            // lets it be watched and nothing more, and the cache record cannot say so.
            if (!Td.maySave(row.record.chatId, row.record.messageId)) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            OfflineDownloads.start(context, row.record.toMediaItem().copy(fileId = row.fileId), row.chatTitle)
            // Not said: the row moves to Downloading, or its bar starts moving again.
            if (row.cached) {
                tab = ONGOING
                // The page's Downloading section is at the top: show it arriving there.
                if (onePage) listState.animateScrollToItem(0)
            }
        }
    }

    /**
     * Stops a download and deletes what it had fetched. Cancelling alone leaves the part file on
     * the disk with nothing naming it, which is space the viewer can no longer find. A file that
     * is also the recently played video is left to that, since the same bytes are its.
     */
    suspend fun dropUnfinished(fileId: Int) {
        OfflineDownloads.cancel(context, fileId)
        withContext(Dispatchers.IO) {
            runCatching { Td.cancelDownload(fileId) }
            if (cachedRows.none { it.fileId == fileId }) runCatching { Td.deleteFile(fileId) }
        }
    }

    // Free up space's review, from the same measurement the page draws: the groups in the order
    // the sheet lists them, each item keyed as its row is. A download still coming down, and a
    // file open in the player, are listed as kept rather than left out, so the figures add up.
    val reviewSections = remember(rows, cachedRows, split, unfinished) {
        val coming = unfinished.map { it.fileId }.toSet()
        fun playing(fileId: Int) = if (com.tmplayer.data.WatchCache.isPlaying(fileId)) FreeUpReview.Lock.Playing else null
        val recent = cachedRows.filter { it.fileId !in coming && it.bytes > 0 }
        val kept = rows.filter { !it.missing && it.bytes > 0 }.sortedByDescending { it.bytes }
        listOf(
            FreeUpReview.Section(
                FreeUpReview.Group.Unfinished,
                unfinished.sumOf { it.downloadedBytes },
                items = unfinished.map { p ->
                    val busy = p.stage == OfflineDownloads.Stage.Running || p.stage == OfflineDownloads.Stage.Moving
                    FreeUpReview.Item(
                        key = p.fileId.toString(),
                        title = com.tmplayer.data.VideoCorrections.titleFor(p.request.chatId, p.request.messageId, p.title),
                        bytes = p.downloadedBytes,
                        lock = if (busy) FreeUpReview.Lock.Downloading else null,
                    )
                },
            ),
            FreeUpReview.Section(
                FreeUpReview.Group.Recent,
                recent.sumOf { it.bytes },
                count = recent.size,
                items = recent.map { FreeUpReview.Item(it.key, it.title, it.bytes, playing(it.fileId)) },
            ),
            FreeUpReview.Section(
                FreeUpReview.Group.Unmatched,
                split.strayBytes,
                items = split.strays.map { FreeUpReview.Item(it.path, it.title, it.bytes) },
            ),
            FreeUpReview.Section(FreeUpReview.Group.Pictures, split.pictureBytes),
            FreeUpReview.Section(
                FreeUpReview.Group.Downloads,
                kept.sumOf { it.bytes },
                items = kept.map { FreeUpReview.Item(it.key, it.title, it.bytes, playing(it.fileId)) },
            ),
        )
    }

    /**
     * Free up space: what the review has ticked, group by group, each through the one way this
     * page removes that kind of thing. A download that started again, or a file the player opened,
     * since the sheet was drawn is left and counted as left. Once started it runs to the end even
     * if the page goes, so a file is never half handled; the line it leaves says what it freed.
     */
    fun freeUp(picks: FreeUpReview.Picks) {
        if (freeUpBusy) return
        val sections = reviewSections
        val keptRows = rows
        val recentRows = cachedRows
        val strays = split.strays
        freeingUp = false
        freeUpBusy = true
        freeUpResult = null
        scope.launch {
            val outcome = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                var freed = 0L
                var skipped = 0
                for (section in sections) {
                    when (section.group) {
                        FreeUpReview.Group.Unfinished -> FreeUpReview.picked(picks, section).forEach { item ->
                            val now = OfflineDownloads.active.value.values.firstOrNull { it.fileId.toString() == item.key }
                            when {
                                now == null -> Unit
                                now.stage == OfflineDownloads.Stage.Running || now.stage == OfflineDownloads.Stage.Moving -> skipped++
                                else -> {
                                    dropUnfinished(now.fileId)
                                    freed += item.bytes
                                }
                            }
                        }
                        FreeUpReview.Group.Recent, FreeUpReview.Group.Downloads -> {
                            val pool = if (section.group == FreeUpReview.Group.Recent) recentRows else keptRows
                            FreeUpReview.picked(picks, section).forEach { item ->
                                val row = pool.firstOrNull { it.key == item.key } ?: return@forEach
                                when {
                                    com.tmplayer.data.WatchCache.isPlaying(row.fileId) -> skipped++
                                    runCatching { removeOne(row) }.getOrDefault(false) -> freed += item.bytes
                                    else -> skipped++
                                }
                            }
                        }
                        FreeUpReview.Group.Unmatched -> FreeUpReview.picked(picks, section).forEach { item ->
                            val stray = strays.firstOrNull { it.path == item.key } ?: return@forEach
                            if (runCatching { com.tmplayer.data.WatchCache.forget(context, stray) }.getOrDefault(false)) freed += item.bytes else skipped++
                        }
                        FreeUpReview.Group.Pictures -> if (FreeUpReview.wholePicked(picks, section)) {
                            if (runCatching { Td.clearPicturesAndPreviews() }.isSuccess) freed += section.bytes
                            runCatching { com.tmplayer.online.OnlineMetadata.current?.purgeImages() }
                        }
                    }
                }
                FreeUpReview.Outcome(freed, skipped)
            }
            refresh(history)
            freeUpBusy = false
            freeUpResult = com.tmplayer.ui.components.freeUpResult(outcome)
            // The button the remote was on may be off now (nothing left to free): back to the tab.
            deleted++
        }
    }

    fun delete(row: DownloadRow, done: () -> Unit = {}) {
        scope.launch {
            if (!removeOne(row)) toast(L.downloadsDeleteFailed(file = row.title))
            refresh(history)
            done()
        }
    }

    /** A row whose file is gone: there is nothing to confirm deleting, only a record to drop. */
    /**
     * Download again on a row whose file went (HX21): the message as Telegram has it now, through
     * the same plan as any Download (room first, saving allowed). A message gone for good says so
     * and keeps the row for Remove; no answer says Telegram could not be reached. The row stays
     * until the new file lands, which writes the record again.
     */
    fun downloadAgain(row: DownloadRow) {
        scope.launch {
            when (val lookup = Td.lookupMedia(row.record.chatId, row.record.messageId)) {
                is com.tmplayer.data.MessageLookup.Found -> {
                    if (!lookup.item.canBeSaved) {
                        toast(ContentProtection.NOT_SAVABLE)
                        return@launch
                    }
                    com.tmplayer.ui.browse.queueDownloads(context, settings, listOf(lookup.item)) { row.chatTitle }?.let(toast)
                }
                is com.tmplayer.data.MessageLookup.Gone -> toast(com.tmplayer.data.GoneVideos.openMessage(lookup.reason))
                com.tmplayer.data.MessageLookup.Unreachable -> toast(L.errorsOffline)
            }
        }
    }

    fun removeMissing(row: DownloadRow, done: () -> Unit = {}) {
        scope.launch {
            withContext(Dispatchers.IO) { settings.forgetDownload(row.record.chatId, row.record.messageId) }
            // The row leaving is the answer.
            refresh(history)
            done()
        }
    }

    /**
     * Save to folder (one video or a selection): the system's folder picker, then the
     * confirmation with the sizes and the room there (FolderExport.prepare), then the copy with its
     * progress and Cancel, then a toast for a whole run or a dialog that names everything skipped,
     * failed or stopped. The downloads stay where they are unless the confirmation's "Remove from
     * TMPlayer's downloads after saving" was ticked.
     */
    fun saveToFolder(ticked: List<DownloadRow>) {
        if (ticked.isEmpty()) return
        if (!canSaveToFolder) {
            explainingNoFolder = true
            return
        }
        if (FolderExport.busy) {
            toast(L.downloadsSaveToFolderBusy)
            return
        }
        scope.launch {
            // One video from a chat that forbids saving is said at once, before any picker opens.
            if (ticked.size == 1 && !Td.maySave(ticked[0].record.chatId, ticked[0].record.messageId)) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            FolderExport.pendingKeys = ticked.map { it.key }
            runCatching { folderPicker.launch(null) }.onFailure {
                FolderExport.pendingKeys = null
                explainingNoFolder = true
            }
        }
    }

    /**
     * Save to gallery (one video or a selection): as Save to folder without the picker, so the
     * confirmation with the sizes and the room left comes up at once, then the copy with its
     * progress here and in the notification, then the same toast or summary.
     */
    fun saveToGallery(ticked: List<DownloadRow>) {
        if (ticked.isEmpty() || !canSaveToGallery) return
        if (FolderExport.busy) {
            toast(L.downloadsSaveBusy)
            return
        }
        val candidates = ticked.map { row ->
            FolderExport.Candidate(row.key, row.record, row.fileId, row.title, whole = !row.cached && !row.missing && row.complete)
        }
        com.tmplayer.platform.Background.scope.launch {
            // One video from a chat that forbids saving is said at once, with no question first.
            if (ticked.size == 1 && !Td.maySave(ticked[0].record.chatId, ticked[0].record.messageId)) {
                withContext(Dispatchers.Main) { toast(ContentProtection.NOT_SAVABLE) }
                return@launch
            }
            FolderExport.prepare(context.applicationContext, null, candidates, settings)
        }
    }

    // The picker's answer, once the rows it was opened for are on the list again (after a
    // rotation they are measured afresh). A row deleted meanwhile is simply not in the plan.
    LaunchedEffect(pickedTree, counted) {
        val tree = pickedTree ?: return@LaunchedEffect
        if (!counted) return@LaunchedEffect
        pickedTree = null
        val keys = FolderExport.pendingKeys.orEmpty().toSet()
        FolderExport.pendingKeys = null
        val chosenNow = rows.filter { it.key in keys }
        if (chosenNow.isEmpty()) return@LaunchedEffect
        val candidates = chosenNow.map { row ->
            FolderExport.Candidate(row.key, row.record, row.fileId, row.title, whole = !row.cached && !row.missing && row.complete)
        }
        // Off the screen's own scope: the list is measured again on the way back from the picker,
        // which restarts this effect, and a preparation tied to it was cancelled half way and
        // reported as a lost folder (POCO M2 Pro, 2026-10-22). Its answer arrives through
        // FolderExport.state whatever the screen does meanwhile.
        com.tmplayer.platform.Background.scope.launch {
            FolderExport.prepare(context.applicationContext, tree, candidates, settings)
        }
    }

    // The end of a run: a toast when every video went, the dialog otherwise. The list is measured
    // again either way, since a removal after saving changes it.
    LaunchedEffect(folder.outcome) {
        val outcome = folder.outcome ?: return@LaunchedEffect
        if (outcome.copied > 0) leavePicking()
        if (outcome.moved > 0) refresh(history)
        if (outcome.clean) {
            toast(outcome.headline + (if (outcome.moved > 0) ". " + L.downloadsSaveToFolderMoved(count = outcome.moved) else ""))
            FolderExport.consume()
        }
    }

    fun deleteMany(these: List<DownloadRow>) {
        scope.launch {
            val stuck = these.filterNot { removeOne(it) }
            stuck.firstOrNull()?.let { toast(L.downloadsDeleteFailed(file = it.title)) }
            leavePicking()
            refresh(history)
        }
    }

    // The downloads as both lists draw them (the phone's page, the television's Downloaded tab).
    // The episodes of one show sit together under its name, in watching order, and every other
    // video stands on its own, newest first. The rows
    // and every action on them are the tabs' own; only the order and the headings are new.
    val shelf = remember(rows, downloadsSort) {
        com.tmplayer.data.DownloadShelf.arrange(rows, { it.record.toMediaItem() }, { it.key }, { it.record.updatedAt }, sort = downloadsSort)
    }
    // The downloads' rows in the order the list shows them, for where the remote goes after one.
    val keptOrder = remember(shelf) {
        shelf.flatMap { entry ->
            when (entry) {
                is com.tmplayer.data.DownloadShelf.Entry.Single -> listOf(entry.row.key)
                is com.tmplayer.data.DownloadShelf.Entry.Show -> entry.episodes.map { it.key }
            }
        }
    }
    // Read once the removal has run, when the list composed earlier is out of date.
    fun keptNow(): Set<String> = rows.mapTo(HashSet()) { it.key }
    fun activeNow(): Set<String> = OfflineDownloads.active.value.values.mapTo(HashSet()) { "active_${it.fileId}" }
    val activeOrder = remember(active) { active.map { "active_${it.fileId}" } }
    // Cancel takes the row the remote is on out of the queue: the next one down takes it.
    val cancelRow: (OfflineDownloads.Progress) -> Unit = { progress ->
        val before = activeOrder
        scope.launch {
            dropUnfinished(progress.fileId)
            // The download service takes it off the queue a moment after it is asked to.
            kotlinx.coroutines.withTimeoutOrNull(ROW_GONE_WAIT_MS) {
                OfflineDownloads.active.first { queue -> queue.values.none { it.fileId == progress.fileId } }
            }
            landAfterRemoving(before, "active_${progress.fileId}", activeNow())
        }
    }
    // Newest, Name and Largest over the downloads, once there are two to put in order.
    val sortChips: @Composable () -> Unit = {
        DownloadsSortChips(downloadsSort) { picked -> scope.launch { settings.setDownloadsSort(picked) } }
    }
    val keptCard: @Composable (DownloadRow) -> Unit = { row -> focusTarget(row.key) {
        DownloadCard(
            row = row,
            picking = picking,
            checked = row.key in picked,
            onPlay = { if (row.partial) saveToDownloads(row) else onPlay(row.record) },
            onShare = { share(listOf(row)) },
            // A phone's card has room for it as a square beside Delete; a television's card stays
            // Watch, Share and Delete, and its heading's Save to folder says why it cannot.
            onSaveToFolder = if (canSaveToFolder) ({ saveToFolder(listOf(row)) }) else null,
            onSaveToGallery = if (canSaveToGallery) ({ saveToGallery(listOf(row)) }) else null,
            onDownloadAgain = { downloadAgain(row) },
            onDelete = {
                if (row.missing) {
                    val before = keptOrder
                    removeMissing(row) { landAfterRemoving(before, row.key, keptNow()) }
                } else {
                    confirmingDelete = row
                }
            },
            onToggle = { picked = if (row.key in picked) picked - row.key else picked + row.key },
            onHold = {
                picking = true
                picked = picked + row.key
            },
        )
    } }
    // The top of the page: what TMPlayer uses and what is free, and the one Free up space. Every
    // way to clear a part of it (the chart's legend, the cards' Delete on Recently played and the
    // unmatched files, Delete all) is in the review this opens.
    val storageCard: @Composable () -> Unit = {
        StorageSummary(
            figures = split.chart(disk),
            measured = counted && disk.totalBytes > 0,
            nothingToFree = FreeUpReview.nothingToFree(reviewSections),
            busy = freeUpBusy,
            result = freeUpResult,
            onFreeUp = { if (!freeUpBusy) freeingUp = true },
        )
    }
    // What each of the television's tabs lists.
    val tabBody: LazyListScope.() -> Unit = body@{
        if (tab == ONGOING) {
            if (active.isNotEmpty()) {
                item {
                    QueueHeading(
                        title = queueHeading(active),
                        // Only worth offering on a real queue. With one video the card's
                        // own button says the same thing an inch further down the screen.
                        bulk = if (active.size > 1) {
                            if (active.any { it.busy }) L.commonPauseAll else L.commonResumeAll
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
                    focusTarget("active_${progress.fileId}") {
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
                        // The row goes with the remote on it: on to the next one, as after a delete.
                        onCancel = { cancelRow(progress) },
                        onFreeUp = { freeingUp = true },
                    )
                    }
                }
            } else {
                item {
                    // Given a height of its own: BigEmpty fills what it is given, and
                    // inside a LazyColumn that is nothing, so it would collapse to a line.
                    EmptyState(0.7f, viewport) {
                        BigEmpty(
                            L.downloadsEmptyDownloading,
                            icon = TmIcons.Download,
                        )
                    }
                }
            }
            return@body
        }

        if (tab == CACHED) {
            item {
                Text(
                    L.downloadsCachedTabNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 16.dp, bottom = 4.dp),
                )
            }
            // Watch and Save to Downloads, the two things only this list does. Clearing them, and
            // the unmatched files beside them, is Free up space's review on the Downloaded tab.
            items(cachedRows, key = { it.key }) { row ->
                DownloadCard(
                    row = row,
                    picking = false,
                    checked = false,
                    onPlay = { onPlay(row.record) },
                    onShare = { saveToDownloads(row) },
                    onDelete = null,
                    onToggle = {},
                    onHold = null,
                )
            }
            if (counted && cachedRows.isEmpty()) {
                item {
                    EmptyState(0.6f, viewport) {
                        BigEmpty(
                            L.downloadsEmptyCached,
                            icon = TmIcons.Download,
                        )
                    }
                }
            }
            return@body
        }

        // Free up space first, then the list: the remote steps down from the tabs onto the button.
        item(key = "storage") { storageCard() }
        if (rows.size > 1) item(key = "sort") { sortChips() }
        // Grouped by show as the phone's page is, so the two read as one list. The headings take
        // no focus: the remote steps from card to card and each heading scrolls in with its first.
        shelf.forEach { entry ->
            when (entry) {
                is com.tmplayer.data.DownloadShelf.Entry.Single -> item(key = entry.key) { keptCard(entry.row) }
                is com.tmplayer.data.DownloadShelf.Entry.Show -> {
                    item(key = entry.key) {
                        ShowHeading(
                            title = entry.title,
                            detail = listOf(
                                if (entry.pieces) L.gridPartsCount(entry.episodes.size) else L.seriesEpisodesCount(count = entry.episodes.size),
                                Translator.messages.formatter.bytes(entry.episodes.sumOf { it.bytes }),
                            ).joinToString(DOT),
                        )
                    }
                    items(entry.episodes, key = { it.key }) { keptCard(it) }
                }
            }
        }
        if (counted && shown.isEmpty()) {
            item {
                EmptyState(0.6f, viewport) {
                    BigEmpty(
                        L.downloadsEmptyDownloaded(folder = LegacyDownloads.FOLDER),
                        icon = TmIcons.Download,
                        actionLabel = onBrowse?.let { L.browseGoToChats },
                        onAction = onBrowse,
                    )
                }
            }
        }
    }
    val pageBody: LazyListScope.() -> Unit = {
        var at = 0
        item(key = "storage") { storageCard() }
        at++
        // Only while something is on its way: a Downloading heading over nothing is a section to
        // read past on every visit.
        if (active.isNotEmpty()) {
            item(key = "h-active") {
                QueueHeading(
                    title = queueHeading(active),
                    bulk = if (active.size > 1) {
                        if (active.any { it.busy }) L.commonPauseAll else L.commonResumeAll
                    } else {
                        null
                    },
                    onBulk = {
                        if (active.any { it.busy }) OfflineDownloads.pauseAll(context) else OfflineDownloads.resumeAll(context)
                    },
                )
            }
            items(active, key = { "active_${it.fileId}" }) { progress ->
                ActiveDownloadCard(
                    progress = progress,
                    place = queuePlaces[progress.fileId] ?: -1,
                    onPause = { OfflineDownloads.pause(context, progress.fileId) },
                    onResume = { OfflineDownloads.resume(context, progress.fileId) },
                    // The row goes with the remote on it: on to the next one, as after a delete.
                    onCancel = { cancelRow(progress) },
                    onFreeUp = { freeingUp = true },
                )
            }
            at += 1 + active.size
        }
        item(key = "h-done") {
            SectionHeading(if (rows.isEmpty()) L.downloadsDownloaded else L.downloadsTabDownloadedCount(count = rows.size))
        }
        at += 1
        if (rows.size > 1) {
            item(key = "sort") { sortChips() }
            at++
        }
        shelf.forEach { entry ->
            when (entry) {
                is com.tmplayer.data.DownloadShelf.Entry.Single -> {
                    item(key = entry.key) { keptCard(entry.row) }
                    at++
                }
                is com.tmplayer.data.DownloadShelf.Entry.Show -> {
                    item(key = entry.key) {
                        ShowHeading(
                            title = entry.title,
                            detail = listOf(
                                if (entry.pieces) L.gridPartsCount(entry.episodes.size) else L.seriesEpisodesCount(count = entry.episodes.size),
                                Translator.messages.formatter.bytes(entry.episodes.sumOf { it.bytes }),
                            ).joinToString(DOT),
                        )
                    }
                    items(entry.episodes, key = { it.key }) { keptCard(it) }
                    at += 1 + entry.episodes.size
                }
            }
        }
        if (counted && rows.isEmpty()) {
            item(key = "e-done") {
                EmptyState(0.4f, viewport) {
                    BigEmpty(
                        L.downloadsEmptyDownloaded(folder = LegacyDownloads.FOLDER),
                        icon = TmIcons.Download,
                        actionLabel = onBrowse?.let { L.browseGoToChats },
                        onAction = onBrowse,
                    )
                }
            }
            at++
        }
        cachedHeadingAt[0] = at
        item(key = "h-cached") {
            CollapsibleHeading(
                title = if (cachedRows.isEmpty()) L.downloadsCached else L.downloadsTabCachedCount(count = cachedRows.size),
                detail = cachedRows.sumOf { it.bytes }.takeIf { it > 0 }?.let { Translator.messages.formatter.bytes(it) },
                open = cachedOpen,
                onToggle = { cachedOpen = !cachedOpen },
            )
        }
        if (cachedOpen) {
            item(key = "n-cached") {
                Text(
                    if (counted && cachedRows.isEmpty()) L.downloadsEmptyCached else L.downloadsCachedTabNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, bottom = 4.dp),
                )
            }
            // Watch and Save to Downloads; clearing them is Free up space's review at the top.
            items(cachedRows, key = { it.key }) { row ->
                DownloadCard(
                    row = row,
                    picking = false,
                    checked = false,
                    onPlay = { onPlay(row.record) },
                    onShare = { saveToDownloads(row) },
                    onDelete = null,
                    onToggle = {},
                    onHold = null,
                )
            }
        }
    }
    val body = if (onePage) pageBody else tabBody
    // Opened from Settings' Recently played row: that section, unfolded, in view.
    LaunchedEffect(counted) {
        if (!counted || !openOnCached || !onePage) return@LaunchedEffect
        withFrameNanos { }
        if (cachedHeadingAt[0] > 0) runCatching { listState.scrollToItem(cachedHeadingAt[0]) }
    }
    val tabLabel = { which: Int ->
        when (which) {
            ONGOING -> if (active.isEmpty()) L.downloadsTabDownloading else L.downloadsTabDownloadingCount(count = active.size)
            COMPLETED -> if (rows.isEmpty()) L.downloadsTabDownloaded else L.downloadsTabDownloadedCount(count = rows.size)
            else -> if (cachedRows.isEmpty()) L.downloadsTabCached else L.downloadsTabCachedCount(count = cachedRows.size)
        }
    }

    if (usesFloatingChrome()) {
        PhoneDownloads(
            title = L.downloadsTitle,
            tab = tab,
            tabLabel = tabLabel,
            onTab = { tab = it },
            picking = picking,
            chosenCount = chosen.size,
            canSelect = rows.isNotEmpty(),
            allChosen = chosen.size >= shown.size,
            onBack = onBack,
            onSelect = { picking = true },
            onLeavePicking = { leavePicking() },
            onSelectAll = { picked = shown.map { it.key }.toSet() },
            onShareChosen = { share(chosen) },
            onSaveChosen = { saveToFolder(chosen) },
            onSaveChosenToGallery = if (canSaveToGallery) ({ saveToGallery(chosen) }) else null,
            chosenSize = chosenSize,
            chosenWarning = chosenWarning,
            onDeleteChosen = { confirmingDeleteMany = true },
            listState = listState,
            body = body,
        )
    } else if (!isTouch()) {
        TvDownloads(
            tab = tab,
            tabLabel = tabLabel,
            onTab = { tab = it },
            picking = picking,
            chosenCount = chosen.size,
            canSelect = rows.isNotEmpty() && tab == COMPLETED,
            allChosen = chosen.size >= shown.size,
            onBack = onBack,
            onSelect = { picking = true },
            onLeavePicking = { leavePicking() },
            onSelectAll = { picked = shown.map { it.key }.toSet() },
            onShareChosen = { share(chosen) },
            onSaveChosen = { saveToFolder(chosen) },
            chosenSize = chosenSize,
            chosenWarning = chosenWarning,
            onDeleteChosen = { confirmingDeleteMany = true },
            listState = listState,
            refocus = deleted,
            body = body,
        )
    } else {
        Scaffold(
            containerColor = Tone.background,
            // A television crops its outer few percent, and this screen is stock Material laid out to
            // the panel's edge, so on a TV the whole of it moves inside the overscan margin.
            modifier = if (isTouch()) {
                Modifier
            } else {
                Modifier.background(Tone.background).padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (picking) {
                                if (chosen.isEmpty()) L.downloadsSelectVideos else listOfNotNull(L.downloadsSelected(count = chosen.size), chosenSize).joinToString(DOT)
                            } else {
                                L.downloadsTitle
                            },
                        )
                    },
                    navigationIcon = {
                        // While picking, the arrow leaves the selection rather than the screen: that
                        // is what Back does here, and the two must not disagree.
                        val backFocus = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { if (picking) leavePicking() else onBack() },
                            interactionSource = backFocus,
                            modifier = Modifier.tvFocusRing(backFocus, CircleShape),
                        ) {
                            Icon(
                                if (picking) TmIcons.Close else TmIcons.ArrowBack,
                                contentDescription = if (picking) L.downloadsLeaveSelection else L.commonBack,
                            )
                        }
                    },
                    actions = {
                        if (picking) {
                            // Everything the list is showing, and never more than that.
                            TextAction(L.downloadsSelectAll, enabled = chosen.size < shown.size) {
                                picked = shown.map { it.key }.toSet()
                            }
                            return@TopAppBar
                        }
                        // Select, for Share, Save to folder and Delete on several at once. Delete all
                        // went: Select all and Delete, or Free up space's review, say the same.
                        if (rows.isNotEmpty()) {
                            TextAction(L.downloadsSelect) { picking = true }
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
                        PrimaryAction(L.commonShare, TmIcons.Share) { share(chosen) }
                        if (canSaveToFolder) SecondaryAction(L.downloadsSaveToFolder, TmIcons.Folder, { saveToFolder(chosen) })
                        if (canSaveToGallery) SecondaryAction(L.downloadsSaveToGallery, TmIcons.Image, { saveToGallery(chosen) })
                        SecondaryAction(
                            label = L.commonDelete,
                            icon = TmIcons.Delete,
                            onClick = { confirmingDeleteMany = true },
                            danger = true,
                        )
                    }
                }
            },
        ) { padding ->
            // A tablet: the phone's one page, under Material's bar.
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChrome.current),
                        content = body,
                    )
                    ScrollToTop(listState)
                    FastScroller(listState)
                }
            }
        }
    }

    // The app's one confirmation (TvConfirm): Material's dialog on a phone, the remote's panel
    // with Cancel focused on a television, where a stock dialog drew in a look of its own.
    // Save to folder's dialogs, drawn from FolderExport's state so they come back after a rotation.
    folder.offer?.let { offer ->
        FolderCopyConfirmDialog(
            plan = offer.plan,
            remove = removeAfterSaving,
            onRemove = { removeAfterSaving = it },
            onSave = { items ->
                if (!FolderExport.start(context, offer, items, removeAfterSaving)) toast(L.downloadsSaveToFolderBusy)
                removeAfterSaving = false
            },
            onCancel = {
                FolderExport.dismissOffer(context)
                removeAfterSaving = false
            },
        )
    }
    if (folder.preparing) com.tmplayer.ui.components.FolderCopyPreparingDialog(gallery = folder.gallery)
    folder.progress?.let { progress -> FolderCopyProgressDialog(progress, onCancel = { FolderExport.cancel() }, gallery = folder.gallery) }
    folder.outcome?.takeIf { !it.clean }?.let { outcome -> FolderCopyResultDialog(outcome, onClose = { FolderExport.consume() }) }
    if (explainingNoFolder) {
        TvConfirm(
            title = L.downloadsSaveToFolder,
            message = L.downloadsSaveToFolderTvUnavailable,
            confirmLabel = L.commonClose,
            onConfirm = {
                explainingNoFolder = false
                // The remote goes back to the tab rather than nowhere, as after a delete.
                deleted++
            },
            onDismiss = {
                explainingNoFolder = false
                deleted++
            },
            destructive = false,
        )
    }

    confirmingDelete?.let { row ->
        // Named as its card names it.
        val name = com.tmplayer.ui.browse.rememberVideoName(remember(row.record) { row.record.toMediaItem() })
        TvConfirm(
            title = if (row.cached) L.downloadsDeleteCachedTitle else L.downloadsDeleteOneTitle,
            message = if (row.cached) {
                L.downloadsDeleteCachedBody(title = name, size = Translator.messages.formatter.bytes(row.bytes))
            } else {
                L.downloadsDeleteOneBody(title = name, size = Translator.messages.formatter.bytes(row.bytes))
            },
            confirmLabel = L.commonDelete,
            cancelLabel = L.downloadsKeepIt,
            onConfirm = {
                // The remote goes on to the next row once this one has left the list (the one
                // above after the last, the tab after the only one), not back to the tab.
                val before = keptOrder
                delete(row) { landAfterRemoving(before, row.key, keptNow()) }
                confirmingDelete = null
            },
            onDismiss = { confirmingDelete = null },
        )
    }

    if (confirmingDeleteMany) {
        // One chosen reads as one: its name, "it" and "Keep it", not "them".
        val only = chosen.singleOrNull()
        val onlyName = only?.let { com.tmplayer.ui.browse.rememberVideoName(remember(it.record) { it.record.toMediaItem() }) }
        TvConfirm(
            title = L.downloadsDeleteManyTitle(count = chosen.size),
            message = if (only != null) {
                L.downloadsDeleteOneBody(title = onlyName ?: only.title, size = Translator.messages.formatter.bytes(only.bytes))
            } else {
                L.downloadsDeleteManyBody(size = Translator.messages.formatter.bytes(chosen.sumOf { it.bytes }))
            },
            confirmLabel = L.commonDelete,
            cancelLabel = if (only != null) L.downloadsKeepIt else L.downloadsKeepThem,
            onConfirm = {
                confirmingDeleteMany = false
                deleteMany(chosen)
                deleted++
            },
            onDismiss = { confirmingDeleteMany = false },
        )
    }

    // Free up space's review: every group with what it holds, caches ticked, downloads not, one
    // Free up press, and a question first only when a download is ticked. Low storage mode leads
    // it on a device short of room while it is off, as the one answer that deletes nothing.
    if (freeingUp) {
        val short = com.tmplayer.data.FreeUpSpace.isShort(disk.freeBytes, disk.totalBytes, askedForRoom = askedForRoom)
        FreeUpReviewDialog(
            sections = reviewSections,
            onFree = { picks -> freeUp(picks) },
            onDismiss = { freeingUp = false },
            lowStorage = if (short && (!lowStorageOn || lowStorageTurned)) {
                com.tmplayer.ui.components.LowStorageOffer(
                    mode = L.settingsLowStorage,
                    windowBytes = com.tmplayer.player.LowStorageWindow.WINDOW_BYTES,
                    on = lowStorageTurned,
                ) {
                    lowStorageTurned = true
                    scope.launch { runCatching { settings.setLowStorageMode(true) } }
                }
            } else {
                null
            },
        )
    }
}

/**
 * The ring that says where the remote is standing, on the stock Material widgets this screen is
 * built from.
 *
 * The app's own television controls answer focus with a filled row plus [focusRing]; the Material
 * buttons, tabs and icon buttons here own their fill and answer it with a state layer a few
 * percent of alpha deep, which from a sofa is no answer at all. So on a television this draws the
 * theme's accent ring around whichever widget holds focus, in that widget's own shape, and on a
 * phone it is a no-op: there is no roving focus to mark, and the screen must not change.
 *
 * [focusRing] on its own is not enough, because it deliberately draws nothing in the dark theme:
 * its callers swap their fill to the focus colour, which in the dark is a bright block that would
 * swallow a same-colour ring. Nothing here changes fill, so the dark theme gets the same 2.dp
 * accent ring drawn by hand.
 *
 * [interactions] must also be handed to the widget itself, through its interactionSource
 * parameter, so the state collected here is the widget's own focus rather than a parallel guess.
 */
@Composable
private fun Modifier.tvFocusRing(
    interactions: MutableInteractionSource,
    shape: Shape,
): Modifier {
    if (isTouch()) return this
    val focused by interactions.collectIsFocusedAsState()
    return this
        .focusRing(focused, shape)
        .then(
            if (focused && LocalDarkTheme.current) {
                Modifier.border(Focus.Ring, Tone.accent, shape)
            } else {
                Modifier
            },
        )
}

/**
 * [TextButton], with the ring a remote needs.
 *
 * Every plain text action on this screen goes through here: the top bar, the queue heading and
 * the dialog buttons, so none of them can be the one control focus disappears on. The dialogs
 * matter most: a viewer who cannot see whether Delete or Keep holds focus is one press away from
 * deleting the wrong thing. On a phone this is exactly the stock button.
 */
@Composable
private fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    if (!isTouch()) {
        TvPill(label, icon = null, enabled = enabled, onClick = onClick)
        return
    }
    val interactions = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = Modifier.tvFocusRing(interactions, CircleShape),
    ) {
        Text(label)
    }
}

/** What the top section is called, which depends on whether anything is actually moving. */
private fun queueHeading(active: List<OfflineDownloads.Progress>): String {
    val waiting = active.count { it.stage == OfflineDownloads.Stage.Queued }
    return when {
        active.any { it.stage == OfflineDownloads.Stage.Running } && waiting > 0 ->
            L.downloadsHeadingRunningWaiting(count = waiting)
        active.any { it.stage == OfflineDownloads.Stage.Running } -> L.downloadsHeadingRunning
        active.all { it.stage == OfflineDownloads.Stage.NoWifi } -> L.downloadsWaitingWifi
        active.all { it.stage == OfflineDownloads.Stage.Offline } -> L.downloadsWaitingConnection
        active.all { it.stage == OfflineDownloads.Stage.Paused } -> L.downloadsPaused
        else -> L.downloadsHeadingQueued
    }
}

/** A section heading with the one control that acts on the whole of the section beside it. */
@Composable
private fun QueueHeading(title: String, bulk: String?, onBulk: () -> Unit) {
    Row(
        // The button's own 12 dp of padding leaves its word on the cards' inner edge too.
        modifier = Modifier.fillMaxWidth().padding(start = TEXT_EDGE, end = TEXT_EDGE - 12.dp, top = 8.dp),
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
            TextAction(bulk, onClick = onBulk)
        }
    }
}

/**
 * What this device holds and the one way to give some of it back: the shared [StorageOverview]
 * (the small ring, what TMPlayer uses, what is free, Free up space) on the page's own card.
 *
 * The whole device rather than the downloads alone: a friend saw 2.5 GB used with nothing on the
 * Cached tab, and a panel that names only part of the space is how that happens. Every part of it
 * is named and measured in the review the button opens, which is also the one place any of it is
 * cleared (user, 2026-10-22).
 */
@Composable
internal fun StorageSummary(
    figures: StorageChart.Figures,
    measured: Boolean,
    nothingToFree: Boolean,
    onFreeUp: () -> Unit,
    busy: Boolean = false,
    result: String? = null,
) {
    val tv = !isTouch()
    Card(
        Modifier.fillMaxWidth().padding(CARD_EDGE),
        shape = if (tv) RoundedCornerShape(Corner.Large) else MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
    ) {
        StorageOverview(
            figures = figures,
            measured = measured,
            nothingToFree = nothingToFree,
            onFreeUp = onFreeUp,
            busy = busy,
            result = result,
            modifier = Modifier.padding(CARD_PAD),
        )
    }
}

/**
 * The Downloads page's order as a row of chips, the look History's and Chats' chips have on each
 * device: Newest, Name, and Largest, which is the one for making room.
 */
@Composable
private fun DownloadsSortChips(current: com.tmplayer.data.DownloadShelf.Sort, onPick: (com.tmplayer.data.DownloadShelf.Sort) -> Unit) {
    val s = LocalStrings.current
    com.tmplayer.ui.browse.OrderChips(
        com.tmplayer.data.DownloadShelf.Sort.entries.map { sort ->
            com.tmplayer.ui.browse.OrderChip(
                key = sort.name,
                label = when (sort) {
                    com.tmplayer.data.DownloadShelf.Sort.Newest -> s.downloadsSortNewest
                    com.tmplayer.data.DownloadShelf.Sort.Name -> s.downloadsSortName
                    com.tmplayer.data.DownloadShelf.Sort.Largest -> s.downloadsSortLargest
                },
                icon = when (sort) {
                    com.tmplayer.data.DownloadShelf.Sort.Newest -> TmIcons.Clock
                    com.tmplayer.data.DownloadShelf.Sort.Name -> TmIcons.TextSize
                    com.tmplayer.data.DownloadShelf.Sort.Largest -> TmIcons.Download
                },
                selected = sort == current,
                onClick = { onPick(sort) },
            )
        },
        description = s.downloadsSortLabel,
        modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 8.dp, bottom = 4.dp),
    )
}

/** A section's name on the one page: Downloaded, over the downloads. */
@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 16.dp, bottom = 4.dp),
    )
}

/** A show's name over its downloaded episodes, with how many there are and what they take. */
@Composable
private fun ShowHeading(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 12.dp, bottom = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Recently played's heading at the foot of the page, which opens and closes the section: those
 * videos are not downloads, so they stay out of the way until somebody asks for them.
 */
@Composable
private fun CollapsibleHeading(title: String, detail: String?, open: Boolean, onToggle: () -> Unit) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_EDGE, vertical = 8.dp)
            .clip(shape)
            .tvFocusRing(interactions, shape)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
            .padding(horizontal = CARD_PAD, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            if (open) TmIcons.ChevronUp else TmIcons.ChevronDown,
            contentDescription = if (open) s.commonHide else s.commonShow,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val interactions = remember { MutableInteractionSource() }
    // A clickable card is a focusable card, and on a television that is a trap: directional
    // search will not step from a focused parent onto the buttons drawn inside it, so a card
    // that takes D-pad focus in browse mode swallows the remote one row above Watch, with no
    // ring to say where it went. So the card only takes D-pad focus while a press on it means
    // something a remote wants, toggling its tick in a selection, and it wears the ring then;
    // the rest of the time the remote lands straight on the buttons, and the hold-to-select
    // shortcut stays what it always was, a touch gesture, with the Select button covering it.
    val dpadFocusable = onClick != null || isTouch()
    val phone = usesFloatingChrome()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_EDGE, vertical = 6.dp)
            .tvFocusRing(interactions, RoundedCornerShape(Corner.Large))
            .then(
                if (onClick == null && onHold == null) {
                    Modifier
                } else {
                    Modifier
                        .focusProperties { canFocus = dpadFocusable }
                        .combinedClickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            onClick = { onClick?.invoke() ?: onHold?.invoke() },
                            onLongClick = onHold,
                        )
                },
            ),
        // A phone's card is History's: its large shape, its fill, no edge but a chosen one.
        shape = if (phone) MaterialTheme.shapes.large else RoundedCornerShape(Corner.Large),
        // The same fill the Continue watching cards use, not the scheme's surfaceVariant: that
        // one sits a shade above the window on a dark theme, so a screenful reads as pale panels
        // floating on the background rather than as the app's own cards.
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
        border = when {
            accent != null -> BorderStroke(2.dp, accent)
            phone -> null
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        // [CARD_PAD] on every device, the storage card's and the switch row's own figure, so the
        // words of every card, the note over the list and the queue's heading start on one line.
        Column(Modifier.padding(CARD_PAD)) { content() }
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
    if (!isTouch()) {
        TvPill(label, icon, modifier = Modifier.weight(1f), onClick = onClick)
        return
    }
    val interactions = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
    ) {
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
    if (!isTouch()) {
        TvPill(label, icon, danger = danger, modifier = Modifier.weight(1f), onClick = onClick)
        return
    }
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
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
    if (!isTouch()) {
        TvPill(label, icon, danger = danger, showLabel = false, onClick = onClick)
        return
    }
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.size(48.dp).tvFocusRing(interactions, CircleShape),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = colour)
    }
}

/**
 * A finished video: what it is, what it costs, and what is worth doing with it.
 *
 * On a phone or a tablet the card is the play button, as History's are, and Share, Save to folder
 * and Delete sit behind the one More button at its end (user, 2026-10-22: the row of four buttons
 * under every card was most of what made the page read as clutter). A hold starts a selection, for
 * the same three on several at once. A television keeps its named buttons under the card: a
 * remote has no hold and a menu behind a button is a step further from the sofa.
 *
 * While a selection is on, the whole card is one target that ticks and unticks, which is how a
 * phone's list of anything behaves and keeps small buttons out of a tick target.
 */
@Composable
private fun DownloadCard(
    row: DownloadRow,
    picking: Boolean,
    checked: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    /** Delete; null for a recently played video, which Free up space's review clears. */
    onDelete: (() -> Unit)?,
    onToggle: () -> Unit,
    onHold: (() -> Unit)?,
    /** Save to folder, for a finished download; null where there is no folder picker. */
    onSaveToFolder: (() -> Unit)? = null,
    /** Save to gallery, for a finished download; null where there is no gallery. */
    onSaveToGallery: (() -> Unit)? = null,
    /** A download whose file went: fetched from Telegram again (HX21); null where not offered. */
    onDownloadAgain: (() -> Unit)? = null,
) {
    val touch = isTouch()
    // The phone's card plays on a tap and keeps the rest behind More: a whole download only.
    val tapToPlay = touch && !picking && !row.missing && !row.partial
    var menuOpen by remember { mutableStateOf(false) }
    val s = LocalStrings.current
    val details = listOfNotNull(
        // A part-loaded video says both figures: "710 MB" alone reads as the size of the video,
        // while "710 MB of 1.4 GB" says what is on the disk and what would finish it.
        if (row.partial && row.totalBytes > row.bytes) {
            s.commonOfTotal(done = s.formatter.bytes(row.bytes), total = s.formatter.bytes(row.totalBytes))
        } else {
            s.formatter.bytes(row.bytes).takeIf { row.bytes > 0 }
        },
        s.formatter.duration(row.durationSec.toLong()).ifBlank { null },
        row.chatTitle.ifBlank { null },
        // A part-loaded file still occupies its bytes, and that is the row a viewer looking for
        // space is most likely to want gone.
        // A cached video was never asked for as a download, so it is cached, not downloaded.
        // A download that stopped part way says so, and what it holds here (user, 2026-10-18).
        if (row.partial) (if (row.cached) s.downloadsPartCached else s.downloadsUnfinishedOnDevice(size = s.formatter.bytes(row.bytes))) else null,
        if (row.missing) s.downloadsFileMissing else null,
    ).joinToString(DOT)

    // The clean name the rest of the app uses for it, with the file's own name on a quiet line of
    // its own under it, which tells two copies of one film apart. Only the words on screen change.
    val item = remember(row.record) { row.record.toMediaItem() }
    val name = com.tmplayer.ui.browse.rememberVideoName(item)
    val fileName = remember(item, name) { com.tmplayer.ui.browse.fileNameLine(item, name) }

    val pickTick = com.tmplayer.ui.components.rememberToggleTick()
    RowCard(
        accent = if (checked) MaterialTheme.colorScheme.primary else null,
        onClick = when {
            picking -> ({ pickTick(!checked); onToggle() })
            tapToPlay -> onPlay
            else -> null
        },
        onHold = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picking) {
                Checkbox(checked = checked, onCheckedChange = { pickTick(!checked); onToggle() })
                Spacer(Modifier.width(8.dp))
            }
            run {
                // The small poster History's rows and a chat's rows wear, on the phone, the
                // television and the desktop alike, so a download reads as the same kind of thing:
                // the picture, or the generated cover until there is one.
                VideoPosterThumb(
                    item = item,
                    fraction = if (row.partial && row.totalBytes > 0) (row.bytes.toFloat() / row.totalBytes).coerceIn(0f, 1f) else 0f,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fileName != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        // The end of a file name is its extension and release tags; the middle is
                        // what goes when it does not fit.
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
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
            // A whole download on a phone: More, with Share, Save to folder and Delete behind it.
            if (tapToPlay && !row.cached) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(TmIcons.MoreVert, contentDescription = s.commonMoreOptions, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    com.tmplayer.ui.components.TmDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(s.commonShare) },
                            leadingIcon = { Icon(TmIcons.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onShare()
                            },
                        )
                        if (onSaveToFolder != null) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(s.downloadsSaveToFolder) },
                                leadingIcon = { Icon(TmIcons.Folder, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSaveToFolder()
                                },
                            )
                        }
                        if (onSaveToGallery != null) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(s.downloadsSaveToGallery) },
                                leadingIcon = { Icon(TmIcons.Image, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSaveToGallery()
                                },
                            )
                        }
                        if (onDelete != null) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(s.commonDelete, color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(TmIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
        // Hidden while picking: a Delete button inside something the viewer is tapping to select
        // is a mistake waiting to be made. A phone's whole download has its More instead.
        if (!picking && !(tapToPlay && !row.cached)) {
            CardActions {
                when {
                    // Something took the file. The record is all that is left to deal with.
                    row.missing -> {
                        onDownloadAgain?.let { PrimaryAction(s.downloadsDownloadAgain, TmIcons.Download, it) }
                        SecondaryAction(s.commonRemove, TmIcons.Delete, onDelete ?: {}, danger = true)
                    }
                    row.cached -> {
                        // A phone's card plays on a tap; a television's needs the button.
                        if (!tapToPlay) PrimaryAction(s.navWatch, TmIcons.Play, onPlay)
                        // What a recently played video wants is keeping. Clearing it is Free up
                        // space's review, with the rest of what playing left behind.
                        SecondaryAction(s.downloadsSaveToDownloads, TmIcons.Download, onShare)
                    }
                    row.partial -> {
                        PrimaryAction(s.commonResume, TmIcons.Refresh, onPlay)
                        if (onDelete != null) IconOnlyAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
                    }
                    else -> {
                        PrimaryAction(s.navWatch, TmIcons.Play, onPlay)
                        // With Save to folder beside it there is no room for two words on a
                        // phone: both "Watch" and "Share" were cut to a letter. Share goes to an icon.
                        if (onSaveToFolder != null) {
                            IconOnlyAction(s.commonShare, TmIcons.Share, onShare)
                            IconOnlyAction(s.downloadsSaveToFolder, TmIcons.Folder, onSaveToFolder)
                        } else {
                            SecondaryAction(s.commonShare, TmIcons.Share, onShare)
                        }
                        if (onDelete != null) IconOnlyAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
                    }
                }
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
    /** Free up space, for a row that failed because the drive was full. */
    onFreeUp: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val stage = progress.stage
    val failed = stage == OfflineDownloads.Stage.Failed
    val fraction = progress.fraction
    val size = if (progress.totalBytes > 0) {
        s.commonOfTotal(done = s.formatter.bytes(progress.downloadedBytes), total = s.formatter.bytes(progress.totalBytes))
    } else {
        s.formatter.bytes(progress.downloadedBytes)
    }
    val line = when (stage) {
        OfflineDownloads.Stage.Failed -> progress.failure.orEmpty()
        OfflineDownloads.Stage.Queued -> listOfNotNull(
            if (place <= 0) s.downloadsNextInQueue else s.downloadsPlaceInQueue(place = place + 1),
            s.formatter.bytes(progress.totalBytes).takeIf { progress.totalBytes > 0 },
            // Only worth saying when an earlier attempt left something behind, since a queued
            // video normally has nothing on disk and "0 B so far" is not news.
            s.downloadsAlreadyHere(percent = s.formatter.percent((fraction ?: 0f).toDouble()))
                .takeIf { progress.downloadedBytes > 0 },
        ).joinToString(DOT)
        OfflineDownloads.Stage.Paused -> listOfNotNull(
            s.downloadsPaused,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.NoWifi -> listOfNotNull(
            s.downloadsWaitingWifi,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Offline -> listOfNotNull(
            s.downloadsWaitingConnection,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Moving ->
            if (progress.heldByPlayer) s.downloadsFinishesAfterPlayback else s.downloadsMovingIntoDownloads
        OfflineDownloads.Stage.Running -> listOfNotNull(
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
            s.formatter.speed(progress.bytesPerSecond)
                .takeIf { progress.bytesPerSecond >= 1024 },
            s.formatter.eta(progress.remainingSeconds).ifBlank { null },
        ).joinToString(DOT)
    }

    // Stopped, waiting or failed with part of the file here: said plainly, with what it holds,
    // since that is space a viewer looking for room can get back (user, 2026-10-18).
    val held = stage != OfflineDownloads.Stage.Running && stage != OfflineDownloads.Stage.Moving &&
        progress.downloadedBytes > 0
    val name = com.tmplayer.ui.browse.rememberVideoName(remember(progress.request) { progress.request.item() })
    RowCard(accent = if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else null) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (held) {
            Spacer(Modifier.height(2.dp))
            Text(
                s.downloadsUnfinishedOnDevice(size = s.formatter.bytes(progress.downloadedBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.caution,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                    PrimaryAction(s.commonPause, TmIcons.Pause, onPause)
                OfflineDownloads.Stage.Paused ->
                    PrimaryAction(s.commonResume, TmIcons.Play, onResume)
                // Refused because the chat forbids keeping a copy: trying again is refused the
                // same way, so the only offer left is Dismiss below.
                // Refused for room: Try again would find the same full drive, so the first
                // offer is the one that makes room, and Try again stays beside it for after.
                OfflineDownloads.Stage.Failed ->
                    if (com.tmplayer.data.isNoSpaceFailure(progress.failure) && onFreeUp != null) {
                        PrimaryAction(s.downloadsFreeUpSpace, TmIcons.Delete, onFreeUp)
                        PrimaryAction(s.commonTryAgain, TmIcons.Refresh, onResume)
                    } else if (progress.failure != ContentProtection.NOT_SAVABLE) {
                        PrimaryAction(s.commonTryAgain, TmIcons.Refresh, onResume)
                    }
                // It will start itself the moment the signal is back, so the useful offer is the
                // other one: hold it, and do not.
                OfflineDownloads.Stage.Offline, OfflineDownloads.Stage.NoWifi ->
                    PrimaryAction(s.commonPause, TmIcons.Pause, onPause)
                // Nothing to pause that has not started. Cancel below fills the row on its own.
                OfflineDownloads.Stage.Queued -> Unit
                // Fetched already; the move is short, and stopping it half way gains nothing.
                OfflineDownloads.Stage.Moving -> Unit
            }
            SecondaryAction(
                label = if (failed) s.commonDismiss else s.commonCancel,
                icon = TmIcons.Close,
                onClick = onCancel,
                danger = true,
            )
        }
    }
}

/**
 * The room an empty section's message is given. A phone's share of the screen centres it in the
 * space under the storage card, and grows with the message where the share is too short for it. A television's would not fit: the card and the switch already take
 * most of a 540 dp screen, so a share of the whole list put the message's last line under the
 * bottom edge, and with nothing focusable down there the remote could never scroll to it. There
 * the message takes only the room it needs, straight under the switch.
 */
@Composable
private fun EmptyState(fraction: Float, viewport: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    // At least the share, never at most: a phone on its side has a short list, and a fixed share
    // of it (fillParentMaxHeight) cut the message off after a line or two and let the Recently
    // played heading under it draw over the rest (2026-10-22). The message's own height wins.
    Box(
        if (isTouch()) Modifier.fillMaxWidth().heightIn(min = viewport * fraction) else Modifier.padding(top = 8.dp),
        contentAlignment = Alignment.Center,
        // BigEmpty fills what it is given: handed the least height, it centres in at least that.
        propagateMinConstraints = true,
    ) { content() }
}

/**
 * Downloads on a phone, drawn like the pages behind the picture it is reached from (History is the
 * model): its name along the top beside the round Back, one page of sections rather than tabs,
 * cards shaped like History's, and the floating bar along the foot with the picture ringed. Select and
 * Delete all ride in the small pill over the bar, so nothing to press is at the top.
 *
 * A selection takes the bar's place with one of its own: the count, Select all, Share and Delete,
 * with Close where Back was. Outside the app's bar (the screenshot fixture) the pill's buttons sit
 * in a sticky bar of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneDownloads(
    title: String,
    tab: Int,
    tabLabel: (Int) -> String,
    onTab: (Int) -> Unit,
    picking: Boolean,
    chosenCount: Int,
    canSelect: Boolean,
    allChosen: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onLeavePicking: () -> Unit,
    onSelectAll: () -> Unit,
    onShareChosen: () -> Unit,
    onSaveChosen: () -> Unit,
    /** Save to gallery for the selection; null where there is no gallery to save to. */
    onSaveChosenToGallery: (() -> Unit)?,
    /** The selection's total size, and the line when it is more than the last folder has room for. */
    chosenSize: String?,
    chosenWarning: String?,
    onDeleteChosen: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    body: LazyListScope.() -> Unit,
) {
    val s = LocalStrings.current
    val hosted = LocalTouchChrome.current != null
    val actions: (@Composable RowScope.() -> Unit)? = if (canSelect && !picking) {
        {
            // Select, and nothing else up here: Delete all went (Select all and Delete, or Free up
            // space's review, say the same), so the pill is one plain button.
            FloatingBarIcon(TmIcons.Checklist, s.downloadsSelect, onSelect)
        }
    } else {
        null
    }
    TouchPage(TouchPageSpec(current = null, inProfile = true, hidden = picking, actions = actions))

    val compact = rememberCompactChrome()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The list's end clears whatever floats over it: the selection's bar, the app's bar, or the
    // sticky bar standing in for it.
    // The app's bar and the pill of this page's buttons are counted here too, not only read from
    // LocalBottomChrome, so the last card's Delete and an active card's Pause and Cancel come out
    // from under them at the list's end even on a frame where the host has not yet heard this
    // page's buttons (portrait: the bar with the pill over it; a rail: the pill alone).
    val shell = if (hosted) {
        navigationBar + com.tmplayer.ui.browse.touchShellClearance(
            withActions = actions != null,
            rail = com.tmplayer.ui.browse.LocalTouchRail.current,
        )
    } else {
        0.dp
    }
    val bottom = when {
        picking || (!hosted && actions != null) -> navigationBar + FloatingChrome.clearance(compact)
        else -> maxOf(LocalBottomChrome.current, shell, navigationBar) + 16.dp
    }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Box(Modifier.fillMaxSize().background(Tone.background)) {
        Scaffold(
            containerColor = Tone.background,
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = { FloatingPageTitle({ Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, scroll) },
            // The list runs to the foot of the window, under the bars that float over it.
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            // One page, no tabs: the sections follow one another down the list.
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottom),
                        content = body,
                    )
                    ScrollToTop(listState)
                    FastScroller(listState)
                }
            }
        }

        FloatingBackButton(
            icon = if (picking) TmIcons.Close else TmIcons.ArrowBack,
            // While picking, the button leaves the selection rather than the screen: that is what
            // Back does here, and the two must not disagree.
            label = if (picking) s.downloadsLeaveSelection else s.commonBack,
            onClick = if (picking) onLeavePicking else onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )

        if (picking) {
            StickyActionBar(Modifier.align(Alignment.BottomCenter), compact = compact, fill = true) {
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (chosenCount == 0) s.downloadsSelectVideos else s.downloadsSelected(count = chosenCount),
                        style = MaterialTheme.typography.titleSmall,
                        color = Tone.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The running total, or in red when the last folder saved to has less room.
                    (chosenWarning ?: chosenSize)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (chosenWarning != null) MaterialTheme.colorScheme.error else Tone.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Everything the list is showing, and never more than that.
                TextButton(onClick = onSelectAll, enabled = !allChosen) { Text(s.downloadsSelectAll, maxLines = 1) }
                FloatingBarIcon(TmIcons.Share, s.commonShare, onShareChosen, enabled = chosenCount > 0)
                FloatingBarIcon(TmIcons.Folder, s.downloadsSaveToFolder, onSaveChosen, enabled = chosenCount > 0)
                onSaveChosenToGallery?.let { FloatingBarIcon(TmIcons.Image, s.downloadsSaveToGallery, it, enabled = chosenCount > 0) }
                FloatingBarIcon(TmIcons.Delete, s.commonDelete, onDeleteChosen, tint = MaterialTheme.colorScheme.error, enabled = chosenCount > 0)
            }
        } else if (!hosted && actions != null) {
            StickyActionBar(Modifier.align(Alignment.BottomCenter), compact = compact, content = actions)
        }
    }
}

/**
 * Downloads on a television, laid out like the app's other full pages there (Settings, a chat's
 * videos): the name in the large heading on the left with the round Back before it, the three
 * tabs as the History page's chips with room between them, and the actions as the same pills the
 * rest of the television uses, filled with the focus colour rather than ringed.
 *
 * Stock Material's app bar and tab row used to draw this page: a small title, every tab in the
 * accent, nothing focused on arrival, and a square ring around a focused tab that butted against
 * the selected tab's line.
 */
@Composable
private fun TvDownloads(
    tab: Int,
    tabLabel: (Int) -> String,
    onTab: (Int) -> Unit,
    picking: Boolean,
    chosenCount: Int,
    canSelect: Boolean,
    allChosen: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onLeavePicking: () -> Unit,
    onSelectAll: () -> Unit,
    onShareChosen: () -> Unit,
    /** Save to folder; on a television without a folder picker it explains why it cannot. */
    onSaveChosen: () -> Unit,
    chosenSize: String?,
    chosenWarning: String?,
    onDeleteChosen: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    /**
     * Bumped when a delete left no row to go on to (or took several at once): focus goes back to
     * the tab.
     */
    refocus: Int = 0,
    body: LazyListScope.() -> Unit,
) {
    val s = LocalStrings.current
    val tabFocus = remember { List(3) { FocusRequester() } }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { tabFocus[tab].requestFocus() }
    }
    // The remote lands on the tab being shown, as the rail lands on the page being shown: before
    // this nothing held focus until the first press, which then went to Back.
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { tabFocus[tab].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            // The rows below keep 16dp of their own each side, so the page owes that much less to
            // the overscan margin and the heading, tabs and rows all start on one line.
            // The page starts right below the top bar.
            .padding(start = Tv.SafeH - 16.dp, end = Tv.SafeH - 16.dp, top = 4.dp),
    ) {
        // Under the top bar, whose Back goes up to the bar, the page keeps a compact one line
        // title (the bar marks the account's picture, not Downloads) and no back button but the
        // one that leaves a selection.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picking) {
                TvPill(
                    label = if (picking) s.downloadsLeaveSelection else s.commonBack,
                    icon = if (picking) TmIcons.Close else TmIcons.ArrowBack,
                    showLabel = false,
                    onClick = if (picking) onLeavePicking else onBack,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                TvText(
                    when {
                        !picking -> s.downloadsTitle
                        chosenCount == 0 -> s.downloadsSelectVideos
                        else -> listOfNotNull(s.downloadsSelected(count = chosenCount), chosenSize).joinToString(DOT)
                    },
                    style = TvTheme.typography.titleLarge,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    picking -> {
                        TvPill(s.downloadsSelectAll, TmIcons.Checklist, enabled = !allChosen, onClick = onSelectAll)
                        if (chosenCount > 0) {
                            TvPill(s.commonShare, TmIcons.Share, onClick = onShareChosen)
                            TvPill(s.downloadsSaveToFolder, TmIcons.Folder, onClick = onSaveChosen)
                            TvPill(s.commonDelete, TmIcons.Delete, danger = true, onClick = onDeleteChosen)
                        }
                    }
                    canSelect -> TvPill(s.downloadsSelect, TmIcons.Checklist, onClick = onSelect)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).focusRestorer(tabFocus[tab]),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvPill(tabLabel(ONGOING), TmIcons.Download, selected = tab == ONGOING, modifier = Modifier.focusRequester(tabFocus[ONGOING])) { onTab(ONGOING) }
            TvPill(tabLabel(COMPLETED), TmIcons.CheckCircle, selected = tab == COMPLETED, modifier = Modifier.focusRequester(tabFocus[COMPLETED])) { onTab(COMPLETED) }
            TvPill(tabLabel(CACHED), TmIcons.Clock, selected = tab == CACHED, modifier = Modifier.focusRequester(tabFocus[CACHED])) { onTab(CACHED) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().keepTopInSight(listState),
                contentPadding = PaddingValues(bottom = Tv.SafeV),
                content = body,
            )
            ScrollToTop(listState)
        }
    }
}

/**
 * Where the remote goes once [gone] has been taken out of a list that showed [before], in that
 * order, and now holds [now]: the first row after it that is still there, else the nearest one
 * above it, else null (the tab). [gone] itself when it is still there, its file not deleted.
 */
internal fun rowAfterRemoving(before: List<String>, gone: String, now: Set<String>): String? {
    if (gone in now) return gone
    val at = before.indexOf(gone)
    if (at < 0) return null
    return before.subList(at + 1, before.size).firstOrNull { it in now }
        ?: before.subList(0, at).lastOrNull { it in now }
}

/** Frames the row the remote goes to after a removal is asked for the focus over. */
private const val ROW_FOCUS_TRIES = 10

/** How long a cancelled download is waited for to leave the queue before the remote moves on. */
private const val ROW_GONE_WAIT_MS = 3_000L

/**
 * A pill on the television's Downloads page: a tab, an action on a card or in the heading.
 *
 * Drawn as the browse pages draw theirs (BrowseScreen's chips and pills): the step above the page
 * at rest, a tint when it is the chosen tab, the focus colour under the remote, and the accent
 * ring in daylight where that fill alone is too pale. [danger] fills red under the remote.
 */
@Composable
private fun TvPill(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    /** False draws the icon alone; the label is still what a screen reader says. */
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            focused && danger -> Danger
            focused -> Tone.focusFill
            selected -> Tone.accent.copy(alpha = 0.16f)
            else -> Tone.surfaceHigh
        },
        animationSpec = tween(140),
        label = "downloadsPill",
    )
    val foreground = when {
        focused && danger -> Color.White
        focused -> Tone.onFocusFill
        danger -> Danger
        selected -> Tone.accent
        else -> Tone.text
    }
    Row(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .focusRing(focused && !danger, CircleShape)
            .semantics { if (selected) this.selected = true }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) { if (enabled) onClick() }
            .alpha(if (enabled) 1f else 0.5f)
            // An icon on its own gets even padding, so the pill comes out round rather than wide.
            .padding(horizontal = if (showLabel) 18.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = if (showLabel) null else label, tint = foreground, modifier = Modifier.size(22.dp))
        }
        if (showLabel) {
            TvText(
                label,
                style = TvTheme.typography.bodyLarge,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The three tabs, as indices, because that is what [TabRow] counts in. */
private const val ONGOING = 0
private const val COMPLETED = 1
private const val CACHED = 2

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "

/** How far a card on this screen sits in from the sides of the list. */
private val CARD_EDGE = 16.dp

/** How far a card's words sit in from the card's own edge. */
private val CARD_PAD = 16.dp

/** Where words outside a card start, on the line the words inside the cards start on. */
private val TEXT_EDGE = CARD_EDGE + CARD_PAD
