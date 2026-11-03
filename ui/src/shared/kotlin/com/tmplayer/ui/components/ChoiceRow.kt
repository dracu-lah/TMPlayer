package com.tmplayer.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalDarkTheme
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
    // A new choice taken is one tick; the one already chosen pressed again is not news.
    val tick = rememberStepTick()
    val target = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) tick(); onClick() })
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

/**
 * The chip that leads a settings row: the row's icon in [tint] on a 36 dp square of the same
 * colour at 12 per cent, rounded to [Corner.Medium]. Every settings row on every device carries
 * one, so a column of rows reads as one set and the titles all start on one line.
 *
 * [inverted] is a television row under focus: the row is filled with [Tone.focusFill], so the chip
 * turns round, a solid square in the colour written on that fill with the icon cut out of it in
 * the fill's own colour.
 */
@Composable
fun SettingIconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Tone.accent,
    inverted: Boolean = false,
) {
    val chip = if (inverted) Tone.onFocusFill else tint.copy(alpha = ICON_CHIP_ALPHA)
    val glyph = if (inverted) Tone.focusFill else tint
    Box(
        modifier
            .size(SettingIconChipSize)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(20.dp))
    }
}

/** The side of a [SettingIconChip]. */
val SettingIconChipSize = 36.dp

/**
 * The label over a group of settings: small, bold, muted and spaced out, in capitals where the
 * language has them. Upper-casing follows the app's own locale, so a Turkish "i" keeps its dot,
 * and a script with no case (Arabic, Malayalam, Chinese) is left exactly as written.
 */
@Composable
fun SettingsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Text(
        text.uppercase(java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())),
        style = style.copy(fontWeight = FontWeight.Bold, letterSpacing = SECTION_TRACKING),
        color = Tone.muted,
        modifier = modifier,
    )
}

/** Where a row sits in its group of settings, which decides which of its corners are round. */
data class GroupPlace(val first: Boolean, val last: Boolean) {
    companion object {
        /** A row that is a group on its own. */
        val Alone = GroupPlace(first = true, last = true)
    }
}

/**
 * One row's share of a grouped settings card: the card's fill in [Tone.surface], its outer corners
 * at [Corner.Large] on the first and last rows of the group, and, from the second row on, a
 * divider along its top inset by [dividerStart] so it starts where the row's words do. In daylight
 * the card also carries a hairline in [Tone.outline], drawn round the group's outside only; the
 * dark ground is enough of a step without one.
 *
 * Drawn per row rather than round the whole group because the rows are a lazy list's items, and a
 * lazy list cannot put one background behind several of its items.
 */
@Composable
fun Modifier.groupedCard(place: GroupPlace, dividerStart: Dp = SettingsDividerStart): Modifier {
    val top = if (place.first) Corner.Large else 0.dp
    val bottom = if (place.last) Corner.Large else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val fill = Tone.surface
    val line = Tone.outline
    val hairline = !LocalDarkTheme.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Only a rounded end clips its row: a clip is a layer, and a long list of square middle rows
    // made one each per row a TV (API 28) records through a View on every scroll (HX76).
    return this
        .then(if (place.first || place.last) Modifier.clip(shape) else Modifier)
        .background(fill, shape)
        .drawWithContent {
            drawContent()
            val stroke = 1.dp.toPx()
            if (!place.first) {
                val inset = dividerStart.toPx()
                val y = stroke / 2
                if (rtl) {
                    drawLine(line, Offset(0f, y), Offset(size.width - inset, y), stroke)
                } else {
                    drawLine(line, Offset(inset, y), Offset(size.width, y), stroke)
                }
            }
            if (hairline) {
                // The outline of this row's own shape, with its top edge cut away where a row sits
                // above and its bottom where one sits below, so only the group's outside is drawn.
                // Twice the width, because the clip to the shape has already taken the outer half.
                val outline = shape.createOutline(size, layoutDirection, this)
                clipRect(
                    top = if (place.first) 0f else stroke,
                    bottom = if (place.last) size.height else size.height - stroke,
                ) {
                    drawOutline(outline, line, style = Stroke(stroke * 2))
                }
                // The cut takes the sides' ends with it, so the straight part of each side is
                // drawn again across the whole row, and the sides run unbroken down the group.
                val from = if (place.first) Corner.Large.toPx() else 0f
                val to = if (place.last) size.height - Corner.Large.toPx() else size.height
                if (to > from) {
                    drawLine(line, Offset(stroke / 2, from), Offset(stroke / 2, to), stroke)
                    drawLine(line, Offset(size.width - stroke / 2, from), Offset(size.width - stroke / 2, to), stroke)
                }
            }
        }
}

/**
 * Where the divider between two settings rows starts: past the row's own 16 dp inset, its
 * [SettingIconChip] and the 16 dp after it, so the line runs under the words and not the chips.
 */
val SettingsDividerStart = 16.dp + SettingIconChipSize + 16.dp

/** How much the chip's colour shows through behind its icon. */
private const val ICON_CHIP_ALPHA = 0.12f

/** The extra tracking of a [SettingsSectionLabel]. */
private val SECTION_TRACKING = 0.8.sp
