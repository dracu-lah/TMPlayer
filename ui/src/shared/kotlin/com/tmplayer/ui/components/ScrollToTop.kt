package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "back to top" button of a long vertical grid, laid over it at the bottom centre of the
 * [BoxScope] the grid fills. See the [LazyListState] overload for when it shows.
 */
@Composable
fun BoxScope.ScrollToTop(state: LazyGridState, modifier: Modifier = Modifier, bottom: Dp = 16.dp) {
    ScrollToTop(
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        screenful = { state.layoutInfo.visibleItemsInfo.size },
        scrolling = { state.isScrollInProgress },
        jump = { state.scrollToItem(it) },
        glide = { state.animateScrollToItem(it) },
        modifier = modifier,
        bottom = bottom,
        owner = state,
    )
}

/**
 * The "back to top" button of a long vertical list, laid over it at the bottom centre of the
 * [BoxScope] the list fills.
 *
 * It shows only when it is wanted: the list is at least a screenful down and the finger (or the
 * wheel) has just moved it back up, which is the moment someone is heading for the top. Scrolling
 * down again, reaching the top, or [IDLE_MS] without a scroll puts it away, so it never sits over
 * the videos while they are being looked at. A press jumps to two screenfuls from the top and
 * glides the rest, so a list of a thousand videos does not take seconds to unwind.
 *
 * Touch and pointer only: a remote has no fling to undo and steps back up a row at a time, and a
 * floating button would be one more place for its focus to land, so on the television this draws
 * nothing: the television's way to the top is the Back key and the Page and Channel keys (see
 * [BackToTop] and [pageKeys]). [bottom] is the room under it above the navigation bar. Only as much of the bar as the
 * list actually reaches down over is added, so a list that a Scaffold has already lifted clear of
 * the bar does not get the bar's height twice.
 */
@Composable
fun BoxScope.ScrollToTop(state: LazyListState, modifier: Modifier = Modifier, bottom: Dp = 16.dp) {
    ScrollToTop(
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        screenful = { state.layoutInfo.visibleItemsInfo.size },
        scrolling = { state.isScrollInProgress },
        jump = { state.scrollToItem(it) },
        glide = { state.animateScrollToItem(it) },
        modifier = modifier,
        bottom = bottom,
        owner = state,
    )
}

@Composable
private fun BoxScope.ScrollToTop(
    position: () -> Pair<Int, Int>,
    screenful: () -> Int,
    scrolling: () -> Boolean,
    jump: suspend (Int) -> Unit,
    glide: suspend (Int) -> Unit,
    modifier: Modifier,
    bottom: Dp,
    owner: Any,
) {
    if (!isTouch()) return
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    // Set while a press is carrying the list up, so that scroll is not read as the user's.
    var returning by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // How far up counts as heading up rather than a finger settling: a small drag back is not it.
    val nudge = with(density) { NUDGE.toPx() }
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    // How far the list's bottom edge sits above the bottom of the window.
    var above by remember { mutableStateOf(0f) }
    Box(
        Modifier.matchParentSize().onGloballyPositioned {
            above = it.findRootCoordinates().size.height - it.boundsInRoot().bottom
        },
    )
    // A floating bar at the foot of the screen (LocalBottomChrome, measured from the window's
    // bottom edge, the navigation bar included) is cleared rather than covered.
    val chrome = with(density) { LocalBottomChrome.current.toPx() }
    val lift = with(density) { (maxOf(navigationBar.toFloat(), chrome) - above).coerceAtLeast(0f).toDp() }

    LaunchedEffect(Unit) {
        var last = position()
        var climbed = 0f
        snapshotFlow(position).collect { now ->
            val (index, offset) = now
            // Within the first screenful the top is a short swipe away and the button would only be
            // in the way.
            val deep = index >= maxOf(screenful(), MIN_ITEMS)
            when {
                returning -> Unit
                !deep -> {
                    shown = false
                    climbed = 0f
                }
                index < last.first -> {
                    shown = true
                    climbed = 0f
                }
                index > last.first -> {
                    shown = false
                    climbed = 0f
                }
                offset < last.second -> {
                    climbed += last.second - offset
                    if (climbed >= nudge) shown = true
                }
                offset > last.second -> {
                    shown = false
                    climbed = 0f
                }
            }
            last = now
        }
    }
    // Put away once the list has been still for a while; a fresh scroll up brings it back.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        snapshotFlow(scrolling).collectLatest { moving ->
            if (!moving) {
                delay(IDLE_MS)
                shown = false
            }
        }
    }

    AnimatedVisibility(
        // Not while the list's fast scroller is being dragged: a drag up is not a call for the top.
        visible = shown && !FastScrollHeld.isHeld(owner),
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = lift + bottom),
        enter = fadeIn(Motion.standard(Motion.Fast)) +
            scaleIn(Motion.standard(Motion.Normal), initialScale = 0.8f) +
            slideInVertically(Motion.standard(Motion.Normal)) { it / 2 },
        exit = fadeOut(Motion.standard(Motion.Fast)) +
            scaleOut(Motion.standard(Motion.Fast), targetScale = 0.8f) +
            slideOutVertically(Motion.standard(Motion.Fast)) { it / 2 },
    ) {
        IconTip(s.commonBackToTop) {
            Box(
                Modifier
                    .padding(4.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(FloatingTone.menu)
                    .border(floatingBorder(), CircleShape)
                    .clickable(role = Role.Button) {
                        shown = false
                        returning = true
                        scope.launch {
                            try {
                                val near = screenful() * 2
                                if (position().first > near) jump(near)
                                glide(0)
                            } finally {
                                returning = false
                            }
                        }
                    }
                    .semantics { contentDescription = s.commonBackToTop }
                    .size(SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TmIcons.ChevronUp, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A thumb's reach: Material's smallest comfortable touch target. */
private val SIZE = 48.dp

/** How far a list has to be pulled back up, within one item, before it counts as heading up. */
private val NUDGE = 24.dp

/** A list shorter than this many items never shows the button, whatever fits on screen. */
private const val MIN_ITEMS = 4

/** How long the button waits on a still list before it goes. */
private const val IDLE_MS = 2_500L
