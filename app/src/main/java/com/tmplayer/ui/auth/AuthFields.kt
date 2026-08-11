package com.tmplayer.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.Text as M3Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tone

/**
 * The field every sign-in pane types into.
 *
 * Pulled out of the old one-field login form because the number pane now wants three of them, of
 * two different widths, and a field that is redrawn per call site is a field that stops matching
 * the one beside it.
 *
 * A phone gets the platform's own outlined field, floating label and all, because a sign-in is the
 * one screen where looking like every other Android sign-in is the point: people trust the field
 * they already know, and a hand-drawn box on a light theme cannot borrow that. The television keeps
 * the dark rounded box, which is sized for a remote and for a room.
 */
@Composable
fun PaneField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** Drawn inside the field, ahead of the text: the "+" on a dial code. */
    prefix: String? = null,
    /**
     * What the field is called once something has been typed into it. Defaults to the placeholder,
     * which is the right answer everywhere except the dial code, whose placeholder is "00".
     */
    label: String = placeholder,
) {
    if (isTouch()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { M3Text(label) },
            // The placeholder survives the move because it is doing a second job on this flow:
            // it shows the shape of the answer, and a floating label leaves room for it.
            placeholder = { M3Text(placeholder) },
            prefix = prefix?.let { { M3Text(it) } },
            textStyle = M3.typography.titleMedium,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = M3.shapes.medium,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier
            .height(FIELD_HEIGHT)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(SurfaceDark)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prefix != null) {
                Text(prefix, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth(),
                // Placeholder and field must be stacked, not siblings: emitted flat they are laid
                // out one after the other and the text lands off to the side.
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextMuted,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * The arrow at the top left of a sign-in pane, on a phone.
 *
 * A phone's system Back is a gesture from the edge that plenty of people never learn, and this
 * flow has several panes deep enough to strand somebody on. The remote has a Back button that is
 * unmissable and a focus model that an arrow up here would only get in the way of, so on a
 * television the pane's text buttons remain the way out and this draws nothing.
 */
@Composable
fun BackArrow(onBack: () -> Unit, description: String = "Back") {
    IconButton(onClick = onBack, modifier = Modifier.size(BACK_TARGET)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = description,
            tint = Tone.text,
        )
    }
}

/** A row that opens something rather than accepting text: the country the number belongs to. */
@Composable
fun PaneChooser(
    label: String,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isTouch()) {
        ListItem(
            headlineContent = { M3Text(label, style = M3.typography.titleMedium) },
            trailingContent = { M3Text(trailing, style = M3.typography.labelLarge) },
            colors = ListItemDefaults.colors(containerColor = Tone.surface),
            modifier = modifier
                .fillMaxWidth()
                .clip(M3.shapes.medium)
                // Still spelled out: a ListItem is a row of text until something says otherwise,
                // and a screen reader had no way to know the country row was something to press.
                .clickable(role = Role.Button, onClick = onClick),
        )
        return
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(SurfaceDark)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.defaultMinSize(minWidth = 0.dp).weight(1f),
            )
            Text(trailing, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
        }
    }
}

internal val FIELD_HEIGHT = 60.dp

// Material's own 48 dp minimum, spelled out because the icon inside it is 24.
private val BACK_TARGET = 48.dp
