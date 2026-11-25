package com.tmplayer.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.components.bottomFade
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay

/**
 * The quick tour ([Tour]), the first run's last group, in its shared frame ([StepFrame]): one page
 * at a time, the page's marked picture with numbered badges over it, and the same numbers in front
 * of its lines. Next moves on (Done on the last page), Back goes to the page before, and on the
 * first page to whatever came before the tour ([onBackFromFirst], the setup's last step). On a
 * phone a swipe does the same. Skip all at the top ends it.
 *
 * [before] and [groups] put the pages on the whole first run's count, so "Step 5 of 9" and the bar
 * at the top run across the setup and the tour.
 */
@Composable
fun TourScreen(
    state: TourProgress,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    before: Int = 0,
    groups: List<Int> = listOf(state.pages.size),
    onBackFromFirst: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val form = deviceForm()
    val page = state.page
    val copy = s.tourCopy(page, form)
    val badges = TourBadges.of(page, form)
    val next = remember { FocusRequester() }
    // Which page has Next focused, so the first focus can be asked for until it lands.
    var focusedOn by remember { mutableStateOf<TourPage?>(null) }

    val forward: () -> Unit = { if (!state.next()) onDone() }
    val backward: (() -> Unit)? = if (!state.isFirst) ({ state.back() }) else onBackFromFirst
    // Leaving before the last page is a skip: [onSkip] says so (the hint registry counts it), else it ends as done.
    val skip = onSkip ?: onDone
    BackHandler { backward?.invoke() ?: skip() }

    StepFrame(
        group = s.tourLabel,
        position = FlowPosition(before + state.index, groups),
        primary = if (state.isLast) s.setupDone else s.setupNext,
        onPrimary = forward,
        onBack = backward,
        onSkipAll = if (state.isLast) null else skip,
        modifier = modifier,
        primaryFocus = next,
        onPrimaryFocused = { focusedOn = page },
    ) { wide ->
        val lines: @Composable ColumnScope.() -> Unit = {
            copy.lines.forEachIndexed { i, line ->
                Row(verticalAlignment = Alignment.Top) {
                    Badge(s.formatter.number(i + 1), marked = i < badges.size)
                    Spacer(Modifier.width(12.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (wide) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepHeading(copy.title, body = null)
                    lines()
                }
                Box(Modifier.weight(1.6f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Picture(page, badges)
                }
            }
        } else {
            // The page scrolls under the Back and Next row: its foot melts into the ground while there is more,
            // so a line is never cut through its middle by a hard edge (phone audit, 2026-11-25).
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().bottomFade(scroll, 28.dp, Tone.background).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepHeading(copy.title, body = null)
                Picture(page, badges, Modifier.fillMaxWidth())
                lines()
            }
        }
    }

    // A remote or a keyboard starts every page on Next, asked for over a few frames since the page
    // that held the focus has just gone.
    if (form != DeviceForm.Phone) {
        LaunchedEffect(page) {
            repeat(FOCUS_TRIES) {
                withFrameNanos { }
                if (it > 0) delay(FOCUS_RETRY_MS)
                if (focusedOn == page) return@LaunchedEffect
                runCatching { next.requestFocus() }
            }
        }
    }
}

/**
 * The page's picture, as large as its box allows at its own shape, with a badge on each marked
 * thing. The badges follow the picture, not the reading direction: the picture is a shot of the
 * screen as it is, so a right-to-left page keeps them where the marks are.
 */
@Composable
private fun Picture(page: TourPage, badges: List<androidx.compose.ui.geometry.Offset>, modifier: Modifier = Modifier) {
    val size = tourShotSize(page) ?: return
    val s = LocalStrings.current
    BoxWithConstraints(
        modifier
            .aspectRatio(size.width / size.height, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surfaceHigh),
    ) {
        TourShot(page, Modifier.fillMaxSize())
        badges.forEachIndexed { i, at ->
            // From the picture's left edge whatever the reading direction, as the picture is. Up and
            // to the left of the marked thing's corner, so the number never covers what it points at.
            val x = (maxWidth * at.x - BADGE * OUTSIDE).coerceIn(0.dp, (maxWidth - BADGE).coerceAtLeast(0.dp))
            val y = (maxHeight * at.y - BADGE * OUTSIDE).coerceIn(0.dp, (maxHeight - BADGE).coerceAtLeast(0.dp))
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset(x, y).clearAndSetSemantics { }) {
                Badge(s.formatter.number(i + 1), marked = true)
            }
        }
    }
}

/** A number in a filled circle: on the picture and in front of the line it belongs to. */
@Composable
private fun Badge(number: String, marked: Boolean) {
    // As big as its number: a fixed 28 dp disc cut the digit at twice the font (phone audit, 2026-11-25).
    val disc = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(BADGE, BADGE.value.sp.toDp()) }
    Box(
        Modifier
            .size(disc)
            .clip(CircleShape)
            .background(if (marked) Tone.accent else Tone.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number,
            style = MaterialTheme.typography.labelLarge,
            color = if (marked) Tone.onAccent else Tone.text,
            textAlign = TextAlign.Center,
        )
    }
}

private val BADGE = 28.dp

/** How much of a badge sits outside its mark's corner: most of it, so it touches the circle's edge. */
private const val OUTSIDE = 0.85f

/** The quick tour on its own: taken from the Tips card on Home, or opened from Settings. */
@Composable
fun TourOnly(onDone: () -> Unit, modifier: Modifier = Modifier, onSkip: (() -> Unit)? = null) {
    val form = deviceForm()
    val tour = remember(form) { TourProgress(Tour.pages(form)) }
    TourScreen(tour, onDone = onDone, modifier = modifier, onSkip = onSkip)
}
