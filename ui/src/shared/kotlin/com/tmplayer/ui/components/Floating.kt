package com.tmplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder

/**
 * Material's alert dialog in the app's floating look ([Floating]): the dialog fill, the dialog
 * corner and the hairline every floating surface carries. The phone and the desktop use this
 * wherever they would use [AlertDialog], so a stock dialog and a panel the television draws
 * itself have the same edge.
 */
@Composable
fun TmAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    // A computer driven by a remote (the desktop's big-screen mode): the dialog takes the remote
    // as it opens, on the button that changes nothing (else the only one), and its arrows walk
    // the buttons, which a desktop dialog leaves to Tab.
    val remote = LocalRemoteArrows.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = if (remote) {
            { DialogRemotePart(onDismissRequest, takeFocus = dismissButton == null, content = confirmButton) }
        } else {
            confirmButton
        },
        // Drawn over the dialog's own surface, which is the same size and shape, so the hairline
        // sits exactly on its edge.
        modifier = modifier.border(floatingBorder(), Floating.DialogShape),
        dismissButton = if (remote && dismissButton != null) {
            { DialogRemotePart(onDismissRequest, takeFocus = true, content = dismissButton) }
        } else {
            dismissButton
        },
        icon = icon,
        title = title,
        text = if (remote && text != null) {
            { DialogRemotePart(onDismissRequest, takeFocus = false, content = text) }
        } else {
            text
        },
        shape = Floating.DialogShape,
        containerColor = FloatingTone.dialog,
    )
}

/**
 * True where the window is driven by a remote, so a dialog's arrows move between its buttons and
 * the dialog takes the remote as it opens. The desktop's big-screen mode provides it; the phone
 * and the television never do (the television's dialogs are its own).
 */
val LocalRemoteArrows = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A part of a dialog under a remote: the arrows a control inside did not use move focus, Back
 * (Backspace, which a text field keeps for itself) closes the dialog, and with [takeFocus] the
 * part takes the remote when the dialog opens.
 */
@Composable
private fun DialogRemotePart(onDismiss: () -> Unit, takeFocus: Boolean, content: @Composable () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.foundation.layout.Box(
        Modifier
            .then(if (takeFocus) Modifier.focusRequester(first) else Modifier)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    Key.Backspace -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
    ) { content() }
    if (takeFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/** Material's dropdown menu with the menu fill, the menu corner and the floating hairline. */
@Composable
fun TmDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = Floating.MenuShape,
        containerColor = FloatingTone.menu,
        border = floatingBorder(),
        content = content,
    )
}
