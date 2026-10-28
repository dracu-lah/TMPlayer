package com.tmplayer.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.Dp
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.browse.tookFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DetailAction
import com.tmplayer.data.DetailActions
import com.tmplayer.data.DetailContext
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Background
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.setWatchLater
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.WideDetailPanel
import com.tmplayer.ui.browse.detailRow
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * The detail sheet (R5) over whatever page is showing, when [ShellState.detail] asks for one: the
 * large title page ([WideDetailPanel]) filling the window over the page. The page under it never moves or resizes. Esc, Back or the page's Back button
 * closes it, and focus goes back to the poster it came from. Focus stays inside the
 * sheet while it is open, so Tab walks the sheet's buttons rather than the page hidden under it.
 *
 * The sheet slides a little way in from the end edge as it fades in, and back out as it closes;
 * with less motion asked for it only fades.
 */
@Composable
fun BoxScope.DetailPaneHost(state: ShellState) {
    val request = state.detail
    // The last request shown, so the sheet keeps its content while it leaves. Not state: it only
    // ever changes along with [ShellState.detail], which already recomposes this.
    val last = remember { arrayOfNulls<DetailRequest>(1) }
    if (request != null) last[0] = request
    val shown = request ?: last[0]
    val open = request != null
    BackHandler(enabled = open) { state.closeDetail() }
    val still = com.tmplayer.ui.components.rememberLessMotion()
    val fade = androidx.compose.animation.core.tween<Float>(com.tmplayer.ui.theme.Motion.Normal)
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(fade),
        exit = fadeOut(fade),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FloatingTone.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.closeAllDetails() },
        )
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val width = sheetWidth(maxWidth)
        val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(
            com.tmplayer.ui.theme.Motion.Normal,
            easing = com.tmplayer.ui.theme.Motion.Standard,
        )
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(fade) + if (still) EnterTransition.None else slideInHorizontally(slide) { it / 10 },
            exit = fadeOut(fade) + if (still) ExitTransition.None else slideOutHorizontally(slide) { it / 10 },
        ) {
            shown?.let { PaneSheet(state, it, open, Modifier.width(width)) }
        }
    }
}

/**
 * The detail page fills the whole window (user, 2026-10-27), like a page of its own with a Back
 * button, so nobody takes it for a card to dismiss. The page under it stays where it was.
 */
internal fun sheetWidth(available: Dp): Dp = available

/** Frames to wait for Play to be placed before giving its focus up. */
private const val FOCUS_TRIES = 10

@Composable
private fun PaneSheet(state: ShellState, request: DetailRequest, open: Boolean, modifier: Modifier) {
    // The pane runs the window's height at its right end, over the page header's buttons.
    CoverWindowControls()
    val shape = RoundedCornerShape(0.dp)
    Surface(
        shape = shape,
        // The page's own colour, so the backdrop's fade runs into it; the border and the scrim
        // set it apart from the page under it.
        color = Tone.background,
        border = null,
        modifier = modifier
            .fillMaxHeight()
                        // Focus that would leave the open sheet stays in it; once it is closing, it may go.
            .focusProperties { onExit = { if (open && state.detail != null) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    state.closeDetail()
                    true
                } else {
                    false
                }
            },
    ) {
        // A new video is a new sheet: nothing of the last one's state carries over.
        key(request.item.id) { DetailPane(state, request) }
    }
}

@Composable
private fun DetailPane(state: ShellState, request: DetailRequest) {
    val s = LocalStrings.current
    val item = request.item
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val watchedList by state.watched.watched.collectAsState(initial = emptyMap())
    val laterKeys by state.watched.watchLater.keys.collectAsState(initial = emptySet())
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val downloads by OfflineDownloads.active.collectAsState()
    val key = SettingsStore.progressKey(item.chatId, item.messageId)
    val point = progress[key]
    val finished = key in watchedList
    val record = history.firstOrNull { it.chatId == item.chatId && it.messageId == item.messageId }
    // Another copy of the same film kept, or coming down, counts as well: the film is here (or
    // on its way), and a second size of it is not what anybody asked for.
    val otherCopies = item.versions.filter { it.id != item.id }
    val copyKept = record == null && otherCopies.any { copy -> history.any { it.chatId == copy.chatId && it.messageId == copy.messageId } }
    val coming = downloads[item.fileId]?.takeIf { it.busy }
        ?: otherCopies.firstNotNullOfOrNull { copy -> downloads[copy.fileId]?.takeIf { it.busy } }
    val queued = coming != null
    val title = request.chatTitle.ifBlank { state.chatTitleOf(item.chatId) }
    val chat = state.chatOf(item.chatId)
    val free by produceState(0L, item.id) {
        value = withContext(Dispatchers.IO) { DiskInfo.of(DesktopPaths.downloadsDir).freeBytes }
    }
    val first = remember(item.id) { FocusRequester() }
    val back = state.detailReturn
    val bookmarks by remember(item.chatId, item.messageId) {
        state.bookmarks?.bookmarks(item.chatId, item.messageId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // The viewer's corrections to this file, and whether a provider's match is showing for it.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val correction = corrections[item.id]
    val matched = com.tmplayer.ui.online.rememberMatched(item) != null
    // The comments under a channel post (CP81).
    val comments = com.tmplayer.ui.browse.rememberCommentCount(item)

    val actions = DetailActions.of(
        DetailContext(
            canBeSaved = item.canBeSaved,
            resumeMs = point?.positionMs ?: 0L,
            finished = finished,
            watchLater = key in laterKeys,
            inDownloads = record != null,
            copyInDownloads = copyKept,
            cached = item.onDevice,
            downloading = queued,
            canSelect = request.selectThis != null,
            // Another app is the desktop's one hand off: there is no share sheet, so Share is dropped below.
            canHandOff = true,
            hasClipboard = true,
            outsideChat = request.outsideChat && chat != null,
            canCorrect = true,
            grouped = item.versions.size > 1,
            separated = correction?.separate == true,
            matched = matched,
            wrongMatch = correction?.wrongMatch == true,
            comments = comments,
            // A copy of the finished download in a folder the viewer picks (CP82).
            canExport = true,
            canRemoveFromHistory = true,
        ),
    ).filterNot { it == DetailAction.Share }

    fun close() = state.closeDetail()
    // The lines that close the pane before their work is done run on the process's scope: the
    // pane's own is cancelled as it leaves, which dropped the copied link and the hand off.
    val outlives = Background.scope
    // "Remove from Downloads?" is asked over the pane, which stays open until it is answered.
    var confirmingRemove by remember(item.id) { mutableStateOf(false) }
    // "Rename" is asked over the pane too, which closes once the title is saved.
    var renaming by remember(item.id) { mutableStateOf(false) }
    // The comments' videos open over the pane, which stays until they close.
    var commentsOpen by remember(item.id) { mutableStateOf(false) }

    fun run(action: DetailAction) {
        if (action == DetailAction.RemoveDownload) {
            confirmingRemove = record != null
            return
        }
        if (action == DetailAction.Rename) {
            renaming = true
            return
        }
        if (action == DetailAction.CommentVideos) {
            commentsOpen = true
            return
        }
        // The pane stays open for these: the button turns into the download's state (the ring,
        // then Downloaded) where the viewer is looking, and the keyboard's focus stays on it.
        // Closing it here sent the viewer back to the page behind for no reason.
        when (action) {
            DetailAction.Download, DetailAction.SaveToDownloads -> {
                // The queue ignores a second press on a file already coming down.
                OfflineDownloads.start(state.downloads, item, title)
                return
            }
            DetailAction.CancelDownload -> {
                OfflineDownloads.cancel(state.downloads, coming?.fileId ?: item.fileId)
                return
            }
            // The player puts the pane away and brings it back when it closes (see
            // ShellState.openPlayer), so leaving the video lands here, not on the page behind.
            DetailAction.Resume, DetailAction.Play, DetailAction.StartOver -> {
                state.openPlayer(item, startFromBeginning = action == DetailAction.StartOver)
                return
            }
            // These turn over in place: closing the pane for them sent the viewer back to the page
            // for a change they could have seen right here.
            DetailAction.MarkWatched, DetailAction.MarkUnwatched -> {
                val mark = action == DetailAction.MarkWatched
                // Not said: the button and the tick on the card show it.
                outlives.launch { runCatching { setWatched(state.watched, state.settings, item, title, mark) } }
                return
            }
            // The pane stays: Resume turns into Play, and the line under the title says Undo.
            DetailAction.RemoveFromHistory -> {
                outlives.launch {
                    val undo = runCatching { com.tmplayer.data.HistoryEdits.removeVideo(state.settings, state.watched, item) }.getOrNull()
                    if (undo != null && !undo.isEmpty) {
                        undoToast(s.browseRemovedFromHistory(1), s.browseUndo) {
                            outlives.launch { runCatching { com.tmplayer.data.HistoryEdits.undo(state.settings, state.watched, undo) } }
                        }
                    }
                }
                return
            }
            DetailAction.AddWatchLater, DetailAction.RemoveWatchLater -> {
                val add = action == DetailAction.AddWatchLater
                outlives.launch {
                    runCatching { setWatchLater(state.watched, item, title, add) }
                    toast(if (add) s.detailWatchLaterAdded(item.title) else s.detailWatchLaterRemoved(item.title))
                }
                return
            }
            else -> Unit
        }
        // The link, the hand off and the copy say how they went in a toast over the pane, which
        // stays; the rest are done with it, or take the viewer somewhere else.
        val staysOpen = action == DetailAction.OpenElsewhere || action == DetailAction.CopyLink || action == DetailAction.ExportCopy
        if (!staysOpen) close()
        when (action) {
            // Handled above, with the pane left open.
            DetailAction.Resume, DetailAction.Play, DetailAction.StartOver -> Unit
            DetailAction.MarkWatched, DetailAction.MarkUnwatched -> Unit
            DetailAction.AddWatchLater, DetailAction.RemoveWatchLater, DetailAction.RemoveFromHistory -> Unit
            DetailAction.Download, DetailAction.SaveToDownloads, DetailAction.CancelDownload -> Unit
            DetailAction.InDownloads -> state.go(Destination.Downloads)
            DetailAction.RemoveDownload -> Unit
            DetailAction.SelectVideos -> request.selectThis?.invoke()
            DetailAction.Share -> Unit
            DetailAction.OpenElsewhere -> outlives.launch {
                if (record == null && !Td.maySave(item.chatId, item.messageId)) {
                    toast(ContentProtection.NOT_SAVABLE)
                    return@launch
                }
                val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                    ?: runCatching { Td.localFilePath(Td.currentFileId(item.chatId, item.messageId, item.fileId)) }
                        .getOrNull()?.let(::File)
                if (file == null) toast(s.downloadsFileGone) else OpenExternal.open(file)
            }
            DetailAction.CopyLink -> outlives.launch {
                val link = runCatching {
                    Td.client.getMessageLink(item.chatId, item.messageId, 0, 0, "", false, false).valueOrNull?.link
                }.getOrNull()
                if (link.isNullOrBlank()) {
                    toast(s.commonNoLinks)
                } else {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
                    toast(s.commonLinkCopied)
                }
            }
            DetailAction.OpenChat -> chat?.let(state::openChat)
            DetailAction.SplitCopy, DetailAction.JoinCopies -> {
                val apart = action == DetailAction.SplitCopy
                outlives.launch { runCatching { state.settings.correct(listOf(item)) { it.copy(separate = apart) } } }
            }
            DetailAction.WrongMatch, DetailAction.UseMatch -> {
                val wrong = action == DetailAction.WrongMatch
                outlives.launch {
                    runCatching { state.settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(wrongMatch = wrong) } }
                }
            }
            DetailAction.Rename, DetailAction.CommentVideos -> Unit
            DetailAction.ExportCopy -> outlives.launch {
                val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                if (file == null) {
                    toast(s.downloadsFileGone)
                    return@launch
                }
                // Asked again at the copy: the chat may have restricted saving since.
                if (!Td.maySave(item.chatId, item.messageId)) {
                    toast(ContentProtection.NOT_SAVABLE)
                    return@launch
                }
                val folder = com.tmplayer.desktop.os.FolderPicker.pick(s.keepExportFolder, DesktopPaths.downloadsDir.parentFile) ?: return@launch
                toast(s.keepExporting(item.title))
                val said = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = com.tmplayer.desktop.DesktopExport.freeName(folder, file.name)
                        java.nio.file.Files.copy(file.toPath(), target.toPath())
                        s.keepExportedFolder(folder.path)
                    }.getOrElse { s.keepExportFailed(com.tmplayer.data.Failures.humanise(it)) }
                }
                toast(said)
            }
        }
    }

    val freeLabel = DiskInfo.freeLabel(free)
    val rows: List<DetailRow> = actions.map { action ->
        val row = detailRow(action, s, point, chat?.title ?: title, onSelect = { run(action) }, free = freeLabel)
            // The desktop's own copy goes to a folder, not a gallery.
            .let { if (it.action == DetailAction.ExportCopy) it.copy(label = s.keepExportFolder, detail = s.keepExportFolderDetail, icon = TmIcons.Folder) else it }
        // The desktop's own words where it has them: the queue lives on the Downloads page.
        // and the ring round the button fills as the download comes down.
        if (action == DetailAction.CancelDownload) row.copy(label = s.downloadsCancel, progress = coming?.fraction ?: 0f) else row
    }

    Box(Modifier.fillMaxSize()) {
        // A film posted more than once opens on the copy that would play, and a copy chosen from
        // its list is remembered for the video before the pane turns to it.
        fun turnTo(copy: MediaItem) = state.turnDetail(
            DetailRequest(copy, state.chatTitleOf(copy.chatId).ifBlank { request.chatTitle }, request.outsideChat, request.selectThis, request.onClosed),
        )
        LaunchedEffect(item.id) {
            if (item.versions.size <= 1) return@LaunchedEffect
            val pick = runCatching { state.settings.copyToPlay(item) }.getOrNull() ?: return@LaunchedEffect
            if (pick.id != item.id) turnTo(pick)
        }
        // Keyed by the video, so "More like this" opening another starts it at the top, and the
        // one Back comes down to starts afresh, its focus on the poster it was left by.
        androidx.compose.runtime.key(item.id) {
            androidx.compose.runtime.CompositionLocalProvider(com.tmplayer.ui.browse.LocalDetailReturn provides back) {
            WideDetailPanel(
                item = item,
                chatTitle = title,
                watched = point,
                finished = finished,
                rows = rows,
                onClose = { close() },
                firstAction = first,
                // Over this pane, so Esc or Back comes back to it.
                onOpenItem = { other ->
                    state.pushDetail(DetailRequest(other, state.chatTitleOf(other.chatId), outsideChat = true, onClosed = request.onClosed))
                },
                bookmarks = bookmarks,
                // The player takes the position on opening this video, in place of its resume point.
                onOpenBookmark = { mark ->
                    // The pane comes back when the player closes, as it does for Play.
                    BookmarkJump.request(item.chatId, item.messageId, mark.positionMs)
                    state.openPlayer(item, startFromBeginning = false)
                },
                onPickCopy = { copy ->
                    scope.launch {
                        runCatching { state.settings.setCopyPick(copy) }
                        turnTo(copy)
                    }
                },
                copyStarted = { SettingsStore.progressKey(it.chatId, it.messageId) in progress },
            )
            }
        }
    }
    // Play takes focus once it is placed: the sheet lays its page out to its own size, so Play
    // is composed a frame after this effect starts, and a request before then is lost. Back from
    // a video opened by "More like this" leaves it to the poster that opened it.
    LaunchedEffect(item.id) {
        if (back.tookFocus()) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    if (commentsOpen) {
        val commentScope = rememberCoroutineScope()
        val list = remember(item.id) { com.tmplayer.ui.browse.PagedVideos.comments(commentScope, item.chatId, item.messageId) }
        MoreVideosDialog(
            title = s.detailCommentsVideos,
            note = s.detailCommentsCount(comments),
            list = list,
            empty = s.detailCommentsNone,
            round = false,
            // The pane stays open until the player takes it away, so it comes back when the video
            // closes (or stays, should the video be refused).
            onPlay = { video -> com.tmplayer.ui.browse.LinkRequests.play(video) },
            onDismiss = { commentsOpen = false },
        )
    }

    if (renaming) {
        RenameDialog(
            initial = correction?.title ?: com.tmplayer.data.SeriesShelf.filmTitle(item),
            onSave = { title ->
                renaming = false
                // Every copy of the tile, so the title holds whichever one it shows.
                outlives.launch {
                    runCatching { state.settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(title = title) } }
                }
                close()
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingRemove) {
        ConfirmDialog(
            title = s.gridRemoveDownloadTitle,
            message = s.gridRemoveDownloadMessage(item.title),
            confirmLabel = s.commonRemove,
            onDismiss = { confirmingRemove = false },
            onConfirm = {
                confirmingRemove = false
                val kept = record
                scope.launch {
                    if (kept != null) {
                        toast(if (DownloadIndex.delete(state.settings, kept)) s.downloadsRemoved(item.title) else s.downloadsInUse)
                    }
                    close()
                }
            },
        )
    }
}

/**
 * The desktop's [com.tmplayer.ui.components.SheetHost]: what a phone shows in its bottom sheet (a
 * video's copies, with the picture over the list) as a card in the middle of the window, over the
 * window's dim, as wide as a reading column and no taller than its content or most of the window.
 * Esc or a click outside closes it.
 */
val DesktopSheetHost = com.tmplayer.ui.components.SheetHost { onDismiss, _, content -> DesktopSheet(onDismiss, content) }

@Composable
internal fun DesktopSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val tall = maxHeight * 0.85f
            Column(
                Modifier
                    .widthIn(max = SHEET_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(max = tall)
                    .clip(com.tmplayer.ui.theme.Floating.DialogShape)
                    .background(FloatingTone.sheet)
                    .border(floatingBorder(), com.tmplayer.ui.theme.Floating.DialogShape),
                content = content,
            )
        }
    }
}

private val SHEET_WIDTH = 600.dp
