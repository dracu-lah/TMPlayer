package com.tmplayer.ui.browse

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The detail panel (R5) on Android: a bottom sheet on a phone, a side pane on a television. Opened
 * by a long press, a hold of OK or the remote's info key on a video's tile, wherever the tile is: a
 * chat's grid, Home's rows, the all-chats search.
 *
 * The television's pane is a window of its own, like the menus, so Back closes it and the tile
 * behind keeps its focus; the pane holds focus only while it is open. Focus starts on the first
 * action, which is safe because the window ignores the release of the OK that opened it.
 *
 * @param onSelectVideos non-null in a chat's grid, where picking several videos is possible.
 * @param onOpenChat non-null outside the video's own chat (Home, search).
 * @param onDownload queues the video; see [queueDownloads]. Run by the caller so it outlives this.
 * @param onRemoved a download was removed from here, so a tile's badge is out of date.
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

    if (touch) {
        DetailSheet(item, chatTitle, watched, finished, rows, onDismiss)
    } else {
        DetailPane(item, chatTitle, watched, finished, rows, onDismiss)
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
) {
    // Half open first, which shows the picture, the name and the first actions; a drag up shows
    // the rest of the actions and the facts.
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = Modifier.border(floatingBorder(), Floating.SheetShape),
        shape = Floating.SheetShape,
        containerColor = FloatingTone.sheet,
    ) {
        MediaDetailPanel(
            item = item,
            chatTitle = chatTitle,
            watched = watched,
            finished = finished,
            rows = rows,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The television's side pane: the right edge of the screen, full height, inside the overscan, in a
 * window of its own so the grid behind keeps its focus and Back closes the pane.
 */
@Composable
private fun DetailPane(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    // This pane is opened by a hold, so OK is still down as it appears; ignoreRelease keeps that
    // release from choosing the focused first action.
    FloatingWindow(onDismiss = onDismiss, ignoreRelease = true) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(DETAIL_PANE_WIDTH)
                .floatingSurface(FloatingTone.sheet, PANE_SHAPE),
        ) {
            MediaDetailPanel(
                item = item,
                chatTitle = chatTitle,
                watched = watched,
                finished = finished,
                rows = rows,
                firstAction = first,
                compactArt = true,
                scroll = scroll,
                contentPadding = PaddingValues(start = 28.dp, end = Tv.SafeH, top = Tv.SafeV, bottom = 12.dp),
                modifier = Modifier.weight(1f),
            )
            Text(
                s.detailCloseHint,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier.padding(start = 28.dp, end = Tv.SafeH, bottom = Tv.SafeV, top = 4.dp),
            )
        }
        LaunchedEffect(Unit) {
            runCatching { first.requestFocus() }
            // Focus asks to be brought into view with room to spare, which scrolls the picture
            // and the name off the top of a pane they fit in. Back to the top once it has.
            delay(FOCUS_SETTLE_MS)
            scroll.scrollTo(0)
        }
    }
}

private const val FOCUS_SETTLE_MS = 120L

/** Rounded where the pane meets the screen, square against the edge it is pinned to. */
private val PANE_SHAPE = RoundedCornerShape(topStart = Corner.ExtraLarge, bottomStart = Corner.ExtraLarge)
