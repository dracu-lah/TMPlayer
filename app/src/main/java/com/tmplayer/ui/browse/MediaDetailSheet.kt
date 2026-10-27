package com.tmplayer.ui.browse

import com.tmplayer.ui.components.TopMessageHost
import android.widget.Toast
import com.tmplayer.App
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.Bookmark
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.DetailAction
import com.tmplayer.data.DetailActions
import com.tmplayer.data.DetailContext
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MessageLink
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchLaterStore
import com.tmplayer.data.WatchPoint
import com.tmplayer.data.cancel
import com.tmplayer.i18n.Messages
import com.tmplayer.data.MediaFacts
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.ignoreStrayRelease
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A video's actions on Android, wherever its tile is: a chat's grid, Home's rows, the all-chats
 * search. Two ways to show them, kept apart:
 *
 * - the detail page ([DetailScreen]), opened by a tap or OK when Settings asks for the page first;
 * - the hold menu ([asMenu]), opened by a long press, a held OK or the remote's info key: a quick
 *   list of every action with Details at its head, a bottom sheet on a phone and a [TvMenu] window
 *   on a television.
 *
 * Both are windows of their own, so Back closes them and the tile behind keeps its focus. Focus
 * starts on the first action, which is safe because the window ignores the release of the OK that
 * opened it.
 *
 * @param asMenu the hold menu rather than the page.
 * @param onOpenDetails the menu's Details line; null leaves it out.
 * @param onSelectVideos non-null in a chat's grid, where picking several videos is possible.
 * @param onOpenChat non-null outside the video's own chat (Home, search).
 * @param onDownload queues the video; see [queueDownloads]. Run by the caller so it outlives this.
 *   The page stays open for it (the button turns into the download's own state where it is);
 *   only the hold menu closes, as it does for every line.
 * @param onRemoved a download was removed from here, so a tile's badge is out of date.
 * @param onOpenItem opens another video's page over this one, for "More like this" and a show's
 *   episodes; the host stacks it ([DetailBackStack]) so Back comes back here.
 * @param onTurnTo turns this page to another copy of the same film, in place.
 * @param onBack set while a page lies under this one: Back, Close and the lines that used to close
 *   the page go back down to it. Null closes the page, as [onDismiss] does.
 * @param returnTo the tile on this page the remote's focus goes back to, after Back from a video
 *   opened by it.
 */
@Composable
internal fun MediaDetailOpened(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    onPlay: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onSelectVideos: (() -> Unit)? = null,
    onOpenChat: (() -> Unit)? = null,
    onRemoved: () -> Unit = {},
    onOpenItem: ((MediaItem) -> Unit)? = null,
    asMenu: Boolean = false,
    onOpenDetails: (() -> Unit)? = null,
    onTurnTo: ((MediaItem) -> Unit)? = onOpenItem,
    onBack: (() -> Unit)? = null,
    returnTo: DetailReturn? = null,
) {
    // Done with this page: back to the one under it, or closed when there is none.
    val leave: () -> Unit = onBack ?: onDismiss
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val touch = isTouch()
    val downloads by OfflineDownloads.active.collectAsStateWithLifecycle()
    // Another copy of the same film on the queue counts too: the film is coming down, and a second
    // size of it is not what anybody asked for. Its Cancel then acts on that copy.
    val downloading = downloads[item.fileId]
        ?: item.versions.firstNotNullOfOrNull { copy -> downloads[copy.fileId]?.takeIf { copy.id != item.id } }
    // Pressed, and still being planned against the disk: the button shows it took the press and
    // a second press does not plan it twice. See [QueueingDownloads].
    val planning by QueueingDownloads.fileIds.collectAsStateWithLifecycle()
    val asking = item.fileId in planning
    val settings = remember(context) { SettingsStore(context) }
    // Watch later is this device's own list, read and written here so every place that opens a
    // video's actions offers it without passing it down.
    val laterStore = remember(context) { WatchLaterStore(context) }
    val laterKeys by remember(laterStore) { laterStore.keys }.collectAsStateWithLifecycle(initialValue = emptySet())
    val later = SettingsStore.progressKey(item.chatId, item.messageId) in laterKeys

    // Asked once the panel is up: which kind of copy is here decides half the actions. The
    // download index first, since TDLib let go of a download's file when it moved. Read again
    // whenever the index changes, so a download that finishes, or one deleted from Downloads
    // while this is open, shows here at once: keyed on the queue alone, the queue let go of a
    // finished file a moment before its record was written, and the page offered Download again.
    val history by remember(settings) { settings.downloadHistory }.collectAsStateWithLifecycle(initialValue = null)
    val kept by produceState(initialValue = Kept(item.locality == MediaItem.Locality.Downloaded, null), item.id, history) {
        val records = history ?: return@produceState
        value = withContext(Dispatchers.IO) {
            fun present(copy: MediaItem) = LocalDownloads.presentFile(
                records.firstOrNull { it.chatId == copy.chatId && it.messageId == copy.messageId },
            ) != null
            val own = present(item)
            Kept(own, if (own) null else item.versions.firstOrNull { it.id != item.id && present(it) })
        }
    }
    val inDownloads = kept.here
    val cachedHere by produceState(initialValue = false, item.fileId, downloading) {
        value = runCatching { Td.isFileCached(item.fileId) }.getOrDefault(false)
    }
    // Room on the volume downloads go to, measured off the UI thread (a statvfs), for the
    // "x GB free" on the download line.
    val freeBytes by produceState(initialValue = 0L) {
        value = withContext(Dispatchers.IO) {
            DiskSpace.read(context, java.io.File(context.filesDir, AndroidPaths.DOWNLOADS)).freeBytes
        }
    }
    // A film posted more than once opens on the copy that would play (see SettingsStore.copyToPlay),
    // so the page, its facts and Play all speak of the same file. Settled by the time it has run
    // once: the copy it turns to picks itself again.
    androidx.compose.runtime.LaunchedEffect(item.id) {
        if (item.versions.size <= 1 || onTurnTo == null) return@LaunchedEffect
        val pick = runCatching { settings.copyToPlay(item) }.getOrNull() ?: return@LaunchedEffect
        if (pick.id != item.id) onTurnTo(pick)
    }
    var confirmingRemove by remember { mutableStateOf(false) }
    // The prompt stays up until the file has gone, and a second press on Remove meanwhile deleted
    // nothing more but said "could not be removed" after "Removed".
    var removing by remember { mutableStateOf(false) }
    if (confirmingRemove) {
        TvConfirm(
            title = s.gridRemoveDownloadTitle,
            message = s.gridRemoveDownloadMessage(item.title),
            confirmLabel = s.commonRemove,
            onConfirm = {
                if (removing) return@TvConfirm
                removing = true
                scope.launch {
                    val record = settings.downloadRecord(item.chatId, item.messageId)
                    val removed = record != null && LocalDownloads.delete(settings, record)
                    com.tmplayer.ui.components.showMessage(context, if (removed) s.gridRemovedDownload else s.gridRemoveDownloadFailed)
                    onRemoved()
                    // The hold menu is gone already; the page goes, back to the one under it.
                    if (asMenu) onDismiss() else leave()
                }
            },
            // Keep it is a change of mind, not a way out: the page comes back, where it was
            // opened from. Only the hold menu, which is gone by now, closes with it.
            onDismiss = { if (asMenu) onDismiss() else confirmingRemove = false },
        )
        return
    }

    // Remove from history: at once with Undo on a touch screen, asked first under a remote,
    // which cannot reach the Undo line.
    val historyRemoval = rememberHistoryRemoval()
    var confirmingHistory by remember { mutableStateOf(false) }
    val removeFromHistory: () -> Unit = {
        historyRemoval.run({ s.browseRemovedFromHistory(it) }) { st, w -> com.tmplayer.data.HistoryEdits.removeVideo(st, w, item) }
    }
    if (confirmingHistory) {
        TvConfirm(
            title = s.browseRemoveFromHistoryTitle,
            message = s.browseRemoveFromHistoryMessage(rememberVideoName(item)),
            detail = s.browseRemoveSelectedDetail,
            confirmLabel = s.commonRemove,
            onConfirm = {
                confirmingHistory = false
                removeFromHistory()
                if (asMenu) onDismiss()
            },
            onDismiss = { if (asMenu) onDismiss() else confirmingHistory = false },
        )
        return
    }

    // The viewer's corrections to this file, and whether a provider's match is showing for it.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsStateWithLifecycle()
    val correction = corrections[item.id]
    val matched = com.tmplayer.ui.online.rememberMatched(item) != null
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        com.tmplayer.player.BookmarkNoteSheet(
            title = s.gridRenameTitle,
            initial = correction?.title ?: com.tmplayer.data.SeriesShelf.filmTitle(item),
            saveLabel = s.gridRenameSave,
            onSave = { text ->
                // Every copy of the tile, so the title holds whichever one it shows. Empty goes
                // back to the file name.
                App.backgroundScope.launch {
                    runCatching { settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(title = text.trim().ifEmpty { null }) } }
                }
                leave()
            },
            // Backing out of the name leaves the page as it was, rather than closing it too.
            onDismiss = { if (asMenu) onDismiss() else renaming = false },
            onClose = { if (asMenu) onDismiss() else renaming = false },
            hint = s.gridRenameHint,
            maxLength = com.tmplayer.data.VideoCorrections.TITLE_MAX,
        )
        return
    }

    // The comments under a channel post (CP81).
    val comments = rememberCommentCount(item)
    var commentsOpen by remember { mutableStateOf(false) }
    if (commentsOpen) {
        val list = remember(item.id) { PagedVideos.comments(scope, item.chatId, item.messageId) }
        MoreVideosSheet(
            title = s.detailCommentsVideos,
            note = s.detailCommentsCount(comments),
            list = list,
            empty = s.detailCommentsNone,
            round = false,
            // The comments live in the discussion group, whose name the window looks up. The list
            // stays under the player, so Back from a comment's video comes back to it, and from
            // there to the page; only the hold menu goes.
            onPlay = { video -> if (asMenu) onDismiss(); LinkRequests.play(video) },
            onDismiss = { commentsOpen = false },
        )
        return
    }

    val actions = DetailActions.of(
        DetailContext(
            canBeSaved = item.canBeSaved,
            resumeMs = watched?.positionMs ?: 0L,
            finished = finished,
            watchLater = later,
            inDownloads = inDownloads,
            copyInDownloads = kept.copy != null,
            cached = cachedHere,
            downloading = downloading != null,
            canSelect = onSelectVideos != null,
            // A television has no other app to hand a file to and no clipboard worth a link.
            canHandOff = touch,
            hasClipboard = touch,
            outsideChat = onOpenChat != null,
            canCorrect = true,
            grouped = item.versions.size > 1,
            separated = correction?.separate == true,
            matched = matched,
            wrongMatch = correction?.wrongMatch == true,
            comments = comments,
            // The phone's gallery: Android 10 and later, where MediaStore needs no permission. A
            // television has no gallery anyone looks at.
            canExport = touch && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
            canRemoveFromHistory = true,
        ),
    )
    val free = DiskInfo.freeLabel(freeBytes)
    // A Save to gallery under way, for this video's ring, keyed as the Downloads screen keys its
    // rows so a copy started there rings here too.
    val exporting by com.tmplayer.data.FolderExport.state.collectAsStateWithLifecycle()
    val exportKey = "kept_${item.chatId}_${item.messageId}"
    val rows = actions.map { action ->
        val run: () -> Unit = when (action) {
            // The page stays under the player, so Back from the video (or from a video that would
            // not play) comes back to it, as a streaming app's title page does; it closed before,
            // and the viewer landed on the list they had opened it from. The hold menu still goes.
            DetailAction.Resume, DetailAction.Play -> { { if (asMenu) onDismiss(); onPlay() } }
            // The saved position goes first, so the player opening a moment later finds nothing
            // to resume from.
            DetailAction.StartOver -> { {
                scope.launch {
                    runCatching { settings.clearResumePosition(item.chatId, item.messageId, item) }
                    if (asMenu) onDismiss()
                    onPlay()
                }
            } }
            // On the page these turn over in place, as the download button does: closing it sent
            // the viewer back to the list for a tick they could have seen right here.
            DetailAction.MarkWatched -> { { if (asMenu) onDismiss(); onSetWatched(true) } }
            DetailAction.MarkUnwatched -> { { if (asMenu) onDismiss(); onSetWatched(false) } }
            // The page stays on a touch screen: Resume turns into Play, and the line says Undo.
            DetailAction.RemoveFromHistory -> { {
                if (touch) {
                    removeFromHistory()
                    if (asMenu) onDismiss()
                } else {
                    confirmingHistory = true
                }
            } }
            DetailAction.AddWatchLater, DetailAction.RemoveWatchLater -> { {
                val add = action == DetailAction.AddWatchLater
                // On the app's scope: the hold menu closes at once and takes its own scope with it.
                App.backgroundScope.launch {
                    runCatching {
                        if (add) {
                            laterStore.add(WatchLaterRecord.of(item, chatTitle, System.currentTimeMillis()))
                        } else {
                            laterStore.remove(item.chatId, item.messageId)
                        }
                    }
                }
                com.tmplayer.ui.components.showMessage(context, if (add) s.detailWatchLaterAdded(item.title) else s.detailWatchLaterRemoved(item.title))
                if (asMenu) onDismiss()
            } }
            // The page stays where it is: the button turns into the download's state (the ring,
            // then Downloaded), the message says what happened, and a remote keeps its focus on
            // the button. Closing it here sent the viewer back to Home for no reason. A press
            // while the first is still being planned is the same press again, so it is let go.
            DetailAction.Download, DetailAction.SaveToDownloads -> { {
                if (!asking) onDownload()
                if (asMenu) onDismiss()
            } }
            // On the page it only says how things stand; the copy that is kept opens from it.
            DetailAction.InDownloads -> when {
                asMenu -> onDismiss
                kept.copy != null && onTurnTo != null -> { {
                    val copy = kept.copy
                    if (copy != null) scope.launch { runCatching { settings.setCopyPick(copy) }; onTurnTo(copy) }
                } }
                else -> { {} }
            }
            DetailAction.RemoveDownload -> { { confirmingRemove = true } }
            // The file on the queue, which is another copy's when that is the one coming down.
            DetailAction.CancelDownload -> { {
                OfflineDownloads.cancel(context, downloading?.fileId ?: item.fileId)
                if (asMenu) onDismiss()
            } }
            DetailAction.SelectVideos -> { { onDismiss(); onSelectVideos?.invoke() } }
            // Back from the other app, or after the copy, the page is still there.
            DetailAction.Share -> { { scope.launch { shareVideo(context, item, send = true); if (asMenu) onDismiss() } } }
            DetailAction.OpenElsewhere -> { { scope.launch { shareVideo(context, item, send = false); if (asMenu) onDismiss() } } }
            DetailAction.CopyLink -> { { scope.launch { MessageLink.copy(context, item.chatId, item.messageId); if (asMenu) onDismiss() } } }
            DetailAction.OpenChat -> { { onDismiss(); onOpenChat?.invoke() } }
            DetailAction.SplitCopy, DetailAction.JoinCopies -> { {
                val apart = action == DetailAction.SplitCopy
                App.backgroundScope.launch { runCatching { settings.correct(listOf(item)) { it.copy(separate = apart) } } }
                leave()
            } }
            DetailAction.WrongMatch, DetailAction.UseMatch -> { {
                val wrong = action == DetailAction.WrongMatch
                App.backgroundScope.launch { runCatching { settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(wrongMatch = wrong) } } }
                leave()
            } }
            DetailAction.Rename -> { { renaming = true } }
            DetailAction.CommentVideos -> { { commentsOpen = true } }
            DetailAction.ExportCopy -> { {
                // A second press while this video is copying is the same press again.
                if (exportKey !in exporting.keys) {
                    com.tmplayer.ui.components.showMessage(context, s.keepExporting(item.title))
                    // Through FolderExport's run, as the Downloads screen's Save to gallery: a
                    // foreground service with the progress and Cancel in its notification, so the
                    // copy goes on with the page (or the app) gone, and the end is said in a toast
                    // and the shade. Off the main thread, with the application's context: the file
                    // is looked up on the disk and the chat is asked whether it allows saving.
                    val app = context.applicationContext
                    App.backgroundScope.launch(Dispatchers.IO) {
                        val record = runCatching { settings.downloadRecord(item.chatId, item.messageId) }.getOrNull()
                        val said = if (record == null || LocalDownloads.presentFile(record) == null) {
                            s.downloadsFileGone
                        } else {
                            com.tmplayer.data.FolderExport.saveToGalleryNow(
                                app,
                                com.tmplayer.data.FolderExport.Candidate(exportKey, record, item.fileId, item.title, whole = true),
                                settings,
                            )
                        }
                        if (said != null) withContext(Dispatchers.Main) { com.tmplayer.ui.components.showMessage(app, said) }
                    }
                }
                if (asMenu) onDismiss()
            } }
        }
        when {
            action == DetailAction.CancelDownload && downloading != null -> cancelRow(s, downloading, run)
            // Being copied to the gallery: the button's ring fills as it goes.
            action == DetailAction.ExportCopy && exportKey in exporting.keys && exporting.progress != null ->
                detailRow(action, s, watched, chatTitle, run).copy(progress = exporting.progress?.overall ?: 0f)
            // Taken, and on its way to the queue: an empty ring at once, so the press shows.
            asking && (action == DetailAction.Download || action == DetailAction.SaveToDownloads) ->
                detailRow(action, s, watched, chatTitle, run, free = free).copy(progress = 0f)
            // Kept already: said, dimmed, and pressing it does nothing (it stays reachable, so the
            // remote's focus stays on it once Download has turned into it). Another copy that is
            // kept is the exception, being a way to that copy.
            action == DetailAction.InDownloads && !asMenu && !(kept.copy != null && onTurnTo != null) ->
                detailRow(action, s, watched, chatTitle, run).copy(label = s.downloadsDownloaded, enabled = false)
            else -> detailRow(action, s, watched, chatTitle, run, free = free)
        }
    }

    if (asMenu) {
        HoldMenu(item, chatTitle, watched, rows, onDismiss, onOpenDetails)
    } else {
        val bookmarkStore = remember(context) { BookmarkStore(context) }
        val bookmarks by remember(item.chatId, item.messageId) { bookmarkStore.bookmarks(item.chatId, item.messageId) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val progress by remember(settings) { settings.watchProgress }.collectAsStateWithLifecycle(initialValue = emptyMap())
        // Remembered for the video before the page turns, or the turn would pick the old copy again.
        val onPickCopy: ((MediaItem) -> Unit)? = onTurnTo?.let { open ->
            { copy: MediaItem -> scope.launch { runCatching { settings.setCopyPick(copy) }; open(copy) }; Unit }
        }
        val started: (MediaItem) -> Boolean = { SettingsStore.progressKey(it.chatId, it.messageId) in progress }
        DetailScreen(item, chatTitle, watched, finished, rows, onDismiss, onBack, returnTo, onOpenItem, bookmarks, onPickCopy, started) { mark ->
            // The player takes the position on opening this video, in place of its resume point.
            BookmarkJump.request(item.chatId, item.messageId, mark.positionMs)
            // The page stays under the player, as it does for Play.
            onPlay()
        }
    }
}

/**
 * The hold menu: what the file is in one line under its name (length, size, picture, codec and
 * chat), then Play or Resume, Details, and the rest of the actions as the page has them.
 */
@Composable
private fun HoldMenu(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
    onOpenDetails: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val facts = remember(item.id) { MediaFacts.of(item) }
    val subtitle = listOfNotNull(
        item.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
        item.sizeBytes.takeIf { it > 0 }?.let { com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(it)) },
        com.tmplayer.data.VideoCopies.qualityLabel(item) ?: facts.resolution,
        facts.videoCodec,
        chatTitle.ifBlank { null },
    ).joinToString("  ·  ").ifBlank { null }
    val details = onOpenDetails?.let { open ->
        MenuAction(s.detailOpenDetails, TmIcons.Info, s.detailOpenDetailsDetail) { onDismiss(); open() }
    }
    val actions = rows.map { MenuAction(it.label, it.icon, it.detail, it.destructive, it.onSelect) }
    TvMenu(
        // The name the tile it was held on goes by, not the file's release name.
        title = rememberVideoName(item),
        subtitle = subtitle,
        // Details right under the way to play, where a viewer holding a tile to learn more looks.
        actions = if (details == null) actions else actions.take(1) + details + actions.drop(1),
        onDismiss = onDismiss,
    )
}

/** A queued download's line, worded for the stage it is at. */
private fun cancelRow(s: Messages, entry: OfflineDownloads.Progress, run: () -> Unit): DetailRow {
    val base = detailRow(DetailAction.CancelDownload, s, null, "", run)
    return base.copy(
        label = when (entry.stage) {
            OfflineDownloads.Stage.Queued -> s.gridCancelQueued
            OfflineDownloads.Stage.Paused -> s.gridCancelPaused
            OfflineDownloads.Stage.Failed -> s.gridCancelFailed
            OfflineDownloads.Stage.Offline -> s.gridCancelOffline
            OfflineDownloads.Stage.NoWifi -> s.gridCancelNoWifi
            OfflineDownloads.Stage.Moving -> s.gridCancelRunning
            OfflineDownloads.Stage.Running -> s.gridCancelRunning
        },
        detail = when (entry.stage) {
            OfflineDownloads.Stage.Queued -> s.gridQueuedDetail
            OfflineDownloads.Stage.Offline -> s.gridOfflineDetail
            OfflineDownloads.Stage.NoWifi -> s.gridNoWifiDetail
            else -> entry.fraction?.let { s.gridProgressKept(s.formatter.percent(it.toDouble())) }
        },
        // The ring round the button, for the download that is actually moving.
        progress = entry.fraction.takeIf {
            entry.stage == OfflineDownloads.Stage.Running || entry.stage == OfflineDownloads.Stage.Moving
        },
    )
}

/**
 * The detail as a screen of its own over everything ([MediaDetailScreen]), in a window so Back
 * closes it and the tile behind keeps its focus. Opened by a hold on a television, so the release
 * of that OK is ignored rather than choosing the focused first action.
 */
@Composable
private fun DetailScreen(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    returnTo: DetailReturn?,
    onOpenItem: ((MediaItem) -> Unit)?,
    bookmarks: List<Bookmark>,
    onPickCopy: ((MediaItem) -> Unit)?,
    copyStarted: (MediaItem) -> Boolean,
    onOpenBookmark: (Bookmark) -> Unit,
) {
    val touch = isTouch()
    val first = remember { FocusRequester() }
    // On a phone the page grows out of the tile that was tapped, and folds back towards it.
    val motion = com.tmplayer.ui.components.rememberPageMotion(onDismiss)
    // With a page under this one, Back turns straight to it, in the same window: the fold back
    // towards the tile is for leaving the last page.
    val back = onBack ?: motion.close
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        motion.Frame {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // Keyed by the video, so one opened from "More like this" starts at its top, and the one
        // Back comes down to starts afresh, its focus on the tile it was left by.
        androidx.compose.runtime.key(item.id) {
            androidx.compose.runtime.CompositionLocalProvider(LocalDetailReturn provides returnTo) {
            MediaDetailScreen(
                item = item,
                chatTitle = chatTitle,
                watched = watched,
                finished = finished,
                rows = rows,
                onClose = back,
                modifier = if (touch) Modifier else Modifier.ignoreStrayRelease(),
                firstAction = if (touch) null else first,
                onOpenItem = onOpenItem,
                bookmarks = bookmarks,
                onOpenBookmark = onOpenBookmark,
                onPickCopy = onPickCopy,
                copyStarted = copyStarted,
                // Cast (CP73) at the top end, opposite the round Back on a phone and before Close
                // on a tablet: picked here, Play then goes straight to the TV.
                topEndAction = if (touch) ({ com.tmplayer.cast.CastPageButton() }) else null,
            )
            }
        }
        // Over the page, which is a window of its own above the app's.
        TopMessageHost()
        }
        }
    }
}

/** Whether this video is in the Downloads folder ([here]), or else which other copy of it is. */
private data class Kept(val here: Boolean, val copy: MediaItem?)
