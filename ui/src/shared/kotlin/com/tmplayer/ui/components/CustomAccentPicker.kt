package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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

/** Where a first pick starts: a calm blue-violet, unlike Telegram's own so the change is seen. */
private const val DEFAULT_SEED = 0xFF7C5CFF.toInt()

/** The lightness the seed is taken at; [TmCustomAccent] finds each mode's own from it. */
private const val SEED_LIGHTNESS = 0.55f

/** Below this a colour is nearly grey: Monochrome is the swatch for that. */
private const val MIN_STRENGTH = 0.25f

/** A light tick every this many degrees of hue. */
private const val HUE_TICK = 30f

private val HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f)
