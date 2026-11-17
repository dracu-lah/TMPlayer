package com.tmplayer.desktop.ui

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
 * Cancel on a download, asked first when it is running ([CancelDownloadConfirm]), the desktop's
 * twin of the Android `rememberStopDownload`: "Stop this download?", what happens, and focus on
 * Keep downloading (Esc keeps too). Returns what the Cancel button, menu line or pane button calls;
 * the prompt is drawn by this call.
 *
 * A second press while the prompt is up changes nothing, the prompt closes by itself when the
 * download finishes or goes meanwhile, and [stop] gets the row as it is when Stop is pressed,
 * never a finished one. [active] is the queue, a parameter so a test can hand in its own.
 */
@Composable
fun rememberStopDownload(
    active: StateFlow<Map<Int, OfflineDownloads.Progress>> = OfflineDownloads.active,
    stop: (OfflineDownloads.Progress) -> Unit,
): (OfflineDownloads.Progress) -> Unit {
    val s = LocalStrings.current
    var asked by remember { mutableStateOf<OfflineDownloads.Progress?>(null) }
    asked?.let { row ->
        LaunchedEffect(row.fileId) {
            active.first { CancelDownloadConfirm.liveRow(row.fileId, it) == null }
            asked = null
        }
        ConfirmDialog(
            title = s.downloadsCancelConfirmTitle,
            message = s.downloadsCancelConfirmMessage(row.title),
            confirmLabel = s.downloadsCancelConfirmStop,
            cancelLabel = s.downloadsCancelConfirmKeep,
            onConfirm = {
                asked = null
                CancelDownloadConfirm.liveRow(row.fileId, active.value)?.let(stop)
            },
            onDismiss = { asked = null },
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
