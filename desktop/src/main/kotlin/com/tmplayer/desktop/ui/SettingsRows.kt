package com.tmplayer.desktop.ui

import com.tmplayer.ui.settings.seekable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tmplayer.ui.components.SettingIconChip
import com.tmplayer.ui.components.SettingsSectionLabel
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone

/*
 * The pieces every settings page on the desktop is drawn from: the phone's row (an icon chip, the
 * title, the line under it and the value, switch or control at the end), its grouped cards and
 * section labels, and the keyboard's focus ring, which only a keyboard brings up.
 */

/**
 * Whether the focus on a settings row was brought there by the keyboard, so the ring is drawn for
 * Tab and the arrows and not for a click. A press anywhere on the page puts it out; a key, or
 * focus arriving with no press just before it (Tab from the bar), puts it back.
 */
internal class KeyboardFocus {
    var keyboard by mutableStateOf(false)
    var lastPressNanos = 0L
}

/** The page's [KeyboardFocus]; null outside Settings, where a focused row always shows its ring. */
internal val LocalKeyboardFocus = staticCompositionLocalOf<KeyboardFocus?> { null }

/** Watches the pointer and the keys over the page for [focus], without taking either from anybody. */
internal fun Modifier.tracksKeyboardFocus(focus: KeyboardFocus): Modifier = this
    .pointerInput(focus) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    focus.keyboard = false
                    focus.lastPressNanos = System.nanoTime()
                }
            }
        }
    }
    .onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) focus.keyboard = true
        false
    }

/** True while [interactions] has focus and the keyboard put it there. */
@Composable
internal fun keyboardFocused(interactions: InteractionSource): Boolean {
    val focused by interactions.collectIsFocusedAsState()
    val tracker = LocalKeyboardFocus.current ?: return focused
    LaunchedEffect(focused) {
        if (focused && System.nanoTime() - tracker.lastPressNanos > CLICK_FOCUS_NANOS) tracker.keyboard = true
    }
    return focused && tracker.keyboard
}

/** The accent ring round a control the keyboard is on, drawn over its neighbours. */
@Composable
internal fun Modifier.keyboardRing(interactions: InteractionSource, shape: Shape): Modifier =
    if (keyboardFocused(interactions)) zIndex(1f).border(RING, Tone.accent, shape) else this

/**
 * The label over a group of settings, in line with the words of the rows under it. Inside a
 * [SettingsColumn] the rows that follow it, up to the next label or anything that is not a
 * [Setting], are drawn as one card.
 */
@Composable
internal fun Group(title: String) {
    SettingsSectionLabel(
        title,
        Modifier.layoutId(SettingsPart.Label).padding(start = SETTING_INSET, end = SETTING_INSET, top = 16.dp),
    )
}

/**
 * One setting, the phone's row on a desktop: the [icon]'s chip, what it is and what it is set to,
 * with its [control] at the end. With [onClick] the whole row is the target (a page, a picker, an
 * action), lit under the pointer and ringed under the keyboard; with [toggled] as well it is a
 * switch, which [Toggle] draws. Inside a [SettingsColumn] it is one row of its group's card.
 *
 * [selected] marks the page open beside the category list in the two pane layout.
 */
@Composable
internal fun Setting(
    title: String,
    detail: String,
    titleColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Tone.accent,
    onClick: (() -> Unit)? = null,
    toggled: Boolean? = null,
    /** The service the row is about: its badge takes the icon chip's place (HX93). */
    service: com.tmplayer.ui.components.Service? = null,
    selected: Boolean = false,
    control: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Corner.Medium)
    val target = when {
        onClick == null -> Modifier
        toggled != null -> Modifier.toggleable(
            value = toggled,
            interactionSource = interactions,
            indication = null,
            role = Role.Switch,
            onValueChange = { onClick() },
        )
        else -> Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
    }
    Box(
        Modifier
            .layoutId(SettingsPart.Row)
            .then(modifier)
            .seekable(title)
            .fillMaxWidth()
            .padding(ROW_GAP)
            .clip(shape)
            .then(if (onClick != null || selected) Modifier.rowStates(interactions, shape, selected) else Modifier)
            .then(target)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = SETTING_INSET - ROW_GAP, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                SettingIconChip(icon, tint = iconTint, service = service)
                Spacer(Modifier.width(16.dp))
            }
            WordsAndControl(
                title = title,
                detail = detail,
                titleColor = when {
                    titleColor != Color.Unspecified -> titleColor
                    selected -> Tone.accent
                    else -> Tone.text
                },
                control = control,
            )
        }
    }
}

/** Hover, press, the open page and the keyboard's ring, on a row's own rounded shape. */
@Composable
private fun Modifier.rowStates(interactions: MutableInteractionSource, shape: Shape, selected: Boolean): Modifier {
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val ring = keyboardFocused(interactions)
    val fill = when {
        selected -> Tone.accent.copy(alpha = if (pressed) 0.20f else if (hovered) 0.16f else 0.12f)
        pressed -> Tone.text.copy(alpha = 0.10f)
        hovered || ring -> Tone.text.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    return background(fill, shape).then(if (ring) Modifier.border(RING, Tone.accent, shape) else Modifier)
}

/**
 * The words and the control side by side, unless the control would leave the words under half
 * the row and they need more than that: a row of four segments in German or Malayalam squeezed
 * the title to a word a line, so there the control goes under the words instead.
 */
@Composable
private fun WordsAndControl(title: String, detail: String, titleColor: Color, control: @Composable () -> Unit) {
    Layout(
        content = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
            Box { control() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val controlPlaced = measurables[1].measure(Constraints(maxWidth = width))
        val beside = width - controlPlaced.width - (if (controlPlaced.width > 0) gap else 0)
        val stacked = beside < width / 2 && measurables[0].maxIntrinsicWidth(constraints.maxHeight) > beside
        val words = measurables[0].measure(Constraints(maxWidth = if (stacked) width else beside.coerceAtLeast(0)))
        if (stacked) {
            val below = words.height + 8.dp.roundToPx()
            val height = maxOf(constraints.minHeight, below + controlPlaced.height)
            layout(width, height) {
                words.placeRelative(0, 0)
                controlPlaced.placeRelative(0, below)
            }
        } else {
            val height = maxOf(constraints.minHeight, words.height, controlPlaced.height)
            layout(width, height) {
                words.placeRelative(0, (height - words.height) / 2)
                controlPlaced.placeRelative(width - controlPlaced.width, (height - controlPlaced.height) / 2)
            }
        }
    }
}

/** What a row that does something shows at its end. */
internal enum class RowEnd {
    /** Nothing: the press does the thing itself (a clear, a check). */
    None,

    /** Opens a page, a picker or a dialog. */
    Chevron,

    /** Opens a page in the browser. */
    External,
}

/**
 * A row that is pressed rather than set, the phone's ActionRow: the whole row the target, with
 * [value] (what it is set to, or what is happening) and the [end] mark after the words.
 */
@Composable
internal fun Action(
    title: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    iconTint: Color = Tone.accent,
    value: String? = null,
    end: RowEnd = RowEnd.Chevron,
    enabled: Boolean = true,
    service: com.tmplayer.ui.components.Service? = null,
    onClick: () -> Unit,
) {
    Setting(
        title,
        detail,
        titleColor = titleColor,
        modifier = modifier,
        icon = icon,
        iconTint = iconTint,
        service = service,
        onClick = if (enabled) onClick else null,
    ) { RowTail(value, end) }
}

/** A row's [value] in the muted colour, then its [end] mark. */
@Composable
internal fun RowTail(value: String?, end: RowEnd) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1)
            if (end != RowEnd.None) Spacer(Modifier.width(8.dp))
        }
        when (end) {
            RowEnd.None -> Unit
            RowEnd.Chevron -> Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
            RowEnd.External -> Icon(TmIcons.OpenExternal, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** A switch row: the whole row flips it, and the switch at the end only says which way it is. */
@Composable
internal fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    service: com.tmplayer.ui.components.Service? = null,
    onChange: (Boolean) -> Unit,
) {
    Setting(title, detail, modifier = modifier, icon = icon, service = service, onClick = { onChange(!checked) }, toggled = checked) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A few short choices side by side, as the theme row has them; each segment takes the ring. */
@Composable
internal fun <T> Segments(choices: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        choices.forEachIndexed { index, choice ->
            val interactions = remember { MutableInteractionSource() }
            val shape = SegmentedButtonDefaults.itemShape(index, choices.size)
            SegmentedButton(
                selected = selected == choice,
                onClick = { onPick(choice) },
                shape = shape,
                interactionSource = interactions,
                modifier = Modifier.keyboardRing(interactions, shape),
            ) { Text(label(choice)) }
        }
    }
}

/** Less and more, with the [value] between them when the row's words do not already say it. */
@Composable
internal fun Stepper(
    onLess: () -> Unit,
    onMore: () -> Unit,
    value: String? = null,
    canLess: Boolean = true,
    canMore: Boolean = true,
) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RingIconButton(TmIcons.Remove, s.commonLess, canLess, onLess)
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 48.dp),
            )
        }
        RingIconButton(TmIcons.Add, s.commonMore, canMore, onMore)
    }
}

@Composable
private fun RingIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    BarTooltip(description, null) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactions,
            modifier = Modifier.keyboardRing(interactions, CircleShape),
        ) { Icon(icon, contentDescription = description) }
    }
}

/** A button inside a row that has more than one thing to press (the storage folder's Change and Reset). */
@Composable
internal fun RowButton(label: String, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val shape = ButtonDefaults.outlinedShape
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = Modifier.keyboardRing(interactions, shape),
    ) { Text(label, color = color) }
}

/** What a child of a [SettingsColumn] is, which decides the gap above it and whether it is carded. */
internal enum class SettingsPart { Label, Row }

/** How far a setting's words (or its chip) sit in from its card's edge, and the labels from the page's. */
internal val SETTING_INSET = 16.dp

/** The room round a row's own rounded shape inside the card, where the hover fill stops. */
private val ROW_GAP = 4.dp

/** The keyboard's ring. */
private val RING = 2.dp

/** Focus that lands this soon after a press came from the press, not the keyboard. */
private const val CLICK_FOCUS_NANOS = 300_000_000L

/**
 * The settings page's column: the children stacked as a [Column] would, with each run of
 * consecutive [Setting] rows drawn as one grouped card on [Tone.surface] (rounded to
 * [Corner.Large], dividers between the rows inset by [dividerStart] to where their words start,
 * and in daylight a hairline round the outside). A [Group] label gets room above it and sits 8 dp
 * over its card; anything else (a note, the storage card, a progress line) sits 8 dp from its
 * neighbours and ends the card it follows.
 *
 * One layout rather than a card composable round each group, so the groups (several of them in
 * their own files and shared with the About page) need no wrapping at every call.
 */
@Composable
internal fun SettingsColumn(
    modifier: Modifier = Modifier,
    dividerStart: Dp = SETTING_INSET,
    content: @Composable () -> Unit,
) {
    val cards = remember { mutableStateOf(emptyList<SettingsCard>()) }
    val fill = Tone.surface
    val line = Tone.outline
    val hairline = !LocalDarkTheme.current
    Layout(
        content = content,
        modifier = modifier.drawBehind {
            val radius = CornerRadius(Corner.Large.toPx())
            val stroke = 1.dp.toPx()
            val inset = dividerStart.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            cards.value.forEach { card ->
                val topLeft = Offset(0f, card.top.toFloat())
                val box = Size(size.width, (card.bottom - card.top).toFloat())
                drawRoundRect(fill, topLeft, box, radius)
                card.dividers.forEach { y ->
                    if (rtl) {
                        drawLine(line, Offset(0f, y.toFloat()), Offset(size.width - inset, y.toFloat()), stroke)
                    } else {
                        drawLine(line, Offset(inset, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
                    }
                }
                if (hairline) {
                    drawRoundRect(
                        line,
                        topLeft + Offset(stroke / 2, stroke / 2),
                        Size(box.width - stroke, box.height - stroke),
                        radius,
                        style = Stroke(stroke),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map {
            it.measure(Constraints(minWidth = 0, maxWidth = width, minHeight = 0))
        }
        val kinds = measurables.map { it.layoutId as? SettingsPart }
        val gap = 8.dp.roundToPx()
        val labelGap = 16.dp.roundToPx()
        val ys = IntArray(placeables.size)
        val runs = mutableListOf<SettingsCard>()
        var y = 0
        var runTop = -1
        var dividers = mutableListOf<Int>()
        fun closeRun(bottom: Int) {
            if (runTop >= 0) runs += SettingsCard(runTop, bottom, dividers)
            runTop = -1
            dividers = mutableListOf()
        }
        placeables.forEachIndexed { index, placeable ->
            val kind = kinds[index]
            val previous = kinds.getOrNull(index - 1)
            if (index > 0) {
                y += when {
                    kind == SettingsPart.Row && previous == SettingsPart.Row -> 0
                    kind == SettingsPart.Label -> labelGap
                    else -> gap
                }
            }
            if (kind == SettingsPart.Row) {
                if (runTop < 0) runTop = y else dividers += y
            } else {
                if (index > 0) closeRun(ys[index - 1] + placeables[index - 1].height)
            }
            ys[index] = y
            y += placeable.height
        }
        closeRun(y)
        if (cards.value != runs) cards.value = runs
        layout(width, y) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(0, ys[index]) }
        }
    }
}

/** One group's card in a [SettingsColumn], in pixels from the column's top. */
internal data class SettingsCard(val top: Int, val bottom: Int, val dividers: List<Int>)

/** The two glyphs the desktop's settings need that the shared set has no use for elsewhere. */
internal object SettingsGlyphs {
    /** A mouse, for the page the phone calls Player gestures. */
    val Mouse: ImageVector by lazy {
        glyph(
            "Mouse",
            "M12 2.5C8.41 2.5 5.5 5.41 5.5 9V15C5.5 18.59 8.41 21.5 12 21.5C15.59 21.5 18.5 18.59 18.5 15V9C18.5 5.41 15.59 2.5 12 2.5Z",
            "M12 6.5V9.5",
        )
    }

    /** Two arrows forward, for holding to speed up. */
    val Faster: ImageVector by lazy {
        glyph(
            "Faster",
            "M3.5 7.2V16.8C3.5 17.6 4.4 18.1 5.1 17.6L11.5 12.8C12.1 12.4 12.1 11.6 11.5 11.2L5.1 6.4C4.4 5.9 3.5 6.4 3.5 7.2Z",
            "M12.5 7.2V16.8C12.5 17.6 13.4 18.1 14.1 17.6L20.5 12.8C21.1 12.4 21.1 11.6 20.5 11.2L14.1 6.4C13.4 5.9 12.5 6.4 12.5 7.2Z",
        )
    }

    private fun glyph(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = true)
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                pathFillType = PathFillType.NonZero,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
