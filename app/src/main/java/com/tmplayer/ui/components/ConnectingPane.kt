package com.tmplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * The first thing anybody sees, and for a few seconds the only thing.
 *
 * Opening TMPlayer means waiting on TDLib to open its database, find a socket and say hello to
 * Telegram, which on a cold start over a slow connection is long enough to wonder whether the
 * press registered. What filled that wait was a grey spinner and four grey words on a black field,
 * which is the screen an app shows when it has nothing to say for itself, and it was the app's
 * first impression on every launch.
 *
 * So it says who it is: the mark it is installed under, a ring that is plainly moving, and a line
 * that changes as the wait goes on. The changing line is the honest part. A message that never
 * moves reads as a hang after about five seconds, and this one is telling the truth in order,
 * ending on the two things that are actually worth checking when a connection is not happening.
 */
@Composable
fun ConnectingPane(label: String = "Connecting to Telegram") {
    val touch = isTouch()
    // Cycles once through and then stays on the last line. Looping back to "Connecting" after
    // suggesting the viewer check their connection would be the app forgetting what it just said.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (index in REASSURANCES.indices) {
            delay(if (index == 0) FIRST_STEP_MS else STEP_MS)
            step = index
        }
    }

    Box(
        Modifier.fillMaxSize().padding(horizontal = if (touch) PhonePad.Side else 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (touch) 20.dp else 24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                PulseRings(size = if (touch) 132.dp else 168.dp)
                AppMark(size = if (touch) MarkSize.HeroTouch else MarkSize.Hero)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = if (touch) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = Tone.text,
                textAlign = TextAlign.Center,
            )
            Text(
                REASSURANCES[step],
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                textAlign = TextAlign.Center,
                // A sentence that needs two lines breaks near the middle rather than running the
                // full width of a television and leaving three words underneath.
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Two rings swelling out of the mark and fading as they go.
 *
 * A spinner says "busy". This says "reaching out", which is what is actually happening, and it is
 * the one animation on the screen so it can afford to be slow: four seconds a ring, the second
 * half a cycle behind the first, which reads as a pulse rather than as something being stirred.
 *
 * Drawn rather than assembled from views because it is two circles, and because a `Canvas` costs
 * nothing to leave running on a television that will sit on this screen for three seconds and
 * never come back to it.
 */
@Composable
private fun PulseRings(size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "connectingPulse")
    val first by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring1",
    )
    val second by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring2",
    )
    val accent = Tone.accent

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        for (progress in listOf(first, second % 1f)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    // From the size of the mark out to the full width of the box, fading as it
                    // goes: a ring that vanished at full opacity would read as a flicker.
                    .scale(RING_START + (1f - RING_START) * progress)
                    .alpha((1f - progress).coerceIn(0f, 1f) * RING_ALPHA),
            ) {
                drawCircle(
                    color = accent,
                    radius = this.size.minDimension / 2f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = RING_STROKE),
                )
            }
        }
    }
}

/**
 * What the second line says, in the order the wait earns it.
 *
 * The last two are the only advice worth giving: everything this app does needs the internet, and
 * a first launch after an install has a database to build before it can talk to anybody.
 */
private val REASSURANCES = listOf(
    "Opening your session",
    "This takes a moment on the first launch",
    "Still trying. Check that this device is online",
)

/** Long enough that a connection that lands immediately shows the first line and nothing else. */
private const val FIRST_STEP_MS = 1_200L
private const val STEP_MS = 5_000L

private const val PULSE_MS = 4_000
private const val RING_START = 0.42f
private const val RING_ALPHA = 0.5f
private const val RING_STROKE = 3f
