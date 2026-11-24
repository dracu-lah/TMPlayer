package com.tmplayer.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.tmplayer.data.HintIds
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Page and Channel keys in every long list, and Back to the top on the television (WP-12). One
// helper for the chat list, the video grid, History, Lists, Downloads and the TV search results,
// so no list needs its own jump. Pure Compose: no Android API, so it compiles for iOS and runs on
// the desktop (a keyboard's Page Up and Page Down are the same keys).
//
// A jump is one scrollToItem (never an animated scroll: a stick composes only the rows it lands on,
// not the thousands it skips) and then one focus request on the tile that is there, through that
// tile's FocusRequester, after a frame for the composition. A jump still running swallows the
// repeats, so a held Channel key is one screenful at a time and never queues up.

/** What a remote key means in a long list. The key names live here only (docs/agents/tv-keys.md). */
object PageKeys {
    /** 1 for the keys that go down a screenful, -1 for up, 0 for any other key. */
    fun step(key: Key, tv: Boolean = true): Int = when (key) {
        Key.PageDown, Key.ChannelDown -> 1
        Key.PageUp, Key.ChannelUp -> -1
        // A Bluetooth headset on a phone has these keys too: only a television's remote pages with them.
        Key.MediaFastForward -> if (tv) 1 else 0
        Key.MediaRewind -> if (tv) -1 else 0
        else -> 0
    }

    /** A Page or Channel key, as against Fast forward and Rewind, which a Fire TV remote has for it. */
    fun isPageOrChannel(key: Key): Boolean = when (key) {
        Key.PageDown, Key.PageUp, Key.ChannelDown, Key.ChannelUp -> true
        else -> false
    }

    /** How many rows a screenful is in a list that shows [visible] of them, as the chat picker counts it. */
    fun listScreenful(visible: Int): Int = (visible - 2).coerceAtLeast(3)

    /** After this many screens down the Back key goes to the top first (the plan's "more than 2"). */
    const val DEEP_SCREENS = 2

    /** After this many screens down the one-time hint says so. */
    const val HINT_SCREENS = 3

    /** How long "Back again to leave" stays. */
    const val TIP_MS = 2_000L

    /** Frames spent waiting for the landing tile to be composed before the nearest one is taken. */
    private const val EXACT_FRAMES = 2
    private const val TOTAL_FRAMES = 10

    internal fun plan(view: PageView, focused: Any?, dir: Int): PagePlan? {
        if (dir == 0 || view.total <= 0 || view.items.isEmpty()) return null
        val whole = view.items.filter { it.start >= view.viewStart && it.end <= view.viewEnd }.ifEmpty { view.items }
        val rows = whole.map { it.line }.distinct().size.coerceAtLeast(1)
        val step = rows * view.columns.coerceAtLeast(1)
        val at = view.items.firstOrNull { it.key == focused } ?: whole.first()
        val last = view.total - 1
        val target = (at.index + dir * step).coerceIn(0, last)
        if (target == 0) return PagePlan(0, 0, 0)
        // The list moves as far as the focus does, so the landing tile is where the old one was.
        val first = (view.firstIndex + (target - at.index)).coerceIn(0, last)
        return PagePlan(target, first, view.firstOffset)
    }

    /** The visible item nearest [target] that [take] accepts, the exact one first. */
    internal fun nearest(view: PageView, target: Int, exactOnly: Boolean, take: (Any) -> Boolean): Boolean {
        val exact = view.items.firstOrNull { it.index == target }
        if (exact != null && take(exact.key)) return true
        if (exactOnly) return false
        return view.items.sortedBy { kotlin.math.abs(it.index - target) }.any { take(it.key) }
    }

    internal suspend fun land(source: PageSource, tiles: PageTiles, plan: PagePlan): Boolean =
        landWith(source, plan, request = tiles::request, requestAny = tiles::requestAny, nextFrame = { withFrameNanos { } })

    /**
     * Scrolls, then puts the remote on the tile at the plan's target once it is composed (the exact
     * one for the first frames, the nearest visible one after). When no visible tile took the focus
     * in [TOTAL_FRAMES] frames, any tile of the list does, so the remote is never left on a tile that
     * the jump scrolled away. False only when the list has no tile at all.
     */
    internal suspend fun landWith(
        source: PageSource,
        plan: PagePlan,
        request: (Any) -> Boolean,
        requestAny: () -> Boolean,
        nextFrame: suspend () -> Unit,
    ): Boolean {
        source.scrollTo(plan.scrollIndex, plan.scrollOffset)
        repeat(TOTAL_FRAMES) { frame ->
            nextFrame()
            if (nearest(source.view(), plan.target, exactOnly = frame < EXACT_FRAMES, take = request)) return true
        }
        return requestAny()
    }
}

/** One lazy item as the jump sees it: its place in the list, its key and where it stands along the scroll. */
internal class PageItem(val index: Int, val key: Any, val line: Int, val start: Int, val end: Int)

/** The visible part of a lazy list or grid, read from its layout info. */
internal class PageView(
    val total: Int,
    val firstIndex: Int,
    val firstOffset: Int,
    val viewStart: Int,
    val viewEnd: Int,
    val columns: Int,
    val items: List<PageItem>,
)

/** Where to scroll to ([scrollIndex], [scrollOffset]) and which item to put the remote on ([target]). */
internal class PagePlan(val target: Int, val scrollIndex: Int, val scrollOffset: Int)

/** A lazy list or grid, as far as the jump needs. */
internal interface PageSource {
    fun view(): PageView
    suspend fun scrollTo(index: Int, offset: Int)
}

internal class GridSource(private val state: LazyGridState) : PageSource {
    override fun view(): PageView {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        return PageView(
            total = info.totalItemsCount,
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            viewStart = info.viewportStartOffset + info.beforeContentPadding,
            viewEnd = info.viewportEndOffset - info.afterContentPadding,
            columns = items.maxOfOrNull { it.column + it.span }?.coerceAtLeast(1) ?: 1,
            items = items.map { PageItem(it.index, it.key, it.row, it.offset.y, it.offset.y + it.size.height) },
        )
    }

    override suspend fun scrollTo(index: Int, offset: Int) = state.scrollToItem(index, offset)
}

internal class ListSource(private val state: LazyListState) : PageSource {
    override fun view(): PageView {
        val info = state.layoutInfo
        return PageView(
            total = info.totalItemsCount,
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            viewStart = info.viewportStartOffset + info.beforeContentPadding,
            viewEnd = info.viewportEndOffset - info.afterContentPadding,
            columns = 1,
            items = info.visibleItemsInfo.map { PageItem(it.index, it.key, it.index, it.offset, it.offset + it.size) },
        )
    }

    override suspend fun scrollTo(index: Int, offset: Int) = state.scrollToItem(index, offset)
}

/**
 * The tiles of one list that a jump can put the remote on, by their lazy item key, and the one the
 * remote is on now. A tile registers with [pageTile]; [settings] (null where there is none) is where
 * "a Page or Channel key arrived on this device" is recorded, which the Back to top hint reads.
 */
@Stable
class PageTiles(internal val settings: SettingsStore? = null) {
    private val requesters = HashMap<Any, FocusRequester>()

    /** The key of the tile the remote is on, or null when it is outside the list. */
    var focused: Any? = null
        private set

    /** True while the remote is on one of the list's tiles (read by Back, so it is state). */
    var listHasFocus by mutableStateOf(false)
        private set

    private var told = false

    internal fun attach(key: Any, requester: FocusRequester) {
        requesters[key] = requester
    }

    internal fun detach(key: Any, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
        if (focused == key) {
            focused = null
            listHasFocus = false
        }
    }

    internal fun focusChanged(key: Any, has: Boolean) {
        if (has) {
            focused = key
            listHasFocus = true
        } else if (focused == key) {
            focused = null
            listHasFocus = false
        }
    }

    internal fun request(key: Any): Boolean {
        val requester = requesters[key] ?: return false
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }

    /** Any tile of the list: the last resort when the one a jump aimed at never came. */
    internal fun requestAny(): Boolean = requesters.keys.firstOrNull { request(it) } != null

    /** The first Page or Channel key of this visit: kept on the device for the hint's wording. */
    internal suspend fun arrived() {
        if (told) return
        told = true
        runCatching { settings?.markHintSeen(HintIds.PAGE_KEY_ARRIVED) }
    }
}

@Composable
fun rememberPageTiles(settings: SettingsStore? = null): PageTiles = remember(settings) { PageTiles(settings) }

/**
 * Marks this tile as one a jump can land on, by its lazy item [key] (the key the list gave the
 * item). Put it first on the tile's modifier, before anything that takes focus: when the item's
 * root is a plain wrapper, the request goes to the first focusable inside it. Not gated on touch: a
 * keyboard on a tablet has the same keys.
 */
@Composable
fun Modifier.pageTile(tiles: PageTiles, key: Any): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(tiles, key) {
        tiles.attach(key, requester)
        onDispose { tiles.detach(key, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { tiles.focusChanged(key, it.hasFocus) }
}

/**
 * Page Down, Channel Down and Fast forward move the remote one screenful down a [state] grid, and
 * the Up keys one up; at the top, the first tile. When the remote is already on the tile an Up key
 * would land on, [onTop] runs (the chat picker moves to its filters).
 */
@Composable
fun Modifier.pageKeys(state: LazyGridState, tiles: PageTiles, onTop: (() -> Unit)? = null): Modifier =
    pageKeys(remember(state) { GridSource(state) }, tiles, onTop)

/** [pageKeys] for a list. */
@Composable
fun Modifier.pageKeys(state: LazyListState, tiles: PageTiles, onTop: (() -> Unit)? = null): Modifier =
    pageKeys(remember(state) { ListSource(state) }, tiles, onTop)

@Composable
private fun Modifier.pageKeys(source: PageSource, tiles: PageTiles, onTop: (() -> Unit)?): Modifier {
    val tv = !isTouch()
    val scope = rememberCoroutineScope()
    val busy = remember { BooleanArray(1) }
    return this
        .onPreviewKeyEvent { event ->
            val dir = PageKeys.step(event.key, tv)
            if (dir == 0 || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // A jump on its way swallows the repeats of a held key, so a slow stick is not queued up.
            if (busy[0]) return@onPreviewKeyEvent true
            val before = tiles.focused
            val plan = PageKeys.plan(source.view(), before, dir) ?: return@onPreviewKeyEvent false
            busy[0] = true
            val pageKey = PageKeys.isPageOrChannel(event.key)
            // The device note is a disk write: it never holds the jump back.
            if (pageKey) scope.launch { tiles.arrived() }
            scope.launch {
                try {
                    PageKeys.land(source, tiles, plan)
                    if (dir < 0 && onTop != null && tiles.focused == before) onTop()
                } finally {
                    busy[0] = false
                }
            }
            true
        }
}

/**
 * Back goes to the top of a long list first, on a television: from more than [PageKeys.DEEP_SCREENS]
 * screens down, the first Back scrolls to the top and puts the remote on the first tile, with a short
 * "Back again to leave"; the second Back leaves as it always did. Draws that note, and the one-time
 * hint (once a list passes [PageKeys.HINT_SCREENS] screens, worded for the Channel keys once one has
 * arrived on this device), at the foot of the [BoxScope] the list fills.
 *
 * [enabled] is false while something else owns Back (a selection, a search). Nothing on a phone or the
 * desktop: a finger has the scroll-to-top button ([ScrollToTop]), and the desktop a Home key.
 */
@Composable
fun BoxScope.BackToTop(state: LazyGridState, tiles: PageTiles, enabled: Boolean = true) {
    val source = remember(state) { GridSource(state) }
    BackToTop(source, tiles, enabled, depth = { state.screensDown() })
}

/** [BackToTop] for a list. */
@Composable
fun BoxScope.BackToTop(state: LazyListState, tiles: PageTiles, enabled: Boolean = true) {
    val source = remember(state) { ListSource(state) }
    BackToTop(source, tiles, enabled, depth = { state.screensDown() })
}

private fun LazyGridState.screensDown(): Float =
    firstVisibleItemIndex.toFloat() / layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

private fun LazyListState.screensDown(): Float =
    firstVisibleItemIndex.toFloat() / layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

@Composable
private fun BoxScope.BackToTop(source: PageSource, tiles: PageTiles, enabled: Boolean, depth: () -> Float) {
    if (isTouch()) return
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val deep by remember(depth) { derivedStateOf { depth() > PageKeys.DEEP_SCREENS } }
    val far by remember(depth) { derivedStateOf { depth() > PageKeys.HINT_SCREENS } }
    var tip by remember { mutableStateOf(false) }
    var hintGone by remember { mutableStateOf(false) }
    val busy = remember { BooleanArray(1) }
    // Only while the remote is in the list: on the bar or in a dialog, Back is theirs.
    BackHandler(enabled = enabled && deep && tiles.listHasFocus) {
        if (busy[0]) return@BackHandler
        busy[0] = true
        scope.launch {
            try {
                PageKeys.land(source, tiles, PagePlan(target = 0, scrollIndex = 0, scrollOffset = 0))
                tip = true
            } finally {
                busy[0] = false
            }
        }
    }
    LaunchedEffect(tip) {
        if (tip) {
            delay(PageKeys.TIP_MS)
            tip = false
        }
    }
    if (tip) {
        TipBubble(s.mainBackAgain, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Tv.SafeV + 8.dp))
        return
    }
    val settings = tiles.settings ?: return
    LaunchedEffect(far) {
        if (far) {
            delay(HINT_SHOWN_MS)
            hintGone = true
        }
    }
    val hints by settings.hintState.collectAsState(initial = null)
    val label = if (hints?.seen(HintIds.PAGE_KEY_ARRIVED) == true) s.tvPageKeysHint else s.tvBackToTopHint
    val hint: @Composable () -> Unit = {
        androidx.compose.foundation.layout.Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Tv.SafeV + 8.dp)) {
            OneTimeHint(
                settings = settings,
                id = HintIds.LONG_LIST,
                label = label,
                visible = enabled && far && !hintGone,
                style = HintStyle.Bubble,
            )
        }
    }
    // Under a screen's own slot when it provides one, else under one of its own, so the one hint per
    // screen visit applies to this hint too.
    if (LocalHintSlot.current == null) ProvideHintSlot(hint) else hint()
}

/** How long the long list hint stays once it has appeared. */
private const val HINT_SHOWN_MS = 5_000L
