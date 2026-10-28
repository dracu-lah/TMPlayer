package com.tmplayer.ui.browse

import com.tmplayer.ui.components.keepTopInSight
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.background
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.scrollBy
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.WithinMargins
import com.tmplayer.ui.components.edgeFade
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalOnlineArt
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind

/**
 * Home: a row of what the viewer was part way through, a row of what they saved for later, the new episodes of the shows they
 * are watching, a row per starred chat, and the newest videos across the starred chats (the rows
 * are built by [com.tmplayer.data.HomeRows]).
 *
 * Continue watching is wide cards with the name written on the art and the time left in a chip.
 * A starred chat's row and Recently added keep the chat's own order in one strip of 2:3 posters.
 *
 * One vertical lazy list of horizontal lazy rows, so only the rows on screen exist and only the
 * tiles on screen in each of those. Every row and tile is keyed, and every tile is a fixed width,
 * which is what lets a 1 GB television scroll this without measuring anything twice.
 *
 * On a television the D-pad moves along a row and between rows, each row remembering the tile it
 * was left on. Home opens on the first tile. A row that leaves videos out offers "See all" on its
 * heading.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomePane(
    rows: List<HomeRow>,
    watch: SeriesWatch,
    /** Continue watching videos as Telegram describes them, for their pictures. */
    art: Map<String, MediaItem>,
    chatTitle: (Long) -> String,
    start: Dp,
    end: Dp,
    bottom: Dp,
    onRowShown: (HomeRow) -> Unit,
    onArtWanted: (ResumeRecord) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onPlay: (MediaItem, String) -> Unit,
    /** A long press, a held OK or the info key on a video's tile: its detail panel. */
    onHoldMedia: (MediaItem, String) -> Unit,
    onSeeContinue: () -> Unit,
    onOpenChat: (Long) -> Unit,
    /**
     * True when no chat is starred, so Home has no chat rows to show and says how to get them.
     * Not needed when [rows] is empty: the empty state already says the same.
     */
    noFavourites: Boolean = false,
    /** False when Settings turned the carousel off: Home opens on its rows, Recently added among them. */
    carouselOn: Boolean = true,
    /** Every row of Home off in Settings ([com.tmplayer.data.HomeLayout.allOff]): a card says so, with the way back. */
    allOff: Boolean = false,
    /** Settings, on its Home page: from the all-off card and from Recently added's "no chosen chats" line. */
    onOpenHomeSettings: () -> Unit = {},
    /** False while the rows have not been built from an answer yet ([HomeViewModel.rowsSettled]). */
    rowsReady: Boolean = true,
    /** What the first-run state says and where its button goes: Saved Messages, or a folder. */
    /** Opens the first-run state's target. */
    onOpenSection: (BrowseSection) -> Unit = {},
    /** A Watch later tile: its detail or the player, as a Continue watching tile does. */
    onOpenWatchLater: (WatchLaterRecord) -> Unit = {},
    onLaterArtWanted: (WatchLaterRecord) -> Unit = {},
    onSeeWatchLater: () -> Unit = {},
    /** Download season and Download unwatched on a show's page; null leaves them out. */
    onDownloadSeason: ((SeasonDownloadRequest) -> Unit)? = null,
    /**
     * Starts a video at once, whatever "Open a video's page first" says: a show's page plays from
     * its Play pill and its episodes. [onPlay] is a tile's, which may open the page first, and a
     * page opened from the show's own Play sat behind the show and sent the viewer round again.
     */
    onPlayNow: (MediaItem, String) -> Unit = onPlay,
    /** A video's page, whatever "Open a video's page first" says: the carousel's Details on a television. */
    onDetails: (MediaItem, String) -> Unit = onHoldMedia,
    /**
     * The television's page heading. With the carousel up it stands on the carousel's picture
     * (true: the words in the colours for art) and scrolls away with it; otherwise it sits over
     * the rows. Null on a phone, whose heading is the app bar.
     */
    tvHeader: (@Composable (onArt: Boolean) -> Unit)? = null,
    /** Asked before Home takes the remote for its first row: not while the TV top bar holds it. */
    mayTakeFocus: () -> Boolean = { true },
    /** On the TV carousel's Play, for Down from the top bar. */
    tvPlayFocus: FocusRequester? = null,
    /**
     * Recently added reads on through Home's chats as it is scrolled to its end
     * ([com.tmplayer.ui.browse.HomeViewModel.seeAll]); null keeps each row to what Home holds.
     */
    feedOf: (HomeRow) -> com.tmplayer.ui.browse.RowFeed? = { null },
) {
    val s = LocalStrings.current
    val touch = isTouch()
    // On a phone the page runs up under the status bar (see LocalUnderStatusBar).
    val under = if (touch) LocalUnderStatusBar.current else 0.dp
    if (rows.isEmpty() && allOff) {
        LaunchedEffect(Unit) { HomeFirstPaint.markReady() }
        val focus = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().padding(top = under)) {
            tvHeader?.invoke(false)
            if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (touch) 16.dp else 72.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                com.tmplayer.ui.browse.HomeOffCard(onSettings = onOpenHomeSettings, focus = focus)
            }
        }
        // The remote lands on the one thing there is to press, unless the top bar holds it.
        if (!touch) LaunchedEffect(Unit) { if (mayTakeFocus()) runCatching { focus.requestFocus() } }
        return
    }
    if (rows.isEmpty()) {
        LaunchedEffect(Unit) { HomeFirstPaint.markReady() }
        // Rows still being built: the page's heading over a blank page, not the first run's card.
        // A list of the chats' videos that never comes back gives way to the card after a while,
        // so a first run without a connection still says what to do.
        var waitedOut by remember { mutableStateOf(false) }
        if (!rowsReady) LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(ROWS_WAIT_MS)
            waitedOut = true
        }
        if (!rowsReady && !waitedOut) {
            Column(Modifier.fillMaxSize().padding(top = under)) {
                tvHeader?.invoke(false)
                if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            }
            return
        }
        // Nothing played and nothing starred: a first run, so say how to get a first video in.
        Column(Modifier.fillMaxSize().padding(top = under)) {
            tvHeader?.invoke(false)
            if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            Box(Modifier.fillMaxSize()) { FirstVideoEmpty(onOpenSection) }
        }
        return
    }
    // Recently added is the featured carousel at the top as well as a row, on a phone and a
    // television alike: its frame is held from the first frame whenever the row is, so nothing
    // below it moves when it fills, and given up (the rows moving up) once its fill finds no
    // complete page, a moment after the row is in at most ([HomeFeatured.FILL_WAIT_MS]).
    val recent = rows.firstNotNullOfOrNull { it as? HomeRow.Recent }
    // Off in Settings, nothing is picked or turned over for it either.
    val featured = rememberFeatured(recent.takeIf { carouselOn })
    val carousel = carouselOn && recent != null && featured?.isEmpty() != true
    // With no carousel the rows are the whole first screen; with one, its first picture tells
    // HomeFirstPaint (through the carousel), so the loader lifts over a finished Home.
    // Not at once: the starred chats' row can arrive a moment after the rest, bringing the carousel
    // (and its picture) with it, and the loader lifting before that let Home jump down.
    LaunchedEffect(carousel) {
        if (carousel) return@LaunchedEffect
        kotlinx.coroutines.delay(if (touch) NO_CAROUSEL_SETTLE_MS else NO_CAROUSEL_SETTLE_TV_MS)
        HomeFirstPaint.markReady()
    }
    // Recently added is a row of its own as well, under Continue watching (user, 2026-10-23),
    // unless Settings turned the row off and kept the carousel it feeds. Its "no chosen chats"
    // line shows either way, so the carousel's empty place is explained.
    val listed = remember(rows) { rows.filter { it !is HomeRow.Recent || it.asRow || it.noneChosen } }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Kept across a turn of the phone: the show is looked up again in the rows once they are back.
    val opened = rememberOpenSeries { key, chatId -> HomeLaunch.find(key, chatId, rows) }
    // Recently added's See all page, by the row's key, so a turn of the phone keeps it open.
    var seeAllKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val seeAllRow = seeAllKey?.let { key -> rows.firstOrNull { it.key == key } as? HomeRow.Recent }
    // The row whose tiles the remote goes back to once its page closes.
    var returnTo by remember { mutableStateOf<String?>(null) }
    val listState = com.tmplayer.ui.components.rememberAheadListState(column = true)
    // Home pressed again on the bar: back to the top, the featured carousel whole.
    ScrollToTopOnReselect(TouchDestination.Home) { animate -> listState.toTop(animate) }
    // Back from Home's rows to the TV top bar: the same, as Home looks when it opens.
    if (!touch) {
        // The bar takes the page's ground behind it once the rows run up under it.
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
                .collect { TvBarSignals.homeScrolled.value = it }
        }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { TvBarSignals.homeScrolled.value = false } }
        val still = com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
        LaunchedEffect(listState, still) { TvBarSignals.homeToTop.collect { listState.toTop(!still) } }
    }
    // A show's card on the TV home screen asked for its page: opened once the show is in the rows.
    val showAsk by HomeLaunch.show.collectAsState()
    // Not before this window has focus: the launcher is still on top while the app comes up, and a
    // page opened then never got the remote's focus on its Play button.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Where the remote stands on Home, for putting it back after the player (see [HomeFocusMemory]).
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    val shellState by shellLifecycle.currentStateFlow.collectAsState()
    val windowFocusedNow by androidx.compose.runtime.rememberUpdatedState(windowFocused)
    // The Continue watching card a hold menu was opened on, and where it stood in the row: when
    // the menu takes it out of History the remote moves to the card that took its place (or, the
    // row gone, to the top of Home), rather than being left on nothing. Forgotten once the shell
    // is covered (the player), so a card finished there does not move the remote on the way back.
    var heldContinue by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val continueRefocus = remember { FocusRequester() }
    val continueRecords = (listed.firstOrNull { it is HomeRow.Continue } as? HomeRow.Continue)?.records.orEmpty()
    val heldContinueGone = heldContinue?.let { (key, _) ->
        continueRecords.none { SettingsStore.progressKey(it.chatId, it.messageId) == key }
    } == true
    val continueStandIn = if (heldContinueGone) continueRecords.getOrNull(minOf(heldContinue?.second ?: 0, continueRecords.lastIndex)) else null
    LaunchedEffect(shellState.isAtLeast(Lifecycle.State.RESUMED)) {
        if (!shellState.isAtLeast(Lifecycle.State.RESUMED)) heldContinue = null
    }
    LaunchedEffect(heldContinueGone, continueStandIn?.let { SettingsStore.progressKey(it.chatId, it.messageId) }) {
        if (!heldContinueGone) return@LaunchedEffect
        if (!touch) {
            withFrameNanos { }
            if (continueStandIn != null) runCatching { continueRefocus.requestFocus() } else runCatching { first.requestFocus() }
        }
        heldContinue = null
    }
    // The row (by key) or the carousel the remote is in right now, null when it is elsewhere.
    val focusedSpot = remember { arrayOfNulls<String>(1) }
    // Whether the remote has been in Home in this composition, so the first "no focus" report a
    // freshly built Home makes is not taken for the remote leaving it.
    val homeHad = remember { booleanArrayOf(false) }
    // Set once the remote has been put back after the player, which the first row's own focus
    // grab below then leaves alone.
    val restored = remember { booleanArrayOf(false) }
    // Each row's tiles, by row key, for putting the remote back into the row it was in.
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    if (!touch) {
        androidx.compose.runtime.DisposableEffect(shellLifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                // The player (another activity) covering the shell, with the remote on Home.
                if (event == Lifecycle.Event.ON_STOP && HomeFocusMemory.spot != null) HomeFocusMemory.away = true
            }
            shellLifecycle.addObserver(observer)
            onDispose {
                shellLifecycle.removeObserver(observer)
                // Home left for another page while the app is in use: nothing to put back. Gone
                // because the activity is being destroyed behind the player, it is kept for the
                // Home built when the viewer comes back.
                if (shellLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) HomeFocusMemory.forget()
            }
        }
    }
    LaunchedEffect(showAsk, rows, windowFocused) {
        val ask = showAsk ?: return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        val series = HomeLaunch.find(ask, rows) ?: return@LaunchedEffect
        opened.open(series)
        HomeLaunch.show.compareAndSet(ask, null)
    }

    // The carousel arrives after the rows under it (Recently added is read last), and a list keeps
    // its first row on screen when something is put above it: Home would open scrolled past the
    // carousel. Until the viewer has scrolled, the carousel's arrival takes Home back to its top.
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        scrolled = true
    }
    LaunchedEffect(carousel) {
        // On a television too: a carousel arriving after the rows otherwise sits above the screen,
        // out of reach of Up.
        if (carousel && !scrolled) listState.scrollToItem(0)
        // The carousel gone (no complete page once its fill decided) with the remote on its
        // stand-in: the remote moves to the first row, now at the top, rather than onto nothing.
        if (!carousel && !touch && focusedSpot[0] == FEATURED_KEY) {
            focusedSpot[0] = null
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    // Asked for as the row would be when it first came on screen, and again after a refresh.
    // The carousel is built from the starred chats' newest videos, so while it waits they are asked
    // for straight away, not only once their rows scroll into view (below the fold on a TV they
    // never did, and the carousel waited for good).
    if (recent != null) LaunchedEffect(recent.key, recent.loaded) {
        if (recent.loaded) return@LaunchedEffect
        onRowShown(recent)
        rows.filterIsInstance<HomeRow.Chat>().filterNot { it.loaded }.take(com.tmplayer.ui.browse.HomeViewModel.EAGER_CHATS).forEach(onRowShown)
    }
    val density = LocalDensity.current

    // On a phone Home shares a pager with Chats and stays composed beside it: only while most of
    // it is on screen does the carousel move on or the status bar take its colours from it.
    var shown by remember { mutableStateOf(true) }
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (touch) {
                Modifier.onGloballyPositioned { coordinates ->
                    val width = coordinates.size.width
                    shown = width > 0 && coordinates.boundsInWindow().width > width / 2f
                }
            } else {
                Modifier
            },
        ),
    ) {
        // Fixed from the window alone, so it is the same before and after the pages arrive.
        val carouselHeight = if (touch) featuredHeight(maxWidth, maxHeight - under) + under else TV_FEATURED_HEIGHT
        val carouselPx = with(density) { carouselHeight.roundToPx() }
        val underPx = with(density) { under.roundToPx() }
        // While the carousel's picture is under the status bar the bar's icons go light over it;
        // once it has scrolled away the rows pass under a strip of the page's own ground.
        val pictureShown = carousel && !featured.isNullOrEmpty()
        val overArt by remember(pictureShown, carouselPx, underPx) {
            derivedStateOf {
                pictureShown && listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset < carouselPx - underPx
            }
        }
        if (touch) StatusIconsOverArt(overArt && shown)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateFlow.collectAsState()
        val carouselOnScreen by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
        // A sheet or a dialog takes the window's focus, which is what stops the pages moving under it.
        val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && windowFocused && carouselOnScreen && shown && opened.series == null
        // Tiles here may wear the show's or the film's picture (see LocalOnlineArt); a chat's grid keeps frames.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val rowStep = with(density) { TV_DOWN_STEP.toPx() }
        val headerOnArt = !touch && carousel && tvHeader != null
        // Under the TV top bar, Home's own list keeps still while the remote stays in its upper part
        // (the carousel's Play, the first row): the television's default moved every focused item
        // to a fixed height, which scrolled Home down and back each time Play took the remote.
        val barPx = with(density) { TV_BAR_HEIGHT.toPx() }
        // A row the remote moves into stands just under the bar, its heading in sight, wherever it
        // was reached from, as the web app's and Google TV's rows do. Brought to three tenths of the
        // way down instead, a row reached by Up sat with its heading under the bar and one reached
        // by Down with it in sight; the first row was held at the list's top with its tiles cut off
        // at the screen's foot, and a second Down moved the page that the next Left or Right took
        // back (user, 2026-10-28). Found by where the request lands: inside a row's own place.
        val rowKeys by androidx.compose.runtime.rememberUpdatedState(remember(listed) { listed.mapTo(HashSet()) { it.key } })
        // Without the carousel the bar stands above the list rather than over it.
        val rowLine by androidx.compose.runtime.rememberUpdatedState(if (headerOnArt) barPx else 0f)
        val calm = remember(barPx, listState) {
            calmVertical(barPx) { offset, size, _ ->
                val info = listState.layoutInfo
                for (row in info.visibleItemsInfo) {
                    // The item's offset counts from inside the list's top padding; the request's from the list's top.
                    val top = (row.offset - info.viewportStartOffset).toFloat()
                    if (offset < top - 1f || offset + size > top + row.size + 1f) continue
                    if (row.key !in rowKeys) return@calmVertical null
                    return@calmVertical top - rowLine
                }
                null
            }
        }
        val platformSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        // The states every tile reads, collected once for Home rather than per tile ([LocalTileShared]).
        val tileShared = com.tmplayer.ui.components.rememberTileShared()
        CompositionLocalProvider(
            LocalOnlineArt provides true,
            com.tmplayer.ui.components.LocalTileShared provides tileShared,
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if (!touch) calm else platformSpec,
        ) {
          // While a See all page stands over Home, the remote cannot move into the rows under it.
          val pageOpen by androidx.compose.runtime.rememberUpdatedState(seeAllRow != null)
          // The last key pressed anywhere on Home, which stops the TV carousel turning once nobody is there.
          val homeIdle = remember { HomeIdle() }
          Column(
              Modifier
                  .fillMaxSize()
                  .then(
                      if (touch) {
                          Modifier
                      } else {
                          Modifier.onPreviewKeyEvent {
                              homeIdle.touched()
                              false
                          }.onFocusChanged { state ->
                              if (state.hasFocus) {
                                  homeHad[0] = true
                              } else if (homeHad[0]) {
                                  homeHad[0] = false
                                  focusedSpot[0] = null
                                  // Moved off Home by the viewer (the top bar, a page over it):
                                  // nothing to put back. Lost because the player covered the
                                  // window, the place is kept.
                                  if (shellLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && windowFocusedNow) HomeFocusMemory.forget()
                              }
                          }
                      },
                  )
                  .focusProperties { onEnter = { if (pageOpen) cancelFocusChange() } }
                  .focusGroup(),
          ) {
            if (!touch && !headerOnArt) tvHeader?.invoke(false)
            LazyColumn(
                state = listState,
                // The heading on the carousel's picture comes back whole with the carousel's buttons.
                modifier = Modifier.fillMaxSize().keepTopInSight(listState, enabled = !touch).then(
                    if (touch) {
                        Modifier
                    } else {
                        // Down from a row of tiles finds nothing when the next row is wholly below
                        // the screen (the row's own lazy layout answers the search first), which
                        // the carousel at the top made the usual case on Home: the list then moves
                        // down by a row's height and the remote follows into the row that appeared.
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if ((event.key == Key.DirectionDown || event.key == Key.DirectionUp) && staleRepeat(event)) return@onPreviewKeyEvent true
                            // Up the same way: the row above may be wholly over the screen now
                            // that every row stands at the bar.
                            if (event.key == Key.DirectionUp) {
                                if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) return@onPreviewKeyEvent true
                                if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                                scope.launch {
                                    listState.scrollBy(-rowStep)
                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) return@onPreviewKeyEvent true
                            if (!listState.canScrollForward) return@onPreviewKeyEvent false
                            // From the last row there is nothing to move into: the list shows what
                            // is left under it (the favourites hint) but keeps the row whole, so the
                            // next Left or Right does not have to bring it back.
                            val lastKey = listed.lastOrNull()?.key
                            if (lastKey != null && focusedSpot[0] == lastKey) {
                                val info = listState.layoutInfo
                                val row = info.visibleItemsInfo.firstOrNull { it.key == lastKey } ?: return@onPreviewKeyEvent true
                                val room = (row.offset - info.viewportStartOffset - barPx).coerceIn(0f, rowStep)
                                if (room > 0f) scope.launch { listState.scrollBy(room) }
                                return@onPreviewKeyEvent true
                            }
                            scope.launch {
                                // In one step, as the rows move: a slide here drew a dozen slow frames.
                                listState.scrollBy(rowStep)
                                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            }
                            true
                        }
                    },
                ),
                contentPadding = PaddingValues(
                    top = if (headerOnArt) 0.dp else if (!touch) Tv.FocusClearance else if (carousel) 0.dp else under + 4.dp,
                    bottom = bottom,
                ),
                verticalArrangement = Arrangement.spacedBy(if (touch) ROW_GAP_TOUCH else ROW_GAP_TV),
            ) {
                if (carousel) {
                    if (!touch) item(key = FEATURED_KEY, contentType = FEATURED_KEY) {
                        TvFeaturedCarousel(
                            picks = featured,
                            height = carouselHeight,
                            start = start,
                            end = end,
                            watch = watch,
                            chatTitle = chatTitle,
                            active = active,
                            onPlay = onPlayNow,
                            onDetails = { pick ->
                                val series = pick.series
                                if (series != null) opened.open(series) else onDetails(pick.item, chatTitle(pick.item.chatId))
                            },
                            header = tvHeader?.let { heading -> { heading(true) } },
                            playFocus = tvPlayFocus,
                            idle = homeIdle,
                            modifier = Modifier.onFocusChanged { state ->
                                if (state.hasFocus) {
                                    // Up from the first row, which moved Home up to stand whole,
                                    // brings the carousel back whole, picture and title.
                                    if (focusedSpot[0] != FEATURED_KEY && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                        scope.launch { listState.scrollToItem(0) }
                                    }
                                    focusedSpot[0] = FEATURED_KEY
                                    HomeFocusMemory.spot = FEATURED_KEY
                                } else if (focusedSpot[0] == FEATURED_KEY) {
                                    focusedSpot[0] = null
                                }
                            },
                        )
                    } else item(key = FEATURED_KEY, contentType = FEATURED_KEY) {
                      Box {
                        FeaturedCarousel(
                            picks = featured,
                            height = carouselHeight,
                            under = under,
                            start = start,
                            end = end,
                            watch = watch,
                            chatTitle = chatTitle,
                            active = active,
                            onPlay = onPlayNow,
                            onDetails = { pick ->
                                val series = pick.series
                                if (series != null) opened.open(series) else onHoldMedia(pick.item, chatTitle(pick.item.chatId))
                            },
                        )
                        // The app's mark and name in the picture's top corner, under the status
                        // bar, as the desktop sets them (user, 2026-10-22). A phone on its side
                        // sets the title's art down the left, so there the name takes the right
                        // corner and stays off it (user, 2026-10-27).
                        val sideways = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
                        PhoneHomeBrand(
                            if (sideways) {
                                Modifier.align(Alignment.TopEnd).padding(end = end, top = under + BRAND_TOP)
                            } else {
                                Modifier.align(Alignment.TopStart).padding(start = start, top = under + BRAND_TOP)
                            },
                            onArt = true,
                        )
                      }
                    }
                } else if (touch) {
                    // No carousel: the name heads the rows instead.
                    item(key = BRAND_KEY, contentType = BRAND_KEY) {
                        PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP, bottom = 4.dp), onArt = false)
                    }
                }
                items(listed, key = { it.key }, contentType = { it::class }) { row ->
                    val loaded = row.isLoaded
                    // Asked for whenever the row comes on screen: the model fetches it the first
                    // time, and again only when a refresh passed it by (HomeViewModel.request).
                    LaunchedEffect(row.key) { onRowShown(row) }
                    val isFirst = row === listed.first()
                    val title = homeRowTitle(row, s)
                    // Only a row that leaves something out offers the rest of it.
                    val seeAll: (() -> Unit)? = when (row) {
                        is HomeRow.Continue -> onSeeContinue
                        is HomeRow.WatchLater -> onSeeWatchLater
                        is HomeRow.Chat -> { { onOpenChat(row.chatId) } }
                        is HomeRow.Recent -> { { seeAllKey = row.key } }
                        is HomeRow.NewEpisodes -> null
                    }?.takeIf { row.hasMore }
                    // On a television "See all" is the row's last tile, where Right past the last
                    // video lands, as a television's rows end (user, 2026-10-28); a phone keeps it
                    // on the heading. On the heading it took a press of its own, Up or Right, and
                    // Up stopping there made going up two presses a row.
                    val seeAllEnd = if (touch || seeAll == null) null else SeeAllEnd(seeAll, poster = row is HomeRow.Recent || row is HomeRow.Chat)
                    // This row's tiles, for the remote to be put back into: back from its See all
                    // page, or from the player.
                    val tilesFocus = remember { FocusRequester() }
                    if (!touch) androidx.compose.runtime.DisposableEffect(row.key) {
                        rowFocus[row.key] = tilesFocus
                        onDispose { if (rowFocus[row.key] === tilesFocus) rowFocus.remove(row.key) }
                    }
                    val rowSpot = if (touch) {
                        Modifier
                    } else {
                        Modifier.onFocusChanged { state ->
                            if (state.hasFocus) {
                                focusedSpot[0] = row.key
                                HomeFocusMemory.spot = row.key
                            } else if (focusedSpot[0] == row.key) {
                                focusedSpot[0] = null
                            }
                        }
                    }
                    val rowTiles = Modifier.focusRequester(tilesFocus)
                    Column(rowSpot) {
                        RowHeading(
                            title,
                            start = start,
                            end = end,
                            seeAll = seeAll.takeIf { touch },
                        )
                        if (!loaded) {
                            PlaceholderRow(start, poster = row is HomeRow.Recent || row is HomeRow.Chat)
                            return@Column
                        }
                        when (row) {
                            is HomeRow.Continue -> TileRow(start, end, rowTiles, seeAll = seeAllEnd?.copy(width = continueWidth(touch))) {
                                items(row.records, key = { "r-${it.chatId}_${it.messageId}" }) { record ->
                                    val key = SettingsStore.progressKey(record.chatId, record.messageId)
                                    LaunchedEffect(key) { onArtWanted(record) }
                                    val item = art[key] ?: remember(record) { record.toMediaItem() }
                                    val chat = record.chatTitle.ifEmpty { chatTitle(record.chatId) }
                                    val strings = LocalStrings.current
                                    VideoTile(
                                        // The name under the picture, then Telegram's facts; none on the picture.
                                        title = remember(item, strings) { continueCardTitle(item, strings) },
                                        onClick = { onResume(record) },
                                        // The detail panel, as on every other tile here: it already
                                        // offers Resume, Start over and Mark watched for a video with
                                        // a saved place.
                                        onLongClick = {
                                            heldContinue = key to row.records.indexOf(record)
                                            onHoldMedia(item, chat)
                                        },
                                        modifier = Modifier.width(continueWidth(touch))
                                            .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier)
                                            .then(if (record === continueStandIn) Modifier.focusRequester(continueRefocus) else Modifier),
                                        meta = { ink -> CaptionLine(telegramFacts(chat, item), ink) },
                                    ) {
                                        ContinueCardArt(record, item, Modifier.fillMaxSize())
                                    }
                                }
                            }
                            is HomeRow.WatchLater -> TileRow(start, end, rowTiles, seeAll = seeAllEnd?.copy(width = wideWidth(touch))) {
                                items(row.records, key = { "l-${it.id}" }) { record ->
                                    LaunchedEffect(record.key) { onLaterArtWanted(record) }
                                    val item = art[record.key] ?: remember(record) { record.toMediaItem() }
                                    val holdShow = LocalSeriesHold.current
                                    val stub = remember(record) { record.stubSeries() }
                                    MediaCard(
                                        item = item,
                                        watched = if (record.isShow) null else watch.point(item),
                                        // A show saved whole (HX20) is named as the show, its episode's picture on it.
                                        title = record.title.takeIf { record.isShow },
                                        onClick = { onOpenWatchLater(record) },
                                        // The detail panel, which offers Remove from Watch later; a show's menu for a show.
                                        onLongClick = if (stub != null && holdShow != null) {
                                            { holdShow(stub) }
                                        } else {
                                            { onHoldMedia(item, record.chatTitle.ifEmpty { chatTitle(record.chatId) }) }
                                        },
                                        dense = touch,
                                        modifier = Modifier.width(wideWidth(touch))
                                            .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier),
                                    )
                                }
                            }
                            is HomeRow.NewEpisodes -> TileRow(start, end, rowTiles) {
                                items(row.episodes, key = { "n-${it.item.id}" }) { episode ->
                                    val item = episode.item
                                    val chat = chatTitle(item.chatId)
                                    // The show's name, then which episode: the file name would say the
                                    // same less readably. Under it Telegram's facts, as on every other
                                    // row; the New badge on the art says it is new.
                                    MediaCard(
                                        item = item,
                                        watched = null,
                                        onClick = { onPlay(item, chatTitle(item.chatId)) },
                                        onLongClick = { onHoldMedia(item, chatTitle(item.chatId)) },
                                        dense = touch,
                                        title = if (episode.show.isBlank()) episode.code else "${episode.show}  ·  ${episode.code}",
                                        badge = { NewBadge(compact = touch) },
                                        meta = { ink -> CaptionLine(telegramFacts(chat, item), ink) },
                                        modifier = Modifier.width(wideWidth(touch))
                                            .then(if (isFirst && episode === row.episodes.first()) Modifier.focusRequester(first) else Modifier),
                                    )
                                }
                            }
                            is HomeRow.Chat -> ShelfRows(row.entries, watch, touch, isFirst, first, { row.title }, onPlay, onHoldMedia, start, end, rowTiles, seeAllEnd) { opened.open(it) }
                            is HomeRow.Recent -> if (row.noneChosen) {
                                com.tmplayer.ui.browse.HomeNoneChosenCard(
                                    onSettings = onOpenHomeSettings,
                                    start = start,
                                    end = end,
                                    focus = if (isFirst) first else null,
                                )
                            } else {
                                // Its tiles alone, as every row: the rest is on its See all page,
                                // which reads on through Home's chats. Read on in the row itself,
                                // it asked Telegram for more each time it neared its end, on every
                                // device (user, 2026-10-28: fewer requests, and See all for the rest).
                                // Back from its See all page, the remote lands on this row again.
                                if (!touch) LaunchedEffect(returnTo) {
                                    if (returnTo != row.key) return@LaunchedEffect
                                    withFrameNanos {}
                                    runCatching { tilesFocus.requestFocus() }
                                    returnTo = null
                                }
                                ShelfRows(row.entries, watch, touch, isFirst, first, chatTitle, onPlay, onHoldMedia, start, end, rowTiles, seeAllEnd) { opened.open(it) }
                            }
                        }
                    }
                }
                // Where the starred chats' rows would be, a card saying how to get them; otherwise a
                // Home of Continue watching alone gives no clue that it could hold more. Never
                // focusable: the remote has nothing to do here, and the way to star a chat is in
                // the chat list, which the hint names.
                if (noFavourites) {
                    item(key = "favourites-hint", contentType = "favourites-hint") {
                        FavouritesHintCard(s.homeFavouritesHint(if (touch) "phone" else "tv"), start = start, end = end)
                    }
                }
            }
          }
        }
        if (touch && under > 0.dp) {
            val ground by androidx.compose.animation.core.animateFloatAsState(
                if (overArt) 0f else 1f,
                com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                label = "statusGround",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(under)
                    .graphicsLayer { alpha = ground }
                    .background(Tone.background),
            )
        }
        ScrollToTop(listState)
        // Home's rows are not in an order a bubble could name, so the handle carries no label.
        FastScroller(listState)
    }

    // The carousel alone on Home (every row off in Settings): the remote stands on its Play.
    val carouselOnly = !touch && carousel && listed.isEmpty() && !featured.isNullOrEmpty()
    LaunchedEffect(carouselOnly) {
        if (!carouselOnly || HomeFocusMemory.away || !mayTakeFocus()) return@LaunchedEffect
        withFrameNanos { }
        tvPlayFocus?.let { play -> runCatching { play.requestFocus() } }
    }

    // Somewhere for the remote to stand the moment Home opens. Keyed on the first row, so a row
    // arriving above (Continue appearing after a first play) moves focus to the new top.
    val firstKey = listed.firstOrNull()?.key
    val firstReady = listed.firstOrNull()?.isLoaded == true
    LaunchedEffect(firstKey, firstReady) {
        // Back from the player the remote goes back where it was (below), and a row arriving
        // above afterwards (Continue watching after a first play) does not move it.
        if (!touch && (HomeFocusMemory.away || (restored[0] && focusedSpot[0] != null))) return@LaunchedEffect
        if (!touch && firstReady && mayTakeFocus()) {
            runCatching { first.requestFocus() }
            // The focused row asks to be brought into view with room round it, which scrolled the
            // carousel above it half away; Home opens at its top, the carousel whole over the row.
            if (carousel) {
                withFrameNanos { }
                listState.scrollToItem(0)
            }
        }
    }

    // Back from the player (or anything else that covered the app) the remote goes back to the
    // carousel's Play or the row it was on. The player is another activity: the shell's view lost
    // the focus behind it, or was built again from scratch where the system had destroyed it, and
    // either way the remote came back to the first thing on screen, the top bar's Home tab.
    // Held for a few frames, so whatever else takes the focus as the shell comes back (the
    // window's own first focus) is put right.
    val shellResumed = shellState.isAtLeast(Lifecycle.State.RESUMED)
    if (!touch) LaunchedEffect(shellResumed, windowFocused, firstReady) {
        if (!shellResumed || !windowFocused || !firstReady || !HomeFocusMemory.away) return@LaunchedEffect
        HomeFocusMemory.away = false
        val spot = HomeFocusMemory.spot ?: return@LaunchedEffect
        restored[0] = true
        // The carousel's lazy index is 0; the rows follow it.
        suspend fun putBack(): Boolean {
            if (spot == FEATURED_KEY) {
                val play = tvPlayFocus ?: return false
                if (!carousel) return false
                if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
                return runCatching { play.requestFocus() }.isSuccess
            }
            val at = listed.indexOfFirst { it.key == spot }
            if (at < 0) return false
            val index = at + if (carousel) 1 else 0
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                withFrameNanos { }
            }
            val tiles = rowFocus[spot] ?: return false
            return runCatching { tiles.requestFocus() }.isSuccess
        }
        var settled = 0
        for (frame in 0 until RESTORE_FRAMES) {
            withFrameNanos { }
            if (focusedSpot[0] == spot) {
                if (++settled >= RESTORE_SETTLED_FRAMES) break
                continue
            }
            settled = 0
            if (!putBack()) break
        }
        // The place is gone (the row emptied or the carousel left): the first row, as Home opens.
        if (focusedSpot[0] == null) runCatching { first.requestFocus() }
    }

    seeAllRow?.let { row ->
        RowSeeAll(
            row = row,
            title = homeRowTitle(row, LocalStrings.current),
            feed = remember(row.key) { feedOf(row) },
            watch = watch,
            start = start,
            end = end,
            bottom = bottom,
            chatTitle = chatTitle,
            onPlay = onPlay,
            onHold = onHoldMedia,
            onOpenSeries = { opened.open(it) },
            onClose = {
                returnTo = row.key
                seeAllKey = null
            },
        )
    }
    opened.series?.let { series ->
        SeriesOpened(
            series = series,
            watch = watch,
            onPlay = { onPlayNow(it, chatTitle(it.chatId)) },
            onDismiss = { opened.open(null) },
            onLongClick = { onHoldMedia(it, chatTitle(it.chatId)) },
            onDownload = onDownloadSeason,
            // Home is outside every chat: the page names the show's and goes to it (HX20).
            chatTitle = chatTitle(series.cover.chatId),
            onOpenChat = { opened.open(null); onOpenChat(series.cover.chatId) },
        )
    }
}

/**
 * A starred chat's row or Recently added: one strip in the row's own order, films, shows and other
 * videos together, all as 2:3 posters ([SeriesShelf.posterKeys]).
 */
@Composable
private fun ShelfRows(
    entries: List<ShelfEntry>,
    watch: SeriesWatch,
    touch: Boolean,
    isFirstRow: Boolean,
    first: FocusRequester,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    start: Dp,
    end: Dp,
    modifier: Modifier,
    /** A television's See all tile at the row's end; null on a phone and for a row that holds everything. */
    seeAll: SeeAllEnd? = null,
    onOpenSeries: (Series) -> Unit,
) {
    val poster = remember(entries) { SeriesShelf.posterKeys(entries).isNotEmpty() }
    TileRow(start, end, modifier, seeAll = seeAll?.copy(width = if (poster) posterWidth(touch) else wideWidth(touch), poster = poster)) {
        entries(entries, poster, watch, touch, if (isFirstRow) entries.firstOrNull() else null, first, chatTitle, onPlay, onHold, onOpenSeries)
    }
}

private fun LazyListScope.entries(
    entries: List<ShelfEntry>,
    poster: Boolean,
    watch: SeriesWatch,
    touch: Boolean,
    lead: ShelfEntry?,
    first: FocusRequester,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    items(entries, key = { it.key }, contentType = { if (poster) "poster-${it::class.simpleName}" else it::class }) { entry ->
        val focus = if (entry === lead) Modifier.focusRequester(first) else Modifier
        val width = if (poster) posterWidth(touch) else wideWidth(touch)
        ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.width(width).then(focus))
    }
}

/**
 * Recently added's See all on a phone and a television: the row's tiles as a grid, read on
 * through Home's chats as it nears its end ([com.tmplayer.ui.browse.RowFeed], the same reading
 * the row itself pages with). Back, or the arrow on a phone, goes back to Home.
 */
@Composable
private fun RowSeeAll(
    row: HomeRow.Recent,
    title: String,
    feed: com.tmplayer.ui.browse.RowFeed?,
    watch: SeriesWatch,
    start: Dp,
    end: Dp,
    bottom: Dp,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    com.tmplayer.ui.nav.BackHandler { onClose() }
    val fed = feed?.state?.collectAsState()?.value
    LaunchedEffect(feed, row) { feed?.reshape(row) }
    val entries = fed?.entries ?: row.entries
    val poster = remember(entries) { SeriesShelf.posterKeys(entries).isNotEmpty() }
    val grid = com.tmplayer.ui.components.rememberAheadGridState()
    if (feed != null) {
        com.tmplayer.ui.components.LoadMoreAhead(
            grid,
            enabled = fed == null || fed.wants(scrolling = false) || (fed.wants(scrolling = true) && grid.isScrollInProgress),
        ) { feed.loadMore() }
    }
    // The remote lands on the first tile as the page opens.
    val firstTile = remember { FocusRequester() }
    if (!touch) LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { firstTile.requestFocus() }
    }
    val gap = if (touch) TILE_GAP_TOUCH else TILE_GAP_TV
    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            // Taps and the remote stop here: nothing reaches Home under the page.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .then(if (touch) Modifier.statusBarsPadding() else Modifier.padding(top = TV_BAR_HEIGHT)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (touch) 4.dp else start, end = end).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (touch) {
                androidx.compose.material3.IconButton(onClick = onClose) {
                    androidx.compose.material3.Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text)
                }
                M3Text(
                    title,
                    style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (poster) posterWidth(touch) else wideWidth(touch)),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = start,
                end = end,
                top = if (touch) 4.dp else Tv.FocusClearance,
                bottom = bottom + if (touch) 24.dp else Tv.FocusClearance,
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(if (touch) 16.dp else gap + Tv.FocusClearance),
        ) {
            items(
                entries.size,
                key = { entries[it].key },
                // As the row's own: a show's tile and a video's are built differently, so each reuses its own kind.
                contentType = { if (poster) "poster-${entries[it]::class.simpleName}" else entries[it]::class },
            ) { index ->
                val entry = entries[index]
                val focus = if (index == 0) Modifier.focusRequester(firstTile) else Modifier
                ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth().then(focus))
            }
            if (fed != null && (fed.loading || fed.failure != null)) {
                item(key = "more-foot", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, contentType = "more-foot") {
                    com.tmplayer.ui.components.PageFoot(fed.loading, fed.failure, onRetry = { feed.loadMore() }) {
                        // A whole row of placeholders, as many as the grid fits across and as wide
                        // as its cells, so the next page arrives in the shape it is waiting in
                        // (user, 2026-10-23), rather than one card alone at the start of a line.
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val least = if (poster) posterWidth(touch) else wideWidth(touch)
                            val across = ((maxWidth + gap) / (least + gap)).toInt().coerceAtLeast(1)
                            val cell = (maxWidth - gap * (across - 1)) / across
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                repeat(across) { PlaceholderTile(cell, touch, poster) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One of a shelf's tiles: a video's card, or a show's that opens its page. */
@Composable
private fun ShelfTile(
    entry: ShelfEntry,
    poster: Boolean,
    watch: SeriesWatch,
    touch: Boolean,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier,
) {
    when (entry) {
        is ShelfEntry.File -> MediaCard(
            item = entry.item,
            watched = watch.point(entry.item),
            finished = watch.finished(entry.item),
            onClick = { onPlay(entry.item, chatTitle(entry.item.chatId)) },
            onLongClick = { onHold(entry.item, chatTitle(entry.item.chatId)) },
            dense = touch,
            poster = poster,
            // Its first bytes asked for while the card is in focus or under the finger.
            modifier = modifier,
        )
        is ShelfEntry.Show -> {
            val progress = remember(entry.series, watch) { watch.progress(entry.series) }
            // A show's card warms the episode its page would play.
            val next = remember(progress, watch) {
                (progress.next ?: entry.series.episodes.firstOrNull())?.let { watch.pick(it, progress) }
            }
            SeriesCard(
                series = entry.series,
                progress = progress,
                fresh = remember(entry.series, watch) { watch.newEpisodes(entry.series) > 0 },
                onClick = { onOpenSeries(entry.series) },
                dense = touch,
                poster = poster,
                modifier = modifier.warmUpOnIntent(next),
            )
        }
    }
}

/** One horizontal lazy row, padded to line up with the heading, remembering its tile for the D-pad. */
@Composable
private fun TileRow(
    start: Dp,
    end: Dp,
    modifier: Modifier = Modifier,
    row: androidx.compose.foundation.lazy.LazyListState = com.tmplayer.ui.components.rememberAheadListState(),
    /** On a television, a last tile that opens the rest of the row; null for none. */
    seeAll: SeeAllEnd? = null,
    content: LazyListScope.() -> Unit,
) {
    // Set once the remote has moved the row on a television, so new videos at its front do not
    // send it back to its start under the viewer.
    val moved = remember { booleanArrayOf(false) }
    com.tmplayer.ui.components.KeepAtStart(row) { moved[0] }
    if (!isTouch()) {
        // On a television the tiles fade out over the margins rather than being cut off at the
        // rail, and the tile the remote moves to stays inside them.
        // Left and Right move the focus across the tiles on screen, as a television's home screen
        // does, and the row moves only when the next tile is past its edge, by that tile, in one
        // step ([EdgeStep]). The remote held at the row's start with the row jumping under it on
        // every press read as the whole row swapping (user, 2026-10-28); slid, each press drew
        // about nine frames of some 40 ms on a 1 GB stick (Mi TV).
        val stepping = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key != Key.DirectionRight && event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
            // A held button's repeats queue up behind slow frames; one already stale is
            // dropped, so the row stops where the viewer let go instead of running on.
            if (staleRepeat(event)) return@onPreviewKeyEvent true
            moved[0] = true
            false
        }
        EdgeStep(start, end) {
            LazyRow(
                state = row,
                // Coming back down into a row lands on the tile it was left on, not whichever is nearest.
                modifier = modifier.then(stepping).fillMaxWidth().focusRestorer().edgeFade(row, start, end, ground = Tone.background),
                // Room above and below for a focused tile to grow into without being clipped by the row.
                contentPadding = PaddingValues(start = start, end = end, top = Tv.FocusClearance, bottom = Tv.FocusClearance),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_TV),
            ) {
                content()
                if (seeAll != null) item(key = SEE_ALL_TILE, contentType = SEE_ALL_TILE) { SeeAllTile(seeAll) }
            }
        }
        return
    }
    LazyRow(
        state = row,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = start, end = end),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_TOUCH),
        content = content,
    )
}

/** The Telegram facts under a continue card: where it was posted, its quality and its size. */
@Composable
private fun telegramFacts(chat: String, item: MediaItem): String {
    val s = LocalStrings.current
    return remember(chat, item, s) {
        listOfNotNull(
            chat.ifBlank { null },
            item.qualityTags.firstOrNull(),
            item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
        ).joinToString("  ·  ")
    }
}

/** One line of facts under a card, in the tile's [ink]: muted at rest, the full text under focus. */
@Composable
private fun CaptionLine(text: String, ink: androidx.compose.ui.graphics.ColorProducer) {
    TileCaption(text, if (isTouch()) M3MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelMedium, ink)
}

/**
 * A row's name, SemiBold: titleLarge on a phone and on a television. A row that leaves videos out
 * offers the rest at the end of the heading, as a link with a chevron and no fill ([SeeAllLink]);
 * on a television it takes focus only from the row under it (see [HomePane]).
 */
@Composable
private fun RowHeading(
    title: String,
    start: Dp,
    end: Dp,
    /** A phone's See all; a television's is the row's last tile ([SeeAllTile]). */
    seeAll: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end, bottom = if (touch) HEADING_TO_ROW else 0.dp)
            // Every heading is as tall as its button, with or without one, so the gap between a
            // title and its tiles is the same on every row.
            .heightIn(min = if (touch) SEE_ALL_CHIP else TV_SEE_ALL_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (touch) {
            M3Text(
                title,
                style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (seeAll != null) {
            // The touch target is Material's 48 dp round the link.
            SeeAllLink(
                label = s.homeSeeAll,
                onClick = seeAll,
                modifier = Modifier.padding(start = 12.dp).minimumInteractiveComponentSize(),
            )
        }
    }
}

/**
 * A row still asking Telegram: blank cards the size of the real ones, so nothing jumps when it
 * fills. The caption is the cards' own, in the same type with nothing visible in it, rather than
 * a guessed height: it then matches at any font scale.
 *
 * [poster] for a row whose cards are 2:3 ([SeriesShelf.posterKeys]: Recently added and a chat's
 * row). Drawn 16:9 there, the row grew by half when its posters came in (TV, 2026-10-27).
 */
@Composable
private fun PlaceholderRow(start: Dp, poster: Boolean = false) {
    val touch = isTouch()
    val width = if (poster) posterWidth(touch) else wideWidth(touch)
    Row(
        Modifier.fillMaxWidth().padding(start = start, top = if (touch) 0.dp else Tv.FocusClearance, bottom = if (touch) 0.dp else Tv.FocusClearance),
        horizontalArrangement = Arrangement.spacedBy(if (touch) TILE_GAP_TOUCH else TILE_GAP_TV),
    ) {
        repeat(PLACEHOLDER_TILES) { PlaceholderTile(width, touch, poster) }
    }
}

/** One blank card the size of a real one: a [PlaceholderRow]'s, and the foot of a row reading on. */
@Composable
private fun PlaceholderTile(width: Dp, touch: Boolean, poster: Boolean = false) {
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                .clip(RoundedCornerShape(Corner.Medium))
                .background(Tone.surface),
        )
        Spacer(Modifier.height(if (touch) 8.dp else 10.dp))
        if (touch) {
            M3Text(" ", style = M3MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            M3Text(" ", style = M3MaterialTheme.typography.labelMedium, maxLines = 1)
        } else {
            Text(" ", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(" ", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * A film's or a show's poster: 126 x 189 dp on both, Nuvio's measure, which on a phone held
 * upright shows about 2.8 across, the cut third saying there is more this way.
 */
@Suppress("UNUSED_PARAMETER")
private fun posterWidth(touch: Boolean): Dp = POSTER_WIDTH

private val POSTER_WIDTH = 126.dp

/**
 * Any other video's 16:9 card. A television's fits [Tv.FocusClearance] when it grows, and is about
 * the size Netflix and Google TV give a landscape card: five or so across rather than four
 * (user, 2026-10-23: the cards made everything look big and showed too little).
 */
private fun wideWidth(touch: Boolean): Dp = if (touch) 200.dp else 176.dp

/**
 * A Continue watching card, a little wider, as it carries its name on the art. On a television four
 * or so across, so the first row under the carousel fits whole on the first screen.
 */
private fun continueWidth(touch: Boolean): Dp = if (touch) 247.dp else 208.dp

private const val PLACEHOLDER_TILES = 5

/** The TV button's own height, which every TV row heading takes so the rows space evenly. */
private val TV_SEE_ALL_HEIGHT = 40.dp

/** A television's See all at a row's end: what it opens, and its tile's size. */
private data class SeeAllEnd(val onClick: () -> Unit, val width: Dp = 0.dp, val poster: Boolean = false)

private const val SEE_ALL_TILE = "see-all"

/**
 * A row's last tile on a television, where Right past the last video lands: the art the size of
 * the row's own, with the same focus, and "See all" on it in the accent with a chevron. Nothing under it:
 * a count read "0 videos" on Recently added, which cannot tell, and the words on the art read
 * at a glance (user, 2026-10-28).
 */
@Composable
private fun SeeAllTile(end: SeeAllEnd) {
    val s = LocalStrings.current
    VideoTile(
        title = s.homeSeeAll,
        onClick = end.onClick,
        poster = end.poster,
        showTitle = false,
        modifier = Modifier.width(end.width),
    ) {
        Column(
            // The app's accent, faint over the surface, so it reads as the row's way on without
            // shouting over the pictures beside it (user, 2026-10-28: "too big").
            Modifier.fillMaxSize().background(Tone.surface).background(Tone.accent.copy(alpha = 0.10f)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                s.homeSeeAll,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.accent,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** The phone's See all chip. */
private val SEE_ALL_CHIP = 32.dp

/** Between a phone row's heading and its cards. */
private val HEADING_TO_ROW = 10.dp

private val ROW_GAP_TOUCH = 24.dp

/** A television's rows already keep [Tv.FocusClearance] above and below their tiles. */
private val ROW_GAP_TV = 12.dp

private val TILE_GAP_TOUCH = 10.dp
private val TILE_GAP_TV = 12.dp

/** Between a row's poster strip and its strip of cards, on a phone. */
private val STRIP_GAP = 12.dp


/** The lazy key of the phone's featured carousel at the top of Home. */
private const val FEATURED_KEY = "featured"

/** How far Home moves down on a television when Down finds no row on screen below the remote. */
private val TV_DOWN_STEP = 200.dp

/**
 * The carousel's pages from the Recently added row [recent]: null while that row is still being
 * asked for or the fill is still deciding (the carousel then holds its frame with a stand-in, at
 * most [HomeFeatured.FILL_WAIT_MS] once the row is in), empty for nothing to feature, which hides
 * the carousel. Only complete pages ([com.tmplayer.ui.online.completeFeatured]), decided once
 * per fill so no page comes or goes while it is looked at; a refresh keeps the pages up until
 * the new ones are decided. With online lookups off there are no complete pages, so none at once.
 */
@Composable
private fun rememberFeatured(recent: HomeRow.Recent?): List<com.tmplayer.data.FeaturedPick>? {
    val online = com.tmplayer.online.OnlineMetadata.current
    val enabled = online?.settings?.collectAsState()?.value?.enabled == true
    val candidates = remember(recent) {
        recent?.takeIf { it.loaded }?.let { com.tmplayer.data.HomeFeatured.pick(it.entries, com.tmplayer.data.HomeFeatured.CANDIDATES) }
    }
    var decided by remember { mutableStateOf<List<com.tmplayer.data.FeaturedPick>?>(null) }
    LaunchedEffect(candidates, online, enabled) {
        if (!enabled) {
            decided = emptyList()
            return@LaunchedEffect
        }
        val picks = candidates ?: return@LaunchedEffect
        if (picks.isEmpty()) {
            decided = picks
            return@LaunchedEffect
        }
        // The lookups still unanswered when the wait is up go on in this effect, so their answers
        // are on the device for the next fill; they stop with it.
        val keys = try {
            com.tmplayer.ui.online.completeFeatured(picks, this)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        decided = picks.filter { it.key in keys }.take(com.tmplayer.data.HomeFeatured.LIMIT)
    }
    return if (!enabled) emptyList() else decided
}


/**
 * A row that keeps still while the focused tile stands between its margins ([start], [end]) and
 * otherwise moves just far enough to bring it inside them, at once rather than slid.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EdgeStep(start: Dp, end: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val startPx = with(density) { start.toPx() }
    val endPx = with(density) { end.toPx() }
    val spec = remember(startPx, endPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            @Deprecated("Matches the interface; the snap is the point.")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.snap()

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < startPx -> offset - startPx
                offset + size > containerSize - endPx -> offset + size - (containerSize - endPx)
                else -> 0f
            }
        }
    }
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}


/** How long an empty Home waits for its rows before it takes itself for a first run. */
private const val ROWS_WAIT_MS = 10_000L

/** How long Home without a carousel waits for one to turn up before the cold start's loader lifts. */
private const val NO_CAROUSEL_SETTLE_MS = 1_500L

/** The same on a television, where getting to the rows fast matters more than a jump the focus hides. */
private const val NO_CAROUSEL_SETTLE_TV_MS = 300L


/**
 * A phone's "See all" at the end of a Home row's heading: muted words and a chevron with no fill at rest. Under
 * the remote, or pressed, the words and the chevron turn the accent, grow a little and are
 * underlined, so the place reads from across the room without a pill round it.
 */
@Composable
private fun SeeAllLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val lit = focused || pressed
    val still = com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
    val scale by androidx.compose.animation.core.animateFloatAsState(
        when {
            still -> 1f
            pressed -> com.tmplayer.ui.theme.Motion.PressScale
            focused -> com.tmplayer.ui.theme.Motion.FocusScale
            else -> 1f
        },
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllScale",
    )
    val ink by androidx.compose.animation.animateColorAsState(
        if (lit) Tone.accent else Tone.muted,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllInk",
    )
    val line by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllLine",
    )
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .drawBehind {
                if (line <= 0f) return@drawBehind
                val stroke = 2.dp.toPx()
                drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - stroke), size = androidx.compose.ui.geometry.Size(size.width * line, stroke))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(label, style = M3MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        androidx.compose.material3.Icon(TmIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * A held D-pad's repeat that has waited longer than a couple of frames to be handled. Leanback
 * caps the moves it queues the same way (GridLayoutManager's pending moves): on a stick whose
 * frames take 40 ms, repeats pile up, and once the button is let go the focus kept moving.
 */
private fun staleRepeat(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val native = event.nativeKeyEvent
    return native.repeatCount > 0 && android.os.SystemClock.uptimeMillis() - native.eventTime > STALE_REPEAT_MS
}

/** How old a repeat may be and still move the focus: about two frames on a slow stick. */
private const val STALE_REPEAT_MS = 80L

/**
 * Home's vertical list under the TV top bar: a focused item already standing between the bar's foot
 * ([barPx]) and three fifths of the way down stays where it is; anything above that band, under
 * the bar, or below it is brought to three tenths of the way down, as the television does.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun calmVertical(
    barPx: Float,
    /** A distance of its own for a request (the first row under the carousel), or null for the band's. */
    held: (offset: Float, size: Float, containerSize: Float) -> Float? = { _, _, _ -> null },
) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    // At once rather than slid: a slide down to the next row drew some seventeen frames on the
    // 1 GB stick, each 20 to 130 ms, so the page swam for half a second after every Up and Down.
    // The rows already move in one step ([EdgeStep]); the page now does too.
    @Deprecated("Matches the interface; the snap is the point.")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.snap()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        held(offset, size, containerSize)?.let { return it }
        val lead = containerSize * 0.3f
        return when {
            offset < barPx -> offset - lead
            offset + size > containerSize -> offset - lead
            offset > containerSize * 0.6f -> offset - lead
            else -> 0f
        }
    }
}

/**
 * Where the remote stood on the television's Home: the carousel ([FEATURED_KEY]) or a row's key.
 * For the process rather than the screen, since the system may destroy the shell behind the
 * player and build Home again when the viewer comes back. [away] is set as the player (or
 * anything else) covers the app with the remote on Home, and cleared once it has been put back.
 */
internal object HomeFocusMemory {
    @Volatile var spot: String? = null
    @Volatile var away: Boolean = false

    /** The remote left Home on purpose: nothing to put back. */
    fun forget() {
        spot = null
        away = false
    }
}

/** How many frames after coming back the remote's place on Home is held against other claims. */
private const val RESTORE_FRAMES = 12

/** Frames in a row the remote has to stay put for its place to count as restored. */
private const val RESTORE_SETTLED_FRAMES = 3

/** The list key of the name over Home's rows when there is no carousel. */
private const val BRAND_KEY = "brand"

/** The name's distance below the status bar on a phone's Home. */
internal val BRAND_TOP = 12.dp

/** The mark and "TMPlayer" at the top of a phone's Home, in the colours for art over the carousel. */
@Composable
internal fun PhoneHomeBrand(modifier: Modifier, onArt: Boolean) {
    com.tmplayer.ui.components.BrandLockup(
        modifier,
        layout = com.tmplayer.ui.components.BrandLayout.Row,
        color = if (onArt) Tone.onArt else Tone.text,
    )
}
