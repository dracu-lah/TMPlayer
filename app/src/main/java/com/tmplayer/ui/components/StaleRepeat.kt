package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A held D-pad's repeat that has waited longer than a couple of frames to be handled. Leanback
 * caps the moves it queues the same way (GridLayoutManager's pending moves): on a stick whose
 * frames take 40 ms, repeats pile up, and once the button is let go the focus kept moving.
 */
internal fun staleRepeat(event: KeyEvent): Boolean {
    val native = event.nativeKeyEvent
    return native.repeatCount > 0 && android.os.SystemClock.uptimeMillis() - native.eventTime > STALE_REPEAT_MS
}

/** How old a repeat may be and still move the focus: about two frames on a slow stick. */
internal const val STALE_REPEAT_MS = 80L

/** Whether [event] is a stale repeat of an arrow key, the keys that move focus in a grid or list. */
internal fun staleArrowRepeat(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = event.key
    val arrow = key == Key.DirectionDown || key == Key.DirectionUp || key == Key.DirectionLeft || key == Key.DirectionRight
    return arrow && staleRepeat(event)
}

/**
 * Drops a held arrow key's repeats that have waited too long ([staleRepeat]), so a long list or
 * grid on a slow TV stops where the viewer let go instead of running on. Put it on the grid, above
 * its items: it sees the key before they do. A first press and every other key pass untouched.
 */
fun Modifier.dropStaleArrowRepeats(): Modifier = onPreviewKeyEvent { staleArrowRepeat(it) }
