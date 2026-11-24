package com.tmplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import com.tmplayer.ui.theme.focusStrokeWidth
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Accent
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmAccents
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState

/**
 * The accent colours as a row of round swatches, with no names: each is the colour its accent
 * draws buttons in under the theme on screen now. The chosen one carries a ring in its own colour
 * and a check; the one under the remote or the keyboard grows and takes a ring in the text colour,
 * and on a desktop the pointer lifts it a little and a tooltip names it. The names are otherwise
 * only spoken, by a screen reader. Left and Right walk the row on a television.
 *
 * With [enabled] off (the phone's wallpaper colours in use) the row is faded and takes no presses
 * and no focus; the caller says why under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentSwatches(
    selected: Accent,
    onPick: (Accent) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    /** The viewer's own colour as ARGB, or null before one was picked. */
    custom: Int? = null,
    /** Where a colour picked in the picker goes; null leaves the last swatch, Your own colour, out. */
    onCustom: ((Int) -> Unit)? = null,
) {
    // A colour taken is one tick, as a choice in a list is.
    val tick = rememberStepTick()
    var picking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    FlowRow(
        modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Accent.entries.filter { it != Accent.Custom || onCustom != null }.forEach { accent ->
            val swatch: @Composable () -> Unit = {
                Swatch(accent, accent == selected, enabled, size, custom) {
                    if (accent == Accent.Custom) {
                        // Always the picker: the swatch is the way to change the colour, not only to take it.
                        picking = true
                    } else {
                        if (accent != selected) tick()
                        onPick(accent)
                    }
                }
            }
            if (deviceForm() == DeviceForm.Desktop) IconTip(accent.label, content = swatch) else swatch()
        }
    }
    if (picking && onCustom != null) {
        CustomAccentPicker(
            initial = custom,
            onPick = { argb -> picking = false; tick(); onCustom(argb) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Swatch(accent: Accent, chosen: Boolean, enabled: Boolean, size: Dp, custom: Int?, onClick: () -> Unit) {
    val dark = LocalDarkTheme.current
    val colour = if (accent == Accent.Custom && custom != null) {
        com.tmplayer.ui.theme.TmCustomAccent.roles(Color(custom), dark).primary
    } else {
        TmAccents.swatch(accent, dark)
    }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val less = rememberLessMotion()
    val scale by animateFloatAsState(
        when {
            less -> 1f
            pressed -> 0.92f
            focused -> 1.15f
            hovered -> 1.08f
            else -> 1f
        },
        animationSpec = Motion.standard(Motion.Fast),
        label = "swatchScale",
    )
    val ring = when {
        focused -> Tone.text
        chosen -> colour
        else -> Color.Transparent
    }
    Box(
        Modifier
            // Read in the draw phase: the swatch is not recomposed on every frame of its grow.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size + 12.dp)
            .border(if (focused) focusStrokeWidth(3.dp) else 2.dp, ring, CircleShape)
            .padding(5.dp)
            .focusProperties { canFocus = enabled }
            .semantics {
                contentDescription = accent.label
                this.selected = chosen
            }
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val edge = Tone.outline
        Canvas(Modifier.size(size)) {
            if (accent == Accent.Custom && custom == null) {
                // Not picked yet: the colour wheel, every hue round the disc.
                drawCircle(
                    androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                    ),
                )
                drawCircle(edge, style = Stroke(1.dp.toPx()))
            } else if (accent == Accent.Monochrome) {
                // Half black, half white, so it reads as "no colour" on either ground.
                drawArc(Color(0xFF111111), 90f, 180f, useCenter = true)
                drawArc(Color(0xFFF2F2F2), 270f, 180f, useCenter = true)
                drawCircle(edge, style = Stroke(1.dp.toPx()))
            } else {
                drawCircle(colour)
            }
            // Monochrome's check sits on a grey disc, which white reads on (5.3:1) across both halves.
            if (chosen && accent == Accent.Monochrome) drawCircle(MONO_DISC, radius = this.size.minDimension * 0.34f)
        }
        if (chosen) {
            val ink = if (accent == Accent.Monochrome) Color.White else Tone.readableOn(colour)
            Icon(TmIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.55f))
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

private val MONO_DISC = Color(0xFF6B6B6B)
