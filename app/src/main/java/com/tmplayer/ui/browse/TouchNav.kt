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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.keepScrollInSheet
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.Account
import com.tmplayer.ui.components.FloatingChrome
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.toastFloor
import androidx.compose.ui.platform.testTag
import com.tmplayer.ui.components.captionStyle
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.ui.components.swallowClicks
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * Where the phone's floating bar can take the viewer, with the slot each button stands in: Home,
 * Chats, Search (a button the search field grows out of rather than a page of its own, so no
 * destination), then Downloads (R2: a place of its own, no longer behind the profile picture).
 * The account, your lists and Settings are behind the account's picture at the top end of Home
 * and of the Downloads page ([AccountButton]).
 */
internal enum class TouchDestination(val slot: Int) { Home(0), Chats(1), Downloads(3) }

/** Which of the bar's slots carries the sliding highlight: [current]'s button, or none. */
internal fun barHighlightIndex(current: TouchDestination?): Int? = current?.slot

/**
 * What the floating bar and the popover behind the picture show and do, the same on every page
 * the bar is on. Built once, by whatever hosts the bar ([TouchNavHost]).
 */
@Immutable
internal class TouchNav(
    val account: Account?,
    val favoriteCount: Int,
    /** Downloads running now, the number on the Downloads button of the bar. */
    val downloadCount: Int,
    /** "Your viewing" over the picture's menu: videos part way through, and videos watched. */
    val watchingCount: Int = 0,
    val watchedCount: Int = 0,
    val updateVersion: String?,
    val onHome: () -> Unit,
    val onChats: () -> Unit,
    val onSearch: () -> Unit,
    /** Settings, from the picture's menu (the phone, the television, the desktop). */
    val onSettings: () -> Unit,
    val onFavourites: () -> Unit,
    val onDownloads: () -> Unit,
    val onHistory: (HistoryTab) -> Unit,
    val onUpdate: () -> Unit,
    /** About, from the compact version row of the account menu; null falls back to Settings. */
    val onAbout: (() -> Unit)? = null,
    /** Switch profile (HX88): the picker, with two profiles or more on the device; null hides the row. */
    val onSwitchProfile: (() -> Unit)? = null,
    /**
     * The profile picker, whatever the profile count (WP-02): with one profile it shows the one tile
     * and Add account, so the account menu says "Add account" there and "Switch profile" when
     * [onSwitchProfile] is set. Null where there is no profile list (a preview).
     */
    val onSwitchOrAdd: (() -> Unit)? = null,
    /** Sign out, asked of the app shell's host ([com.tmplayer.ui.settings.SignOutHost]); null hides the entry. */
    val onSignOut: (() -> Unit)? = null,
    /**
     * Where the shell's sign out is (WP-02): the account menu stays under the first question so Cancel
     * puts the remote back on Sign out, and closes once the sign out has started, so a backup is
     * never tied to the menu.
     */
    val signOutPhase: com.tmplayer.ui.settings.SignOutPhase = com.tmplayer.ui.settings.SignOutPhase.Idle,
    /**
     * Something of the shell's own is over the pages now (WP-103): a profile panel or its PIN pad, or
     * the app lock. The one-time hint, a popup of its own, waits until it has gone.
     */
    val promptUp: Boolean = false,
    /**
     * The profile open now (WP-01): its saved photo and name stand in for the account's picture and
     * name while Telegram has not answered yet, or never will (offline), so the bar says who is
     * signed in from the first frame. Null where there is no profile list (a preview).
     */
    val profile: com.tmplayer.data.Profile? = null,
    /**
     * The television's standing sync status as a sentence ("Sync stopped. Turn it on again in Settings: Sync across my
     * devices."), or null when none stands: the avatar's dot and the account sheet's row once the bar's pill has folded.
     */
    val syncNote: String? = null,
    /** The row's press: Settings on the page of the sync switch. */
    val onSyncNote: (() -> Unit)? = null,
)

/**
 * What one page asks of the floating bar while it is the page on screen.
 *
 * @param current the bar button lit for this page, or null for a page behind the picture (Favourites,
 *   History, Watch later).
 * @param hidden the bar steps out of the way for now (a search open, a selection under way)
 *   while the page still keeps its room at the foot.
 * @param actions the page's own buttons, in a small pill above the bar; null for none.
 */
internal class TouchPageSpec(
    val current: TouchDestination?,
    val hidden: Boolean = false,
    val actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Where the highlight stands while Home and Chats are swiped between: 0 on Home, 1 on Chats,
     * and the fraction between them mid-swipe. Read as the bar is laid out, so a swipe moves the
     * highlight without recomposing the bar. Null leaves the highlight on [current].
     */
    val position: (() -> Float)? = null,
    /**
     * The page's refresh (WP-103), the same that pulling it down runs: Home and the chat list set
     * it, and the account menu shows its Refresh row only while it is set.
     */
    val refresh: (() -> Unit)? = null,
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

    /**
     * The page's refresh, asked from a keyboard (F5, Ctrl+R: WP-40); false where this page has none
     * (Downloads, a settings page), so the key stays free. It is the pull's and the menu row's own.
     */
    fun refreshNow(): Boolean {
        val refresh = specs[pageKey]?.refresh ?: return false
        refresh()
        return true
    }

    /** The account menu behind the picture ([AccountButton]) is open. */
    var youOpen by mutableStateOf(false)

    /** An update is waiting: the picture wears a dot. */
    var youBadge by mutableStateOf(false)

    /** The picture, for the keyboard's focus to come back to when the account menu closes. */
    internal val accountFocus = androidx.compose.ui.focus.FocusRequester()

    /** A bar button pressed again on its own page, for that page to go back to its top ([ScrollToTopOnReselect]). */
    internal val reselected = MutableSharedFlow<TouchDestination>(extraBufferCapacity = 1)

    internal val bar = BarVisibility()
}

internal val LocalTouchChrome = staticCompositionLocalOf<TouchChrome?> { null }

/**
 * The host's [TouchNav], for the account's picture on a page ([AccountButton]) to say who is signed
 * in. Not static: a new [TouchNav] each time the shell recomposes redraws only the pictures.
 */
internal val LocalTouchNav = compositionLocalOf<TouchNav?> { null }

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
 * The phone's floating bar, hosted once over every page: Home, Chats, Search and Downloads (badged
 * with how many are running), with the card of Favourites, History, Watch later, Settings and
 * Update behind the account's picture ([AccountButton]).
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
    /** The connection banner, drawn above the bar and never over it (R4, 9.1); [bannerShown] says when. */
    bannerShown: Boolean = false,
    banner: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion() || Motion.reduced
    val bar = chrome.bar
    SideEffect {
        chrome.pageKey = pageKey
        chrome.youBadge = nav.updateVersion != null
    }
    val spec = chrome.specs[pageKey]

    // Whether the account's picture is up: with every bar page, hidden with the page that hides the
    // bar (a search open, a selection under way). A page change leaves the spec missing for a frame
    // or two while the new page reports in; the picture rides that out rather than blinking.
    val accountWanted = spec != null && !spec.hidden
    var accountHeld by remember { mutableStateOf(accountWanted) }
    LaunchedEffect(accountWanted, spec == null) {
        if (accountWanted) {
            accountHeld = true
        } else if (spec != null) {
            accountHeld = false
        } else {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            accountHeld = false
        }
    }
    val showAccount = accountWanted || accountHeld

    // Back closes the card before it leaves the screen.
    BackHandler(enabled = chrome.youOpen) { chrome.youOpen = false }

    // The bar comes back whenever the page underneath changes: a bar left hidden across a change
    // of page has no pull-down to restore it on a page too short to scroll.
    LaunchedEffect(pageKey, spec?.current, spec?.hidden) { bar.show() }
    // And on the way back from the player, an activity coming forward rather than a recomposition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) bar.show() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BarRest(bar)
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
        // The banner's own height, measured, so the pages end above it and nothing it covers is lost.
        var bannerHeight by remember { mutableStateOf(0.dp) }
        val bannerRoom = if (bannerShown && !rail) bannerHeight + BAR_GAP else 0.dp
        // Its foot: on the bar's top edge while the bar is out, at the window's foot when it is not.
        val bannerFoot by animateDpAsState(
            targetValue = if (barShown && !rail) room else navigationBar + BAR_MARGIN,
            animationSpec = Motion.standard(if (reduced) 0 else Motion.Normal),
            label = "bannerFoot",
        )

        SharedTransitionLayout(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalBottomChrome provides room + bannerRoom,
                    com.tmplayer.ui.components.LocalBannerRoom provides bannerRoom,
                    LocalTouchChrome provides chrome,
                    LocalTouchNav provides nav,
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
                            position = shown.position,
                            downloading = nav.downloadCount,
                            onHome = retap(TouchDestination.Home, nav.onHome),
                            onChats = retap(TouchDestination.Chats, nav.onChats),
                            onSearch = nav.onSearch,
                            onDownloads = retap(TouchDestination.Downloads, nav.onDownloads),
                            searchShared = searchShared,
                            vertical = rail,
                        )
                    }
                    if (rail) {
                        barContent()
                    } else {
                        // The bar (and the page's pill over it) tells toasts where its top edge is, so they stand above it.
                        Column(Modifier.fillMaxWidth().toastFloor(), horizontalAlignment = Alignment.End) {
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

                // The connection banner: a card with its own buttons, above the bar, so a tap meant for
                // the bar never lands on it. Beside a rail it keeps the window's foot, off the rail.
                AnimatedVisibility(
                    visible = bannerShown,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp + railRoom, end = 16.dp, bottom = bannerFoot)
                        .widthIn(max = 560.dp),
                    enter = fadeIn(Motion.standard(if (reduced) 0 else Motion.Fast)),
                    exit = fadeOut(Motion.standard(if (reduced) 0 else Motion.Fast)),
                ) {
                    val bannerDensity = LocalDensity.current
                    Box(Modifier.toastFloor().onSizeChanged { bannerHeight = with(bannerDensity) { it.height.toDp() } }) { banner() }
                }

                // The account's picture: ONE instance, the shell's, over every page (user, 2026-11-25).
                // It is not part of any page, so a swipe between Home and Chats, a tap on the bar and a
                // page change never move, fade or drop it; it is out only where the bar's page says so
                // (a search open, a selection under way) or where no bar page is up (a chat, Settings).
                if (showAccount) {
                    CompositionLocalProvider(LocalTouchChrome provides chrome, LocalTouchNav provides nav) {
                        AccountButton(Modifier.align(Alignment.TopEnd), movedHint = spec?.current == TouchDestination.Home)
                    }
                }

                // Something of the shell's own over the pages (the app lock, a PIN pad) or the app
                // going to the background closes the menu: a sheet is a window of its own and
                // must never stay over the lock.
                LaunchedEffect(nav.promptUp) { if (nav.promptUp) chrome.youOpen = false }
                DisposableEffect(lifecycleOwner) {
                    val closer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) chrome.youOpen = false }
                    lifecycleOwner.lifecycle.addObserver(closer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(closer) }
                }
                AccountMenu(
                    open = chrome.youOpen,
                    rail = rail,
                    reduced = reduced,
                    account = nav.account,
                    profile = nav.profile,
                    focusBack = chrome.accountFocus,
                    onRefresh = spec?.refresh?.let { refresh -> { chrome.youOpen = false; refresh() } },
                    favoriteCount = nav.favoriteCount,
                    watchingCount = nav.watchingCount,
                    watchedCount = nav.watchedCount,
                    onDismiss = { chrome.youOpen = false },
                    onFavourites = { chrome.youOpen = false; nav.onFavourites() },
                    onHistory = { chrome.youOpen = false; nav.onHistory(HistoryTab.Continue) },
                    onWatchLater = { chrome.youOpen = false; nav.onHistory(HistoryTab.WatchLater) },
                    onSettings = { chrome.youOpen = false; nav.onSettings() },
                    onUpdate = { chrome.youOpen = false; nav.onUpdate() },
                    onAbout = { chrome.youOpen = false; (nav.onAbout ?: nav.onSettings)() },
                    onSwitchProfile = nav.onSwitchProfile?.let { switch -> { chrome.youOpen = false; switch() } },
                    onSwitchOrAdd = (nav.onSwitchOrAdd ?: nav.onSwitchProfile)?.let { act -> { chrome.youOpen = false; act() } },
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
    /** The page's refresh, for the account menu's Refresh row ([TouchPageSpec.refresh]); null hides it. */
    refresh: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalTouchChrome.current
    if (host == null) {
        val own = remember { TouchChrome() }
        TouchNavHost(nav, own, DEFAULT_PAGE) {
            TouchBrowseShell(nav, selected, searchOpen, onOpenSearch, searchScreen, actions, barPosition, refresh, content)
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
            hidden = searchOpen,
            actions = actions,
            position = barPosition,
            refresh = refresh,
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

    /**
     * How many times the list under the bar has moved. [BarRest] waits on it: while it keeps
     * counting the list is in motion, and the bar comes back once it has stood still for
     * [BAR_REST_MS].
     */
    var moves by mutableIntStateOf(0)
        private set

    fun show() {
        shown = true
        travelled = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val moved = consumed.y
            if (moved != 0f) moves++
            when {
                // A push or fling that reaches the end of the list (nothing left to scroll, so some of
                // it comes back unconsumed) brings the bar back: at the end of Home there was no bar
                // to reach Chats with (R4, 4.5).
                available.y < 0f && moved <= 0f -> show()
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

/**
 * Brings the bar back after the list has rested for [BAR_REST_MS] (the bar returns after one
 * second of stillness, so a person who stops reading to reach for a tab is not left to find that a
 * pull down is needed). Every move of the list starts the wait again, so a slow scroll or a fling
 * keeps the bar out; a bar that is already out has nothing to wait for.
 */
@Composable
internal fun BarRest(bar: BarVisibility) {
    // The reads sit in the flow, not in composition: the count changes every frame of a scroll.
    LaunchedEffect(bar) {
        snapshotFlow { if (bar.shown) -1 else bar.moves }.collectLatest { hidden ->
            if (hidden >= 0) {
                delay(BAR_REST_MS)
                bar.show()
            }
        }
    }
}

/** How long the list must stand still before a hidden bar returns. */
internal const val BAR_REST_MS = 1_000L

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
 * The floating bar: one rounded pill of four equal slots, clear of the screen's edge and the
 * navigation bar, with a soft shadow and the floating hairline. Each button is its icon with its
 * name in small type under it, as a tab bar has; every icon is the same size at the same gap from
 * its caption, and every caption is the same size (user, 2026-11-25: "Downloads" was shrunk alone):
 * [rememberBarCaptionSize] picks the one size all four fit at, stepping all of them down together
 * and, when even the smallest does not fit, showing icons only. The slots are as wide as each other
 * whatever the window, with room round the icons. The current page's button sits on a rounded pill
 * in the accent, which slides from button to button (and follows a swipe between Home and Chats as
 * it goes). On a page behind the picture (Favourites, History, Watch later) no button is lit.
 * Search is the button the search field grows out of ([searchShared]); Downloads wears the number
 * running ([downloading]). Every press and the highlight are pills, round like the bar they sit in.
 *
 * [vertical] stands the same bar up as a rail down the start edge, for a wide window: the same
 * buttons in the same order, top to bottom, with the highlight sliding down rather than across.
 */
@Composable
private fun FloatingBar(
    current: TouchDestination?,
    position: (() -> Float)?,
    downloading: Int,
    onHome: () -> Unit,
    onChats: () -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    searchShared: Modifier,
    vertical: Boolean = false,
) {
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
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = BAR_SIDE_MARGIN, end = BAR_SIDE_MARGIN, bottom = BAR_MARGIN)
    }
    if (vertical) {
        Box(outer, contentAlignment = Alignment.Center) {
            BarPill(RAIL_WIDTH, BUTTON_WIDTH, current, position, downloading, onHome, onChats, onSearch, onDownloads, searchShared, vertical = true)
        }
    } else {
        BoxWithConstraints(outer, contentAlignment = Alignment.Center) {
            // As wide as the widest label needs, measured with the real style at the user's font
            // size, plus 8 dp each side of four equal slots: about 80 percent of a 393 dp phone, at
            // most 85 percent of the window and 520 dp on a tablet. First too narrow ("Downloads"
            // cut), then too big (90 percent, 76 dp high): user, 2026-11-25, "a bit too big".
            val s = LocalStrings.current
            val sizing = rememberBarSizing(listOf(s.browseTabHome, s.browseTabChats, s.commonSearch, s.navDownloads), maxWidth + BAR_SIDE_MARGIN * 2, minOf(maxWidth, PILL_MAX_WIDTH) - 1.dp)
            BarPill(sizing.width, sizing.slot, current, position, downloading, onHome, onChats, onSearch, onDownloads, searchShared, vertical = false, sizing = sizing)
        }
    }
}

/** The pill itself: its four slots, the highlight under the chosen one and the caption size they share. */
@Composable
private fun BarPill(
    width: Dp,
    slot: Dp,
    current: TouchDestination?,
    position: (() -> Float)?,
    downloading: Int,
    onHome: () -> Unit,
    onChats: () -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    searchShared: Modifier,
    vertical: Boolean,
    sizing: BarSizing? = null,
) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    // Concentric: the highlight's corners (half its height) sit the same distance inside the bar's
    // as the padding around it, so the two curves run parallel. The rail is as wide as the bar is
    // tall plus its margins, so it takes 32 dp corners rather than a full semicircle, which no
    // button could follow.
    val barShape = if (vertical) RAIL_SHAPE else CircleShape
    // The rail keeps its taller buttons and gaps; the horizontal pill is compact (user, 2026-11-25).
    val buttonHeight = if (vertical) RAIL_BUTTON_HEIGHT else BUTTON_HEIGHT
    val gap = if (vertical) BAR_SPACING else BAR_SLOT_GAP
    // One caption size for the four, or none (icons only) when even the smallest does not fit.
    val captionSize = if (sizing != null) sizing.caption else rememberBarCaptionSize(listOf(s.browseTabHome, s.browseTabChats, s.commonSearch, s.navDownloads), slot)
    Box(
        Modifier
            // At least [width]: the four slots and their gaps round to whole pixels, which can add a
            // pixel or two, and the pill grows by that rather than squeezing its last slot.
            .widthIn(min = width)
            .then(if (vertical) Modifier else Modifier.height(BAR_HEIGHT))
            .shadow(BAR_SHADOW, barShape)
            .clip(barShape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), barShape)
            .padding(if (vertical) PaddingValues(vertical = RAIL_INSET) else PaddingValues(horizontal = PILL_PADDING))
            .testTag(BAR_PILL_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart) {
            // One highlight under the chosen page, sliding to the next one rather than blinking
            // off under one button and on under another, so the bar shows where the page went.
            val index = barHighlightIndex(current)
            val step = with(LocalDensity.current) { ((if (vertical) RAIL_BUTTON_HEIGHT else slot) + gap).toPx() }
            // Where the highlight stands, in buttons from the start: sprung to the chosen one, or,
            // while Home and Chats are on screen, following the pager (sprung only the first time,
            // coming in from another page, then held to the finger).
            val place = remember { Animatable((index ?: 0).toFloat()) }
            val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
            LaunchedEffect(index, position == null) {
                if (position != null || index == null) return@LaunchedEffect
                if (reduced) place.snapTo(index.toFloat()) else place.animateTo(index.toFloat(), spring)
            }
            if (position != null) {
                LaunchedEffect(position) {
                    var first = true
                    snapshotFlow { position() }.collectLatest { at ->
                        val glide = first && !reduced
                        first = false
                        if (glide) place.animateTo(at, spring) else place.snapTo(at)
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
                        val along = (place.value * step).roundToInt()
                        if (vertical) IntOffset(0, along) else IntOffset(along, 0)
                    }
                    .size(slot, buttonHeight)
                    .graphicsLayer {
                        alpha = shown
                        // Stretched a little along the way while it springs to another button and
                        // round again as it lands: it reads as moving, not as being redrawn.
                        if (!reduced) {
                            val stretch = (kotlin.math.abs(place.velocity) * STRETCH_PER_SPEED).coerceAtMost(STRETCH_MAX)
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
                BarButton(TmIcons.Home, s.browseTabHome, current == TouchDestination.Home, onHome, slot = slot, captionSize = captionSize, index = 0, vertical = vertical, height = buttonHeight)
                BarButton(TmIcons.Chats, s.browseTabChats, current == TouchDestination.Chats, onChats, slot = slot, captionSize = captionSize, index = 1, vertical = vertical, height = buttonHeight)
                BarButton(
                    TmIcons.Search, s.commonSearch, false, onSearch,
                    slot = slot, captionSize = captionSize, role = Role.Button, modifier = searchShared.testTag(BAR_SEARCH_TAG), index = 2, vertical = vertical, height = buttonHeight,
                )
                BarButton(
                    TmIcons.Download,
                    s.navDownloads,
                    current == TouchDestination.Downloads,
                    onDownloads,
                    slot = slot,
                    captionSize = captionSize,
                    badge = downloading,
                    index = 3,
                    vertical = vertical,
                    height = buttonHeight,
                )
            }
            // One group of four (WP-17): TalkBack says the bar's size and each button's place in it.
            val group = Modifier
                .selectableGroup()
                .semantics {
                    collectionInfo = if (vertical) CollectionInfo(BAR_BUTTONS, 1) else CollectionInfo(1, BAR_BUTTONS)
                }
            if (vertical) {
                Column(group, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) { buttons() }
            } else {
                Row(group, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) { buttons() }
            }
        }
    }
}

/**
 * The signed in profile's picture at the top end of Home and of the Downloads page (WP-103, where the
 * gear was): it opens the account menu ([YouPopover]) with the account, Settings, the viewer's own
 * lists and Sign out. It draws the TV bar's chain ([AccountPicture]) at [Avatar.Compact] inside a
 * 48 dp target: Telegram's picture, else the profile's saved photo, else the letter on the profile's
 * colour, and the person glyph only when no name is known, so it is never an empty disc. A 1.5 dp
 * ring in the bar's own colour keeps it apart from the carousel's art; it wears a dot when an update
 * waits, and the keyboard's focus draws the avatar's ring ([FocusRole.Avatar]). Nothing outside a
 * [TouchNavHost] (no menu to open).
 *
 * [movedHint] (Home only): an install that had the gear is told once where it went (WP-68 registry,
 * [HintIds.NAV_ACCOUNT_MOVED]); a fresh install never is ([SettingsStore.armSetup] marks it seen).
 */
@Composable
internal fun AccountButton(modifier: Modifier = Modifier, movedHint: Boolean = false) {
    val chrome = LocalTouchChrome.current ?: return
    val nav = LocalTouchNav.current
    val s = LocalStrings.current
    val name = accountName(nav?.account, nav?.profile)
    val label = accountLabel(
        named = name?.let { s.navAccountNamed(it) },
        fallback = s.navAccount,
        updateWaiting = if (chrome.youBadge) s.navUpdateWaiting else null,
    )
    Box(modifier) {
        com.tmplayer.ui.components.FloatingEndButton(
            label = label,
            onClickLabel = s.navAccountMenu,
            onClick = { chrome.youOpen = !chrome.youOpen },
            modifier = Modifier.focusRequester(chrome.accountFocus).testTag(ACCOUNT_BUTTON_TAG),
        ) { focused ->
            AccountButtonPicture(nav?.account, nav?.profile, focused, badge = chrome.youBadge)
        }
        if (movedHint) {
            // Never over a question, a PIN pad or the app lock: the hint is a window of its own.
            val blocked = nav == null || nav.promptUp || nav.signOutPhase != com.tmplayer.ui.settings.SignOutPhase.Idle
            AccountMovedHint(open = chrome.youOpen, blocked = blocked)
        }
    }
}

/** Who the picture is: Telegram's name once it is known, else the profile's own, else nobody yet. */
internal fun accountName(account: Account?, profile: com.tmplayer.data.Profile?): String? =
    account?.name?.trim()?.takeIf { it.isNotEmpty() } ?: profile?.shownName?.trim()?.takeIf { it.isNotEmpty() }

/**
 * What a screen reader says for the picture: "Account, Asha", or "Account" while no name is known,
 * with ", an update is ready" while one waits. The click label adds "open the account menu".
 */
internal fun accountLabel(named: String?, fallback: String, updateWaiting: String?): String =
    (named ?: fallback) + (updateWaiting?.let { ", $it" } ?: "")

/**
 * The picture itself: 40 dp in its 48 dp target, the outline and the dot, and the keyboard's ring
 * and halo inside the target. Keyed on the profile, so a switch never shows the old photo under the
 * new name for a frame.
 */
@Composable
private fun AccountButtonPicture(account: Account?, profile: com.tmplayer.data.Profile?, focused: Boolean, badge: Boolean) {
    val accent = Tone.accent
    val outline = FloatingTone.menu
    val ring = com.tmplayer.ui.theme.LocalFocusStyle.current.ring
    val still = rememberReducedMotion() || Motion.reduced
    val lit by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        Motion.standard(if (still) 0 else Motion.Fast),
        label = "accountRing",
    )
    Box(
        Modifier
            .size(com.tmplayer.ui.components.FloatingChrome.PageButton)
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                val inner = ACCOUNT_PICTURE.toPx() / 2f
                // The outline round the picture, always: the carousel's art runs right up to it.
                drawCircle(outline, radius = inner + OUTLINE.toPx() / 2f, center = center, style = Stroke(OUTLINE.toPx()))
                if (lit > 0f) {
                    // FocusRole.Avatar: the ring in the accent, then a soft halo, both inside the 48 dp rect.
                    val width = ring.toPx()
                    val room = size.minDimension / 2f - inner - width
                    val halo = minOf(com.tmplayer.ui.theme.FocusRecipe.HaloWidth.toPx(), room).coerceAtLeast(0f)
                    if (halo > 0f) {
                        drawCircle(
                            accent.copy(alpha = com.tmplayer.ui.theme.FocusRecipe.HaloAlpha * lit),
                            radius = inner + width + halo / 2f,
                            center = center,
                            style = Stroke(halo),
                        )
                    }
                    drawCircle(accent.copy(alpha = lit), radius = inner + width / 2f, center = center, style = Stroke(width))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.key(profile?.id) {
            Box(Modifier.size(ACCOUNT_PICTURE).clip(CircleShape).testTag(ACCOUNT_PICTURE_TAG)) {
                AccountPicture(account, profile, size = ACCOUNT_PICTURE)
            }
        }
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Tone.caution)
                    .border(1.5.dp, FloatingTone.menu, CircleShape)
                    .testTag(ACCOUNT_DOT_TAG),
            )
        }
    }
}

/**
 * "Settings, your lists and your account are behind your picture now", once, under the picture on
 * Home for an install that had the gear (WP-68). It waits for the tour (a tour still to come says
 * it all), goes when the menu opens, when it is tapped, or after [MOVED_HINT_MS], and is hidden
 * while [blocked] (a question, a PIN pad or the app lock is up), since a popup would sit over it.
 */
@Composable
private fun AccountMovedHint(open: Boolean, blocked: Boolean) {
    val context = LocalContext.current
    val settings = remember(context) { com.tmplayer.data.SettingsStore(context) }
    val s = LocalStrings.current
    val tour by settings.tourState.collectAsStateWithLifecycle(initialValue = null)
    var gone by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) gone = true }
    if (tour != com.tmplayer.data.SettingsStore.TourState.Done) return
    LaunchedEffect(Unit) {
        delay(MOVED_HINT_MS)
        gone = true
    }
    com.tmplayer.ui.components.OneTimeHint(
        settings = settings,
        id = com.tmplayer.data.HintIds.NAV_ACCOUNT_MOVED,
        label = s.navAccountMovedHint,
        visible = !gone && !blocked,
        draw = { text ->
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember { HintBelowEnd() },
                onDismissRequest = { gone = true },
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.text,
                    modifier = Modifier
                        .widthIn(max = MOVED_HINT_WIDTH)
                        .padding(horizontal = 16.dp)
                        .shadow(BAR_SHADOW, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(FloatingTone.dialog)
                        .border(floatingBorder(), RoundedCornerShape(14.dp))
                        .clickable { gone = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag(ACCOUNT_HINT_TAG),
                )
            }
        },
    )
}

/** Under the picture, its end edge on the picture's, kept inside the window. */
private class HintBelowEnd : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(x.coerceIn(0, maxX), anchorBounds.bottom)
    }
}

/**
 * A bar button's name under its icon, in the accent while its page is up, at [size], the one size
 * every caption of the pill has. Never shrunk to fit on its own: [rememberBarCaptionSize] chose a
 * size all of them fit at.
 */
@Composable
private fun BarCaption(text: String, chosen: Boolean, size: TextUnit) {
    val ink by androidx.compose.animation.animateColorAsState(if (chosen) Tone.accent else Tone.muted, label = "barCaption")
    // The system's font size stops growing the caption at 1.3 (WP-44): the slot is as wide whatever
    // the font, so past that a word only gets cut. The long press on the button says the name in
    // full size for whoever needs it larger.
    val style = captionStyle().copy(fontSize = size, lineHeight = (size.value + 2f).sp)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(BAR_CAPTION_MAX_SCALE))) {
        Text(
            text,
            style = style,
            color = ink,
            maxLines = 1,
            softWrap = false,
            // Never clipped: the slot is sized from this label's measured width, and a word a hair
            // wider than measured is drawn whole, centred, rather than losing its end letters.
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .padding(top = BAR_CAPTION_GAP)
                .wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

/** The bar caption's base size, and the most the system's font size may scale it (WP-44). */
internal const val BAR_CAPTION_SP = 12
internal const val BAR_CAPTION_MAX_SCALE = 1.3f

/** The sizes tried for the whole pill, largest first; below the last the pill shows icons only. */
internal val BAR_CAPTION_STEPS_SP = intArrayOf(BAR_CAPTION_SP, 11, 10)

/** The icon-to-caption gap, and the least room kept either side of a caption inside its slot. */
internal val BAR_CAPTION_GAP = 3.dp
internal val BAR_CAPTION_SIDE = 2.dp

/** The room each side of the widest caption in a slot when the window has it to give (user, 2026-11-25). */
internal val BAR_SLOT_SIDE = 8.dp

/** The most of the window's width the phone's pill takes, and the room left either side of it. */
internal const val BAR_WIDTH_MAX_SHARE = 0.85f
internal val BAR_SIDE_MARGIN = 12.dp

/**
 * How the horizontal pill is laid out: [width] of the pill, [slot] (the same for all four buttons)
 * and the one [caption] size, or null for icons only.
 */
internal class BarSizing(val width: Dp, val slot: Dp, val caption: TextUnit?)

/**
 * The pill sized from its labels: four equal slots, each the widest label, measured with the real
 * style at [density] (the font scale capped as the captions are), plus [BAR_SLOT_SIDE] either side,
 * never less than a [BUTTON_WIDTH] button (about 76 dp for "Downloads" at 12 sp, a pill of about 316
 * dp, 80 percent of a 393 dp phone). Never wider than [cap] or [BAR_WIDTH_MAX_SHARE] of [window]. When the widest
 * word does not fit at [cap] at 12 sp, all four captions step down together (11, 10 sp), taking the
 * room round the word before its size, and when not even the smallest fits with [BAR_CAPTION_SIDE]
 * either side the pill shows icons only.
 */
internal fun sizeBar(
    labels: List<String>,
    window: Dp,
    cap: Dp,
    density: Density,
    measurer: androidx.compose.ui.text.TextMeasurer,
    base: androidx.compose.ui.text.TextStyle,
): BarSizing {
    val fixed = PILL_PADDING * 2 + BAR_SLOT_GAP * (BAR_BUTTONS - 1)
    val room = (minOf(cap, window * BAR_WIDTH_MAX_SHARE) - fixed) / BAR_BUTTONS
    for (step in BAR_CAPTION_STEPS_SP) {
        val style = base.copy(fontSize = step.sp, lineHeight = (step + 2).sp)
        val widest = with(density) {
            // One pixel over the measured width, for the rounding of a glyph run's last pixel.
            labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1, density = density).size.width + 1 }.toDp()
        }
        if (widest + BAR_CAPTION_SIDE * 2 > room) continue
        val slot = minOf(room, maxOf(BUTTON_WIDTH, widest + BAR_SLOT_SIDE * 2))
        return BarSizing(slot * BAR_BUTTONS + fixed, slot, step.sp)
    }
    val slot = minOf(room, BUTTON_WIDTH)
    return BarSizing(slot * BAR_BUTTONS + fixed, slot, null)
}

@Composable
internal fun rememberBarSizing(labels: List<String>, window: Dp, cap: Dp): BarSizing {
    val density = LocalDensity.current
    val capped = Density(density.density, density.fontScale.coerceAtMost(BAR_CAPTION_MAX_SCALE))
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val base = captionStyle()
    return remember(labels, window, cap, capped, base) { sizeBar(labels, window, cap, capped, measurer, base) }
}

/**
 * The one caption size the pill's labels share: the largest of [BAR_CAPTION_STEPS_SP] at which every
 * label fits [slot] on one line, or null (no captions, icons only) when not even the smallest does.
 * Chosen for all of them together, so no label is ever smaller than its neighbour. Measured at the
 * font size the captions are drawn at.
 */
@Composable
internal fun rememberBarCaptionSize(labels: List<String>, slot: Dp): TextUnit? {
    val density = LocalDensity.current
    val capped = Density(density.density, density.fontScale.coerceAtMost(BAR_CAPTION_MAX_SCALE))
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val base = captionStyle()
    return remember(labels, slot, capped, base) { pickBarCaptionSize(labels, slot - BAR_CAPTION_SIDE * 2, capped, measurer, base) }
}

/** [rememberBarCaptionSize]'s choice, without the composition. */
internal fun pickBarCaptionSize(
    labels: List<String>,
    room: Dp,
    density: Density,
    measurer: androidx.compose.ui.text.TextMeasurer,
    base: androidx.compose.ui.text.TextStyle,
): TextUnit? {
    val limit = with(density) { room.roundToPx() }
    for (step in BAR_CAPTION_STEPS_SP) {
        val style = base.copy(fontSize = step.sp, lineHeight = (step + 2).sp)
        val fits = labels.all { label ->
            measurer.measure(label, style, softWrap = false, maxLines = 1, density = density).size.width <= limit
        }
        if (fits) return step.sp
    }
    return null
}

/**
 * One of the pill's four slots: [slot] wide and [BUTTON_HEIGHT] high, whatever it holds, with its
 * icon at [BAR_ICON] over [captionSize] type, or alone when [captionSize] is null.
 */
@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
    slot: Dp,
    captionSize: TextUnit?,
    role: Role = Role.Tab,
    modifier: Modifier = Modifier,
    /** A count over the icon's top end (Downloads running); 0 draws none. */
    badge: Int = 0,
    /** Its place among the bar's [BAR_BUTTONS], for the screen reader. */
    index: Int = 0,
    vertical: Boolean = false,
    height: Dp = BUTTON_HEIGHT,
) {
    val s = LocalStrings.current
    val press = remember { MutableInteractionSource() }
    var showLarge by remember { mutableStateOf(false) }
    Column(
        modifier
            .size(slot, height)
            .pressScale(press)
            .clip(HIGHLIGHT_SHAPE)
            .combinedClickable(
                interactionSource = press,
                indication = ripple(),
                role = role,
                onClick = onClick,
                // The name at full size, for a person the capped caption is too small for (WP-44).
                onLongClickLabel = label,
                onLongClick = { showLarge = true },
            )
            .semantics {
                // The count is read with the name ("Downloads, 2 in progress"), not as a stray number.
                contentDescription = if (badge > 0) s.navInProgress(label, badge.toString()) else label
                collectionItemInfo = if (vertical) CollectionItemInfo(index, 1, 0, 1) else CollectionItemInfo(0, 1, index, 1)
                if (role == Role.Tab) selected = chosen
                this.role = role
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The highlight behind is the bar's (see BarPill); the icon takes its colour as it lands
        // and fills in with a drop (SelectableIcon).
        val tint by androidx.compose.animation.animateColorAsState(if (chosen) Tone.accent else Tone.text, label = "barIcon")
        Box(Modifier.size(BAR_ICON), contentAlignment = Alignment.Center) {
            com.tmplayer.ui.components.SelectableIcon(icon, selected = chosen, tint = tint, size = BAR_ICON)
            if (badge > 0) {
                // Capped as the captions are: at twice the font the count grew into a disc that covered the
                // icon it sits on (phone audit, 2026-11-25).
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(BAR_CAPTION_MAX_SCALE)),
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tone.onAccent,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-6).dp)
                            .clip(CircleShape)
                            .background(Tone.accent)
                            .border(1.5.dp, FloatingTone.menu, CircleShape)
                            .padding(horizontal = 4.dp)
                            .testTag(BAR_BADGE_TAG),
                    )
                }
            }
        }
        if (captionSize != null) BarCaption(label, chosen, captionSize)
        if (showLarge) LargeBarLabel(label) { showLarge = false }
    }
}

/**
 * The bar button's name at 20 sp in a small card over the button, for a moment (WP-44): the large content
 * viewer pattern, since the caption under the icon is capped at 1.3 times its size. Closes by itself,
 * and on a tap anywhere outside.
 */
@Composable
private fun LargeBarLabel(label: String, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(label) {
        kotlinx.coroutines.delay(LARGE_LABEL_MS)
        onDismiss()
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -LARGE_LABEL_LIFT_PX),
        onDismissRequest = onDismiss,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
            color = Tone.text,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(FloatingTone.menu)
                .border(1.dp, Tone.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private const val LARGE_LABEL_MS = 1_800L
private const val LARGE_LABEL_LIFT_PX = 160

/**
 * The account menu behind the picture at the top end (WP-103, user 2026-11-25), the phone's and
 * tablet's copy of the television's (WP-02). On a phone it is a bottom sheet ([PhoneSheet]: the
 * drag handle, no border handed to the window, Back and a tap outside close it, it scrolls only
 * where the screen is too short). Beside a rail (a tablet, a phone on its side) it is a card that
 * grows out of the top end corner under the picture, over a dimmed screen. Both hold the same
 * [AccountMenuContent]: a header, three tiles, two rows and the version. With a keyboard the first
 * tile has the focus on open and the picture has it again on close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountMenu(
    open: Boolean,
    rail: Boolean,
    reduced: Boolean,
    account: Account?,
    profile: com.tmplayer.data.Profile?,
    /** The picture, which has the keyboard's focus again once the menu closes. */
    focusBack: androidx.compose.ui.focus.FocusRequester,
    /** The page's refresh; null hides the header's Refresh button. */
    onRefresh: (() -> Unit)?,
    favoriteCount: Int,
    watchingCount: Int,
    watchedCount: Int,
    onDismiss: () -> Unit,
    onFavourites: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    /** The picker, with two profiles or more: the header's switcher; null with one. */
    onSwitchProfile: (() -> Unit)?,
    /** Add account (one profile) or the picker with its Add tile (several); null hides the row. */
    onSwitchOrAdd: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val name = accountName(account, profile)
    // Read when it is used, not when the menu is drawn: a keyboard pressed while it is open counts.
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val keyboard = { inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard }
    // Back to the picture once the menu has closed, the way it was opened with a keyboard.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open && wasOpen && keyboard()) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusBack.requestFocus() }
        }
        wasOpen = open
    }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val content: @Composable () -> Unit = {
        AccountMenuContent(
            account = account,
            profile = profile,
            name = name ?: s.navAccount,
            first = first,
            onRefresh = onRefresh,
            favoriteCount = favoriteCount,
            watchingCount = watchingCount,
            watchedCount = watchedCount,
            onFavourites = onFavourites,
            onHistory = onHistory,
            onWatchLater = onWatchLater,
            onSettings = onSettings,
            onUpdate = onUpdate,
            onAbout = onAbout,
            onSwitchProfile = onSwitchProfile,
            onSwitchOrAdd = onSwitchOrAdd,
        )
    }
    val escape: Modifier = Modifier.onPreviewKeyEvent { event ->
        // Escape closes it, as Back does; the picture gets the keyboard's focus back.
        if (event.key == androidx.compose.ui.input.key.Key.Escape) {
            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) onDismiss()
            true
        } else {
            false
        }
    }
    if (!rail) {
        if (open) {
            PhoneSheet(onDismissRequest = onDismiss) {
                Box(escape.verticalScroll(rememberScrollState()).keepScrollInSheet()) { content() }
                LaunchedEffect(Unit) {
                    if (keyboard()) {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { first.requestFocus() }
                    }
                }
            }
        }
        return
    }
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.standard(if (reduced) 0 else Motion.Fast)),
        exit = fadeOut(Motion.standard(if (reduced) 0 else Motion.Fast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FloatingTone.scrim)
                .scrimDismiss(onDismiss),
        )
    }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // The corner the card grows from, in screen terms: the top end one, under the picture.
    val origin = TransformOrigin(if (ltr) 1f else 0f, 0f)
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = com.tmplayer.ui.components.FloatingChrome.PageButton + 16.dp, end = 16.dp, start = 16.dp, bottom = 16.dp),
    ) {
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.TopEnd),
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
            // A card as wide as three tiles need: the tiles' labels and counts do not break in two.
            val widest = POPOVER_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
            Box(
                Modifier
                    .widthIn(max = widest)
                    .shadow(BAR_SHADOW, POPOVER_SHAPE)
                    .clip(POPOVER_SHAPE)
                    .background(FloatingTone.dialog)
                    .border(floatingBorder(), POPOVER_SHAPE)
                    .then(escape)
                    // Taps inside the card stay inside it rather than reaching the scrim.
                    .swallowClicks()
                    .verticalScroll(rememberScrollState()),
            ) { content() }
            LaunchedEffect(Unit) {
                if (keyboard()) {
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { first.requestFocus() }
                }
            }
        }
    }
}

/**
 * What the account menu holds (user, 2026-11-25: "too many options"), one order for the sheet and the
 * card, five things and no more:
 *
 * 1. the header, not a row: the picture at 48 dp, the name, the @username, and at its end two small
 *    48 dp buttons: Refresh (where the page has one) and the switcher (with two profiles or more);
 * 2. your library as one row of three tiles: Favourites, History, Watch later, with the old "Your
 *    viewing" counts on the tiles they belong to ([LibraryTiles]);
 * 3. two plain rows: Settings, and Add account (Switch or add account with two profiles or more);
 * 4. the version as the app's tile and "v1.28.7" ([VersionMark]), which goes to About; an update
 *    waiting is the amber words beside it, a button to the update window.
 *
 * Sign out is not here: it lives in Settings, Account, behind the Settings PIN. [first] takes the
 * keyboard's focus on open: the first tile.
 */
@Composable
internal fun AccountMenuContent(
    account: Account?,
    profile: com.tmplayer.data.Profile?,
    name: String,
    first: androidx.compose.ui.focus.FocusRequester,
    onRefresh: (() -> Unit)?,
    favoriteCount: Int,
    watchingCount: Int,
    watchedCount: Int,
    onFavourites: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onSwitchProfile: (() -> Unit)?,
    onSwitchOrAdd: (() -> Unit)?,
) {
    val s = LocalStrings.current
    // The row's action: the picker with two profiles or more (it has the Add tile), Add account with one.
    val rowAction = onSwitchOrAdd ?: onSwitchProfile
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { paneTitle = if (name == s.navAccount) s.navAccount else s.navAccountNamed(name) }
            .padding(bottom = 6.dp)
            .testTag(ACCOUNT_MENU_TAG),
    ) {
        AccountMenuHead(account, profile, name, onRefresh = onRefresh, onSwitch = onSwitchProfile)
        com.tmplayer.ui.components.LibraryTiles(
            starred = favoriteCount,
            watching = watchingCount,
            watched = watchedCount,
            onFavourites = onFavourites,
            onHistory = onHistory,
            onWatchLater = onWatchLater,
            firstFocus = first,
        )
        PopoverRow(TmIcons.Settings, s.navSettings, null, onClick = onSettings)
        if (rowAction != null) {
            PopoverRow(
                TmIcons.Person,
                if (onSwitchProfile != null) s.profilesSwitchOrAdd else s.profilesAdd,
                null,
                onClick = rowAction,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        // The version, compact: the app's tile and "v1.28.7"; About has the build and the update source.
        com.tmplayer.ui.components.VersionMark(
            info = com.tmplayer.ui.components.rememberVersionInfo(),
            modifier = Modifier.padding(horizontal = 2.dp),
            onClick = onAbout,
            onClickLabel = s.settingsAbout,
            onUpdate = onUpdate,
        )
    }
}

/**
 * The account menu's header: the picture at 48 dp, the name in two lines at most, and the @username
 * under it when Telegram has one, so two profiles of the same name read apart (one node for a
 * screen reader), then Refresh and the switcher as 48 dp icon buttons, each named for TalkBack.
 */
@Composable
private fun AccountMenuHead(
    account: Account?,
    profile: com.tmplayer.data.Profile?,
    name: String,
    onRefresh: (() -> Unit)?,
    onSwitch: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val username = account?.username?.trim()?.trimStart('@')?.takeIf { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .testTag(ACCOUNT_HEAD_TAG),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.key(profile?.id) { AccountPicture(account, profile, size = ACCOUNT_HEAD_PICTURE) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (username != null) {
                    Text("@$username", style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onRefresh != null) HeadButton(TmIcons.Refresh, s.commonRefresh, REFRESH_BUTTON_TAG, onRefresh)
        if (onSwitch != null) HeadButton(TmIcons.ChevronDown, s.profilesSwitchProfile, SWITCH_BUTTON_TAG, onSwitch)
    }
}

/** A small icon button of the header: a 48 dp target, ripple and press scale, its name for TalkBack. */
@Composable
private fun HeadButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(POPOVER_ROW)
            .pressScale(press)
            .clip(CircleShape)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(22.dp))
    }
}

/** Test tags of the header's two buttons. */
internal const val REFRESH_BUTTON_TAG = "account-menu-refresh"
internal const val SWITCH_BUTTON_TAG = "account-menu-switch"

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

/** The test tag of the count over the Downloads button. */
internal const val BAR_BADGE_TAG = "bar-badge"

/** Test tags of the account's picture (its 48 dp target, the 40 dp picture, the update dot), its menu and the menu's head. */
internal const val ACCOUNT_BUTTON_TAG = "account-button"
internal const val ACCOUNT_PICTURE_TAG = "account-picture"
internal const val ACCOUNT_DOT_TAG = "account-dot"
internal const val ACCOUNT_MENU_TAG = "account-menu"
internal const val ACCOUNT_HEAD_TAG = "account-menu-head"
internal const val ACCOUNT_HINT_TAG = "account-moved-hint"

/** The account's picture at the top end, inside its 48 dp target, and its outline. */
internal val ACCOUNT_PICTURE = Avatar.Compact
private val OUTLINE = 1.5.dp

/** The picture in the account menu's head. */
private val ACCOUNT_HEAD_PICTURE = 48.dp

/** How long the "your account moved" card stays before it goes by itself, and its widest. */
private const val MOVED_HINT_MS = 8_000L
private val MOVED_HINT_WIDTH = 300.dp

/** The key the bar's magnifier and the search field share, for the one to grow into the other. */
private const val SEARCH_KEY = "search-field"

/** The bar's height: a button of icon and caption with a little room above and below it. */
internal val BAR_HEIGHT = 64.dp

/** Between the bar and the navigation bar (or the screen's edge). */
internal val BAR_MARGIN = 12.dp

/** Between the bar and whatever a list puts last, or a button floating above it. */
internal val BAR_GAP = 8.dp

/** One of the bar's buttons, its icon over its caption. */
internal val BUTTON_WIDTH = 64.dp
internal val BUTTON_HEIGHT = 52.dp

/** The rail's buttons keep their height, and the gaps between them. */
internal val RAIL_BUTTON_HEIGHT = 56.dp

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
private val RAIL_WIDTH = 80.dp

/** The rail's corners and the room above its first button and under its last, matching the bar's. */
private val RAIL_SHAPE = RoundedCornerShape(32.dp)
private val RAIL_INSET = 8.dp

/** Between the rail and the screen's start edge (or the cutout). */
private val RAIL_MARGIN = 12.dp

/** Between two of the bar's buttons. */
internal val BAR_SPACING = 6.dp

/** Between two slots of the horizontal pill: none, the equal slots touch and one highlight shows the page. */
internal val BAR_SLOT_GAP = 0.dp

/** The floating bar's buttons: Home, Chats, Search, Downloads. */
internal const val BAR_BUTTONS = 4

/** The pill's room round its slots, and its widest: four 64 dp slots, three gaps and the room either side. */
internal val PILL_PADDING = 6.dp
internal val PILL_MAX_WIDTH = 520.dp

/** Every icon of the bar: one size, over one gap to its caption. */
internal val BAR_ICON = 24.dp

/** Test tags of the pill and of its Search button. */
internal const val BAR_PILL_TAG = "bar-pill"
internal const val BAR_SEARCH_TAG = "bar-search"
internal val BAR_SHADOW = 8.dp
private val POPOVER_WIDTH = 208.dp
internal val POPOVER_ROW = 48.dp

/** The popover's rows before the divider: the three lists; Settings and Update come after it. */
private const val POPOVER_LISTS = 3
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

/**
 * Add account or Switch profile from an account menu (WP-02, WP-103): the picker with two profiles or
 * more; with one, Add account itself. Add asks the app lock's PIN on its own
 * ([com.tmplayer.ui.lock.ProfileFlow.add], closed while the lock is unread), so the PIN is asked once.
 */
internal fun switchOrAdd(flow: com.tmplayer.ui.lock.ProfileFlow, several: Boolean) {
    if (several) flow.ask() else flow.add()
}
