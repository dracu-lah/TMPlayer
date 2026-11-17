package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import com.tmplayer.ui.theme.focusRing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlin.math.roundToInt

/** One line of a [ChoicePopover]: what it sets, what it says, and its glyph. */
class PopoverChoice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Where a [ChoicePopover] grows from: the bounds of the button that opens it, in the window.
 * Put [modifier] on that button; [bounds] is read when the popover opens.
 */
class PopoverAnchor {
    var bounds by mutableStateOf<Rect?>(null)
        private set

    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
}

@Composable
fun rememberPopoverAnchor(): PopoverAnchor = remember { PopoverAnchor() }

/**
 * A short list of choices in a small floating card over a dimmed screen, grown out of the button
 * that opened it ([anchor]) the way the floating bar's profile popover grows out of the picture:
 * the floating surface, hairline and shadow, scaling in from the button's side. No radio buttons:
 * the chosen line is tinted in the accent with a tick at its end, and picking a line applies it
 * and closes the card. A tap outside, or Back, closes it too.
 *
 * Drawn where it is called, so call it last in a box that fills the screen.
 */
@Composable
fun <T> ChoicePopover(
    open: Boolean,
    anchor: PopoverAnchor,
    choices: List<PopoverChoice<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    PopoverCard(open, anchor, onDismiss) {
        if (title != null) PopoverHeading(title)
        choices.forEach { choice ->
            ChoiceRow(choice.label, choice.icon, chosen = choice.value == selected) { onPick(choice.value) }
        }
    }
}

/** One line of a [GroupedChoicePopover]: what it says, its glyph, whether it is in force, and what a press does. */
class GroupedChoice(val label: String, val icon: ImageVector?, val chosen: Boolean, val onPick: () -> Unit)

/** A titled group of lines in a [GroupedChoicePopover], one choice of its own (an order, a filter). */
class ChoiceGroup(val title: String, val choices: List<GroupedChoice>)

/**
 * A [ChoicePopover] of several groups, each with its heading and its own line in force: a chat's
 * Sort and filter menu, where the order, which videos show and the size limits are picked in one
 * card. What a pick does, closing the card included, is the line's own [GroupedChoice.onPick].
 */
@Composable
fun GroupedChoicePopover(
    open: Boolean,
    anchor: PopoverAnchor,
    groups: List<ChoiceGroup>,
    onDismiss: () -> Unit,
) {
    PopoverCard(open, anchor, onDismiss) {
        groups.filter { it.choices.isNotEmpty() }.forEachIndexed { index, group ->
            PopoverHeading(group.title, top = if (index == 0) 8.dp else 14.dp)
            group.choices.forEach { choice -> ChoiceRow(choice.label, choice.icon, chosen = choice.chosen, onClick = choice.onPick) }
        }
    }
}

@Composable
private fun PopoverHeading(title: String, top: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = top, bottom = 4.dp),
    )
}

/** The floating card both popovers draw in: the scrim, the place by the [anchor], the grow-in. */
@Composable
private fun PopoverCard(
    open: Boolean,
    anchor: PopoverAnchor,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val reduced = rememberLessMotion()
    BackHandler(enabled = open, onBack = onDismiss)
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.standard(Motion.Fast)),
        exit = fadeOut(Motion.standard(Motion.Fast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FloatingTone.scrim)
                // Pointer only (see [scrimDismiss]): Esc and Back are the keyboard's way.
                .scrimDismiss(onDismiss),
        )
    }
    var here by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { here = it }) {
        val origin = remember { mutableStateOf(TransformOrigin(0.5f, 1f)) }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.layout { measurable, constraints ->
                val margin = 12.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                // Never taller than the room less its margins: a long menu (a chat's Sort and
                // filter) scrolls inside the card rather than running off the screen.
                val tallest = if (constraints.hasBoundedHeight) (constraints.maxHeight - margin * 2).coerceAtLeast(0) else constraints.maxHeight
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = tallest))
                val own = here?.positionInWindow() ?: Offset.Zero
                val button = anchor.bounds?.translate(-own.x, -own.y)
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val spot = popoverSpot(
                    button = button,
                    card = placeable.width to placeable.height,
                    room = width to height,
                    margin = margin,
                    gap = gap,
                )
                origin.value = spot.origin
                layout(width, height) { placeable.place(spot.offset) }
            },
            enter = if (reduced) {
                fadeIn(Motion.standard(0))
            } else {
                fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Motion.standard(Motion.Normal), initialScale = 0.85f, transformOrigin = origin.value)
            },
            exit = if (reduced) {
                fadeOut(Motion.standard(0))
            } else {
                fadeOut(Motion.standard(Motion.Fast)) + scaleOut(Motion.standard(Motion.Fast), targetScale = 0.9f, transformOrigin = origin.value)
            },
        ) {
            Column(
                Modifier
                    .widthIn(min = POPOVER_MIN, max = POPOVER_MAX)
                    .shadow(8.dp, POPOVER_SHAPE)
                    .clip(POPOVER_SHAPE)
                    .background(FloatingTone.dialog)
                    .border(floatingBorder(), POPOVER_SHAPE)
                    // Taps inside the card stay inside it rather than reaching the scrim. A tap
                    // handler, not a clickable: no Tab stop and no button a reader hears that does nothing.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                content = content,
            )
        }
    }
}

/**
 * A line in a popover of choices: icon and label, the chosen one on an accent tint with its ink in
 * the accent and a tick at the end. Also the lines of a phone's sheet of choices, so a sort reads
 * the same in a sheet as in a popover.
 */
@Composable
fun ChoiceRow(label: String, icon: ImageVector?, chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusVisibleAsState()
    // A new choice taken is one tick; the one already chosen pressed again is not news.
    val tick = rememberStepTick()
    val back by animateColorAsState(if (chosen) Tone.accent.copy(alpha = 0.14f) else Color.Transparent, Motion.standard(Motion.Fast), label = "choiceBack")
    val ink by animateColorAsState(if (chosen) Tone.accent else Tone.text, Motion.standard(Motion.Fast), label = "choiceInk")
    Row(
        modifier
            .fillMaxWidth()
            // At least 44 dp, and taller when a big font wraps the words to a second line.
            .heightIn(min = ROW_HEIGHT)
            .clip(ROW_SHAPE)
            .background(back)
            .focusRing(focused, ROW_SHAPE)
            .selectable(selected = chosen, interactionSource = press, indication = ripple(), role = Role.Button, onClick = { if (!chosen) tick(); onClick() })
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp).pressScale(press))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AnimatedVisibility(visible = chosen, enter = fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Motion.standard(Motion.Fast), initialScale = 0.6f), exit = fadeOut(Motion.standard(Motion.Fast))) {
            Icon(TmIcons.Check, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Where a popover's card goes and the corner it grows from. */
internal class PopoverSpot(val offset: IntOffset, val origin: TransformOrigin)

/**
 * Places a [card] of the given size in [room]: above the [button] and centred on it, held [margin]
 * clear of the edges, or below the button when there is no room above. With no button known, at
 * the foot of the room in the middle.
 */
internal fun popoverSpot(
    button: Rect?,
    card: Pair<Int, Int>,
    room: Pair<Int, Int>,
    margin: Int,
    gap: Int,
): PopoverSpot {
    val (w, h) = card
    val (roomW, roomH) = room
    if (button == null) {
        return PopoverSpot(IntOffset((roomW - w) / 2, (roomH - h - margin).coerceAtLeast(0)), TransformOrigin(0.5f, 1f))
    }
    val x = (button.center.x - w / 2f).roundToInt().coerceIn(margin, (roomW - w - margin).coerceAtLeast(margin))
    val above = (button.top - gap - h).roundToInt()
    val y = if (above >= margin) above else (button.bottom + gap).roundToInt().coerceAtMost((roomH - h - margin).coerceAtLeast(0))
    val pivotX = if (w == 0) 0.5f else ((button.center.x - x) / w).coerceIn(0f, 1f)
    return PopoverSpot(IntOffset(x, y), TransformOrigin(pivotX, if (above >= margin) 1f else 0f))
}

private val POPOVER_MIN = 200.dp
private val POPOVER_MAX = 280.dp
private val POPOVER_SHAPE = RoundedCornerShape(20.dp)
private val ROW_HEIGHT = 44.dp
private val ROW_SHAPE = RoundedCornerShape(14.dp)
