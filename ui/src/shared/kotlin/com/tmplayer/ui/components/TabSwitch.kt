package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/**
 * Two answers as one segmented control: the setup's storage step and Settings' "While streaming"
 * row on a television. A touch or a click picks the answer under it; on a remote or a keyboard the
 * control is one stop, where Left and Right pick the answer on that side and OK swaps to the other.
 *
 * [choices] is exactly two, in reading order (flipped under right to left). [title] is each one's
 * words and [tag] its test tag.
 */
@Composable
fun <T> TabSwitch(
    choices: List<T>,
    selected: T?,
    title: (T) -> String,
    tag: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A new side taken is one tick, as a choice in a list is.
    val stepTick = rememberStepTick()
    val picked: (T) -> Unit = { choice -> if (choice != selected) stepTick(); onPick(choice) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val outer = RoundedCornerShape(Corner.Medium)
    val inner = RoundedCornerShape(Corner.Small)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(outer)
            .background(if (focused) Tone.focusFill.copy(alpha = 0.35f) else Tone.background)
            .focusRing(focused, outer)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val other = choices.firstOrNull { it != selected } ?: choices.first()
                val pick = when (event.key) {
                    Key.DirectionLeft -> choices[if (rtl) 1 else 0]
                    Key.DirectionRight -> choices[if (rtl) 0 else 1]
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> other
                    else -> return@onKeyEvent false
                }
                if (pick != selected) picked(pick)
                true
            }
            .focusable(interactionSource = interactions)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in choices) {
            val on = choice == selected
            val words = title(choice)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(inner)
                    .background(if (on) Tone.accent else Color.Transparent)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = on
                        onClick(label = words) {
                            picked(choice)
                            true
                        }
                    }
                    .pointerInput(choice) { detectTapGestures { picked(choice) } }
                    .testTag(tag(choice))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Icon(TmIcons.Check, contentDescription = null, tint = Tone.onAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        words,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) Tone.onAccent else Tone.text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
