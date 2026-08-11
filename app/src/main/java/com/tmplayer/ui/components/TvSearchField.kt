package com.tmplayer.ui.components

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary

/**
 * A search box sized for a remote and a sofa.
 *
 * It is an ordinary focusable target, so the D-pad reaches it like any other control and the TV's
 * on-screen keyboard opens on click. The border turns accent on focus because on a 10-foot screen
 * a cursor alone is not a visible enough "you are typing here".
 */
@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    // Focus alone must not start editing. On a remote the D-pad passes through this box on the
    // way to everything below it, and a keyboard that throws itself over the screen each time
    // makes the row impossible to move past. Editing begins on a deliberate press.
    var editing by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val field = remember { FocusRequester() }
    val box = remember { FocusRequester() }
    // Only ever true once the keyboard has actually been opened. Without it the box would claim
    // focus from the rest of the screen the first time it is composed, which is not the search
    // field's to take.
    var wasEditing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    val active = focused || editing

    // Leaving the keyboard removes the text field from composition, and focus goes with it: press
    // Back and then Down and the remote is nowhere. Focus is handed back to the box the viewer
    // pressed to get here.
    LaunchedEffect(editing) {
        if (editing) {
            wasEditing = true
        } else if (wasEditing) {
            runCatching { box.requestFocus() }
        }
    }

    Row(
        modifier
            .focusRequester(box)
            .height(56.dp)
            .clip(CircleShape)
            // Focus has to be as loud here as it is everywhere else on this screen, where a
            // focused control either fills with Accent or draws a 3dp border. A 1dp-to-2dp
            // border change with no fill was the one place the viewer had to hunt for it.
            .background(if (active) SurfaceRaised else SurfaceDark)
            .border(
                width = if (active) 3.dp else 1.dp,
                color = if (active) Accent else TextMuted.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .then(
                if (editing) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = interactions,
                        indication = null,
                    ) { editing = true }
                },
            )
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagnifierGlyph(if (active) Accent else TextMuted)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    // BasicTextField does not read LocalContentColor: without an explicit colour
                    // it paints black, which is invisible on this background.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            editing = false
                            onSubmit?.invoke()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onFocusChanged { state ->
                            // onFocusChanged fires once with isFocused = false as the field is
                            // first composed, before the focus request lands. Acting on that
                            // closed editing again immediately and the keyboard never appeared.
                            if (state.isFocused) {
                                everFocused = true
                            } else if (everFocused) {
                                editing = false
                            }
                        },
                )
                LaunchedEffect(Unit) {
                    everFocused = false
                    runCatching { field.requestFocus() }
                    keyboard?.show()
                }
                BackHandler {
                    keyboard?.hide()
                    editing = false
                }
            } else {
                Text(
                    value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) TextMuted else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Drawn rather than imported: two shapes are cheaper than another drawable and another import. */
@Composable
private fun MagnifierGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(22.dp)) {
        val radius = size.minDimension * 0.32f
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(radius + stroke.width / 2, radius + stroke.width / 2),
            style = stroke,
        )
        val diagonal = radius * 0.72f
        drawLine(
            color = color,
            start = Offset(radius * 1.72f, radius * 1.72f),
            end = Offset(radius * 1.72f + diagonal, radius * 1.72f + diagonal),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
