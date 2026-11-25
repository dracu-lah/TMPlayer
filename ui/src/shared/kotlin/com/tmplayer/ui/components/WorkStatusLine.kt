package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.tmplayer.ui.theme.focusRing
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.data.secondsLeft
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay

/**
 * What the status line shows of a [WorkStatus] at a moment of its run (the 3.10 spec C timing
 * ladder): the status itself, or [WorkStatus.Idle] while it is too young to show, whether its words
 * are shown yet ([labelled]), and whether it has run on long enough to say Telegram is slow ([slow]).
 */
@Immutable
data class ShownWork(val status: WorkStatus, val labelled: Boolean = true, val slow: Boolean = false) {
    val visible: Boolean get() = status != WorkStatus.Idle

    companion object {
        val None = ShownWork(WorkStatus.Idle, labelled = false)
    }
}

/**
 * The timing ladder, as plain arithmetic so it is tested without a clock: what a status that
 * started [elapsedMs] ago shows, and when that next changes.
 *
 * A wait the viewer asked for shows nothing for [SHOW_AFTER_MS] (an answer that quick needs no
 * sign), then the indicator alone, then its words from [LABEL_AFTER_MS]. Automatic work (the timed
 * Home refresh, back online, back to the front) stays quiet until [QUIET_AFTER_MS] and never says
 * "Up to date". A failure, a partial answer and a wait for Telegram show at once. Offline is the
 * offline banner's to say, so the line shows nothing for it.
 */
object WorkLadder {
    const val SHOW_AFTER_MS = 400L
    const val LABEL_AFTER_MS = 1_000L
    const val QUIET_AFTER_MS = 3_000L
    const val UPDATED_FOR_MS = 2_000L
    val SLOW_AFTER_MS: Long get() = SlowAnswer.AFTER_MS

    fun at(status: WorkStatus, asked: Boolean, elapsedMs: Long): ShownWork = when (status) {
        WorkStatus.Idle, is WorkStatus.Offline -> ShownWork.None
        is WorkStatus.Loading -> waiting(status, quiet = false, elapsedMs)
        is WorkStatus.Syncing -> waiting(status, quiet = !asked, elapsedMs)
        is WorkStatus.Updated ->
            if (asked && elapsedMs < UPDATED_FOR_MS) ShownWork(status) else ShownWork.None
        is WorkStatus.Partial, is WorkStatus.Failed, is WorkStatus.RateLimited -> ShownWork(status)
    }

    /** The next moment after [elapsedMs] at which [at] answers differently, or null when it never will. */
    fun nextChangeMs(status: WorkStatus, asked: Boolean, elapsedMs: Long): Long? {
        val steps = when (status) {
            is WorkStatus.Loading -> listOf(SHOW_AFTER_MS, LABEL_AFTER_MS, SLOW_AFTER_MS)
            is WorkStatus.Syncing ->
                if (asked) listOf(SHOW_AFTER_MS, LABEL_AFTER_MS, SLOW_AFTER_MS) else listOf(QUIET_AFTER_MS, SLOW_AFTER_MS)
            is WorkStatus.Updated -> if (asked) listOf(UPDATED_FOR_MS) else emptyList()
            else -> emptyList()
        }
        return steps.firstOrNull { it > elapsedMs }
    }

    private fun waiting(status: WorkStatus, quiet: Boolean, elapsedMs: Long): ShownWork {
        val showAt = if (quiet) QUIET_AFTER_MS else SHOW_AFTER_MS
        val labelAt = if (quiet) QUIET_AFTER_MS else LABEL_AFTER_MS
        return when {
            elapsedMs < showAt -> ShownWork.None
            else -> ShownWork(status, labelled = elapsedMs >= labelAt, slow = elapsedMs >= SLOW_AFTER_MS)
        }
    }
}

/**
 * [status] run through [WorkLadder] with one effect, restarted only when the kind of status (or
 * [asked]) changes: a growing count does not start the ladder again. Nothing runs while Idle.
 * [asked] is true when the viewer pressed for this work (Refresh, Try again).
 */
@Composable
fun rememberWorkStatusTimer(status: WorkStatus, asked: Boolean = false): ShownWork {
    val kind = status::class
    var elapsed by remember(kind, asked) { mutableLongStateOf(0L) }
    LaunchedEffect(kind, asked) {
        if (status == WorkStatus.Idle) return@LaunchedEffect
        while (true) {
            val next = WorkLadder.nextChangeMs(status, asked, elapsed) ?: break
            delay(next - elapsed)
            elapsed = next
        }
    }
    // The newest status of the same kind, at the ladder's step: "312 so far" becomes "340 so far"
    // without the line hiding again.
    return WorkLadder.at(status, asked, elapsed)
}

/** The 20 dp mark beside the words: a spinner, a ring that fills with a real total, or a still arc under less motion. */
@Composable
private fun WorkIndicator(status: WorkStatus, color: Color, size: Dp = 20.dp) {
    val reduced = rememberLessMotion()
    val total = (status as? WorkStatus.Syncing)?.total
    val done = (status as? WorkStatus.Syncing)?.done
    // The words carry the state for a screen reader; the mark is only a picture of it.
    val mark = Modifier.size(size).clearAndSetSemantics { }
    when {
        total != null && total > 0 && done != null ->
            CircularProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = mark,
                color = color,
                strokeWidth = 2.dp,
                trackColor = Tone.track,
            )
        // Still under less motion: an arc three quarters round, and the words say it runs.
        reduced -> CircularProgressIndicator(progress = { STILL_ARC }, modifier = mark, color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
        else -> CircularProgressIndicator(modifier = mark, color = color, strokeWidth = 2.dp)
    }
}

/**
 * What the screen reader hears of [shown]: the words, but for a wait only as they were when it
 * started (the ticking seconds would be read out every second) and for a growing count at most
 * every [COUNT_SPOKEN_EVERY_MS]. Empty when nothing is said: automatic work says nothing, and
 * neither does the indicator before its words show.
 */
@Composable
private fun rememberSpoken(shown: ShownWork, asked: Boolean, now: () -> Long): String {
    val status = shown.status
    if (!shown.visible || !shown.labelled) return ""
    if (status is WorkStatus.Syncing && !asked) return ""
    if (shown.slow && status.runs() && status !is WorkStatus.RateLimited) return LocalStrings.current.commonSlowAnswer
    return when (status) {
        // Start and end only: the sentence as the wait began, never the ticking seconds. Its end
        // is said by what follows it (Updating when the viewer asked, the failure, or nothing
        // for quiet work), since the line leaves when the wait is over.
        is WorkStatus.RateLimited -> remember(status.untilMs) { WorkStatusWords.line(status, now()) }
        is WorkStatus.Syncing -> {
            val words = WorkStatusWords.line(status, now())
            val latest by rememberUpdatedState(words)
            var said by remember { mutableStateOf(words) }
            // At once, then at most every ten seconds: a list growing by a page a second is not
            // read out page by page.
            LaunchedEffect(Unit) {
                snapshotFlow { latest }.collect {
                    said = it
                    delay(COUNT_SPOKEN_EVERY_MS)
                }
            }
            said
        }
        else -> WorkStatusWords.line(status, now())
    }
}

/**
 * The clock the line reads [shown] at. For a wait, one effect ticks it once a second and stops
 * when the wait is over; for anything else it is [now] as it composes, with no effect at all.
 */
@Composable
private fun rememberTick(shown: ShownWork, now: () -> Long): Long {
    val status = shown.status
    if (status !is WorkStatus.RateLimited) return now()
    var at by remember(status.untilMs) { mutableLongStateOf(now()) }
    LaunchedEffect(status.untilMs) {
        while (status.secondsLeft(at) > 0) {
            delay(TICK_MS)
            at = now()
        }
    }
    return at
}

/**
 * [shown] with a wait that is over taken out: the countdown ends the line by itself, its spinner
 * and its ticking with it, whether or not the caller has moved on yet. What comes next (the retry's
 * Updating, a failure) is the caller's new status.
 */
private fun ShownWork.endedAt(at: Long): ShownWork {
    val wait = status as? WorkStatus.RateLimited ?: return this
    return if (wait.secondsLeft(at) == 0) ShownWork.None else this
}

/** The visible words at [at]: the status's own, "Telegram is slow to answer" once work runs long. */
@Composable
private fun visibleWords(shown: ShownWork, at: Long): String {
    val status = shown.status
    if (shown.slow && status.runs() && status !is WorkStatus.RateLimited) return LocalStrings.current.commonSlowAnswer
    return WorkStatusWords.line(status, at)
}

private fun WorkStatus.needsAction(): Boolean = this is WorkStatus.Failed || this is WorkStatus.Partial

private fun WorkStatus.runs(): Boolean = this is WorkStatus.Loading || this is WorkStatus.Syncing || this is WorkStatus.RateLimited

/** Work that runs long enough for "Telegram is slow to answer", which offers Retry as [SlowAnswer] does. */
private fun ShownWork.slowAnswer(): Boolean = slow && (status is WorkStatus.Loading || status is WorkStatus.Syncing)

/**
 * The one status line for work that is not a first full page load (WP-91), on a phone, a tablet,
 * the desktop and iOS: under the page title or the pull spinner on a phone, the header's right
 * side on the desktop, the page foot on a television when there is something to press.
 *
 * Draws nothing and takes no space while [status] is Idle, Offline or too young to show. While it
 * shows it keeps one height for every state ([LINE_MIN_HEIGHT]), so rows under it do not move
 * between Updating and the failure; at a large font the words wrap and the buttons go under them.
 * A failure or a partial answer offers Try again ([onRetry]) and the platform's Send logs button
 * ([sendLogs], which lives in each app); work that runs past [SlowAnswer.AFTER_MS] says Telegram is
 * slow and offers Retry, as `StateScaffold` does. Never takes focus by itself: Try again is reached
 * in reading order.
 *
 * Retry returns focus: when Try again held the remote or the keyboard and the press takes it away
 * (the status turns to Updating), focus goes to [focusAfterRetry] when the host gives one (on a
 * television, the last row above the line), otherwise to the line's own words, which stay drawn
 * ("Up to date" once the work is done) until focus moves on. A new failure puts it back on Try
 * again.
 */
@Composable
fun WorkStatusLine(
    status: WorkStatus,
    modifier: Modifier = Modifier,
    asked: Boolean = false,
    onRetry: (() -> Unit)? = null,
    sendLogs: (@Composable () -> Unit)? = null,
    now: () -> Long = ::wallClock,
    focusAfterRetry: FocusRequester? = null,
    fillWidth: Boolean = true,
) {
    WorkStatusLine(rememberWorkStatusTimer(status, asked), modifier, asked, onRetry, sendLogs, now, focusAfterRetry, fillWidth)
}

/** [WorkStatusLine] for a status already run through the ladder ([rememberWorkStatusTimer]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkStatusLine(
    shown: ShownWork,
    modifier: Modifier = Modifier,
    asked: Boolean = false,
    onRetry: (() -> Unit)? = null,
    sendLogs: (@Composable () -> Unit)? = null,
    now: () -> Long = ::wallClock,
    focusAfterRetry: FocusRequester? = null,
    /** False for a line that is only words (Updating): it is as wide as they are, not the whole row. */
    fillWidth: Boolean = true,
) {
    val retryFocus = remember { FocusRequester() }
    val wordsFocus = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }
    var wordsFocused by remember { mutableStateOf(false) }
    // Try again was pressed while it held focus, and the press has not yet taken it away.
    var retryPressed by remember { mutableStateOf(false) }
    // The words hold focus that Try again handed them.
    var holding by remember { mutableStateOf(false) }

    val at = rememberTick(shown, now)
    val live = shown.endedAt(at)
    // While the words hold focus the line stays: the spinner alone while the retry is too young
    // to show, "Up to date" once it is done.
    val drawn = when {
        live.visible || !holding -> live
        shown.status is WorkStatus.Loading || shown.status is WorkStatus.Syncing -> ShownWork(shown.status, labelled = false)
        shown.status == WorkStatus.Idle || shown.status is WorkStatus.Updated -> ShownWork(WorkStatus.Updated(at))
        else -> live
    }
    val status = drawn.status
    val failed = status.needsAction()
    val retryShown = onRetry != null && (failed || drawn.slowAnswer())
    val retryShownNow by rememberUpdatedState(retryShown)

    LaunchedEffect(retryPressed, retryShown) {
        if (!retryPressed || retryShown) return@LaunchedEffect
        retryPressed = false
        withFrameNanos { }
        if (focusAfterRetry != null) runCatching { focusAfterRetry.requestFocus() } else holding = true
    }
    // Run once the words are composed focusable, so the request has somewhere to land.
    LaunchedEffect(holding) {
        if (holding && !wordsFocused) runCatching { wordsFocus.requestFocus() }
    }
    LaunchedEffect(retryShown, holding) {
        if (!retryShown || !holding) return@LaunchedEffect
        withFrameNanos { }
        if (wordsFocused) runCatching { retryFocus.requestFocus() }
        holding = false
    }

    if (!drawn.visible) return
    val s = LocalStrings.current
    val words = visibleWords(drawn, at)
    val spoken = rememberSpoken(drawn, asked, now)
    AnnounceOnce(spoken)
    FlowRow(
        (if (fillWidth) modifier.fillMaxWidth() else modifier).heightIn(min = LINE_MIN_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = LINE_MIN_HEIGHT)
                .focusRequester(wordsFocus)
                .onFocusChanged {
                    if (wordsFocused && !it.isFocused) holding = false
                    wordsFocused = it.isFocused
                }
                .focusRing(wordsFocused, WORDS_SHAPE, filled = false)
                // Focusable only while it holds the focus Try again handed it.
                .focusable(enabled = holding)
                .padding(horizontal = if (holding) 8.dp else 0.dp)
                // One node for the screen reader: the spoken words, kept apart from the shown ones
                // so a ticking wait is not read out every second.
                .clearAndSetSemantics {
                    contentDescription = spoken.ifEmpty { words }
                    if (spoken.isNotEmpty()) liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.runs()) WorkIndicator(status, Tone.accent)
            if (drawn.labelled) {
                Text(
                    words,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) Tone.danger else Tone.muted,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (retryShown && onRetry != null) {
            SetupButton(
                if (failed) s.commonTryAgain else s.commonRetry,
                primary = true,
                modifier = Modifier
                    .focusRequester(retryFocus)
                    .onFocusChanged {
                        retryFocused = it.isFocused
                        // Focus moved on by itself while the button stays: the press is forgotten.
                        if (!it.isFocused && retryShownNow) retryPressed = false
                    },
                onClick = {
                    retryPressed = retryFocused
                    onRetry()
                },
            )
        }
        if (failed) sendLogs?.invoke()
    }
}

/**
 * The slot of the television's pills ([WorkStatusPill], `ConnectionStatus`): the bottom centre,
 * inside the overscan margin the panel may crop ([Tv.SafeV], 27 dp). The host gives it to one pill
 * at a time (the connection's wins, see `BrowseScreen`'s `connectionShown`), so two never overlap.
 */
fun Modifier.tvPillSlot(): Modifier = padding(bottom = Tv.SafeV)

/**
 * The television's status pill (WP-91), in the corner slot and shape of `ConnectionStatus`: a
 * spinner and a few words for Loading, Updating, Up to date and a wait for Telegram. Never takes
 * focus and holds no button. A failure the viewer asked to see (Refresh, Try again) is said here as
 * words only for [FAILED_PILL_MS], once ([failedWords] says where Try again is), because the
 * [WorkStatusLine] with the button is in the page's foot or header, which is not where the viewer
 * is looking; a failure the viewer did not ask for is only that line. The host shows the connection
 * pill instead of this one while both have something to say.
 * Past [SlowAnswer.AFTER_MS] it says Telegram is slow with no Retry of its own: the host's time box
 * ends the work in a failure, which the page foot's line offers to try again.
 */
@Composable
fun WorkStatusPill(
    status: WorkStatus,
    modifier: Modifier = Modifier,
    asked: Boolean = false,
    now: () -> Long = ::wallClock,
    failedWords: String? = null,
) {
    val shown = rememberWorkStatusTimer(status, asked)
    WorkStatusPill(shown, modifier, asked, now, failedWords)
}

/** [WorkStatusPill] for a status already run through the ladder. */
@Composable
fun WorkStatusPill(
    shown: ShownWork,
    modifier: Modifier = Modifier,
    asked: Boolean = false,
    now: () -> Long = ::wallClock,
    failedWords: String? = null,
) {
    val reduced = rememberLessMotion()
    val at = rememberTick(shown, now)
    // A wait that is over leaves by itself, its spinner and its ticking with it.
    val live = shown.endedAt(at)
    // An asked failure is said for a few seconds, then only the line says it.
    val askedFailure = asked && live.status is WorkStatus.Failed
    var failureOver by remember(live.status) { mutableStateOf(false) }
    LaunchedEffect(live.status, askedFailure) {
        if (!askedFailure) return@LaunchedEffect
        delay(FAILED_PILL_MS)
        failureOver = true
    }
    val visible = live.visible && live.labelled &&
        (!live.status.needsAction() || (askedFailure && !failureOver))
    // A fade, never a slide; none at all under less motion.
    val fade = if (reduced) 0 else PILL_FADE_MS
    // Kept for the fade out, after the status itself has gone.
    val kept = remember { arrayOf(shown) }
    if (visible) kept[0] = shown
    val last = kept[0]
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.focusProperties { canFocus = false },
        enter = fadeIn(tween(fade)),
        exit = fadeOut(tween(fade)),
    ) {
        val status = last.status
        val failure = status as? WorkStatus.Failed
        // Words only: Try again is not here, so the words say where it is. Nothing kept means the
        // failure has its own reason (offline), which is the better sentence.
        val failureWords = failure?.let {
            it.message.takeIf { reason -> it.keptAt == null && reason.isNotBlank() }
                ?: failedWords ?: LocalStrings.current.stateFailedPill
        }
        val words = failureWords ?: visibleWords(last, at)
        val spoken = failureWords ?: rememberSpoken(last, asked, now)
        AnnounceOnce(spoken)
        Row(
            Modifier
                .clearAndSetSemantics {
                    contentDescription = spoken.ifEmpty { words }
                    if (spoken.isNotEmpty()) liveRegion = LiveRegionMode.Polite
                }
                .background(Tone.surfaceHigh.copy(alpha = 0.96f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.runs()) WorkIndicator(status, Tone.accent)
            Text(
                words,
                style = MaterialTheme.typography.bodyLarge,
                color = Tone.text,
                maxLines = 2,
            )
        }
    }
}

private fun wallClock(): Long = System.currentTimeMillis()

/** One height for every state of the line: a touch target's, so Try again fits without the row growing. */
val LINE_MIN_HEIGHT = 48.dp

private val WORDS_SHAPE = RoundedCornerShape(8.dp)
private const val STILL_ARC = 0.75f
private const val TICK_MS = 1_000L
private const val COUNT_SPOKEN_EVERY_MS = 10_000L
private const val PILL_FADE_MS = 150

/** How long a failure the viewer asked to see stays in the pill. */
const val FAILED_PILL_MS = 6_000L
