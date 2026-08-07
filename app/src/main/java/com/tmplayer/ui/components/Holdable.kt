package com.tmplayer.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * OK opens the thing; holding OK opens its menu.
 *
 * Written out of key events rather than handed to `combinedClickable`, whose long press was
 * verified on the target device and does not fire: neither a real hold on the remote nor an
 * injected `input keyevent --longpress` reached the callback, and a hold swallowed the ordinary
 * click along with it. Auto-repeat is the signal used instead. A remote sends the first repeat
 * only once the key has been down for the system's own long-press interval, which is the same
 * instant a long press is meant to happen and it needs no timer of ours to measure.
 *
 * Click therefore fires on key up, not key down, so that a hold does not also count as a press.
 */
@Composable
fun Modifier.holdable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: () -> Unit,
): Modifier {
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
 * What "OK" arrives as.
 *
 * D-pad centre is the remote's own button. Enter comes from remotes that report their centre key
 * as a keyboard Enter, and from anything plugged into the stick's USB port.
 */
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
