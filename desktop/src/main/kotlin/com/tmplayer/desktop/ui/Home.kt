package com.tmplayer.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.NewEpisode
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.continueCardTitle
import com.tmplayer.ui.browse.ContinueCardArt
import com.tmplayer.ui.browse.FirstVideoCard
import com.tmplayer.ui.browse.HomeViewModel
import com.tmplayer.ui.components.LoadMoreAhead
import com.tmplayer.ui.browse.NewBadge
import com.tmplayer.ui.browse.rememberHomeRows
import com.tmplayer.ui.browse.homeRowTitle
import com.tmplayer.ui.browse.isLoaded
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.WithinMargins
import com.tmplayer.ui.components.edgeFade
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.online.LocalOnlineArt
import com.tmplayer.ui.online.OnlineArt
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Home on the desktop: the same rows as the phone and the television (see
 * [com.tmplayer.data.HomeRows]), each row a horizontal strip that loads when it scrolls into view.
 */
@Composable
fun HomePage(state: ShellState, chats: ChatListViewModel) {
    val model = rememberViewModel(Unit) {
        HomeViewModel(sizeLimits = { state.settings.minSizeBytes.first() to state.settings.maxSizeBytes.first() }, cached = true)
    }
    // Up to date while Home is open: when its rows are a minute old, then every few minutes, in
    // place, the cards staying where they are.
    LaunchedEffect(model) {
        model.refreshIfStale()
        while (true) {
            kotlinx.coroutines.delay(HomeViewModel.AUTO_REFRESH_MS)
            model.refreshInPlace(background = true)
        }
    }
    val chatState by chats.state.collectAsState()
    val list = (chatState as? UiState.Content)?.value?.chats.orEmpty()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val history by state.settings.continueWatching.collectAsState(initial = emptyList())
    val later by state.watched.watchLater.items.collectAsState(initial = emptyList())
    val watched by state.watched.watched.collectAsState(initial = emptyMap())
    val art by model.art.collectAsState()
    // Recently added fills the carousel, so one starred chat is enough for it, as on the phone and
    // the television; with fewer than two it is not drawn as a row as well (see HomeRowsView).
    val rows = rememberHomeRows(model, list, favourites, history, later, watched.values, recentMinStarred = 1)
    // Asked for as soon as the starred chats are known, not once the chat list has loaded and the
    // row is drawn: the loader waits on the carousel, and the carousel on this row.
    // Home fills itself from the search across chats, so it is asked for whether or not any are starred.
    LaunchedEffect(Unit) { model.requestRecent() }
    // With nothing of its own to show yet, Home waits on the chat list, or says why it failed,
    // rather than greet somebody with starred chats as a first run. With starred chats it waits a
    // moment for them, so the carousel does not land over Continue watching, but never for the
    // whole list: an account with thousands of chats sat on "Loading your chats" for hours (HX8).
    // Home's own chats (the last ones, or the starred ones read one by one) stand in meanwhile.
    val ownChats by model.ownChats.collectAsState()
    val starredUnknown = favourites.isNotEmpty() && list.isEmpty() && ownChats.none { it.id in favourites }
    val listBriefly = rememberBriefWait(chatState is UiState.Loading)
    val waitForChats = rows.isEmpty() || (starredUnknown && listBriefly)
    if (waitForChats && (chatState is UiState.Loading || chatState is UiState.Error)) {
        // Retry goes on with the load that is running rather than asking again from the top.
        StateBox(chatState, onRetry = chats::retry) {}
        return
    }
    val stalled by model.stalled.collectAsState()
    HomeRowsView(
        rowsReady = model.rowsSettled,
        state = state,
        rows = rows,
        chats = list.ifEmpty { ownChats },
        stalled = stalled,
        art = art,
        onRowShown = { row ->
            when (row) {
                is HomeRow.Chat -> model.request(row.chatId)
                is HomeRow.Recent -> model.requestRecent()
                is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.NewEpisodes -> Unit
            }
        },
        onArtWanted = model::requestArt,
        onLaterArtWanted = model::requestArt,
        onRefresh = model::refreshInPlace,
        seeAll = model::seeAll,
    )
}

/**
 * Home's rows as drawn, given everything they are built from; the render test calls this directly.
 *
 * Continue watching as wide cards with the name on the art, and in a starred chat's row and in
 * Recently added the films and shows as 2:3 posters in one strip with the other videos as cards in
 * a strip under it.
 *
 * Like a desktop app rather than a stretched phone: the cards are sized from the page's width so
 * whole cards fill a row and a wider window shows more of them; a hovered row shows arrows at its
 * ends and scrolls sideways under a trackpad or Shift and the wheel; every row has "See all",
 * which opens the row as a page of its own ([RowPage]). From the keyboard the arrows move between
 * cards and rows, Enter opens a card's details and Space plays it; a click opens the details and
 * a double click plays, as everywhere else.
 */
@Composable
internal fun HomeRowsView(
    state: ShellState,
    rows: List<HomeRow>,
    chats: List<ChatSummary>,
    art: Map<String, MediaItem>,
    onRowShown: (HomeRow) -> Unit,
    onArtWanted: (ResumeRecord) -> Unit,
    onRefresh: () -> Unit,
    onLaterArtWanted: (WatchLaterRecord) -> Unit = {},
    /** A row's See all page read on through its chats ([HomeViewModel.seeAll]); null keeps it to what Home holds. */
    seeAll: (HomeRow) -> com.tmplayer.ui.browse.RowFeed? = { null },
    /** False while the rows have not been built from an answer yet ([HomeViewModel.rowsSettled]). */
    rowsReady: Boolean = true,
    /** Telegram has been slow for a few seconds ([HomeViewModel.stalled]): a line says so, with Refresh. */
    stalled: Boolean = false,
) {
    val s = LocalStrings.current
    var openSeries by remember { mutableStateOf<Series?>(null) }
    var openRow by remember { mutableStateOf<String?>(null) }
    val titles = remember(chats) { chats.associate { it.id to it.title } }
    LaunchedEffect(titles) { state.noteChatTitles(chats) }
    // Held above the show and "See all" pages, so coming back from either finds the rows where
    // they were rather than at the top.
    val list = com.tmplayer.ui.components.rememberAheadListState()
    val shown = openSeries
    if (shown != null) {
        SeriesPage(state, shown, rememberSeriesWatch(state), onClose = { openSeries = null })
        return
    }
    val paged = openRow?.let { key -> rows.firstOrNull { it.key == key } }
    if (paged != null) {
        RowPage(
            state = state,
            row = paged,
            feed = remember(paged.key) { seeAll(paged) },
            titles = titles,
            chats = chats,
            art = art,
            onArtWanted = onArtWanted,
            onLaterArtWanted = onLaterArtWanted,
            onOpenSeries = { openSeries = it },
            onBack = { openRow = null },
        )
        return
    }
    // F5 and Ctrl+R still refresh Home. No "Home" heading, as on the phone, and no buttons over
    // the carousel either: a desktop page needs no refresh button, and the poster size, which
    // Home's cards follow, is set from a chat's page and History.
    RegisterRefresh(state, onRefresh)
    val recentRow = rows.firstOrNull { it is HomeRow.Recent } as HomeRow.Recent?
    val continueRow = rows.firstOrNull { it is HomeRow.Continue } as HomeRow.Continue?
    // The carousel is filled from Recently added, wherever that row sits on the page, and from
    // Continue watching, whose cards need their videos asked for to have a picture.
    // The starred chats' newest videos feed it too: asked for while it waits, not only on scroll.
    LaunchedEffect(recentRow?.loaded) {
        if (recentRow == null || recentRow.loaded) return@LaunchedEffect
        onRowShown(recentRow)
        rows.filterIsInstance<HomeRow.Chat>().filterNot { it.loaded }.take(HomeViewModel.EAGER_CHATS).forEach(onRowShown)
    }
    LaunchedEffect(continueRow?.records) { continueRow?.records?.forEach(onArtWanted) }
    // Settings can turn the carousel off: then nothing is picked or turned over for it either.
    val carouselOn by state.settings.homeCarousel.collectAsState(initial = true)
    val featured = rememberFeatured(recentRow.takeIf { carouselOn }, continueRow.takeIf { carouselOn }, art)
    // Every tile on Home has a picture of its own; "See all" still lists the whole row. Recently
    // added is a row too, under Continue watching, as well as the carousel (user, 2026-10-23).
    val shownRows = remember(rows, art) { rows.mapNotNull { HomeRows.withArt(it, art) } }
    val watch = rememberSeriesWatch(state)
    // In line with the page header's title, whatever the window's width.
    val side = pageMargin()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                LaunchedEffect(Unit) { com.tmplayer.ui.browse.HomeFirstPaint.markReady() }
                Box(Modifier.fillMaxWidth().height(TOP_DRAG).windowDrag()) {
                    HomeBrand(Modifier.align(Alignment.TopStart).padding(start = side, top = BRAND_TOP))
                }
                // Rows still being built off the main thread: the brand over a blank page, not the
                // first run's card, which flashed up at every start for accounts full of videos.
                // Given up after a while, so a first run without a connection still says what to do.
                var waitedOut by remember { mutableStateOf(false) }
                if (!rowsReady) LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(10_000)
                    waitedOut = true
                }
                if (!rowsReady && !waitedOut) return@Column
                // Nothing played and nothing starred: a first run, so say how to get a first video in:
                // forward it to Saved Messages, which the button opens.
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FirstVideoCard(
                        title = s.homeFirstTitle,
                        body = s.homeFirstBody,
                        more = s.homeFirstMore,
                        button = s.homeFirstOpenSaved,
                        onButton = { state.showChats(BrowseSection.of(BrowseTab.Saved)) },
                    )
                }
                return@Column
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sizes = CardSizes.of(state.posterWidth, maxWidth - side * 2)
                LazyColumn(
                    Modifier.fillMaxSize().homeEndScrolls(list).remembersRemoteItem(),
                    state = list,
                    contentPadding = PaddingValues(bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // With nothing to feature, a strip the window can be dragged by stands in for it.
                    item(key = "featured", contentType = "featured") {
                        if (!carouselOn || (recentRow == null && continueRow == null) || featured?.isEmpty() == true) {
                            // No carousel: the rows are the first screen, once one has had a moment to turn up.
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(1_500L)
                                com.tmplayer.ui.browse.HomeFirstPaint.markReady()
                            }
                            // The name in the strip the window is dragged by, where the carousel would set it.
                            Box(Modifier.fillMaxWidth().height(TOP_DRAG).windowDrag()) {
                                HomeBrand(Modifier.align(Alignment.TopStart).padding(start = side, top = BRAND_TOP))
                            }
                        } else {
                            // The app's name over the carousel's empty top corner, as the side bar sets it
                            // (user, 2026-10-22), with the browser's Beta word beside it.
                            // Read here: the Box below hides the frame's own measures.
                            val heroHeight = featuredHeight(maxWidth, maxHeight)
                            Box {
                                FeaturedCarousel(
                                    picks = featured,
                                    height = heroHeight,
                                    side = side,
                                    watch = watch,
                                    chatTitle = { titles[it].orEmpty() },
                                    onPlay = { state.openPlayer(it, startFromBeginning = false) },
                                    onDetails = { pick ->
                                        val show = pick.series
                                        if (show != null) {
                                            openSeries = show
                                        } else {
                                            state.openDetail(DetailRequest(pick.item, titles[pick.item.chatId].orEmpty(), outsideChat = true))
                                        }
                                    },
                                )
                                HomeBrand(Modifier.align(Alignment.TopStart).padding(start = side, top = BRAND_TOP))
                            }
                        }
                    }
                    if (stalled) item(key = "stalled", contentType = "stalled") { HomeStalledLine(side, onRefresh) }
                    items(shownRows, key = { it.key }, contentType = { it::class }) { row ->
                        val loaded = row.isLoaded()
                        // Every time it comes on screen: a row a refresh passed by is asked again then.
                        LaunchedEffect(row.key) { onRowShown(row) }
                        Column {
                            RowHeading(
                                title = row.title(s, titles),
                                count = row.countLabel(s),
                                side = side,
                                // Every loaded row opens as a page of its own.
                                onSeeAll = if (loaded) ({ openRow = row.key }) else null,
                            )
                            if (!loaded) {
                                PlaceholderStrip(side, sizes.card)
                                return@Column
                            }
                            when (row) {
                                is HomeRow.Continue -> Strip(side) {
                                    items(row.records, key = { "r-${it.chatId}_${it.messageId}" }) { record ->
                                        Box(Modifier.width(sizes.wide)) { ContinueCell(state, record, art, titles, onArtWanted) }
                                    }
                                }
                                // The card a chat's grid draws, so the pane offers Remove from Watch later.
                                is HomeRow.WatchLater -> Strip(side) {
                                    items(row.records, key = { "l-${it.key}" }) { record ->
                                        Box(Modifier.width(sizes.card)) { LaterCell(state, record, art, onLaterArtWanted) }
                                    }
                                }
                                is HomeRow.NewEpisodes -> Strip(side) {
                                    items(row.episodes, key = { "n-${it.item.id}" }) { episode ->
                                        Box(Modifier.width(sizes.card)) { NewEpisodeCell(state, episode, titles) }
                                    }
                                }
                                is HomeRow.Chat -> ShelfStrips(state, side, sizes, row.entries, { row.title }) { openSeries = it }
                                is HomeRow.Recent -> ShelfStrips(state, side, sizes, row.entries, { titles[it].orEmpty() }) { openSeries = it }
                            }
                        }
                    }
                }
                ScrollToTop(list)
                FastScroller(list)
            }
        }
    }
}

/**
 * A Home row opened by its "See all": every video the row holds, Home's own cards in a grid as
 * wide as the page, under a header with the row's name, how many there are and the way back (the
 * back button or Esc). A starred chat's row also offers its chat, which holds every video rather
 * than the ones Home fetched.
 */
@Composable
private fun RowPage(
    state: ShellState,
    row: HomeRow,
    feed: com.tmplayer.ui.browse.RowFeed?,
    titles: Map<Long, String>,
    chats: List<ChatSummary>,
    art: Map<String, MediaItem>,
    onArtWanted: (ResumeRecord) -> Unit,
    onLaterArtWanted: (WatchLaterRecord) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    BackHandler(enabled = true) { onBack() }
    val title = row.title(s, titles)
    // A starred chat's row holds what Home fetched; the chat itself holds every video.
    val more: Pair<String, () -> Unit>? = (row as? HomeRow.Chat)?.let { chatRow ->
        chats.firstOrNull { it.id == chatRow.chatId }?.let { chat -> s.detailOpenChat(chatRow.title) to { state.openChat(chat) } }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title,
            row.countLabel(s),
            leading = {
                BarTooltip(s.commonBack, null, below = true) {
                    IconButton(onClick = onBack) { Icon(TmIcons.ArrowBack, contentDescription = s.commonBack) }
                }
            },
            actions = {
                if (more != null) {
                    TextButton(onClick = more.second) {
                        Text(more.first)
                        Icon(TmIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            },
        )
        val side = pageMargin()
        // The row's chats read on a page at a time as the foot comes near: the row's own tiles
        // first, then everything else, newest first, growing only at the foot.
        val fed = feed?.state?.collectAsState()?.value
        LaunchedEffect(feed, row) { (feed ?: return@LaunchedEffect).reshape(row) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sizes = CardSizes.of(state.posterWidth, maxWidth - side * 2, peek = false)
            val fedEntries = fed?.entries
            // Each kind of card in its own grid, a row of cells at a time, so the grid stays lazy.
            val sections: List<Pair<Dp, List<Pair<String, @Composable () -> Unit>>>> = remember(row, art, sizes, fedEntries) {
                when (row) {
                    is HomeRow.Continue -> listOf(
                        sizes.wide to (row.records + row.more).map { record ->
                            "r-${record.chatId}_${record.messageId}" to @Composable { ContinueCell(state, record, art, titles, onArtWanted) }
                        },
                    )
                    is HomeRow.WatchLater -> listOf(
                        sizes.card to (row.records + row.more).map { record ->
                            "l-${record.key}" to @Composable { LaterCell(state, record, art, onLaterArtWanted) }
                        },
                    )
                    is HomeRow.NewEpisodes -> listOf(
                        sizes.card to row.episodes.map { episode -> "n-${episode.item.id}" to @Composable { NewEpisodeCell(state, episode, titles) } },
                    )
                    is HomeRow.Chat -> shelfSections(state, sizes, fedEntries ?: (row.entries + SeriesShelf.arrange(row.more)), { row.title }, onOpenSeries)
                    is HomeRow.Recent -> shelfSections(state, sizes, fedEntries ?: row.entries, { titles[it].orEmpty() }, onOpenSeries)
                }
            }
            val list = com.tmplayer.ui.components.rememberAheadListState()
            if (feed != null && fed != null) {
                LoadMoreAhead(list, enabled = fed.wants(scrolling = list.isScrollInProgress), ahead = FEED_LINES_AHEAD) { feed.loadMore() }
            }
            LazyColumn(
                Modifier.fillMaxSize().homeEndScrolls(list).remembersRemoteItem(),
                state = list,
                contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                sections.forEachIndexed { at, (width, cells) ->
                    val columns = sizes.columnsFor(width)
                    val lines = cells.chunked(columns)
                    lines.forEachIndexed { line, cellsInLine ->
                        item(key = "s$at-$line-${cellsInLine.first().first}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                cellsInLine.forEach { (_, cell) -> Box(Modifier.width(width)) { cell() } }
                            }
                        }
                    }
                }
                if (feed != null && fed != null && (fed.loading || fed.failure != null)) {
                    item(key = "foot", contentType = "foot") {
                        com.tmplayer.ui.components.PageFoot(fed.loading, fed.failure, onRetry = feed::loadMore) {
                            // A line of placeholder cards shaped as the page's last grid, where the next ones land.
                            val width = sections.lastOrNull()?.first ?: sizes.card
                            val poster = width == sizes.poster
                            val shimmer = com.tmplayer.ui.components.rememberShimmer()
                            Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                repeat(sizes.columnsFor(width)) { index ->
                                    PosterSkeleton(shimmer, poster, index, modifier = Modifier.width(width))
                                }
                            }
                        }
                    }
                }
            }
            ScrollToTop(list)
            FastScroller(list)
        }
    }
}

/** Lines of cards ahead of a See all page's foot that its next page is asked for at. */
private const val FEED_LINES_AHEAD = 3

/** A row's posters and its other videos, as two grids of their own (see [ShelfStrips]). */
private fun shelfSections(
    state: ShellState,
    sizes: CardSizes,
    entries: List<ShelfEntry>,
    chatTitle: (Long) -> String,
    onOpenSeries: (Series) -> Unit,
): List<Pair<Dp, List<Pair<String, @Composable () -> Unit>>>> {
    val (posters, videos) = entries.distinctBy { it.key }.partition(SeriesShelf::isPoster)
    return listOf(
        sizes.poster to posters.map { entry -> entry.key to @Composable { ShelfCell(state, entry, true, chatTitle, onOpenSeries) } },
        sizes.card to videos.map { entry -> entry.key to @Composable { ShelfCell(state, entry, false, chatTitle, onOpenSeries) } },
    ).filter { it.second.isNotEmpty() }
}

/** A row's heading: its name, and "See all" with how many videos it holds. */
@Composable
private fun RowHeading(title: String, count: String?, side: Dp, onSeeAll: (() -> Unit)?) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = side, end = side).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // The words alone, no count (user, 2026-10-22: "just a see all button").
        if (onSeeAll != null) SeeAllLink(s.homeSeeAll, onSeeAll)
    }
}

/** A row still asking Telegram: a card's art and its caption with nothing in it, so the rows below stay put. */
@Composable
private fun PlaceholderStrip(side: Dp, width: Dp) {
    Row(Modifier.padding(start = side, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        repeat(4) {
            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.padding(bottom = 4.dp).fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Tone.surface, MaterialTheme.shapes.medium),
                )
                Text(" ", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(" ", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** A Continue watching card: the name and the progress on the art, Telegram's facts under it. */
@Composable
private fun ContinueCell(
    state: ShellState,
    record: ResumeRecord,
    art: Map<String, MediaItem>,
    titles: Map<Long, String>,
    onArtWanted: (ResumeRecord) -> Unit,
) {
    val key = SettingsStore.progressKey(record.chatId, record.messageId)
    LaunchedEffect(key) { onArtWanted(record) }
    LaunchedEffect(key) { state.noteChatTitle(record.chatId, record.chatTitle) }
    val item = art[key] ?: remember(record) { record.toMediaItem() }
    val chatTitle = record.chatTitle.ifEmpty { titles[record.chatId].orEmpty() }
    val s = LocalStrings.current
    Poster(
        state = state,
        nav = null,
        index = 0,
        item = item,
        chatTitle = chatTitle,
        // The card draws its own pill of progress, under the name.
        progress = null,
        art = { OnHome { ContinueCardArt(record, item, Modifier.fillMaxSize()) } },
        // The name under the picture, then Telegram's facts; none on the picture.
        titleText = androidx.compose.runtime.remember(item, s) { continueCardTitle(item, s) },
        // Where it came from and what the file is: Telegram's facts.
        subtitle = telegramFacts(chatTitle, item),
        onDetails = { refocus ->
            state.openDetail(DetailRequest(item = item, chatTitle = chatTitle, outsideChat = true, onClosed = refocus))
        },
    )
}

/** A Watch later card: the one a chat's grid draws, so the pane offers Remove from Watch later. */
@Composable
private fun LaterCell(
    state: ShellState,
    record: WatchLaterRecord,
    art: Map<String, MediaItem>,
    onLaterArtWanted: (WatchLaterRecord) -> Unit,
) {
    LaunchedEffect(record.key) { onLaterArtWanted(record) }
    LaunchedEffect(record.key) { state.noteChatTitle(record.chatId, record.chatTitle) }
    val item = art[record.key] ?: remember(record) { record.toMediaItem() }
    OnHome { MediaTile(state, item, record.chatTitle, outsideChat = true) }
}

/** A new episode of a show in progress, with the New badge on its art. */
@Composable
private fun NewEpisodeCell(state: ShellState, episode: NewEpisode, titles: Map<Long, String>) {
    val item = episode.item
    val chatTitle = titles[item.chatId].orEmpty()
    Poster(
        state = state,
        nav = null,
        index = 0,
        item = item,
        chatTitle = chatTitle,
        progress = null,
        badge = { NewBadge(compact = false) },
        art = { OnHome { OnlineArt(item, Modifier.fillMaxSize()) { RecordArt(item) } } },
        // Telegram's facts, as on every other row: the title already names the episode, and the
        // New badge on the art says it is new.
        subtitle = telegramFacts(chatTitle, item),
        onDetails = { refocus ->
            state.openDetail(DetailRequest(item = item, chatTitle = chatTitle, outsideChat = true, onClosed = refocus))
        },
    )
}

/** A starred chat's video or show, as a 2:3 [poster] or a 16:9 card. */
@Composable
private fun ShelfCell(
    state: ShellState,
    entry: ShelfEntry,
    poster: Boolean,
    chatTitle: (Long) -> String,
    onOpenSeries: (Series) -> Unit,
) {
    when (entry) {
        is ShelfEntry.File -> OnHome { MediaTile(state, entry.item, chatTitle(entry.item.chatId), outsideChat = true, poster = poster) }
        is ShelfEntry.Show -> SeriesPoster(entry.series, rememberSeriesWatch(state), onOpen = { onOpenSeries(entry.series) }, poster = poster)
    }
}

/** "Film Club  ·  1080p  ·  2.1 GB": where a video came from and what the file is. */
private fun telegramFacts(chatTitle: String, item: MediaItem): String =
    listOfNotNull(
        chatTitle.ifBlank { null },
        item.qualityTags.firstOrNull(),
        item.sizeBytes.takeIf { it > 0 }?.let { MediaMapper.formatSize(it) },
    ).joinToString("  ·  ")

private fun HomeRow.isLoaded(): Boolean = isLoaded

private fun HomeRow.title(s: com.tmplayer.i18n.Messages, titles: Map<Long, String>): String = when (this) {
    is HomeRow.Chat -> homeRowTitle(if (title.isBlank()) copy(title = titles[chatId].orEmpty()) else this, s)
    else -> homeRowTitle(this, s)
}

/** How many videos a row holds, as its "See all" says it; null when it cannot tell. */
private fun HomeRow.countLabel(s: com.tmplayer.i18n.Messages): String? {
    val count = when (this) {
        is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.Chat -> total
        is HomeRow.NewEpisodes -> episodes.size
        is HomeRow.Recent -> entries.size
    }
    if (count <= 0) return null
    return if (totalAtLeast) s.homeSeeAllCountAtLeast(count) else s.homeSeeAllCount(count)
}

/**
 * The widths of Home's cards for a page [available] wide: whole cards across, as many as fit at
 * the size picked in the header ([ShellState.posterWidth]) or larger, so a wider window shows more
 * of them rather than larger ones. With [peek] a strip also shows a slice of the next card, which
 * says the row goes on.
 */
private data class CardSizes(val card: Dp, val wide: Dp, val poster: Dp, private val available: Dp) {
    /** Cells per line of a grid of cards [width] wide. */
    fun columnsFor(width: Dp): Int = (((available + CARD_GAP) / (width + CARD_GAP)).toInt()).coerceAtLeast(1)

    companion object {
        fun of(base: Dp, available: Dp, peek: Boolean = true): CardSizes {
            fun fit(size: Dp): Dp {
                if (available <= 0.dp) return size
                val slice = if (peek) PEEK else 0f
                fun widthFor(count: Int): Dp = (available - CARD_GAP * (count - if (peek) 0 else 1)) / (count + slice)
                // As many as fit at the picked size, or one more a little smaller: whichever is nearer it.
                val fits = ((available + CARD_GAP) / (size + CARD_GAP) - slice).toInt().coerceAtLeast(1)
                return listOf(fits, fits + 1).map(::widthFor).minBy { (it - size).value.let(Math::abs) }.coerceAtLeast(size * 0.75f)
            }
            return CardSizes(fit(base), fit(base * CONTINUE_SHARE), fit(base * POSTER_SHARE), available)
        }
    }
}

/**
 * One horizontal strip of a row, lined up with the heading. A pointer over it shows round arrows
 * at its ends, which move it most of a page; a trackpad's sideways swipe, or Shift and the wheel,
 * scroll it, while the wheel alone still scrolls the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Strip(side: Dp, content: LazyListScope.() -> Unit) {
    val strip = com.tmplayer.ui.components.rememberAheadListState()
    com.tmplayer.ui.components.KeepAtStart(strip)
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier
            .hoverable(hover)
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta
                val sideways = when {
                    delta.x != 0f -> delta.x
                    event.keyboardModifiers.isShiftPressed -> delta.y
                    else -> 0f
                }
                if (sideways == 0f) return@onPointerEvent
                val px = wheelPixels(if (rtl) -sideways else sideways, density)
                scope.launch { strip.scrollBy(px) }
                event.changes.forEach { it.consume() }
            },
    ) {
        // Cards scrolled past the margins fade out there rather than being cut off at the sidebar,
        // and a card the keyboard moves to stays inside them.
        WithinMargins(side, side + 8.dp) {
            LazyRow(
                modifier = Modifier.edgeFade(strip, side, side + 8.dp).remembersRemoteItem(),
                state = strip,
                contentPadding = PaddingValues(start = side, end = side + 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                content = content,
            )
        }
        val shown by animateFloatAsState(if (hovered) 1f else 0f, Motion.standard(Motion.Fast), label = "stripArrows")
        if (shown > 0f) {
            val page = { strip.layoutInfo.viewportSize.width * PAGE_SHARE }
            if (strip.canScrollBackward) {
                StripArrow(TmIcons.ChevronLeft, LocalStrings.current.homeRowScrollBack, Modifier.align(Alignment.CenterStart).padding(start = 8.dp), shown) {
                    scope.launch { strip.animateScrollBy(-page(), Motion.standard(Motion.Slow)) }
                }
            }
            if (strip.canScrollForward) {
                StripArrow(TmIcons.ChevronRight, LocalStrings.current.homeRowScrollOn, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), shown) {
                    scope.launch { strip.animateScrollBy(page(), Motion.standard(Motion.Slow)) }
                }
            }
        }
    }
}

/** A round arrow over a strip's end. Pointer only, so the keyboard's way through a row stays card to card. */
@Composable
private fun StripArrow(icon: ImageVector, label: String, modifier: Modifier, alpha: Float, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    com.tmplayer.ui.components.IconTip(label, modifier) {
        Box(
            Modifier
                .size(STRIP_ARROW)
                .graphicsLayer { this.alpha = alpha }
                .focusProperties { canFocus = false }
                .clip(CircleShape)
                .background(if (hovered) Tone.surfaceHigh else Tone.surfaceHigh.copy(alpha = 0.88f))
                .border(1.dp, Tone.outline, CircleShape)
                .hoverable(interactions)
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Tone.text, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * A starred chat's row or Recently added: films and shows as 2:3 posters in one strip, every
 * other video as a 16:9 card in a strip under it, each in the row's order, so a row never mixes
 * a tall card with a short one.
 */
@Composable
private fun ShelfStrips(
    state: ShellState,
    side: Dp,
    sizes: CardSizes,
    entries: List<ShelfEntry>,
    chatTitle: (Long) -> String,
    onOpenSeries: (Series) -> Unit,
) {
    val (posters, videos) = remember(entries) { entries.partition(SeriesShelf::isPoster) }
    if (posters.isNotEmpty()) Strip(side) { shelf(state, posters, sizes.poster, poster = true, chatTitle, onOpenSeries) }
    if (videos.isNotEmpty()) Strip(side) { shelf(state, videos, sizes.card, poster = false, chatTitle, onOpenSeries) }
}

private fun LazyListScope.shelf(
    state: ShellState,
    entries: List<ShelfEntry>,
    width: Dp,
    poster: Boolean,
    chatTitle: (Long) -> String,
    onOpenSeries: (Series) -> Unit,
) {
    items(entries, key = { it.key }, contentType = { if (poster) "poster-${it::class.simpleName}" else it::class }) { entry ->
        // A film's first bytes asked for while the pointer rests on its card (a show opens its page).
        Box(Modifier.width(width).warmUpOnIntent((entry as? ShelfEntry.File)?.item)) { ShelfCell(state, entry, poster, chatTitle, onOpenSeries) }
    }
}

/** Home's and History's tiles may wear the show's or the film's picture (see LocalOnlineArt); a chat's grid keeps frames. */
@Composable
internal fun OnHome(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOnlineArt provides true, content = content)
}

/** A poster on Home, as in a chat's grid: three quarters of a card's width. */
private const val POSTER_SHARE = 0.75f

/** A Continue watching card, a quarter wider than a card: it carries its name on the art. */
private const val CONTINUE_SHARE = 1.25f

/** The strip at the top the window is dragged by when there is no carousel. */
private val TOP_DRAG = 56.dp

/** The name's distance from the top of the carousel: level with the window's buttons. */
private val BRAND_TOP = 14.dp

/** The mark and "TMPlayer" over the carousel's picture or the top strip; the browser's Beta word is the nav bar's, on every page. */
@Composable
private fun HomeBrand(modifier: Modifier = Modifier) {
    // The big-screen mode's bar signs the window at its end already.
    if (BigScreen.on) return
    com.tmplayer.ui.components.BrandLockup(modifier, layout = com.tmplayer.ui.components.BrandLayout.Row, badge = false)
}

/** Between two cards in a strip or a grid. */
private val CARD_GAP = 16.dp

/** How much of the next card a strip shows past its whole ones. */
private const val PEEK = 0.3f

/** How much of a strip's width one of its arrows moves it. */
private const val PAGE_SHARE = 0.8f

/** A strip's arrow buttons. */
private val STRIP_ARROW = 44.dp



/**
 * "See all" at the end of a row's heading: muted words and a chevron with no fill. Hovered, focused
 * by the keyboard or pressed, they turn the accent and grow a little, and the keyboard's focus is
 * underlined as well.
 */
@Composable
private fun SeeAllLink(label: String, onClick: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val lit = hovered || focused || pressed
    val still = com.tmplayer.ui.theme.Motion.reduced
    val scale by androidx.compose.animation.core.animateFloatAsState(
        when {
            still -> 1f
            pressed -> com.tmplayer.ui.theme.Motion.PressScale
            lit -> com.tmplayer.ui.theme.Motion.FocusScale
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
    val underline = focused && com.tmplayer.ui.theme.FocusVisibility.visible
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactions)
            .clickable(interactionSource = interactions, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .drawBehind {
                if (!underline) return@drawBehind
                val stroke = 2.dp.toPx()
                drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - stroke), size = androidx.compose.ui.geometry.Size(size.width, stroke))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        Icon(TmIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
    }
}

/**
 * Telegram has been slow for a few seconds ([HomeViewModel.stalled]): Home shows what it has, and
 * this line says why the rest is missing, with Refresh (user, 2026-10-26: "if it is stuck, after
 * few seconds show what is there and refresh").
 */
@Composable
private fun HomeStalledLine(side: androidx.compose.ui.unit.Dp, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = side),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(com.tmplayer.ui.components.SlowAnswer.MESSAGE, color = Tone.muted, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRefresh) { Text(com.tmplayer.ui.components.SlowAnswer.RETRY) }
    }
}

/** True while [waiting], for [HOME_LIST_WAIT_MS] at most: a wait Home allows itself, not one it sits in. */
@Composable
private fun rememberBriefWait(waiting: Boolean): Boolean {
    var over by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        kotlinx.coroutines.delay(HOME_LIST_WAIT_MS)
        over = true
    }
    return waiting && !over
}

/** How long Home waits for the chat list to name its starred chats before drawing without them. */
private const val HOME_LIST_WAIT_MS = 3_000L
