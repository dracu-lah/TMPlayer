package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceDark)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Accent else TextMuted.copy(alpha = 0.35f),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagnifierGlyph(if (focused) Accent else TextMuted)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interactions,
                // BasicTextField does not read LocalContentColor: without an explicit colour it
                // paints black, which is invisible on this background.
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        onSubmit?.invoke()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
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
