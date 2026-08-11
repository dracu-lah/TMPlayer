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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary

/** Every screen is exactly one of these: no blank frames, ever. */
sealed interface UiState<out T> {
    data class Loading(val label: String = "Loading…") : UiState<Nothing>

    /** Something actually failed and retrying might help. */
    data class Error(val message: String) : UiState<Nothing>

    /**
     * The request worked; there is simply nothing to show.
     *
     * Kept apart from [Error] because "That didn't work / Try again" over an empty list is both
     * untrue and alarming; a chat with no videos in it is a normal answer, not a failure.
     */
    data class Empty(val message: String) : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>
}

/**
 * @param loading a placeholder shaped like the content that is coming, where the screen has one.
 * Waiting under an outline of the real list tells the viewer what is arriving and stops the layout
 * jumping when it does. Screens with no single predictable shape leave this null and get the
 * labelled spinner instead.
 */
@Composable
fun <T> StateScaffold(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    loading: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> if (loading != null) loading() else BigLoader(state.label)
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
 * its head and tail sweep at different rates, so the arc grows and shrinks as it spins.
 */
@Composable
fun Spinner(size: Dp = 56.dp, color: Color = Accent, strokeWidth: Dp = 5.dp) {
    // On a phone, Material's own. It is the same two motions drawn below, kept in step with every
    // other spinner on the device and with whatever the system's animation scale is set to,
    // including the accessibility setting that turns animation off entirely. The hand-drawn one
    // stays for the television, where TV Material ships no progress indicator at all.
    if (isTouch()) {
        CircularProgressIndicator(
            color = color,
            strokeWidth = strokeWidth,
            modifier = Modifier.size(size),
        )
        return
    }

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

/**
 * A calm, final statement. No Retry button, because there is nothing to retry.
 *
 * The one empty state in the app. The browse tabs drew their own, a copy of this with a
 * television's margins and a television's type on a phone as well, and the two drifted apart the
 * moment either was touched: an empty search said one thing in a chat and another in the list of
 * chats, in two different sizes. [icon] is the only thing that ever legitimately differed, since a
 * search that found nothing is not the same situation as a tab with nothing in it yet.
 */
@Composable
fun BigEmpty(message: String, icon: ImageVector = Icons.Filled.Search) {
    val touch = isTouch()
    Box(
        Modifier.fillMaxSize().padding(horizontal = if (touch) PhonePad.Side else 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.55f),
                modifier = Modifier.size(if (touch) 44.dp else 56.dp),
            )
            Text(
                message,
                style = if (touch) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = TextMuted,
                textAlign = TextAlign.Center,
                // A sentence that needs two lines breaks near the middle rather than running the
                // full width of a television and leaving three words underneath.
                modifier = Modifier.widthIn(max = 400.dp),
            )
        }
    }
}

@Composable
fun BigError(message: String, onRetry: (() -> Unit)?) {
    val touch = isTouch()
    // 72dp of margin either side is a tenth of a television and a third of a phone, which leaves
    // the sentence in a column too narrow to read.
    Box(
        Modifier.fillMaxSize().padding(horizontal = if (touch) PhonePad.Side else 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Danger,
                modifier = Modifier.size(if (touch) 44.dp else 56.dp),
            )
            Text(
                "That didn't work",
                style = if (touch) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = TextPrimary,
            )
            Text(
                message,
                style = if (touch) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            if (onRetry != null) {
                val focus = remember { FocusRequester() }
                TmButton(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
                // The remote needs somewhere to land; a finger does not, and taking focus on a
                // phone only draws a ring around the button.
                if (!touch) {
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
            }
        }
    }
}
