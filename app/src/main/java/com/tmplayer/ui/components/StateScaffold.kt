package com.tmplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.tv.material3.Icon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.tmButtonColors

/** Every screen is exactly one of these — no blank frames, ever. */
sealed interface UiState<out T> {
    data class Loading(val label: String = "Loading…") : UiState<Nothing>

    /** Something actually failed and retrying might help. */
    data class Error(val message: String) : UiState<Nothing>

    /**
     * The request worked; there is simply nothing to show.
     *
     * Kept apart from [Error] because "Something went wrong / Retry" over an empty list is both
     * untrue and alarming — a chat with no films in it is a normal answer, not a failure.
     */
    data class Empty(val message: String) : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>
}

@Composable
fun <T> StateScaffold(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> BigLoader(state.label)
        is UiState.Error -> BigError(state.message, onRetry)
        is UiState.Empty -> BigEmpty(state.message)
        is UiState.Content -> content(state.value)
    }
}

/** Large centered spinner + label, readable from the couch. */
@Composable
fun BigLoader(label: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spinner()
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The indeterminate circular progress indicator Android draws everywhere.
 *
 * Two motions at once, which is what makes it recognisable: the whole arc rotates steadily while
 * its head and tail sweep at different rates, so the arc grows and shrinks as it spins. A single
 * fixed arc spinning at constant speed reads as a cheap imitation of this.
 */
@Composable
fun Spinner(size: Dp = 56.dp, color: Color = Accent, strokeWidth: Dp = 5.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(ROTATION_MS, easing = LinearEasing)),
        label = "rotation",
    )
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SWEEP_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "head",
    )
    val tail by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Delayed against the head, so the gap between them is what opens and closes.
            keyframes {
                durationMillis = SWEEP_MS
                0f at 0 using FastOutSlowInEasing
                0f at SWEEP_MS / 2 using FastOutSlowInEasing
                1f at SWEEP_MS
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "tail",
    )

    Canvas(Modifier.size(size)) {
        val start = rotation + tail * MAX_SWEEP
        val sweep = (head - tail) * MAX_SWEEP
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep.coerceAtLeast(MIN_SWEEP),
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

private const val ROTATION_MS = 1_332
private const val SWEEP_MS = 1_332
private const val MAX_SWEEP = 300f
private const val MIN_SWEEP = 12f
private val Danger = Color(0xFFE5484D)

/** A calm, final statement. No Retry button, because there is nothing to retry. */
@Composable
fun BigEmpty(message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.55f),
                modifier = Modifier.size(56.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.titleLarge,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun BigError(message: String, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Danger,
                modifier = Modifier.size(56.dp),
            )
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            if (onRetry != null) {
                val focus = remember { FocusRequester() }
                Button(
                    onClick = onRetry,
                    colors = tmButtonColors(),
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text("Retry")
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}
