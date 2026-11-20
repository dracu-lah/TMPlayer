package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tmplayer.data.ClearAllConfirm
import com.tmplayer.ui.i18n.LocalStrings

/**
 * "Clear everything in this list", asked first on a phone and under a remote alike ([ClearAllConfirm],
 * HX59): [TvConfirm] says what goes and what stays, and focus starts on Cancel. Returns what the
 * Clear button calls; the prompt is drawn by this call.
 *
 * Pressing again while it is up asks once, an empty list asks nothing, and the question closes by
 * itself when the list empties meanwhile, clearing nothing. [onClear] runs when the answer comes,
 * never for a list that is empty then.
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
        TvConfirm(
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
