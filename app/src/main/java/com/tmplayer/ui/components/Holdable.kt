package com.tmplayer.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.FormFactor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * OK opens the thing; holding OK opens its menu.
 *
 * On the remote this is written out of key events: `combinedClickable`'s long press does not fire
 * for a D-pad hold on the target device, and it swallows the ordinary click as well. Auto-repeat is
 * the signal used instead, since the first repeat arrives exactly at the system's own long-press
 * interval and needs no timer of ours. Click therefore fires on key up, not key down, so that a
 * hold does not also count as a press.
 *
 * A finger sends no key events, and the semantics below describe the gestures without performing
 * them, so touch goes to `combinedClickable`: its long press does work on a real screen, and it
 * brings the press ripple that focus colour alone cannot stand in for.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.holdable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: () -> Unit,
): Modifier {
    if (!FormFactor.isTv(LocalContext.current)) {
        return combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onLongClick = onHold,
            onClick = onClick,
        )
    }

    // Not Compose state: nothing renders from it, and it is written from inside a key callback
    // whose only job is to remember, until the key comes back up, that this press was a hold.
    val held = remember { booleanArrayOf(false) }

    return this
        .semantics {
            role = Role.Button
            onClick { onClick(); true }
            onLongClick { onHold(); true }
        }
        .onKeyEvent { event ->
            if (event.key !in OK_KEYS) return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    // A hold opens a menu in its own window, which takes the key up with it, so
                    // the release below may never arrive here to clear this. Every fresh press
                    // starts at repeat zero, and that is the reliable place to start again.
                    if (event.nativeKeyEvent.repeatCount == 0) held[0] = false
                    if (!held[0] && event.nativeKeyEvent.repeatCount > 0) {
                        held[0] = true
                        onHold()
                    }
                    true
                }

                KeyEventType.KeyUp -> {
                    if (!held[0]) onClick()
                    held[0] = false
                    true
                }

                else -> false
            }
        }
        .focusable(interactionSource = interactionSource)
}

/**
 * A plain press, with the feedback each device understands.
 *
 * A television already marks the focused thing with a border and a colour, so a ripple there is
 * noise. A phone has no focus to show, and without the ripple a tap has no visible answer until
 * whatever it started finishes.
 */
@Composable
fun Modifier.pressable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = if (FormFactor.isTv(LocalContext.current)) null else LocalIndication.current,
    role = Role.Button,
    onClick = onClick,
)

/**
 * Ignores the release of an OK that was already down when this window appeared.
 *
 * A window opened by [holdable] arrives while the viewer is still holding OK. The release then
 * goes to the new window, where it would land on whatever has just taken focus and choose it: a
 * hold would open a menu and pick its first line in one gesture. Only a release that follows a
 * press seen here is a press by the viewer, so that is the only one let through.
 *
 * Preview rather than ordinary key handling: the stray release has to be stopped before it
 * reaches the focused row, not after.
 */
@Composable
fun Modifier.ignoreStrayRelease(): Modifier {
    val pressed = remember { booleanArrayOf(false) }
    return onPreviewKeyEvent { event ->
        if (event.key !in OK_KEYS) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                // Only the start of a press counts. The hold that opened this window keeps
                // repeating into it, and treating a repeat as a press would arm the release
                // this exists to stop.
                if (event.nativeKeyEvent.repeatCount == 0) pressed[0] = true
                !pressed[0]
            }

            KeyEventType.KeyUp -> !pressed[0]
            else -> false
        }
    }
}

/**
 * What "OK" arrives as.
 *
 * D-pad centre is the remote's own button. Enter comes from remotes that report their centre key
 * as a keyboard Enter, and from anything plugged into the stick's USB port.
 */
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
