package com.tmplayer.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * The desktop's back dispatcher: whoever registered last, and is enabled, takes the press.
 *
 * A window has no system Back, so the shell turns the keys and buttons that mean "back" on a
 * desktop into [handleBack] (see [backInput]). Screens never talk to this directly; they call
 * [BackHandler] exactly as they would on Android, and the order they were composed in is the order
 * a press unwinds them: an open search field before the page under it, the page before the shell.
 */
class BackStack {

    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    internal fun add(entry: Entry) {
        entries += entry
    }

    internal fun remove(entry: Entry) {
        entries -= entry
    }

    /** Offers a back press to the newest enabled handler. False when nobody wanted it. */
    fun handleBack(): Boolean {
        val taker = entries.lastOrNull { it.enabled } ?: return false
        taker.onBack()
        return true
    }

    /** Whether a press would be taken by anyone. */
    val canGoBack: Boolean get() = entries.any { it.enabled }
}

/** The window's [BackStack]. A tree with none provided has nothing to go back to. */
val LocalBackStack = staticCompositionLocalOf<BackStack?> { null }

/**
 * Takes Esc, Backspace, Alt+Left, Cmd+[ and the mouse's back button while [enabled], and calls
 * [onBack] instead. The same call the shared screens make on Android.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val stack = LocalBackStack.current ?: return
    val current by rememberUpdatedState(onBack)
    val entry = remember(stack) { BackStack.Entry(enabled) { current() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(stack, entry) {
        stack.add(entry)
        onDispose { stack.remove(entry) }
    }
}

/**
 * Whether [event] means "back" on a desktop: Esc, Backspace, Alt+Left (Windows, Linux) or
 * Cmd+[ (macOS).
 *
 * Read on the way up, after the focused control has had its turn: a text field keeps Backspace
 * for itself, so deleting a character never leaves the page.
 */
fun isBackKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed
    return when (event.key) {
        Key.Escape, Key.Backspace -> plain
        Key.DirectionLeft -> event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
        Key.LeftBracket -> event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed
        else -> false
    }
}

/**
 * Routes the window's back keys and the mouse's back button into [stack]. Put on the root of the
 * window's content.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.backInput(stack: BackStack): Modifier = this
    .onKeyEvent { event -> isBackKey(event) && stack.handleBack() }
    .onPointerEvent(PointerEventType.Press) { event ->
        if (event.button == PointerButton.Back) stack.handleBack()
    }
