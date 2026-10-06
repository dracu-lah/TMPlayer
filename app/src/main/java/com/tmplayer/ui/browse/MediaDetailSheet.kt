package com.tmplayer.ui.browse

import android.widget.Toast
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
import com.tmplayer.data.WatchPoint
import com.tmplayer.data.cancel
import com.tmplayer.i18n.Messages
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.tmplayer.data.MediaFacts
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.ignoreStrayRelease
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
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
 * @param onRemoved a download was removed from here, so a tile's badge is out of date.
 * @param onOpenItem opens another video's detail in place of this one, for "More like this".
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
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val touch = isTouch()
    val downloads by OfflineDownloads.active.collectAsStateWithLifecycle()
    val downloading = downloads[item.fileId]
    val settings = remember(context) { SettingsStore(context) }

    // Asked once the panel is up: which kind of copy is here decides half the actions. The
    // download index first, since TDLib let go of a download's file when it moved.
    val inDownloads by produceState(initialValue = item.locality == MediaItem.Locality.Downloaded, item.id, downloading) {
        value = LocalDownloads.fileFor(settings, item.chatId, item.messageId) != null
    }
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
    var confirmingRemove by remember { mutableStateOf(false) }
    if (confirmingRemove) {
        TvConfirm(
            title = s.gridRemoveDownloadTitle,
            message = s.gridRemoveDownloadMessage(item.title),
            confirmLabel = s.commonRemove,
            onConfirm = {
                scope.launch {
                    val record = settings.downloadRecord(item.chatId, item.messageId)
                    val removed = record != null && LocalDownloads.delete(settings, record)
                    Toast.makeText(
                        context,
                        if (removed) s.gridRemovedDownload else s.gridRemoveDownloadFailed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    onRemoved()
                    onDismiss()
                }
            },
            onDismiss = onDismiss,
        )
        return
    }

    val actions = DetailActions.of(
        DetailContext(
            canBeSaved = item.canBeSaved,
            resumeMs = watched?.positionMs ?: 0L,
            finished = finished,
            inDownloads = inDownloads,
            cached = cachedHere,
            downloading = downloading != null,
            canSelect = onSelectVideos != null,
            // A television has no other app to hand a file to and no clipboard worth a link.
            canHandOff = touch,
            hasClipboard = touch,
            outsideChat = onOpenChat != null,
        ),
    )
    val free = DiskInfo.freeLabel(freeBytes)
    val rows = actions.map { action ->
        val run: () -> Unit = when (action) {
            DetailAction.Resume, DetailAction.Play -> { { onDismiss(); onPlay() } }
            // The saved position goes first, so the player opening a moment later finds nothing
            // to resume from.
            DetailAction.StartOver -> { {
                scope.launch {
                    runCatching { settings.clearResumePosition(item.chatId, item.messageId) }
                    onDismiss()
                    onPlay()
                }
            } }
            DetailAction.MarkWatched -> { { onDismiss(); onSetWatched(true) } }
            DetailAction.MarkUnwatched -> { { onDismiss(); onSetWatched(false) } }
            DetailAction.Download, DetailAction.SaveToDownloads -> { { onDownload(); onDismiss() } }
            DetailAction.InDownloads -> onDismiss
            DetailAction.RemoveDownload -> { { confirmingRemove = true } }
            DetailAction.CancelDownload -> { { OfflineDownloads.cancel(context, item.fileId); onDismiss() } }
            DetailAction.SelectVideos -> { { onDismiss(); onSelectVideos?.invoke() } }
            DetailAction.Share -> { { scope.launch { shareVideo(context, item, send = true); onDismiss() } } }
            DetailAction.OpenElsewhere -> { { scope.launch { shareVideo(context, item, send = false); onDismiss() } } }
            DetailAction.CopyLink -> { { scope.launch { MessageLink.copy(context, item.chatId, item.messageId); onDismiss() } } }
            DetailAction.OpenChat -> { { onDismiss(); onOpenChat?.invoke() } }
        }
        if (action == DetailAction.CancelDownload && downloading != null) {
            cancelRow(s, downloading, run)
        } else {
            detailRow(action, s, watched, chatTitle, run, free = free)
        }
    }

    if (asMenu) {
        HoldMenu(item, chatTitle, watched, rows, onDismiss, onOpenDetails)
    } else {
        DetailScreen(item, chatTitle, watched, finished, rows, onDismiss, onOpenItem)
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
        item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
        facts.resolution,
        facts.videoCodec,
        chatTitle.ifBlank { null },
    ).joinToString("  ·  ").ifBlank { null }
    val details = onOpenDetails?.let { open ->
        MenuAction(s.detailOpenDetails, Icons.Filled.Info, s.detailOpenDetailsDetail) { onDismiss(); open() }
    }
    val actions = rows.map { MenuAction(it.label, it.icon, it.detail, it.destructive, it.onSelect) }
    TvMenu(
        title = item.title,
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
    onOpenItem: ((MediaItem) -> Unit)?,
) {
    val touch = isTouch()
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Keyed by the video, so one opened from "More like this" starts at its top.
        androidx.compose.runtime.key(item.id) {
            MediaDetailScreen(
                item = item,
                chatTitle = chatTitle,
                watched = watched,
                finished = finished,
                rows = rows,
                onClose = onDismiss,
                modifier = if (touch) Modifier else Modifier.ignoreStrayRelease(),
                firstAction = if (touch) null else first,
                onOpenItem = onOpenItem,
            )
        }
    }
}
