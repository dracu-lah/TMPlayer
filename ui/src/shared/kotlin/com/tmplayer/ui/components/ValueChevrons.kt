package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons

/**
 * A value between two chevrons, `‹ value ›`, for a setting the remote walks with Left and Right (a
 * stepper row, the accent colours). The chevrons are the hint: they brighten while the row has
 * focus, dim where there is no value left on that side, and swap sides in a right-to-left layout
 * (the row mirrors and the glyphs are auto mirrored: "previous" is then on the right, pointing right). No words, so a translation cannot
 * wrap it at 200 percent font. Decoration only: the row says its value and its steps to a screen
 * reader itself (state description and custom actions), so this is cleared from the semantics.
 *
 * @param ink the colour of the value and of the live chevrons (the row's text colour, which swaps
 * with the focus fill).
 */
@Composable
fun ValueChevrons(
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    focused: Boolean,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            TmIcons.ChevronLeft,
            contentDescription = null,
            tint = ink.copy(alpha = ValueChevronLook.alpha(canDecrease, focused)),
            modifier = Modifier.size(ValueChevronLook.Size),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = ValueChevronLook.MaxValueWidth),
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            TmIcons.ChevronRight,
            contentDescription = null,
            tint = ink.copy(alpha = ValueChevronLook.alpha(canIncrease, focused)),
            modifier = Modifier.size(ValueChevronLook.Size),
        )
    }
}

/** The numbers of [ValueChevrons], pure so a test holds them. */
object ValueChevronLook {
    val Size = 24.dp

    /** The longest a value may be before it is cut with an ellipsis, so the title keeps its room. */
    val MaxValueWidth = 180.dp

    /** A chevron with a value left on its side, at rest: present, but quieter than the row's text. */
    const val RestAlpha = 0.6f

    /** A chevron with nothing left on its side. */
    const val DimAlpha = 0.25f

    /**
     * The chevron's opacity: full while the row has focus and there is a value on that side, [RestAlpha]
     * at rest, [DimAlpha] where the first or last value is shown, in both states.
     */
    fun alpha(available: Boolean, focused: Boolean): Float = when {
        !available -> DimAlpha
        focused -> 1f
        else -> RestAlpha
    }
}
