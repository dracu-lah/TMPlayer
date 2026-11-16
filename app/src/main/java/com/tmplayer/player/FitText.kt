package com.tmplayer.player

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A control's word in [lines] at most, shrunk a step at a time (to [FIT_MIN]) when they cannot
 * hold it: at twice the system font the track picker's Earlier, its figure and its segments' names
 * and the speed sheet's tiles were cut off mid letter (R8). Never more lines than words, since a
 * second line for one word is that word broken in two. Centred, as a button's word is.
 */
@Composable
internal fun FitText(
    text: String,
    style: TextStyle,
    lines: Int = 1,
    color: Color = Color.Unspecified,
) {
    val words = text.trim().split(' ').count { it.isNotEmpty() }.coerceAtLeast(1)
    // The floor in dp, not sp: at twice the font an sp floor is twice as large and fits nothing.
    // Never above the style's own size: at a font scale under 0.83 the floor would pass the maximum, which autoSize rejects.
    val floor = with(LocalDensity.current) { FIT_MIN.toSp() }.let { f ->
        if (style.fontSize.isSp && f.isSp && f.value > style.fontSize.value) style.fontSize else f
    }
    Text(
        text,
        style = style,
        color = color,
        maxLines = minOf(lines, words),
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(minFontSize = floor, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** The smallest a control's word shrinks to before it is ellipsized, whatever the font size. */
private val FIT_MIN = 10.dp
