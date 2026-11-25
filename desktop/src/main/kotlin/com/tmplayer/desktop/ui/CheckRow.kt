package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A tick box with its words, as one control: a click on the words ticks it as on the box, Space
 * ticks it from the keyboard, and a screen reader hears one checkbox with the label and its state
 * rather than a bare box beside loose text. The box itself is no second Tab stop.
 */
@Composable
internal fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        // A gap after the box (a box with no handler has none of its own, and the words touched it), and the words take
        // what is left of the row, so a long one wraps beside the box instead of running out of the dialog.
        Text(label, Modifier.padding(start = 8.dp).weight(1f), overflow = TextOverflow.Clip)
    }
}
