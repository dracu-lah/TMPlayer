package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tmplayer.data.ClearAllConfirm
import com.tmplayer.ui.i18n.LocalStrings

/**
 * "Clear everything in this list", asked first on the desktop ([ClearAllConfirm], HX59), the twin of
 * the Android `rememberClearAllConfirm`: [ConfirmDialog] says what goes and what stays, focus starts
 * on Cancel (so Enter pressed out of habit keeps everything) and Esc cancels. Returns what the Clear
 * button calls; the prompt is drawn by this call.
 *
 * Pressing again while it is up asks once, an empty list asks nothing, and the question closes by
 * itself when the list empties meanwhile, clearing nothing.
 */
@Composable
fun rememberClearAllConfirm(
    /** The cards that would go, read again when the question is answered. */
    count: Int,
    title: String,
    message: String,
    detail: String,
    onClear: () -> Unit,
): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val countNow by rememberUpdatedState(count)
    val clear by rememberUpdatedState(onClear)
    LaunchedEffect(count, asking) { if (asking && !ClearAllConfirm.stillAsking(asking, count)) asking = false }
    if (asking) {
        ConfirmDialog(
            title = title,
            message = message,
            detail = detail,
            confirmLabel = LocalStrings.current.commonClear,
            onConfirm = {
                asking = false
                if (ClearAllConfirm.confirmClears(countNow)) clear()
            },
            onDismiss = { asking = false },
        )
    }
    return { if (ClearAllConfirm.press(asking, countNow)) asking = true }
}

/** The cards Clear history takes away: Continue watching and Watched, copies of one video folded into one. */
@Composable
internal fun historyCardCount(state: ShellState): Int {
    val continuing by state.settings.continueWatching.collectAsState(initial = emptyList())
    val watched by state.watched.history.collectAsState(initial = emptyList())
    return remember(continuing, watched) {
        ClearAllConfirm.historyCards(continuing, watched)
    }
}
