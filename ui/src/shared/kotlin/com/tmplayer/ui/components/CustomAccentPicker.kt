package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import com.tmplayer.ui.theme.focusStrokeWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmCustomAccent
import com.tmplayer.ui.theme.Tone

/**
 * Your own colour (REELBOX-PARITY: a custom accent): a hue and a strength, and beside them a
 * button drawn as it will be in this theme, after [TmCustomAccent] has made it readable, with a
 * line saying so when it had to be made lighter or darker. Sliders, so a remote's Left and Right
 * and a desktop's arrows move them as a finger does.
 */
@Composable
fun CustomAccentPicker(initial: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val start = remember(initial) { TmCustomAccent.hsl(Color(initial ?: DEFAULT_SEED)) }
    var hue by rememberSaveable { mutableFloatStateOf(start.first) }
    var strength by rememberSaveable { mutableFloatStateOf(start.second.coerceIn(MIN_STRENGTH, 1f)) }
    val seed = Color.hsl(hue.coerceIn(0f, 359.9f), strength, SEED_LIGHTNESS)
    val dark = LocalDarkTheme.current
    val roles = remember(seed, dark) { TmCustomAccent.roles(seed, dark) }
    val shown by animateColorAsState(roles.primary, Motion.standard(Motion.Fast), label = "customAccent")
    val adjusted = remember(seed) { TmCustomAccent.adjusted(seed) }
    val step = rememberStepTick()
    // A remote has no slider that fits it: a television gets sixteen colours to take and a hue to nudge (WP-11).
    val tv = !isTouch()
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.accentCustomTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The colour as a button would wear it, with its own ink on it.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(shown))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(shown)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(s.commonPlay, color = roles.onPrimary, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
                if (tv) {
                    TvAccentControls(
                        hue = hue,
                        strength = strength,
                        onHue = { next -> if (next != hue) step(); hue = next },
                        onPreset = { next -> step(); hue = next; strength = TV_STRENGTH },
                    )
                    if (adjusted) Text(s.accentCustomAdjusted, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                    return@Column
                }
                Text(s.accentCustomHue, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
                Box {
                    // Every hue along the track, so the slider shows where each colour is.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(6.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(HUES.map { Color.hsl(it, strength, SEED_LIGHTNESS) })),
                    )
                    Slider(
                        value = hue,
                        onValueChange = { next -> if ((next / HUE_TICK).toInt() != (hue / HUE_TICK).toInt()) step(); hue = next },
                        valueRange = 0f..359f,
                        modifier = Modifier.semantics { contentDescription = s.accentCustomHue },
                        colors = SliderDefaults.colors(
                            thumbColor = shown,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                        ),
                    )
                }
                Text(s.accentCustomSaturation, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
                Slider(
                    value = strength,
                    onValueChange = { strength = it },
                    valueRange = MIN_STRENGTH..1f,
                    modifier = Modifier.semantics { contentDescription = s.accentCustomSaturation },
                    colors = SliderDefaults.colors(thumbColor = shown, activeTrackColor = shown),
                )
                if (adjusted) Text(s.accentCustomAdjusted, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(seed.toArgb()) }) { Text(s.commonDone) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
}

/**
 * The television's way to a colour of your own (WP-11): [PRESETS] hues round the wheel at the app's
 * own strength as 48 dp swatches the remote walks, and under them a fine hue that Left and Right
 * move by [FINE_STEP] degrees. The first preset takes the remote as the dialog opens. A colour is
 * then taken (or Cancelled) with the dialog's buttons, as on a phone.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TvAccentControls(hue: Float, strength: Float, onHue: (Float) -> Unit, onPreset: (Float) -> Unit) {
    val s = LocalStrings.current
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Text(s.accentPresets, style = MaterialTheme.typography.labelLarge, color = Tone.muted)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(PRESETS) { index ->
            val presetHue = index * (360f / PRESETS)
            val chosen = kotlin.math.abs(hue - presetHue) < (180f / PRESETS)
            val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val focused by interactions.collectIsFocusedAsState()
            Box(
                Modifier
                    .size(TV_SWATCH)
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .border(if (focused) focusStrokeWidth(3.dp) else 2.dp, if (focused) Tone.text else if (chosen) Tone.accent else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color.hsl(presetHue, TV_STRENGTH, SEED_LIGHTNESS))
                    .semantics {
                        contentDescription = "${presetHue.toInt()}\u00B0"
                        this.selected = chosen
                    }
                    .clickable(interactionSource = interactions, indication = null, role = Role.RadioButton) { onPreset(presetHue) },
            )
        }
    }
    Text(s.accentFineHue, style = MaterialTheme.typography.labelLarge, color = Tone.muted)
    val rowInteractions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val rowFocused by rowInteractions.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(if (rowFocused) focusStrokeWidth(3.dp) else 1.dp, if (rowFocused) Tone.text else Tone.surfaceHigh, RoundedCornerShape(12.dp))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onHue(wrapHue(hue - FINE_STEP)); true }
                    Key.DirectionRight -> { onHue(wrapHue(hue + FINE_STEP)); true }
                    else -> false
                }
            }
            .focusable(interactionSource = rowInteractions)
            .semantics { contentDescription = s.accentFineHue; stateDescription = "${hue.toInt()}" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\u2039", style = MaterialTheme.typography.titleLarge, color = Tone.muted)
        Text("${hue.toInt()}\u00B0", style = MaterialTheme.typography.titleMedium, color = Tone.text)
        Text("\u203A", style = MaterialTheme.typography.titleLarge, color = Tone.muted)
    }
}

/** A hue kept inside the wheel, from 0 up to but not including 360. */
internal fun wrapHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

/** Sixteen colours round the wheel, one every 22.5 degrees. */
internal const val PRESETS = 16

/** The fine hue's step on a television. */
internal const val FINE_STEP = 15f

/** The strength the presets are taken at: the app's own tone, between grey and the loudest. */
private const val TV_STRENGTH = 0.7f

private val TV_SWATCH = 48.dp

/** Where a first pick starts: a calm blue-violet, unlike Telegram's own so the change is seen. */
private const val DEFAULT_SEED = 0xFF7C5CFF.toInt()

/** The lightness the seed is taken at; [TmCustomAccent] finds each mode's own from it. */
private const val SEED_LIGHTNESS = 0.55f

/** Below this a colour is nearly grey: Monochrome is the swatch for that. */
private const val MIN_STRENGTH = 0.25f

/** A light tick every this many degrees of hue. */
private const val HUE_TICK = 30f

private val HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f)
