package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tmplayer.data.Thumbnails

/*
 * Lists that compose ahead of the screen. By default a lazy list prefetches a fraction of the
 * next item and nothing while it is still; with a cache window it keeps a screen's worth of
 * tiles composed past the end it is scrolling towards (and half a screen behind), so their
 * thumbnails are already asked for and a fast flick or a held D-pad lands on tiles that are drawn
 * rather than blank. Under Lighter pictures (low memory) the window is halved.
 */

/**
 * How far ahead and behind, in screens: one ahead, half behind; half of that under Lighter
 * pictures. [column] is a list the remote moves down through a row at a time (Home's rows, a
 * grid of tiles).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberAheadWindow(column: Boolean = false): LazyLayoutCacheWindow {
    val light = Thumbnails.light
    val tv = com.tmplayer.ui.theme.Motion.television
    return remember(light, tv, column) {
        // A television composes a quarter of a screen ahead along a row and nothing behind: a
        // screen ahead composed three more rows of sixteen tiles each as Home opened, and their
        // pictures and lookups queued in front of the ones on screen. Down a column it keeps the
        // next row (a little over half a screen) composed while the remote rests, and the one
        // just left: composing a row of seven tiles in the frame the remote moved cost the 1 GB
        // stick 80 to 130 ms, the one hitch left on Home (2026-10-23).
        if (tv && column) LazyLayoutCacheWindow(aheadFraction = 0.6f, behindFraction = 0.3f)
        else if (tv) LazyLayoutCacheWindow(aheadFraction = 0.25f, behindFraction = 0f)
        else if (light) LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.25f)
        else LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f)
    }
}

/** [rememberLazyListState] that composes a screen ahead: for Home's rows and strips and long lists of tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAheadListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    column: Boolean = false,
): LazyListState =
    rememberLazyListState(rememberAheadWindow(column), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

/** [rememberLazyGridState] that composes a screen ahead: for a chat's grid of videos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAheadGridState(initialFirstVisibleItemIndex: Int = 0, initialFirstVisibleItemScrollOffset: Int = 0): LazyGridState =
    rememberLazyGridState(rememberAheadWindow(column = true), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

/**
 * [rememberLazyListState] for a list the remote jumps through a screen at a time (the chat
 * picker's held Down, HX76): the screenful past the end it last moved towards, and [behind] items
 * the other way, are kept composed and measured in the idle time between frames. A cache window
 * cannot do this: it refills only on a scroll, and a jump is a `scrollToItem`, so each one
 * composed and measured its new rows in the frame it landed (135 ms a jump on the Mi TV, about
 * 120 of it the rows). [enabled] off is the platform's own prefetch (the next item of a scroll).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberJumpAheadListState(enabled: Boolean, behind: Int = 2): LazyListState {
    val strategy = remember(enabled, behind) { if (enabled) JumpAheadPrefetch(behind) else LazyListPrefetchStrategy() }
    return rememberLazyListState(prefetchStrategy = strategy)
}

/**
 * Composes ahead by the visible items, not by distance, so it follows a jump of any size. Bounded:
 * at most a screenful plus [behind] items are held, each scheduled once, and nothing is asked for
 * while the visible items and the count stay the same, so a list at rest does no work. The rows
 * held run their effects like rows on screen, so work a row starts for the viewer (a Telegram
 * count) must wait until it is in the visible items.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class JumpAheadPrefetch(behind: Int) : LazyListPrefetchStrategy {
    private val window = JumpAheadWindow(behind)
    private val held = HashMap<Int, LazyLayoutPrefetchState.PrefetchHandle>()

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        // A negative delta moves the content up: the list is going towards its end.
        if (delta != 0f) window.forward = delta < 0f
        refill(layoutInfo)
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = refill(layoutInfo)

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit

    private fun LazyListPrefetchScope.refill(layoutInfo: LazyListLayoutInfo) {
        val visible = layoutInfo.visibleItemsInfo
        val change = window.update(
            first = visible.firstOrNull()?.index ?: -1,
            last = visible.lastOrNull()?.index ?: -1,
            total = layoutInfo.totalItemsCount,
        ) ?: return
        // An item now on screen was used by the layout (its handle no longer holds it); one that
        // left the window is let go, back into the list's reuse pool.
        for (index in change.drop) held.remove(index)?.cancel()
        for (index in change.add) held[index] = schedulePrefetch(index)
    }
}

/**
 * Which items [JumpAheadPrefetch] holds, kept apart from the prefetch handles so it can be tested.
 * [update] answers what to let go of and what to ask for, nearest first, or null when nothing moved.
 */
internal class JumpAheadWindow(private val behind: Int) {
    class Change(val drop: List<Int>, val add: List<Int>)

    /** The way the list last moved: towards its end, or its start. */
    var forward = true
    private val held = HashSet<Int>()
    private var lastFirst = -1
    private var lastLast = -1
    private var lastTotal = -1

    fun update(first: Int, last: Int, total: Int): Change? {
        if (first < 0 || last < first || total <= 0) {
            val drop = held.toList()
            held.clear()
            lastFirst = -1
            lastLast = -1
            lastTotal = -1
            return if (drop.isEmpty()) null else Change(drop, emptyList())
        }
        if (first == lastFirst && last == lastLast && total == lastTotal) return null
        // A changed list (a refold as counts come in) is not started again: that composed a
        // screenful afresh on every refold. A handle holds its item by key, so the layout still
        // uses it wherever the item moved; an index whose item changed is only a row not ready.
        if (lastFirst >= 0 && first != lastFirst) forward = first > lastFirst
        lastFirst = first
        lastLast = last
        lastTotal = total
        val screen = last - first + 1
        val ahead = if (forward) (last + 1)..minOf(last + screen, total - 1) else maxOf(first - screen, 0)..(first - 1)
        val back = if (forward) maxOf(first - behind, 0)..(first - 1) else (last + 1)..minOf(last + behind, total - 1)
        val drop = held.filter { it !in ahead && it !in back }
        held.removeAll(drop.toSet())
        // Nearest first, so a jump that comes early still finds its first rows ready.
        val add = ArrayList<Int>()
        for (index in if (forward) ahead else ahead.reversed()) if (held.add(index)) add += index
        for (index in back) if (held.add(index)) add += index
        return Change(drop, add)
    }
}

/**
 * Keeps a row that rests at its start there when cards arrive in front of the first one (a newer
 * video, or the row filled in from the cache and then from the chats). A lazy list holds on to the
 * card it shows first, so without this a row loaded in two goes opens a card or more along, with
 * the newest one hidden off its start. A row the viewer has scrolled is left where they put it.
 */
@Composable
fun KeepAtStart(state: LazyListState, moved: () -> Boolean = { false }) {
    val movedNow = androidx.compose.runtime.rememberUpdatedState(moved)
    androidx.compose.runtime.LaunchedEffect(state) {
        var atStart = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        androidx.compose.runtime.snapshotFlow {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            when {
                // [moved]: a row that jumps to the remote's tile at once (a television's) scrolls
                // within one frame, which this never sees as scrolling, and would send it back.
                scrolling || movedNow.value() -> atStart = false
                index == 0 && offset == 0 -> atStart = true
                atStart -> state.scrollToItem(0)
            }
        }
    }
}
