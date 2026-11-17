package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tmplayer.data.CancelDownloadConfirm
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Cancel on a download, asked first when it is running ([CancelDownloadConfirm]): "Stop this
 * download?" says what happens (what was fetched is deleted, nothing leaves Telegram) and focus
 * starts on Keep downloading, so a careless remote press is the cheap mistake. Returns what the
 * Cancel button calls; the prompt is drawn by this call.
 *
 * A second press while the prompt is up changes nothing. The prompt closes by itself when the
 * download finishes or goes meanwhile, and [stop] is given the row as it is when Stop is pressed
 * (paused by then, say), never a finished one. [active] is the queue, a parameter so a test can
 * hand in its own.
 */
@Composable
fun rememberStopDownload(
    active: StateFlow<Map<Int, OfflineDownloads.Progress>> = OfflineDownloads.active,
    /** Keep downloading, Back or a tap outside: a hold menu that waited for the answer closes here. */
    onKeep: () -> Unit = {},
    stop: (OfflineDownloads.Progress) -> Unit,
): (OfflineDownloads.Progress) -> Unit {
    val s = LocalStrings.current
    var asked by remember { mutableStateOf<OfflineDownloads.Progress?>(null) }
    asked?.let { row ->
        LaunchedEffect(row.fileId) {
            active.first { CancelDownloadConfirm.liveRow(row.fileId, it) == null }
            asked = null
            onKeep()
        }
        TvConfirm(
            title = s.downloadsCancelConfirmTitle,
            message = s.downloadsCancelConfirmMessage(row.title),
            confirmLabel = s.downloadsCancelConfirmStop,
            cancelLabel = s.downloadsCancelConfirmKeep,
            onConfirm = {
                asked = null
                CancelDownloadConfirm.liveRow(row.fileId, active.value)?.let(stop)
            },
            onDismiss = {
                asked = null
                onKeep()
            },
        )
    }
    return { row ->
        if (!CancelDownloadConfirm.needed(row)) {
            stop(row)
        } else if (asked == null) {
            asked = row
        }
    }
}
