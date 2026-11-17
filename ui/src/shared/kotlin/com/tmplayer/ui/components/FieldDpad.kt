package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * On a television the remote's arrows leave a single line text field (Raju pass 2, sign in): a
 * text field keeps the D-pad for its cursor, which in a one line field goes nowhere, so with the
 * keyboard down the remote was trapped in the number, code or password box and could not reach
 * Sign in or Start over. While the TV keyboard is up it takes these keys before the app sees them,
 * so a key that arrives here means the keyboard is down. The same rule as [TvSearchField]'s.
 */
@Composable
fun Modifier.dpadLeavesField(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }
        if (direction == null) {
            false
        } else {
            if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
            true
        }
    }
}

/**
 * [dpadLeavesField] on a television only: a phone or desktop keyboard's arrows move the cursor in
 * the field, so there they must stay with the field.
 */
@Composable
fun Modifier.dpadLeavesFieldOnTv(): Modifier =
    if (isTouch()) this else dpadLeavesField()
