package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

// A held D-pad's repeat that has waited longer than a couple of frames to be handled (Leanback caps
// the moves it queues the same way, GridLayoutManager's pending moves): on a stick whose frames take
// 40 ms, repeats pile up, and once the button is let go the focus kept moving.
//
// The clock and the native event are Android's, so the check itself is [platformStaleRepeat], one
// per platform under the same name (the way Haptics.kt does it): ui/src/androidMain reads the key
// event's age, ui/src/jvmMain (desktop) and ui/src/iosMain have no held remote and answer false.
// What is shared is the rule: which keys and the modifier. :app's StaleRepeat.kt (a different file name, so no class clash) forwards to these.

/** How old a repeat may be and still move the focus: about two frames on a slow stick. */
const val STALE_REPEAT_MS = 80L

/** Whether [event] is a stale repeat of an arrow key, the keys that move focus in a grid or list. */
fun staleArrowKeyRepeat(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = event.key
    val arrow = key == Key.DirectionDown || key == Key.DirectionUp || key == Key.DirectionLeft || key == Key.DirectionRight
    return arrow && platformStaleRepeat(event)
}

/**
 * Drops a held arrow key's repeats that have waited too long ([platformStaleRepeat]), so a long list
 * or grid on a slow TV stops where the viewer let go instead of running on. Put it on the list,
 * above its items: it sees the key before they do. A first press and every other key pass untouched.
 * A no-op on the desktop and iOS.
 */
fun Modifier.dropStaleRepeats(): Modifier = onPreviewKeyEvent { staleArrowKeyRepeat(it) }
