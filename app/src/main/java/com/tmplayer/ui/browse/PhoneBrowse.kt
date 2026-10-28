package com.tmplayer.ui.browse

import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.tmplayer.ui.components.TouchPullRefresh
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The phone's browser: [TouchBrowseShell] with the page for [tab], changing pages with a short
 * cross-fade and a nudge in the direction of travel, and the search page ([PhoneSearchScreen])
 * that the bar's magnifier opens. Home and Chats are one pager ([HomeChatsPager]): a sideways
 * swipe goes from one to the other, with the bar's highlight following it.
 *
 * The search's text, its filters, its order and its scroll position are kept across a rotation
 * and across the process being stopped behind another app: the text and the list by the saved
 * state, the filters written down beside them and handed back to the model.
 */
@Composable
internal fun PhoneBrowse(
    tab: BrowseSection,
    account: Account?,
    pane: @Composable (BrowseSection) -> Unit,
    /**
     * What pulling a page down does: refresh Home, or the chat list; null for a page with
     * nothing to fetch again. A pull, not a button: a lone floating Refresh reads like a mistake.
     */
    refreshOf: (BrowseSection) -> (() -> Unit)? = { null },
    /** Whether that refresh is still fetching, so the spinner stays until it is done. */
    refreshingOf: (BrowseSection) -> Boolean = { false },
    countOf: (BrowseSection) -> Int,
    actions: (@Composable RowScope.() -> Unit)?,
    sections: List<BrowseSection>,
    favourites: Set<Long>,
    downloadCount: Int,
    updateVersion: String?,
    onPickTab: (BrowseSection) -> Unit,
    onHistory: (HistoryTab) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdate: () -> Unit,
    search: GlobalSearchViewModel,
    chats: List<ChatSummary>,
    continueWatching: List<ResumeRecord>,
    watchLater: List<WatchLaterRecord>,
    homeArt: Map<String, MediaItem>,
    watch: SeriesWatch,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenMedia: (MediaItem, String) -> Unit,
    onPlayMedia: (MediaItem, String) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onMessage: (String) -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion() || Motion.reduced

    // Read only inside the search page's own lambda and in effects, so a letter typed or a page of
    // results redraws the search page, not the whole browser behind it.
    val searchState by search.state.collectAsState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var savedFilters by rememberSaveable { mutableStateOf("") }
    val searchList = rememberLazyListState()
    // The Chats page comes back on the chip it was left on, a folder or one of Telegram's filters.
    var lastChats by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tab) { if (tab.onChatsPage) lastChats = BrowseSection.encode(tab) }

    // Back after the process was stopped: the model is new, the saved filters are not.
    LaunchedEffect(Unit) {
        decodeFilters(savedFilters)?.let { (filters, sort) ->
            if (searchState.filters.isDefault) search.setFilters(filters)
            if (searchState.sort == SearchSort.BestMatch) search.setSort(sort)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { searchState.filters to searchState.sort }.collect { (filters, sort) -> savedFilters = encodeFilters(filters, sort) }
    }
    LaunchedEffect(Unit) { snapshotFlow { searchText.text }.collect { search.type(it) } }
    // "Search all chats" from a chat whose listing matched nothing: the search page, on those words.
    val asked by SearchRequest.pending.collectAsState()
    LaunchedEffect(asked) {
        val text = SearchRequest.take() ?: return@LaunchedEffect
        searchText = TextFieldValue(text, TextRange(text.length))
        search.submit(text)
        searchOpen = true
    }

    // What the local half of the search matches besides the chat listings already seen.
    val downloads by remember(settings) { settings.downloadHistory }.collectAsState(initial = emptyList())
    LaunchedEffect(continueWatching, watchLater, downloads) {
        search.alsoKnown = (continueWatching + downloads).map { it.toMediaItem() } + watchLater.map { it.toMediaItem() }
    }
    val recent by remember(settings) { settings.recentSearches }.collectAsState(initial = emptyList())
    val allowed by ChatGate.allowed.collectAsState()
    val keepsSearches by remember(settings) { settings.rememberSearches }.collectAsState(initial = true)

    var searchSeries by remember { mutableStateOf<Pair<Series, String>?>(null) }
    val titleOf: (Long) -> String = { id -> searchState.chats[id]?.title ?: chats.firstOrNull { it.id == id }?.title.orEmpty() }
    val openGroup: (ResultGroup) -> Unit = { group ->
        scope.launch {
            // Deleted, or its chat left, since the search: say so, and the card goes.
            if (!search.check(group.lead)) {
                onMessage(s.searchNoLongerThere)
                return@launch
            }
            search.remember()
            val series = if (group.isShow) {
                SeriesShelf.arrange(group.copies).firstNotNullOfOrNull { (it as? ShelfEntry.Show)?.series }
            } else {
                null
            }
            if (series != null) {
                searchSeries = series to titleOf(group.lead.chatId)
            } else {
                onOpenMedia(group.asItem(), titleOf(group.lead.chatId))
            }
        }
    }
    val voice = rememberVoiceSearch(s.browseVoicePromptChat) { heard ->
        searchText = TextFieldValue(heard, TextRange(heard.length))
        search.submit(heard)
    }

    // The Chats half of the Home and Chats pager: the page itself, or the chip it was left on.
    val chatsSection = if (tab.onChatsPage) tab else chatsPageOf(lastChats, sections)
    // The pager while it is on screen, for the bar's highlight to follow a swipe.
    val pagerRef = remember { mutableStateOf<PagerState?>(null) }
    val barPosition: (() -> Float)? = if (swipePage(tab) != null) {
        remember(pagerRef, swipePage(tab)) {
            {
                val pager = pagerRef.value
                if (pager == null) (swipePage(tab) ?: 0).toFloat() else pager.currentPage + pager.currentPageOffsetFraction
            }
        }
    } else {
        null
    }

    val openSearch = {
        // Back with the last query, all of it selected, so typing replaces it and the arrow keeps it.
        searchText = searchText.copy(selection = TextRange(0, searchText.text.length))
        searchOpen = true
    }
    TouchBrowseShell(
        // The bar's own, for when no host above draws one (the screenshot fixture); in the app the
        // host's bar is the one on screen, with the same destinations.
        nav = TouchNav(
            account = account,
            favoriteCount = favourites.size,
            downloadCount = downloadCount,
            updateVersion = updateVersion,
            onHome = { onPickTab(BrowseSection.of(BrowseTab.Home)) },
            onChats = { onPickTab(chatsPageOf(lastChats, sections)) },
            onSearch = openSearch,
            onSettings = onOpenSettings,
            onFavourites = { onPickTab(BrowseSection.of(BrowseTab.Favorites)) },
            onDownloads = onOpenDownloads,
            onHistory = onHistory,
            onUpdate = onUpdate,
        ),
        selected = tab,
        searchOpen = searchOpen,
        onOpenSearch = openSearch,
        searchScreen = { field ->
            PhoneSearchScreen(
                state = searchState,
                text = searchText,
                onText = { searchText = it },
                onSubmit = { search.submit(searchText.text) },
                onClose = { searchOpen = false },
                fieldModifier = field,
                chats = chats,
                favourites = favourites,
                // A child's profile keeps no trail of what was looked for.
                recentSearches = if (allowed != null || !keepsSearches) emptyList() else recent,
                continueWatching = continueWatching,
                continueArt = { record -> homeArt[SettingsStore.progressKey(record.chatId, record.messageId)] ?: record.toMediaItem() },
                progress = { item -> watch.point(item)?.fraction },
                onOpenChat = { chat -> search.remember(); onOpenChat(chat) },
                onOpenGroup = openGroup,
                onResume = onResume,
                onFilters = search::setFilters,
                onSort = search::setSort,
                onLoadMore = search::loadMore,
                onRetry = search::retry,
                onRemoveRecent = { entry -> scope.launch { settings.removeRecentSearch(entry) } },
                onClearRecent = {
                    val before = recent
                    scope.launch { settings.clearRecentSearches() }
                    before
                },
                onRestoreRecent = { before -> scope.launch { settings.restoreRecentSearches(before) } },
                onVoice = voice,
                listState = searchList,
            )
        },
        actions = actions,
        barPosition = barPosition,
    ) {
        val order = remember(sections) { navGroups(sections).flatMap { it.entries }.filterIsInstance<NavEntry.Section>().map { it.section } }
        AnimatedContent(
            targetState = tab,
            // Home and Chats are one pager, swiped between; only leaving it for another page fades.
            contentKey = { if (swipePage(it) != null) SWIPE_PAGES else it.navSection },
            transitionSpec = {
                val from = order.indexOf(initialState.navSection)
                val to = order.indexOf(targetState.navSection)
                val enter = if (reduced) {
                    fadeIn(Motion.standard(Motion.Fast))
                } else {
                    fadeIn(Motion.standard(Motion.Normal)) +
                        slideInHorizontally(Motion.standard(Motion.Normal)) { width -> (if (to >= from) width else -width) / SLIDE_PART }
                }
                enter togetherWith fadeOut(Motion.standard(Motion.Fast))
            },
            label = "phonePage",
        ) { page ->
            val frame: @Composable (BrowseSection) -> Unit = { section ->
                PhonePageFrame(
                    section = section,
                    count = if (section.onChatsPage) 0 else countOf(section),
                    refresh = refreshOf(section),
                    refreshing = refreshingOf(section),
                    pane = pane,
                )
            }
            if (swipePage(page) != null) {
                HomeChatsPager(
                    tab = tab,
                    chats = chatsSection,
                    onSettle = { section -> if (section != tab) onPickTab(section) },
                    onState = { pagerRef.value = it },
                    page = frame,
                )
            } else {
                frame(page)
            }
        }
    }

    searchSeries?.let { (series, chatTitle) ->
        SeriesOpened(
            series = series,
            watch = watch,
            onPlay = { onPlayMedia(it, chatTitle) },
            onDismiss = { searchSeries = null },
            chatTitle = chatTitle,
            onOpenChat = chats.firstOrNull { it.id == series.cover.chatId }?.let { chat -> { searchSeries = null; search.remember(); onOpenChat(chat) } },
        )
    }
}

/** The search's filters and order, written down for the saved state. */
private fun encodeFilters(filters: SearchFilters, sort: SearchSort): String =
    listOf(filters.chatType.name, filters.quality.name, filters.hideSmall, filters.includeArchived, sort.name).joinToString(",")

private fun decodeFilters(raw: String): Pair<SearchFilters, SearchSort>? {
    val parts = raw.split(',')
    if (parts.size != 5) return null
    return runCatching {
        SearchFilters(
            chatType = SearchChatType.valueOf(parts[0]),
            quality = SearchQuality.valueOf(parts[1]),
            hideSmall = parts[2].toBoolean(),
            includeArchived = parts[3].toBoolean(),
        ) to SearchSort.valueOf(parts[4])
    }.getOrNull()
}

/** Which page of the Home and Chats pager [section] is: 0 for Home, 1 for Chats or a folder, null for neither. */
internal fun swipePage(section: BrowseSection): Int? = when {
    section.isHome -> HOME_PAGE
    section.onChatsPage -> CHATS_PAGE
    else -> null
}

/** What page [page] of the pager shows: Home, or [chats], the chip the Chats page was left on. */
internal fun swipeSection(page: Int, chats: BrowseSection): BrowseSection =
    if (page == CHATS_PAGE) chats else BrowseSection.of(BrowseTab.Home)

/**
 * The Chats page to come back to: the chip it was left on ([lastChats], written down by
 * [BrowseSection.encode]) when that folder is still there, else All chats.
 */
internal fun chatsPageOf(lastChats: String, sections: List<BrowseSection>): BrowseSection =
    BrowseSection.decode(lastChats)?.takeIf { it.isChats || (it.onChatsPage && it in sections) } ?: BrowseSection.of(BrowseTab.Chats)

/**
 * Home and Chats side by side: a swipe from Home towards the end goes to Chats, and back the other
 * way. Settings is not a page of it: it opens from the picture's popover as a screen of its own,
 * so no swipe leads into it or out of it. A row that scrolls sideways inside a page (a Home shelf,
 * the chips) takes the swipe first; the page turns only once it can go no further, or when the
 * swipe starts outside it. Both pages stay composed, so each keeps its scroll.
 *
 * The bar's buttons turn the pager too ([tab] changing); a swipe that settles on the other page
 * makes it the browser's tab ([onSettle]). [onState] hands the pager up for the bar's highlight
 * to follow the swipe.
 */
@Composable
private fun HomeChatsPager(
    tab: BrowseSection,
    chats: BrowseSection,
    onSettle: (BrowseSection) -> Unit,
    onState: (PagerState?) -> Unit,
    page: @Composable (BrowseSection) -> Unit,
) {
    val reduced = rememberReducedMotion() || Motion.reduced
    val pager = rememberPagerState(initialPage = swipePage(tab) ?: HOME_PAGE) { SWIPE_PAGE_COUNT }
    DisposableEffect(pager) {
        onState(pager)
        onDispose { onState(null) }
    }
    val target = swipePage(tab)
    LaunchedEffect(target) {
        if (target == null || target == pager.currentPage && pager.currentPageOffsetFraction == 0f) return@LaunchedEffect
        // On the bar's own curve and time rather than the pager's default spring, which is soft
        // enough at the start that a press seemed to wait before the page moved, and took about
        // twice as long to settle.
        if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = Motion.standard(Motion.Normal))
    }
    // The page beside the one on screen is composed a frame later, not in the same frame: coming
    // back from Settings or a chat, the browser is built again, and building Home and Chats at
    // once was most of the wait between the press and the first frame.
    var beside by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        beside = 1
    }
    val currentChats by rememberUpdatedState(chats)
    val settle by rememberUpdatedState(onSettle)
    LaunchedEffect(pager) {
        // Not the page it starts on, which is the tab already.
        snapshotFlow { pager.settledPage }.drop(1).collect { settled -> settle(swipeSection(settled, currentChats)) }
    }
    HorizontalPager(
        state = pager,
        beyondViewportPageCount = beside,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        // Only the page on screen may scroll something into view: the Chats page beside Home
        // bringing its chosen chip into view would turn the pager to it on launch.
        val onScreen by remember(index) { derivedStateOf { pager.settledPage == index } }
        CompositionLocalProvider(LocalPageOnScreen provides onScreen) {
            page(swipeSection(index, chats))
        }
    }
}

/**
 * Whether the page this is composed in is the one on screen. False for a page of the Home and
 * Chats pager kept composed beside the one showing, where a request to bring something into view
 * would scroll the pager itself.
 */
internal val LocalPageOnScreen = androidx.compose.runtime.compositionLocalOf { true }

/**
 * One page of the phone's browser under its title. Home, Favourites and History keep the title
 * that slides away with the list and back on the first pull down, with the count under it.
 * The Chats page has its name pinned, from the start as Settings has it: it and the chips under it stay put
 * while the chat list scrolls beneath them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhonePageFrame(
    section: BrowseSection,
    count: Int,
    refresh: (() -> Unit)?,
    /** Whether [refresh] is still fetching. */
    refreshing: Boolean = false,
    pane: @Composable (BrowseSection) -> Unit,
) {
    val s = LocalStrings.current
    val pinned = section.onChatsPage
    val scrollBehavior = if (pinned) TopAppBarDefaults.pinnedScrollBehavior() else TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        // Home has no top bar to collapse: the behaviour's limit is never measured there, so it
        // would take every drag for itself and the page would not scroll.
        modifier = if (pinned || section.isHome) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (section.isHome) {
                // No title: Home's featured carousel is the top of the page.
            } else if (pinned) {
                // From the start, in the same bar and type as Settings' title.
                TopAppBar(title = { Text(s.browseTabChats, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            } else {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = { Text(section.heading, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        },
    ) { padding ->
        // Home runs up under the status bar, and is told how far (LocalUnderStatusBar).
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        val framePadding = if (section.isHome) {
            PaddingValues(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            )
        } else {
            padding
        }
        val under = if (section.isHome) padding.calculateTopPadding() else 0.dp
        // The page's count under the title, where a phone's app bar puts a subtitle.
        androidx.compose.runtime.CompositionLocalProvider(LocalUnderStatusBar provides under) {
        Column(Modifier.fillMaxSize().padding(framePadding)) {
            if (count > 0) {
                Text(
                    countLabel(section, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            if (refresh == null) {
                pane(section)
            } else {
                // TDLib answers through the list's own flow, so the spinner is a short
                // acknowledgement rather than a wait on the fetch itself.
                var pulling by remember { mutableStateOf(false) }
                // Home says when its rows are back ([refreshingOf]): the spinner stays until then,
                // or for the short acknowledgement at least, and at most [PULL_HOLD_MS].
                val busy by androidx.compose.runtime.rememberUpdatedState(refreshing)
                LaunchedEffect(pulling) {
                    if (pulling) {
                        kotlinx.coroutines.delay(PULL_ACK_MS)
                        kotlinx.coroutines.withTimeoutOrNull(PULL_HOLD_MS) {
                            androidx.compose.runtime.snapshotFlow { busy }.first { done -> !done }
                        }
                        pulling = false
                    }
                }
                val pull = rememberPullToRefreshState()
                TouchPullRefresh(
                    isRefreshing = pulling,
                    onRefresh = { pulling = true; refresh() },
                    modifier = Modifier.fillMaxSize(),
                    state = pull,
                    // On Home the spinner comes down below the status bar, not under it.
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pull,
                            isRefreshing = pulling,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = under),
                        )
                    },
                ) { pane(section) }
            }
        }
        }
    }
}

/**
 * How far a page without a title (Home on a phone, whose featured carousel is its top) runs up
 * under the status bar: the bar's height there, zero on every other page.
 */
internal val LocalUnderStatusBar = androidx.compose.runtime.compositionLocalOf { 0.dp }

private const val HOME_PAGE = 0
private const val CHATS_PAGE = 1
private const val SWIPE_PAGE_COUNT = 2

/** The content key of the Home and Chats pager, which a swipe between them does not change. */
private const val SWIPE_PAGES = "home-chats"

/** How far a page slides in as it fades: a twelfth of the width, a nudge rather than a swipe. */
private const val SLIDE_PART = 12

/** How long the pull's spinner stays after a pull, as the acknowledgement of it. */
private const val PULL_ACK_MS = 900L

/** The longest the spinner waits on a refresh that says it is still fetching. */
private const val PULL_HOLD_MS = 8_000L
