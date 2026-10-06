package com.tmplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        // Drawn over the dialog's own surface, which is the same size and shape, so the hairline
        // sits exactly on its edge.
        modifier = modifier.border(floatingBorder(), Floating.DialogShape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = Floating.DialogShape,
        containerColor = FloatingTone.dialog,
    )
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
