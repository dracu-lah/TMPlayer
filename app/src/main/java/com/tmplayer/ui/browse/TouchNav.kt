package com.tmplayer.ui.browse

import android.view.accessibility.AccessibilityManager
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.Account
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.captionStyle
import com.tmplayer.ui.components.pressScale
import androidx.compose.material3.ripple
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * Where the phone's floating bar can take the viewer, in the bar's order. Search comes after them,
 * a button that opens the search over the page rather than a page of its own, and the profile
 * picture last, which opens [ProfilePopover] with the viewer's own lists and Settings.
 */
internal enum class TouchDestination { Home, Chats }

/** The profile picture's place on the bar: after every [TouchDestination] and Search. */
internal val PROFILE_SLOT = TouchDestination.entries.size + 1

/**
 * Which of the bar's slots carries the sliding highlight: [current]'s button, or the picture on a
 * page behind it ([inProfile]: Favourites, History, Watch later, Downloads), or none.
 */
internal fun barHighlightIndex(current: TouchDestination?, inProfile: Boolean = false): Int? =
    current?.ordinal ?: if (inProfile) PROFILE_SLOT else null

/**
 * What the floating bar and the popover behind the picture show and do, the same on every page
 * the bar is on. Built once, by whatever hosts the bar ([TouchNavHost]).
 */
@Immutable
internal class TouchNav(
    val account: Account?,
    val favoriteCount: Int,
    /** Downloads in flight, badged on the Downloads row and on the picture. */
    val downloadCount: Int,
    /** "Your viewing" over the picture's menu: videos part way through, and videos watched. */
    val watchingCount: Int = 0,
    val watchedCount: Int = 0,
    val updateVersion: String?,
    val onHome: () -> Unit,
    val onChats: () -> Unit,
    val onSearch: () -> Unit,
    /** Settings, from the picture's popover (the phone, the television's account menu). */
    val onSettings: () -> Unit,
    val onFavourites: () -> Unit,
    val onDownloads: () -> Unit,
    val onHistory: (HistoryTab) -> Unit,
    val onUpdate: () -> Unit,
)

/**
 * What one page asks of the floating bar while it is the page on screen.
 *
 * @param current the bar button lit for this page, or null for a page behind the picture.
 * @param inProfile the picture is ringed: Favourites, History, Watch later and Downloads.
 * @param hidden the bar steps out of the way for now (a search open, a selection under way)
 *   while the page still keeps its room at the foot.
 * @param actions the page's own buttons, in a small pill above the bar; null for none.
 */
internal class TouchPageSpec(
    val current: TouchDestination?,
    val inProfile: Boolean = false,
    val hidden: Boolean = false,
    val actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Where the highlight stands while Home and Chats are swiped between: 0 on Home, 1 on Chats,
     * and the fraction between them mid-swipe. Read as the bar is laid out, so a swipe moves the
     * highlight without recomposing the bar. Null leaves the highlight on [current].
     */
    val position: (() -> Float)? = null,
)

/**
 * The shared state of the one floating bar: which page is on screen ([pageKey]), what each page
 * asked of the bar, and a search asked for from a page that has none of its own.
 *
 * One bar for every page, rather than a bar per page, is what lets its highlight slide from Home to
 * Chats rather than blink, and what keeps it still while the pages under it change.
 */
@Stable
internal class TouchChrome {
    var pageKey by mutableStateOf<Any?>(null)
    internal val specs = mutableStateMapOf<Any, TouchPageSpec>()

    /** Set by the bar's magnifier on a page without a search; the browser opens its search and clears it. */
    var searchAsked by mutableStateOf(false)

    /** A bar button pressed again on its own page, for that page to go back to its top ([ScrollToTopOnReselect]). */
    internal val reselected = MutableSharedFlow<TouchDestination>(extraBufferCapacity = 1)

    internal val bar = BarVisibility()
}

internal val LocalTouchChrome = staticCompositionLocalOf<TouchChrome?> { null }

/** The key a page reports its [TouchPageSpec] under: the screen it belongs to. */
internal val LocalTouchPageKey = staticCompositionLocalOf<Any> { DEFAULT_PAGE }

/** The bar's shared-element scope, for the search field that grows out of its magnifier. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalTouchShared = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Tells the floating bar what this page wants of it, for as long as the page is composed.
 * Nothing at all happens outside a [TouchNavHost].
 */
@Composable
internal fun TouchPage(spec: TouchPageSpec?) {
    val chrome = LocalTouchChrome.current ?: return
    val key = LocalTouchPageKey.current
    SideEffect {
        if (spec == null) chrome.specs.remove(key) else chrome.specs[key] = spec
    }
    DisposableEffect(chrome, key) { onDispose { chrome.specs.remove(key) } }
}

/**
 * The phone's floating bar, hosted once over every page: Home, Chats, Search and the viewer's
 * picture, with the popover of Favourites, Downloads, History, Watch later, Settings and Update
 * behind the picture.
 *
 * The bar floats, so it takes no room from the listing: it slides away as the list is pushed up
 * and comes back on the first pull down, on a change of page and on the way back from the player.
 * It never hides while TalkBack is on (a control that moves away is one a screen reader cannot
 * find again), and it is out of the way while the keyboard is up. How much of the screen it keeps
 * is [LocalBottomChrome], which the lists under it pad their ends by. A page with no
 * [TouchPageSpec] (a chat, Settings, About) has no bar.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TouchNavHost(
    nav: TouchNav,
    chrome: TouchChrome,
    /** The page on screen: whatever [LocalTouchPageKey] its [TouchPage] reports under. */
    pageKey: Any,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion() || Motion.reduced
    val bar = chrome.bar
    var profileOpen by remember { mutableStateOf(false) }
    SideEffect { chrome.pageKey = pageKey }
    val spec = chrome.specs[pageKey]

    // Back closes the popover before it leaves the screen.
    BackHandler(enabled = profileOpen) { profileOpen = false }

    // The bar comes back whenever the page underneath changes: a bar left hidden across a change
    // of page has no pull-down to restore it on a page too short to scroll.
    LaunchedEffect(pageKey, spec?.current, spec?.inProfile, spec?.hidden) { bar.show() }
    // And on the way back from the player, an activity coming forward rather than a recomposition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) bar.show() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val talkBack = rememberTouchExploration()
    val keyboard = WindowInsets.isImeVisible

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val navigationBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    // The spec as it was when the bar was last out, so the bar does not redraw empty on its way out.
    val lastShown = remember { arrayOf(TouchPageSpec(current = null)) }
    if (spec != null && !spec.hidden) lastShown[0] = spec

    // A press on the button of the page already on screen takes that page back to its top, as a
    // tab bar does everywhere else; a press on any other button goes there.
    fun retap(destination: TouchDestination, go: () -> Unit): () -> Unit = {
        if (lastShown[0].current == destination) chrome.reselected.tryEmit(destination) else go()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A medium or expanded window (a tablet, a phone on its side, a wide split screen) gets the
        // bar as a rail down the start edge, as Material's adaptive layout asks: a bar across the
        // foot of a short, wide window takes height the page needs.
        val rail = maxWidth >= RAIL_FROM
        val barShown = spec != null && !spec.hidden && (rail || ((bar.shown || talkBack) && !keyboard))
        // What the bar keeps, measured from the bottom of the window, with the pill of the page's
        // own buttons over it when there is one. A rail keeps nothing at the foot but that pill.
        val room = if (spec == null) 0.dp else navigationBar + touchShellClearance(withActions = spec.actions != null, rail = rail)
        // And the room a rail keeps at the start, which the page is laid out beside. A page with
        // no bar (a chat, Settings) has the whole width; the change eases rather than jumps.
        val safe = WindowInsets.safeDrawing
        val railInset = with(density) {
            (if (direction == LayoutDirection.Ltr) safe.getLeft(this, direction) else safe.getRight(this, direction)).toDp()
        }
        val railRoom by animateDpAsState(
            targetValue = if (rail && spec != null) railInset + RAIL_MARGIN + RAIL_WIDTH else 0.dp,
            animationSpec = Motion.standard(if (reduced) 0 else Motion.Normal),
            label = "railRoom",
        )
        // The mini player (U8): a docked video's bar, over every page, above the floating bar
        // when that is out and at the foot when it is not. Out of the way while typing.
        val docked by com.tmplayer.player.MiniDock.docked.collectAsStateWithLifecycle()
        val miniShown = docked != null && !keyboard
        // The kids lock stays in force: a docked video from a chat it now leaves out goes.
        val kidsAllowed by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
        LaunchedEffect(docked, kidsAllowed) {
            val player = docked ?: return@LaunchedEffect
            if (!com.tmplayer.data.KidsLock.allows(kidsAllowed, player.chatId)) com.tmplayer.player.MiniDock.dismiss()
        }
        val miniFoot by animateDpAsState(
            targetValue = miniFootOf(
                navigationBar = navigationBar,
                rail = rail,
                barShown = barShown,
                withActions = spec?.actions != null,
                room = room,
            ),
            animationSpec = Motion.standard(if (reduced) 0 else Motion.Normal),
            label = "miniFoot",
        )
        val miniRoom = if (miniShown) MINI_DOCK_HEIGHT + BAR_GAP + if (spec == null) BAR_MARGIN else 0.dp else 0.dp

        SharedTransitionLayout(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalBottomChrome provides room + miniRoom,
                    LocalTouchChrome provides chrome,
                    LocalTouchShared provides this@SharedTransitionLayout,
                    LocalTouchRail provides rail,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // The rail's room counts as the page's start inset from here on, so a
                            // page that pads by the safe drawing area does not count a cutout twice.
                            .padding(start = railRoom)
                            .consumeWindowInsets(PaddingValues(start = railRoom))
                            .nestedScroll(bar.connection),
                    ) { content() }
                }

                AnimatedVisibility(
                    visible = barShown,
                    modifier = Modifier.align(if (rail) Alignment.CenterStart else Alignment.BottomCenter),
                    // Back up on a spring, a touch of give as it lands, and away on a plain ease: the
                    // bar answers a pull at once and leaves without fuss. A rail comes from its edge.
                    enter = when {
                        reduced -> fadeIn(Motion.standard(0))
                        rail -> slideInHorizontally(BAR_SPRING) { -it } + fadeIn(Motion.standard(Motion.Fast))
                        else -> slideInVertically(BAR_SPRING) { it } + fadeIn(Motion.standard(Motion.Fast))
                    },
                    exit = when {
                        reduced -> fadeOut(Motion.standard(0))
                        rail -> slideOutHorizontally(Motion.standard(Motion.Normal)) { -it } + fadeOut(Motion.standard(Motion.Fast))
                        else -> slideOutVertically(Motion.standard(Motion.Normal)) { it } + fadeOut(Motion.standard(Motion.Fast))
                    },
                ) {
                    val shown = lastShown[0]
                    // The search field grows out of the bar's Search button.
                    val searchShared = if (reduced) {
                        Modifier
                    } else {
                        Modifier.sharedBounds(
                            rememberSharedContentState(SEARCH_KEY),
                            animatedVisibilityScope = this@AnimatedVisibility,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        )
                    }
                    val barContent: @Composable () -> Unit = {
                        FloatingBar(
                            current = shown.current,
                            inProfile = shown.inProfile,
                            position = shown.position,
                            account = nav.account,
                            badged = nav.downloadCount > 0 || nav.updateVersion != null,
                            onHome = retap(TouchDestination.Home, nav.onHome),
                            onChats = retap(TouchDestination.Chats, nav.onChats),
                            onSearch = nav.onSearch,
                            onProfile = { profileOpen = true },
                            searchShared = searchShared,
                            vertical = rail,
                        )
                    }
                    if (rail) {
                        barContent()
                    } else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            // The page's own buttons: a small pill at the end, over the bar, so the
                            // top of the screen holds nothing to press.
                            shown.actions?.let { actions -> ActionsPill(actions, Modifier.padding(end = 16.dp, bottom = BAR_GAP)) }
                            barContent()
                        }
                    }
                }

                // Beside a rail the page's own buttons keep their pill at the bottom end, alone.
                if (rail) {
                    val actions = lastShown[0].actions
                    AnimatedVisibility(
                        visible = barShown && actions != null,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        enter = fadeIn(Motion.standard(if (reduced) 0 else Motion.Fast)),
                        exit = fadeOut(Motion.standard(if (reduced) 0 else Motion.Fast)),
                    ) {
                        if (actions != null) {
                            ActionsPill(
                                actions,
                                Modifier
                                    .windowInsetsPadding(safe.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                                    .padding(end = 16.dp, bottom = BAR_MARGIN),
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = miniShown,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp + railRoom, end = 16.dp, bottom = miniFoot)
                        .widthIn(max = MINI_DOCK_MAX_WIDTH),
                    enter = if (reduced) fadeIn(Motion.standard(0)) else slideInVertically(BAR_SPRING) { it / 2 } + fadeIn(Motion.standard(Motion.Fast)),
                    exit = if (reduced) fadeOut(Motion.standard(0)) else slideOutVertically(Motion.standard(Motion.Normal)) { it / 2 } + fadeOut(Motion.standard(Motion.Fast)),
                ) {
                    // The last player docked, so the bar does not redraw empty on its way out.
                    val shownPlayer = remember { arrayOf<com.tmplayer.player.DockedPlayer?>(null) }
                    docked?.let { shownPlayer[0] = it }
                    shownPlayer[0]?.let { MiniDockBar(it) }
                }

                ProfilePopover(
                    open = profileOpen,
                    reduced = reduced,
                    rail = rail,
                    railRoom = railRoom,
                    favoriteCount = nav.favoriteCount,
                    downloadCount = nav.downloadCount,
                    watchingCount = nav.watchingCount,
                    watchedCount = nav.watchedCount,
                    updateVersion = nav.updateVersion,
                    onDismiss = { profileOpen = false },
                    onWatched = { profileOpen = false; nav.onHistory(HistoryTab.Watched) },
                    onFavourites = { profileOpen = false; nav.onFavourites() },
                    onDownloads = { profileOpen = false; nav.onDownloads() },
                    onHistory = { profileOpen = false; nav.onHistory(HistoryTab.Continue) },
                    onWatchLater = { profileOpen = false; nav.onHistory(HistoryTab.WatchLater) },
                    onSettings = { profileOpen = false; nav.onSettings() },
                    onUpdate = { profileOpen = false; nav.onUpdate() },
                )
            }
        }
    }
}

/** A page's own buttons, in one small floating pill. */
@Composable
private fun ActionsPill(actions: @Composable RowScope.() -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(ACTIONS_HEIGHT)
            .shadow(BAR_SHADOW, CircleShape)
            .clip(CircleShape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), CircleShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
}

/**
 * Whether the floating bar is a rail down the start edge in this window ([RAIL_FROM] wide or
 * more), for a page under it to pad its foot by the right amount.
 */
internal val LocalTouchRail = compositionLocalOf { false }

/**
 * Takes a list back to its top when the bar button of the page it is on is pressed again, as a
 * tab bar does. Only the page on screen answers ([LocalPageOnScreen]): Home and Chats stay
 * composed side by side in their pager.
 */
@Composable
internal fun ScrollToTopOnReselect(destination: TouchDestination, toTop: suspend (animate: Boolean) -> Unit) {
    val chrome = LocalTouchChrome.current ?: return
    val onScreen = LocalPageOnScreen.current
    val reduced = rememberReducedMotion() || Motion.reduced
    val action by rememberUpdatedState(toTop)
    LaunchedEffect(chrome, onScreen, destination, reduced) {
        if (!onScreen) return@LaunchedEffect
        chrome.reselected.collect { if (it == destination) action(!reduced) }
    }
}

/** Back to the top of a list: most of a long way is jumped, so the glide is short. */
internal suspend fun LazyListState.toTop(animate: Boolean) {
    if (!animate) return scrollToItem(0)
    if (firstVisibleItemIndex > TOP_GLIDE_ITEMS) scrollToItem(TOP_GLIDE_ITEMS)
    animateScrollToItem(0)
}

internal suspend fun LazyGridState.toTop(animate: Boolean) {
    if (!animate) return scrollToItem(0)
    if (firstVisibleItemIndex > TOP_GLIDE_ITEMS * 3) scrollToItem(TOP_GLIDE_ITEMS * 3)
    animateScrollToItem(0)
}

/** How many rows from the top a second press of a tab glides over; from further it jumps first. */
private const val TOP_GLIDE_ITEMS = 8

/**
 * The touch shell: the page (which draws its own title, see [PhonePageFrame]), and the search page
 * over everything while it is open. The floating bar is [TouchNavHost]'s, shared with Downloads;
 * this tells it which page is up and what the page's own buttons are. Outside a host (the screenshot
 * fixture) the shell hosts a bar of its own from [nav].
 *
 * The page's buttons ride in a small pill over the bar rather than in the top bar, so a large phone
 * held in one hand reaches all of them; the top keeps only the title.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun TouchBrowseShell(
    nav: TouchNav,
    selected: BrowseSection,
    searchOpen: Boolean,
    onOpenSearch: () -> Unit,
    /**
     * The search page, drawn over everything while [searchOpen]. [field] goes on its search field,
     * which is what the bar's magnifier turns into.
     */
    searchScreen: @Composable (field: Modifier) -> Unit,
    /** The page's own buttons, in the pill over the bar. */
    actions: (@Composable RowScope.() -> Unit)? = null,
    /** The highlight's place while Home and Chats are swiped between ([TouchPageSpec.position]). */
    barPosition: (() -> Float)? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalTouchChrome.current
    if (host == null) {
        val own = remember { TouchChrome() }
        TouchNavHost(nav, own, DEFAULT_PAGE) {
            TouchBrowseShell(nav, selected, searchOpen, onOpenSearch, searchScreen, actions, barPosition, content)
        }
        return
    }
    val reduced = rememberReducedMotion() || Motion.reduced

    TouchPage(
        TouchPageSpec(
            current = when {
                selected.isHome -> TouchDestination.Home
                selected.onChatsPage -> TouchDestination.Chats
                else -> null
            },
            inProfile = !selected.isHome && !selected.onChatsPage,
            hidden = searchOpen,
            actions = actions,
            position = barPosition,
        ),
    )
    // The bar's magnifier, pressed on a page (Downloads) that has no search of its own.
    LaunchedEffect(host.searchAsked) {
        if (host.searchAsked) {
            host.searchAsked = false
            onOpenSearch()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Each page draws its own title ([PhonePageFrame]), so a title swiped in moves with its page.
        content()

        val shared = LocalTouchShared.current
        AnimatedVisibility(
            visible = searchOpen,
            enter = if (reduced) EnterTransition.None else fadeIn(Motion.standard(Motion.Normal)),
            exit = if (reduced) ExitTransition.None else fadeOut(Motion.standard(Motion.Normal)),
        ) {
            val field = if (reduced || shared == null) {
                Modifier
            } else {
                with(shared) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(SEARCH_KEY),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    )
                }
            }
            // Whether the field has finished growing out of the magnifier, for the keyboard to wait
            // on: a keyboard rising under a field still on its way moves the place it is going to.
            val transition = this.transition
            val entered = remember(transition) {
                { transition.currentState == androidx.compose.animation.EnterExitState.Visible && !transition.isRunning }
            }
            CompositionLocalProvider(LocalSearchEntered provides entered) { searchScreen(field) }
        }
    }
}

/** Whether the search page has finished coming in ([TouchBrowseShell]); always, outside one. */
internal val LocalSearchEntered = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * How much a page under the bar pads the end of its list by, the navigation bar not included:
 * the bar, its margin and gap, and the pill of the page's own buttons when it has one. Beside a
 * [rail] the foot keeps only that pill, on its margin.
 */
/** The mini player bar's widest, on a tablet or a phone on its side. */
internal val MINI_DOCK_MAX_WIDTH = 560.dp

/**
 * How far above the window's foot the mini player's bar sits: over the floating bar and the
 * page's pill of buttons while they are out ([room]), at the foot beside a rail (over its pill
 * when there is one), and at the foot when the bar is away.
 */
internal fun miniFootOf(navigationBar: Dp, rail: Boolean, barShown: Boolean, withActions: Boolean, room: Dp): Dp = when {
    rail -> navigationBar + maxOf(BAR_MARGIN, touchShellClearance(withActions = withActions, rail = true))
    barShown -> room
    else -> navigationBar + BAR_MARGIN
}

internal fun touchShellClearance(withActions: Boolean, rail: Boolean = false): Dp = when {
    !rail -> BAR_MARGIN + BAR_HEIGHT + BAR_GAP + if (withActions) ACTIONS_HEIGHT + BAR_GAP else 0.dp
    withActions -> BAR_MARGIN + ACTIONS_HEIGHT + BAR_GAP
    else -> 0.dp
}

/**
 * Whether the bar is out, from how the list under it is being moved: pushed up past [HIDE_AFTER_PX]
 * hides it, any pull back down shows it, and a pull at the very top (which nothing above consumes)
 * shows it too.
 */
@Stable
internal class BarVisibility {

    var shown by mutableStateOf(true)
        private set
    private var travelled by mutableFloatStateOf(0f)

    fun show() {
        shown = true
        travelled = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val moved = consumed.y
            when {
                moved < 0f -> {
                    travelled = (travelled + moved).coerceAtMost(0f)
                    if (travelled < -HIDE_AFTER_PX) shown = false
                }
                moved > 0f || available.y > 0f -> show()
            }
            return Offset.Zero
        }
    }
}

/** Whether TalkBack, or anything else exploring the screen by touch, is on, followed as it changes. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * The floating bar: one rounded pill, clear of the screen's edge and the navigation bar, with a
 * soft shadow and the floating hairline. Each button is its icon with its name in small type under
 * it, as a tab bar has. The current page's button sits on a rounded pill in the accent, which
 * slides from button to button (and follows a swipe between Home and Chats as it goes). On a page
 * behind the picture (Favourites, History, Watch later, Downloads) the pill moves under the
 * picture, which wears an accent ring too. Search is the button the search field grows out of
 * ([searchShared]). Every press and the highlight are pills, round like the bar they sit in.
 *
 * [vertical] stands the same bar up as a rail down the start edge, for a wide window: the same
 * buttons in the same order, top to bottom, with the highlight sliding down rather than across.
 */
@Composable
private fun FloatingBar(
    current: TouchDestination?,
    inProfile: Boolean,
    position: (() -> Float)?,
    account: Account?,
    badged: Boolean,
    onHome: () -> Unit,
    onChats: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    searchShared: Modifier,
    vertical: Boolean = false,
) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    val outer = if (vertical) {
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            // A short window (a phone on its side with the keyboard up) scrolls the rail rather
            // than cutting its last button off.
            .verticalScroll(rememberScrollState())
            // A scroll clips to its bounds, so the room above and below is the shadow's as well:
            // with less, its soft edge is cut off square under the rail on a light page.
            .padding(start = RAIL_MARGIN, top = 8.dp + BAR_SHADOW * 2, bottom = 8.dp + BAR_SHADOW * 2)
    } else {
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = BAR_MARGIN)
    }
    // Concentric: the highlight's corners (half its 52 dp height) sit the same distance inside the
    // bar's as the padding around it, so the two curves run parallel. The bar is a pill of 64 dp
    // with 6 dp around the highlight; the rail is as wide as the bar is tall plus its margins, so
    // it takes the bar's 32 dp corners rather than a full semicircle, which no button could follow.
    val barShape = if (vertical) RAIL_SHAPE else CircleShape
    Box(outer, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(RAIL_WIDTH) else Modifier.height(BAR_HEIGHT))
                .shadow(BAR_SHADOW, barShape)
                .clip(barShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), barShape)
                .padding(if (vertical) PaddingValues(vertical = RAIL_INSET) else PaddingValues(horizontal = 6.dp)),
            contentAlignment = Alignment.Center,
        ) {
          Box(contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart) {
            // One highlight under the chosen page, sliding to the next one rather than blinking
            // off under one button and on under another, so the bar shows where the page went.
            val index = barHighlightIndex(current, inProfile)
            val step = with(LocalDensity.current) { ((if (vertical) BUTTON_HEIGHT else BUTTON_WIDTH) + BAR_SPACING).toPx() }
            // Where the highlight stands, in buttons from the start: sprung to the chosen one, or,
            // while Home and Chats are on screen, following the pager (sprung only the first time,
            // coming in from another page, then held to the finger).
            val slot = remember { Animatable((index ?: 0).toFloat()) }
            val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
            LaunchedEffect(index, position == null) {
                if (position != null || index == null) return@LaunchedEffect
                if (reduced) slot.snapTo(index.toFloat()) else slot.animateTo(index.toFloat(), spring)
            }
            if (position != null) {
                LaunchedEffect(position) {
                    var first = true
                    snapshotFlow { position() }.collectLatest { at ->
                        val glide = first && !reduced
                        first = false
                        if (glide) slot.animateTo(at, spring) else slot.snapTo(at)
                    }
                }
            }
            val shown by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (index != null) 1f else 0f,
                animationSpec = Motion.standard(if (reduced) 0 else Motion.Fast),
                label = "barHighlightShown",
            )
            Box(
                Modifier
                    .offset {
                        val along = (slot.value * step).roundToInt()
                        if (vertical) IntOffset(0, along) else IntOffset(along, 0)
                    }
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    .graphicsLayer {
                        alpha = shown
                        // Stretched a little along the way while it springs to another button and
                        // round again as it lands: it reads as moving, not as being redrawn.
                        if (!reduced) {
                            val stretch = (kotlin.math.abs(slot.velocity) * STRETCH_PER_SPEED).coerceAtMost(STRETCH_MAX)
                            if (vertical) {
                                scaleY = 1f + stretch
                                scaleX = 1f - stretch / 3f
                            } else {
                                scaleX = 1f + stretch
                                scaleY = 1f - stretch / 3f
                            }
                        }
                    }
                    .clip(HIGHLIGHT_SHAPE)
                    .background(Tone.accent.copy(alpha = 0.16f)),
            )
            val buttons: @Composable () -> Unit = {
                BarButton(TmIcons.Home, s.browseTabHome, current == TouchDestination.Home, onHome)
                BarButton(TmIcons.Chats, s.browseTabChats, current == TouchDestination.Chats, onChats)
                BarButton(TmIcons.Search, s.commonSearch, false, onSearch, role = Role.Button, modifier = searchShared)
                ProfileButton(account, inProfile, badged, onProfile)
            }
            if (vertical) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BAR_SPACING)) { buttons() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BAR_SPACING)) { buttons() }
            }
          }
        }
    }
}

/** The viewer's picture on the bar, which opens the popover of their own lists. */
@Composable
private fun ProfileButton(account: Account?, inProfile: Boolean, badged: Boolean, onProfile: () -> Unit) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    val profilePress = remember { MutableInteractionSource() }
    // The one button on the bar with no caption under it: its name is in a tooltip.
    com.tmplayer.ui.components.IconTip(s.navProfile) {
        Column(
            Modifier
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .pressScale(profilePress)
                .clip(HIGHLIGHT_SHAPE)
                .clickable(interactionSource = profilePress, indication = ripple(), role = Role.Button, onClick = onProfile)
                .semantics {
                    contentDescription = s.navProfile
                    selected = inProfile
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
          Box(contentAlignment = Alignment.Center) {
            // A ring round the picture while one of its pages is open: the lists behind it are
            // somewhere the bar can say the viewer is, without lighting a button they are not on.
            val ring by androidx.compose.animation.animateColorAsState(
                if (inProfile) Tone.accent else Color.Transparent,
                animationSpec = Motion.standard(if (reduced) 0 else Motion.Fast),
                label = "profileRing",
            )
            Box(Modifier.size(PICTURE + 6.dp).border(1.5.dp, ring, CircleShape))
            ProfilePicture(account, PICTURE)
            if (badged) {
                // News behind the picture: a download running, or an update to install.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Tone.caution)
                        .border(1.5.dp, FloatingTone.menu, CircleShape),
                )
            }
          }
        }
    }
}

/** A bar button's name under its icon, in the accent while its page is up. */
@Composable
private fun BarCaption(text: String, chosen: Boolean) {
    val ink by androidx.compose.animation.animateColorAsState(if (chosen) Tone.accent else Tone.muted, label = "barCaption")
    // A long word steps down to fit the 64 dp button ("Einstellungen" was cut to "Einstellung…").
    // Clip, not Ellipsis, because an ellipsized line never reports an overflow and so never steps.
    // The side padding keeps it clear of the pill's rounded ends, which the button clips to and
    // which reach about 7 dp in at the caption's height.
    val style = captionStyle()
    Text(
        text,
        style = style,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        modifier = Modifier.padding(top = 2.dp, start = 7.dp, end = 7.dp),
    )
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
    role: Role = Role.Tab,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .size(BUTTON_WIDTH, BUTTON_HEIGHT)
            .pressScale(press)
            .clip(HIGHLIGHT_SHAPE)
            .clickable(interactionSource = press, indication = ripple(), role = role, onClick = onClick)
            .semantics {
                contentDescription = label
                if (role == Role.Tab) selected = chosen
                this.role = role
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The highlight behind is the bar's (see FloatingBar); the icon takes its colour as it lands
        // and fills in with a drop (SelectableIcon).
        val tint by androidx.compose.animation.animateColorAsState(if (chosen) Tone.accent else Tone.text, label = "barIcon")
        com.tmplayer.ui.components.SelectableIcon(icon, selected = chosen, tint = tint, size = 24.dp)
        BarCaption(label, chosen)
    }
}

@Composable
private fun ProfilePicture(account: Account?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (account != null) {
            MediaPreview(
                miniThumbnail = account.miniThumbnail,
                thumbnailFileId = account.photoFileId,
                fallbackLabel = account.name,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Icon(TmIcons.Account, contentDescription = null, tint = Tone.muted, modifier = Modifier.align(Alignment.Center).size(size * 0.7f))
        }
    }
}

/**
 * The viewer's own lists, behind their picture: a small card that grows out of the bottom end
 * corner over a dimmed screen, with Favourites, Downloads, History and Watch later, then Settings
 * (which carries the account, Sign out and About) and Update when there is one.
 * A tap outside, Back, or picking a row closes it.
 */
@Composable
private fun ProfilePopover(
    open: Boolean,
    reduced: Boolean,
    /** Beside a rail the card grows out of the bottom start corner, next to the picture. */
    rail: Boolean,
    railRoom: Dp,
    favoriteCount: Int,
    downloadCount: Int,
    watchingCount: Int,
    watchedCount: Int,
    updateVersion: String?,
    onDismiss: () -> Unit,
    onWatched: () -> Unit,
    onFavourites: () -> Unit,
    onDownloads: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
) {
    val s = LocalStrings.current
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.standard(if (reduced) 0 else Motion.Fast)),
        exit = fadeOut(Motion.standard(if (reduced) 0 else Motion.Fast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FloatingTone.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // The corner the card grows from, in screen terms: the end one over a bar, the start one
    // beside a rail.
    val origin = TransformOrigin(if (rail == ltr) 0f else 1f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (rail) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                        .padding(top = 8.dp, bottom = BAR_MARGIN, start = railRoom + BAR_GAP, end = 16.dp)
                } else {
                    Modifier
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(top = 8.dp, bottom = BAR_MARGIN + BAR_HEIGHT + BAR_GAP, end = 16.dp, start = 16.dp)
                },
            ),
    ) {
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(if (rail) Alignment.BottomStart else Alignment.BottomEnd),
            enter = if (reduced) {
                fadeIn(Motion.standard(0))
            } else {
                fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Motion.standard(Motion.Normal), initialScale = 0.85f, transformOrigin = origin)
            },
            exit = if (reduced) {
                fadeOut(Motion.standard(0))
            } else {
                fadeOut(Motion.standard(Motion.Fast)) + scaleOut(Motion.standard(Motion.Fast), targetScale = 0.9f, transformOrigin = origin)
            },
        ) {
            Column(
                Modifier
                    .widthIn(max = POPOVER_WIDTH)
                    .shadow(BAR_SHADOW, POPOVER_SHAPE)
                    .clip(POPOVER_SHAPE)
                    .background(FloatingTone.dialog)
                    .border(floatingBorder(), POPOVER_SHAPE)
                    // Taps inside the card stay inside it rather than reaching the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    // The rows' own 6 dp at the sides, so the last row's fill keeps it below too.
                    .padding(vertical = 6.dp),
            ) {
                com.tmplayer.ui.components.ViewingSummary(
                    watching = watchingCount,
                    watched = watchedCount,
                    starred = favoriteCount,
                    onWatching = onHistory,
                    onWatched = onWatched,
                    onStarred = onFavourites,
                )
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                val rows = buildList<@Composable (Modifier) -> Unit> {
                    add { m -> PopoverRow(TmIcons.StarOutline, s.browseTabFavourites, favoriteCount.takeIf { it > 0 }?.toString(), modifier = m, onClick = onFavourites) }
                    add { m -> PopoverRow(TmIcons.Download, s.navDownloads, downloadCount.takeIf { it > 0 }?.toString(), modifier = m, onClick = onDownloads) }
                    add { m -> PopoverRow(TmIcons.History, s.browseTabHistory, null, modifier = m, onClick = onHistory) }
                    add { m -> PopoverRow(TmIcons.Clock, s.browseTabWatchLater, null, modifier = m, onClick = onWatchLater) }
                    add { m -> PopoverRow(TmIcons.Settings, s.navSettings, null, modifier = m, onClick = onSettings) }
                    if (updateVersion != null) {
                        // Amber, as on the television's rail and the desktop's side bar: the one row that is news.
                        add { m -> PopoverRow(TmIcons.Refresh, s.navUpdate, updateVersion, ink = Tone.caution, modifier = m, onClick = onUpdate) }
                    }
                }
                rows.take(POPOVER_LISTS).forEach { it(Modifier) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                rows.drop(POPOVER_LISTS).forEach { it(Modifier) }
            }
        }
    }
}

@Composable
internal fun PopoverRow(
    icon: ImageVector,
    label: String,
    badge: String?,
    ink: Color = Tone.text,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    // A held or keyboard-focused row fills, as the desktop's popover rows do, so a press is seen
    // under the thumb and not only in the ripple round it.
    val pressed by press.collectIsPressedAsState()
    val focused by press.collectIsFocusedAsState()
    val fill by androidx.compose.animation.animateColorAsState(
        if (pressed || focused) Tone.surfaceHigh else Color.Transparent,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "popoverRowFill",
    )
    Row(
        modifier
            .fillMaxWidth()
            // A floor, not a fixed height: a thumb's 48 dp, and a longer word or a larger font grows the row.
            .heightIn(min = POPOVER_ROW)
            .padding(horizontal = 6.dp)
            // The card's 20 dp corner less the 6 dp inset, so a filled row runs parallel to it.
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp).pressScale(press))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = if (ink == Tone.text) Tone.onAccent else ink,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (ink == Tone.text) Tone.accent else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** The key the bar's magnifier and the search field share, for the one to grow into the other. */
private const val SEARCH_KEY = "search-field"

/** The bar's height: a button of icon and caption with a little room above and below it. */
internal val BAR_HEIGHT = 64.dp

/** Between the bar and the navigation bar (or the screen's edge). */
internal val BAR_MARGIN = 16.dp

/** Between the bar and whatever a list puts last, or a button floating above it. */
internal val BAR_GAP = 8.dp

/** One of the bar's buttons, its icon over its caption. */
private val BUTTON_WIDTH = 64.dp
private val BUTTON_HEIGHT = 52.dp

/** The highlight under the chosen button, and the shape a press ripples in: a pill, as the bar is. */
private val HIGHLIGHT_SHAPE = CircleShape

/** The pill of a page's own buttons over the bar: one row of 48 dp buttons. */
internal val ACTIONS_HEIGHT = 48.dp

/** The key a page reports under when nothing names it, which is the page outside any host. */
internal const val DEFAULT_PAGE = "page"

/**
 * From this window width the bar stands up as a rail down the start edge: Material's medium
 * window class, which a tablet, a phone on its side and a wide split screen all reach.
 */
internal val RAIL_FROM = 600.dp

/** The rail's width: one button and the bar's inner margin either side. */
private val RAIL_WIDTH = 76.dp

/** The rail's corners and the room above its first button and under its last, matching the bar's. */
private val RAIL_SHAPE = RoundedCornerShape(32.dp)
private val RAIL_INSET = 6.dp

/** Between the rail and the screen's start edge (or the cutout). */
private val RAIL_MARGIN = 12.dp

/** Between two of the bar's buttons. */
private val BAR_SPACING = 4.dp
private val PICTURE = 30.dp
internal val BAR_SHADOW = 8.dp
private val POPOVER_WIDTH = 208.dp
internal val POPOVER_ROW = 48.dp

/** The popover's rows before the divider: the four lists; Settings and Update come after it. */
private const val POPOVER_LISTS = 4
private val POPOVER_SHAPE = RoundedCornerShape(20.dp)

/** The bar coming back: quick, with a little give as it lands. */
private val BAR_SPRING = androidx.compose.animation.core.spring<IntOffset>(
    dampingRatio = 0.82f,
    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
)

/** How much the highlight stretches per button a second it is moving, and the most it stretches. */
private const val STRETCH_PER_SPEED = 0.025f
private const val STRETCH_MAX = 0.14f

/** How far a list has to be pushed up before the bar gets out of the way, so a nudge does not. */
private const val HIDE_AFTER_PX = 48f
