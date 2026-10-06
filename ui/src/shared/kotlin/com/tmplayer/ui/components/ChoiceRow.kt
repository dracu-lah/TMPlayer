package com.tmplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

/**
 * One line of a picker on a touch screen or under a pointer: the language picker's row, which the
 * player's subtitle and audio pickers draw too, so every choice in the app reads the same.
 *
 * A radio when [selected] is not null (one of a set, the ticked one is in force); an [icon] in the
 * radio's place otherwise, for a line that does something rather than being chosen (a timing nudge,
 * "Search online"). Material 3's list item heights, 56 dp for one line and 72 dp for two, the
 * whole row the target, with Material's 16 dp between the radio and the words. A line that cannot
 * be chosen now ([enabled] false) is drawn at half strength and ignores the tap.
 */
@Composable
fun ChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Corner.Medium)
    val target = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier
            .heightIn(min = if (detail == null) 56.dp else 72.dp)
            .clip(shape)
            .then(target)
            .padding(horizontal = LocalChoiceInset.current, vertical = 2.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selected != null -> RadioButton(selected = selected, onClick = null, enabled = enabled)
            // The radio's own 24 dp, so an action's words line up with the choices' above it.
            else -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        }
    }
}

/**
 * How far a [ChoiceRow]'s radio sits in from the row's edge. A row in a list laid edge to edge
 * across a dialog (the pickers' panel) takes the dialog's own 24 dp, so the press reaches the
 * dialog's edges while the radio lines up with the headline; elsewhere a row sits in a padded
 * column already and keeps a small inset.
 */
val LocalChoiceInset = staticCompositionLocalOf { 4.dp }

/** How strong a line that cannot be chosen yet is drawn. */
private const val DISABLED_ALPHA = 0.5f
