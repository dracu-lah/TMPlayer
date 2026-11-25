package com.tmplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.settings.SendLogsButton
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay

// UiState lives in :ui (UiState.kt), shared with the desktop.

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
    /** What the button under an empty state does; see [StateAction]. */
    onAction: ((StateAction) -> Unit)? = null,
    /**
     * Whether an empty or failed state puts the remote on its button. Off while the viewer is
     * typing into a search over the listing: the answer to each pause in the typing would take the
     * field's focus, and the keyboard with it.
     */
    takeFocus: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> if (loading != null) {
            // The skeleton keeps its shape; the slow line floats over its lower half, where the
            // remote can reach it without the outline of the listing jumping about. From a second
            // on, one line says what loads, a third of the way down: below the first row, so no
            // row is hidden, and on a television, where the skeleton stands still, it is what
            // tells a stalled page from one that is on its way.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val labelTop = maxHeight / 3
                androidx.compose.runtime.CompositionLocalProvider(LocalSkeletonLabel provides state.label) { loading() }
                // Keyed on the kind of wait, not on its label: a label that counts ("Looked through 120 posts
                // so far", WP-94) must not hide itself again, nor restart the slow answer timer, at each change.
                if (rememberSkeletonLabelDue(UiState.Loading::class)) {
                    SkeletonLabel(state.label, Modifier.align(Alignment.TopCenter).padding(top = labelTop))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.tip?.let { TipCard(it) }
                    SlowAnswerNotice(key = UiState.Loading::class, onRetry = onRetry, card = true)
                }
            }
        } else {
            BigLoader(state.label, tip = state.tip, slowKey = UiState.Loading::class, onRetry = onRetry)
        }
        is UiState.Error -> BigError(state.message, onRetry, takeFocus = takeFocus)
        is UiState.Empty -> BigEmpty(
            state.message,
            icon = state.picture,
            actionLabel = state.action?.label,
            actionIcon = state.action?.buttonIcon,
            onAction = state.action?.let { action -> onAction?.let { { it(action) } } },
            takeFocus = takeFocus,
        )
        is UiState.Content -> content(state.value)
    }
}

/**
 * Large centered spinner + label, readable from the couch.
 *
 * @param tip a line of advice under the label, for a wait the viewer has not met before.
 * @param slowKey with [onRetry], the wait this loader stands for: once it has run longer than
 *   [SlowAnswer.AFTER_MS], a line says Telegram is slow and offers to ask again.
 */
@Composable
fun BigLoader(
    label: String? = null,
    tip: String? = null,
    slowKey: Any? = null,
    onRetry: (() -> Unit)? = null,
) {
    val muted = Tone.muted
    val touch = isTouch()
    StatePage(touch) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spinner()
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            }
            if (tip != null) {
                Text(
                    tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            if (slowKey != null) SlowAnswerNotice(key = slowKey, onRetry = onRetry)
        }
    }
}

/**
 * Where a loader, an empty page or a failure stands: centred in the page. On a phone, where the
 * page has a height, it scrolls once large text makes it taller than the page, and keeps clear of
 * the floating bar ([LocalBottomChrome]), so its last line and its button can always be reached
 * (an iPhone 14 with large text, 2026-11-15). In a list row (Downloads), which gives no height to
 * scroll in, it takes the height the row hands it, as before; a television keeps its own layout.
 */
@Composable
private fun StatePage(touch: Boolean, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        val page = touch && constraints.hasBoundedHeight
        val scroll = if (page) {
            Modifier.verticalScroll(rememberScrollState()).padding(bottom = LocalBottomChrome.current)
        } else {
            Modifier
        }
        Box(
            Modifier.fillMaxSize().then(scroll).padding(horizontal = if (touch) PhonePad.Side else 72.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * What a skeleton stands for, as one muted line on the raised surface the slow notice uses, spoken
 * once as it appears ([liveMessage]). Takes no focus.
 */
@Composable
fun SkeletonLabel(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 520.dp)
            .floatingSurface()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = Tone.muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.liveMessage(),
        )
    }
}

/** A first-time tip floated over a skeleton, on the same raised surface as the slow notice. */
@Composable
fun TipCard(tip: String) {
    Box(
        Modifier
            .widthIn(max = 520.dp)
            .floatingSurface()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            tip,
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.text,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "Telegram is slow to answer" and a Retry button, once the wait for [key] has gone on past
 * [SlowAnswer.AFTER_MS]. Nothing at all before then, and nothing without [onRetry]: a line that
 * only says the wait is long, with nothing to press, is no help to anybody.
 *
 * @param card drawn on a surface of its own, for when it floats over a skeleton.
 */
@Composable
fun SlowAnswerNotice(
    key: Any?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    card: Boolean = false,
    afterMs: Long = SlowAnswer.AFTER_MS,
) {
    if (onRetry == null) return
    var slow by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(afterMs)
        slow = true
    }
    if (!slow) return
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val body: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = if (card) 20.dp else 0.dp, vertical = if (card) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                SlowAnswer.MESSAGE,
                style = MaterialTheme.typography.bodyLarge,
                color = Tone.text,
                // Shrinks first, so a long line (a big font, a longer language) never pushes Retry off the card.
                modifier = Modifier.weight(1f, fill = false),
            )
            TmButton(onClick = onRetry, modifier = Modifier.focusRequester(focus)) {
                Icon(TmIcons.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(SlowAnswer.RETRY)
            }
        }
    }
    if (card) {
        Box(modifier.floatingSurface()) { body() }
    } else {
        Box(modifier) { body() }
    }
    // The remote needs somewhere to land, as it does on the error state.
    if (!touch) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The indeterminate circular progress indicator Android draws everywhere.
 *
 * Two motions at once, which is what makes it recognisable: the whole arc rotates steadily while
 * its head and tail sweep at different rates, so the arc grows and shrinks as it spins.
 */
@Composable
fun Spinner(size: Dp = 56.dp, color: Color = Color.Unspecified, strokeWidth: Dp = 5.dp) {
    // Unspecified rather than a literal, so a caller that says nothing gets the device's own
    // accent: the scheme's primary on a phone, the app's amber on a television.
    @Suppress("NAME_SHADOWING") val color = if (color == Color.Unspecified) Tone.accent else color
    // On a phone, Material's own: the same two motions drawn below, but kept in step with every
    // other spinner on the device and with the system's animation scale, including the
    // accessibility setting that turns animation off. The hand-drawn one is for the television,
    // where TV Material ships no progress indicator at all.
    if (isTouch()) {
        CircularProgressIndicator(
            color = color,
            strokeWidth = strokeWidth,
            modifier = Modifier.size(size),
        )
        return
    }

    // Reduce motion: the arc stands still, three quarters round, and the label under it carries
    // the state. No infinite transition, so nothing repaints while the page waits.
    if (rememberLessMotion()) {
        Canvas(Modifier.size(size).progressSemantics()) {
            drawArc(
                color = color,
                startAngle = STILL_START,
                sweepAngle = STILL_SWEEP,
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
            )
        }
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

    Canvas(Modifier.size(size).progressSemantics()) {
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

/** The still arc under Reduce motion: from twelve o'clock, 270 degrees round. */
private const val STILL_START = -90f
private const val STILL_SWEEP = 270f

/**
 * A calm, final statement. No Retry button, because there is nothing to retry.
 *
 * The one empty state in the app: keep it that way, so an empty search reads the same in a chat as
 * in the list of chats. [icon] is the only thing worth varying, since a search that found nothing
 * is not the same situation as a tab with nothing in it yet; it is drawn large on its disc and
 * draws itself in as the page arrives ([EmptyArt], shared with the desktop).
 */
@Composable
fun BigEmpty(
    message: String,
    icon: ImageVector = TmIcons.Search,
    /** The one button worth offering, such as Keep looking when a chat's walk back gave up. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** A glyph before [actionLabel], where the action has one. */
    actionIcon: ImageVector? = null,
    /** Puts the remote on the button; see [StateScaffold]'s takeFocus. */
    takeFocus: Boolean = true,
    /** A second way on beside [actionLabel] (Downloads: Go to Chats, and Download a season). */
    secondLabel: String? = null,
    onSecondAction: (() -> Unit)? = null,
    /** A text link under the buttons, for a way that is useful but never the page's main one (Downloads: Open folder). */
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
    /**
     * On a television, the empty state under a card that already fills much of the screen (Downloads' storage card): one
     * row, a small icon, the sentence and the buttons, so card, sentence and both buttons fit whole on a 1080p panel.
     * Phones and desktops ignore it.
     */
    compact: Boolean = false,
) {
    val touch = isTouch()
    val muted = Tone.muted
    if (compact && !touch) {
        CompactEmptyRow(message, icon, actionLabel, onAction, actionIcon, takeFocus, secondLabel, onSecondAction)
        return
    }
    StatePage(touch) {
        // On a phone it rises in rather than appearing, so a list emptying or a load failing
        // reads as the page changing, not a flash.
        Column(
            Modifier.riseIn(enabled = touch),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmptyArt(icon, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                message,
                style = if (touch) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = muted,
                textAlign = TextAlign.Center,
                // A sentence that needs two lines breaks near the middle rather than running the
                // full width of a television and leaving three words underneath. Wider on a
                // television, where 400 dp at this size broke the longer messages into three
                // lines and pushed the last one off a screen that cannot scroll to it.
                modifier = Modifier.widthIn(max = if (touch) 400.dp else 560.dp),
            )
            if (actionLabel != null && onAction != null) {
                val focus = remember { FocusRequester() }
                Spacer(Modifier.size(4.dp))
                // Wrapped rather than squeezed, for a narrow phone and the longer languages.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TmButton(onClick = onAction, modifier = Modifier.focusRequester(focus)) {
                        if (actionIcon != null) {
                            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(actionLabel)
                    }
                    if (secondLabel != null && onSecondAction != null) {
                        TmButton(onClick = onSecondAction) { Text(secondLabel) }
                    }
                }
                if (!touch && takeFocus) {
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
            }
            if (linkLabel != null && onLink != null) {
                androidx.compose.material3.TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 48.dp)) { Text(linkLabel) }
            }
        }
    }
}

/** [BigEmpty]'s compact television form: icon, sentence and buttons on one row, 64 dp of icon at most. */
@Composable
private fun CompactEmptyRow(
    message: String,
    icon: ImageVector,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    actionIcon: ImageVector?,
    takeFocus: Boolean,
    secondLabel: String?,
    onSecondAction: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(COMPACT_EMPTY_TAG),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmptyArt(icon, size = 56.dp)
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.muted,
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            val focus = remember { FocusRequester() }
            TmButton(onClick = onAction, modifier = Modifier.focusRequester(focus)) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(actionLabel, maxLines = 1)
            }
            if (secondLabel != null && onSecondAction != null) {
                TmButton(onClick = onSecondAction) { Text(secondLabel, maxLines = 1) }
            }
            if (takeFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/** The compact empty row's test tag. */
const val COMPACT_EMPTY_TAG = "compact-empty"

/**
 * Something went wrong: what, Try again where there is something to retry, and Send logs
 * ([SendLogsButton]) for a report. [sendLogs] is off only where a screen offers its own.
 */
@Composable
fun BigError(message: String, onRetry: (() -> Unit)?, sendLogs: Boolean = true, takeFocus: Boolean = true) {
    val s = LocalStrings.current
    val touch = isTouch()
    val danger = Tone.danger
    val text = Tone.text
    val muted = Tone.muted
    // 72dp of margin either side is a tenth of a television and a third of a phone, which leaves
    // the sentence in a column too narrow to read.
    StatePage(touch) {
        // On a phone it rises in rather than appearing, so a list emptying or a load failing
        // reads as the page changing, not a flash.
        Column(
            Modifier.riseIn(enabled = touch),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmptyArt(TmIcons.Warning, tone = danger, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                s.stateFailed,
                style = if (touch) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = text,
            )
            Text(
                message,
                style = if (touch) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            // Try again first, and the logs beside it for when trying again is not enough: the
            // viewer chooses where they go, and nothing is sent without that choice.
            // Wrapped rather than squeezed, for a narrow phone and the longer languages.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onRetry != null) {
                    val focus = remember { FocusRequester() }
                    TmButton(
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(focus),
                    ) {
                        Icon(
                            TmIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s.commonTryAgain)
                    }
                    // The remote needs somewhere to land; a finger does not, and taking focus on a
                    // phone only draws a ring around the button.
                    if (!touch && takeFocus) {
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    }
                }
                if (sendLogs) {
                    SendLogsButton()
                    // Beside it where the build has a log inbox (L5); nothing otherwise.
                    com.tmplayer.ui.settings.SendToDeveloperButton()
                }
            }
        }
    }
}
