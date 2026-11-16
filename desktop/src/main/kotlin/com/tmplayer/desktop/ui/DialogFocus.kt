package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Puts the keyboard on [requester] (a dialog's first field) once the dialog's window is attached:
 * on the frame it first draws it is not, so the request is tried for a few frames. Run again when
 * [key] changes, for a dialog whose first control arrives later (a list that loads).
 */
@Composable
internal fun FocusWhenShown(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}
