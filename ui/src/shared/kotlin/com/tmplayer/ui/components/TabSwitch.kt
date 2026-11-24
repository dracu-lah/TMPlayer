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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/**
 * A few answers (two or three) as one segmented control: the setup's storage step, Settings'
 * "While streaming" and "On mobile data" rows on a television. A touch or a click picks the answer
 * under it; on a remote or a keyboard the control is one stop, where Left and Right move to the
 * answer on that side (and stop at the ends) and OK goes on to the next, round to the first.
 *
 * [choices] is two or three, in reading order (flipped under right to left). [title] is each one's
 * words and [tag] its test tag. [label] names the control for a screen reader ("While streaming"); without
 * one the answers' words are read. The focused control is one node: a button that says which answer
 * is chosen, never an unnamed stop (F-08).
 */
@Composable
fun <T> TabSwitch(
    choices: List<T>,
    selected: T?,
    title: (T) -> String,
    tag: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
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
            .background(Tone.background)
            .focusRing(focused, outer, filled = false)
            .semantics {
                contentDescription = label ?: choices.joinToString(", ") { title(it) }
                selected?.let { stateDescription = title(it) }
                role = Role.Button
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Left and Right walk the choices (two or three) and stop at the ends; OK goes on to the
                // next, wrapping, which for two is the other one.
                val at = choices.indexOf(selected).coerceAtLeast(0)
                fun step(by: Int) = choices[(at + by).coerceIn(0, choices.lastIndex)]
                val pick = when (event.key) {
                    Key.DirectionLeft -> step(if (rtl) 1 else -1)
                    Key.DirectionRight -> step(if (rtl) -1 else 1)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar ->
                        choices[(choices.indexOf(selected) + 1).mod(choices.size)]
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
                    // The chosen answer keeps the accent; under the remote the others take the row's own
                    // focus fill, at full strength (a 35 % wash was 2.6:1 at the edge).
                    .background(if (on) Tone.accent else if (focused) Tone.focusFill else Color.Transparent)
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
                        color = if (on) Tone.onAccent else if (focused) Tone.onFocusFill else Tone.text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
