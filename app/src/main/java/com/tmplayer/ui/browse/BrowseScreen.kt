package com.tmplayer.ui.browse

import androidx.compose.animation.core.snap
import com.tmplayer.ui.components.keepTopInSight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import com.tmplayer.ui.components.chevronTowardsEnd
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Focus
import com.tmplayer.data.SettingsStore
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.tmplayer.ui.components.TmDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.ChatListSkeleton
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedWhen
import com.tmplayer.data.DayGroup
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmSecondaryButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.flow.map
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.AppMark
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.components.pressScale
import kotlinx.coroutines.delay
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.online.LocalOnlineArt
import com.tmplayer.ui.online.OnlineArt
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.ui.components.CoverArt
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer

/**
 * What the line under the app bar's title counts.
 *
 * Every tab but two is a list of chats; Continue watching and Previously watched are lists of
 * videos, so they count videos.
 */
internal fun countLabel(section: BrowseSection, count: Int): String =
    if (section.listsVideos) L.browseVideosCount(count) else L.browseChatsCount(count)

// BrowseTab, BrowseSection, browseSections and filterChats live in :ui (BrowseSections.kt).

@Composable
internal fun BrowseScreen(
    state: UiState<BrowseData>,
    /**
     * The header, delivered separately from [state] so a cold start can draw the name and face
     * off the snapshot while the chat list is still loading. Falls back to what [state] carries,
     * so callers without the separate flow (previews, fixtures) change nothing.
     */
    account: Account? = null,
    favorites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    onRetry: () -> Unit,
    /**
     * Fetches the chat list again without clearing the screen first. Telegram reorders chats as
     * messages arrive, so a list left open goes stale; this is the button that catches it up.
     */
    onRefresh: () -> Unit = onRetry,
    onOpenChat: (ChatSummary) -> Unit,
    onResumeMedia: (ResumeRecord) -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The Downloads screen, from the drawer on a phone and from the rail on a television. Both
     * form factors keep the downloads the viewer asked for by name until told otherwise; only
     * the watch cache is the television's one-video, replaced-on-next-play arrangement, so a TV
     * accumulates a list worth managing exactly as a phone does.
     */
    onOpenDownloads: () -> Unit = {},
    /**
     * Back from Settings (opened from the account picture's menu, to the picture) or Downloads,
     * or a tab picked on Downloads' bar: the remote is held on the television's top bar while the
     * page comes up. Null the rest of the time.
     */
    returnToRail: RailReturn? = null,
    /** Told once [returnToRail] has been honoured, so it is not honoured again. */
    onReturnedToRail: () -> Unit = {},
    /** What the television's top bar shows and does: the phone's bar's own set. Null on a phone. */
    tvNav: TouchNav? = null,
    /** How many downloads are running, for the badge on that row of the drawer and the rail. */
    downloadCount: Int = 0,
    onToggleFavorite: (ChatSummary) -> Unit = {},
    /**
     * These write to Telegram rather than to this device. Defaulted to nothing so a preview, or
     * the screenshot fixture, can draw this screen without a TDLib client behind it.
     */
    onTogglePinned: (ChatSummary) -> Unit = {},
    onToggleArchived: (ChatSummary) -> Unit = {},
    onToggleMuted: (ChatSummary) -> Unit = {},
    onMarkRead: (ChatSummary) -> Unit = {},
    onRestartMedia: (ResumeRecord) -> Unit = {},
    onForgetMedia: (ResumeRecord) -> Unit = {},
    /** Empties Continue watching in one go, rather than one held-OK menu per video. */
    onClearHistory: () -> Unit = {},
    /**
     * Puts a Continue watching video on the Watched list, which also takes it off this one. For a
     * video the viewer finished somewhere else, or gave up on and wants out of the way.
     */
    onMarkMediaWatched: (ResumeRecord) -> Unit = {},
    /** Previously watched, newest first: what the player saw to the end, and what was marked. */
    watchedHistory: List<WatchedRecord> = emptyList(),
    /** Opens a finished video in the player again, from the start. */
    onOpenWatched: (WatchedRecord) -> Unit = {},
    onMarkUnwatched: (WatchedRecord) -> Unit = {},
    /** Empties Previously watched in one go, behind a confirmation. */
    onClearWatched: () -> Unit = {},
    /** Watch later, newest first: single videos saved without starring their chats. */
    watchLater: List<WatchLaterRecord> = emptyList(),
    /** Plays a Watch later video, as a Continue watching one is played. */
    onOpenWatchLater: (WatchLaterRecord) -> Unit = {},
    onRemoveWatchLater: (WatchLaterRecord) -> Unit = {},
    /** Unstars every chat in one go, the counterpart to the star in each chat's menu. */
    onClearFavorites: () -> Unit = {},
    /** The chat that reopens on launch, marked on its row so the jump is never unexplained. */
    launchChatId: Long = 0L,
    picked: BrowseSection? = null,
    onPickTab: (BrowseSection) -> Unit = {},
    /** The viewer's Telegram folders, which become rail items of their own. Empty until TDLib says. */
    folders: List<ChatFolderSummary> = emptyList(),
    /**
     * Rows or tiles, chosen from the pill beside the tab name and remembered from then on. One
     * arrangement covers every tab: they are all lists of the same card.
     */
    layout: CardLayout = CardLayout.List,
    onToggleLayout: () -> Unit = {},
    /** The newer version on GitHub, if there is one. Shown on the rail, in amber. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
    /** Home's rows, built by [com.tmplayer.data.HomeRows] from what has loaded so far. */
    homeRows: List<HomeRow> = emptyList(),
    homeRowsReady: Boolean = true,
    /** The carousel at the top of Home, which Settings can turn off. */
    homeCarousel: Boolean = true,
    /** Continue watching videos as Telegram describes them, by progress key, for their pictures. */
    homeArt: Map<String, MediaItem> = emptyMap(),
    homeWatch: SeriesWatch = SeriesWatch.None,
    /** Whether the chat list may go on past the chats read ([com.tmplayer.ui.browse.ChatListViewModel.paging]). */
    chatPaging: com.tmplayer.ui.browse.ChatPaging = com.tmplayer.ui.browse.ChatPaging(),
    /** Reads the chat list's next chats, as its end comes near. */
    onMoreChats: () -> Unit = {},
    /** A row came on screen without its videos: fetch them. */
    onHomeRowShown: (HomeRow) -> Unit = {},
    onHomeArtWanted: (ResumeRecord) -> Unit = {},
    /** Recently added reads on through Home's chats as it is scrolled to its end. */
    homeFeedOf: (HomeRow) -> com.tmplayer.ui.browse.RowFeed? = { null },
    onHomeLaterArtWanted: (WatchLaterRecord) -> Unit = {},
    /** Plays a video from one of Home's rows; the second argument is its chat's name. */
    onPlayMedia: (MediaItem, String) -> Unit = { _, _ -> },
    onRefreshHome: () -> Unit = {},
    /** True while Home's rows are being fetched again in place: the phone's pull spinner stays up. */
    homeRefreshing: Boolean = false,
    /** "Mark as watched" (true) or back (false) from a detail panel opened here; the third is the chat's name. */
    onSetMediaWatched: (MediaItem, String, Boolean) -> Unit = { _, _, _ -> },
    /** One line for the viewer, such as what a Download from a detail panel did. */
    onMessage: (String) -> Unit = {},
    /** Where "Videos in all chats" asks: Telegram, or the promo fixture's demo results. */
    videoSearch: VideoSearchSource = VideoSearchSource.Telegram,
    /** Remembers a search worth keeping, for the recent searches. */
    onSearched: (suspend (String) -> Unit)? = null,
) {
    val s = LocalStrings.current
    // An unfinished video wins the landing tab, otherwise Recent, so the first screen is never
    // empty. Those are read from disk after the first frame, so the tab settles once they arrive;
    // an explicit pick always wins. [picked] is hoisted rather than remembered here because
    // opening a chat swaps this screen out of the composition.
    val groupsContext = LocalContext.current
    val groupsSettings = remember(groupsContext) { SettingsStore(groupsContext) }
    val sections = remember(folders) { browseSections(folders) }
    // Where Telegram puts "All chats" among the folder chips.
    val mainListPosition by com.tmplayer.data.Td.mainListPosition.collectAsState()
    // "Only my folders" is retired: somebody who had it on lands once on their first folder's chip.
    LaunchedEffect(folders.isNotEmpty()) {
        val first = folders.firstOrNull() ?: return@LaunchedEffect
        if (runCatching { groupsSettings.takeOnlyFolders() }.getOrDefault(false)) {
            onPickTab(BrowseSection.Folder(first.id, first.title))
        }
    }
    // How many chats have something unread in them, not how many messages are unread across them:
    // the badge sits beside "Chats", whose Unread chip lists those chats.
    val allChats = (state as? UiState.Content)?.value?.chats
    val unreadChats = remember(allChats) {
        allChats.orEmpty().count { it.unreadCount > 0 && !it.isArchived }
    }
    // The saved section is matched back against the live folder list, so the rail never highlights
    // a folder deleted elsewhere and the heading never keeps a stale name.
    val tab = picked?.let { chosen ->
        when (chosen) {
            is BrowseSection.Tab -> chosen
            is BrowseSection.Folder -> sections.filterIsInstance<BrowseSection.Folder>()
                .firstOrNull { it.id == chosen.id }
                ?: chosen.takeIf { folders.isEmpty() }
        }
    // Home is the first destination on every device: it leads with what is half watched, so it
    // is never a worse landing than Continue watching was, and it is never empty for long.
    } ?: BrowseSection.of(BrowseTab.Home)
    var query by remember { mutableStateOf("") }
    // What the viewer held OK on. Only ever one at a time, so two nullable slots cover both lists.
    var chatMenu by remember { mutableStateOf<ChatSummary?>(null) }
    // The chat just starred from its menu, whose row's star pops in rather than simply appearing.
    var justStarred by remember { mutableStateOf<Long?>(null) }
    var mediaMenu by remember { mutableStateOf<ResumeRecord?>(null) }
    var watchedMenu by remember { mutableStateOf<WatchedRecord?>(null) }
    var laterMenu by remember { mutableStateOf<WatchLaterRecord?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearWatched by remember { mutableStateOf(false) }
    var confirmClearFavorites by remember { mutableStateOf(false) }
    // The video whose detail panel is open, with its chat's name, from Home or the search, over
    // the pages "More like this" was taken from, so Back walks back through them.
    val details = rememberDetailBackStack<Pair<MediaItem, String>>(itemOf = { it.first }, labelOf = { it.second }, rebuild = { item, chat -> item to chat })
    // The search across every chat (CP73): the phone's search page and the television's.
    val search: GlobalSearchViewModel = viewModel(
        key = "global-search",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GlobalSearchViewModel(videoSearch, onSearched) as T
        },
    )
    val searchState by search.state.collectAsState()
    // Back online: a search that could only list this device's videos asks Telegram again, as
    // the desktop's does, rather than saying "offline" until the viewer types something new.
    val searchConnected by com.tmplayer.data.Td.connected.collectAsState()
    LaunchedEffect(searchConnected) { if (searchConnected) search.reconnected() }
    val panelScope = rememberCoroutineScope()
    val panelContext = LocalContext.current
    // Hiding a chat from its menu: gone from the lists at once, which is the answer; no toast
    // (user, 2026-10-22). Settings, Library, Hidden chats shows it again.
    val hideScope = rememberCoroutineScope()
    val hideStore = remember(panelContext) { SettingsStore(panelContext) }
    val hideChat: (ChatSummary) -> Unit = { chat ->
        hideScope.launch { hideStore.hideChat(chat.id, chat.title) }
    }
    // A hold, the info key or a right click: the quick menu, which leads to the page by Details.
    var held by remember { mutableStateOf<Pair<MediaItem, String>?>(null) }
    val holdMedia: (MediaItem, String) -> Unit = { item, title -> held = item to title }
    val showDetail: (MediaItem, String) -> Unit = { item, title -> details.open(item to title) }
    // A tap or OK on a video opens its page first, as a streaming app's title does, unless
    // Settings says to play at once; the page is then still a hold and Details away.
    val detailFirst by remember(panelContext) { SettingsStore(panelContext).detailFirst }.collectAsState(initial = true)
    val openMedia: (MediaItem, String) -> Unit = if (detailFirst) showDetail else onPlayMedia
    val openResume: (ResumeRecord) -> Unit =
        if (detailFirst) ({ record -> showDetail(record.toMediaItem(), record.chatTitle) }) else onResumeMedia
    val openLater: (WatchLaterRecord) -> Unit =
        if (detailFirst) ({ record -> showDetail(record.toMediaItem(), record.chatTitle) }) else onOpenWatchLater
    // The Watched list follows the same rule as Continue watching: OK on a row opened the
    // player at once while a Continue row opened the page, so the two lists of History answered
    // the same press differently (user, 2026-10-23). Play again stays in the hold menu.
    val openWatched: (WatchedRecord) -> Unit =
        if (detailFirst) ({ record -> showDetail(record.toMediaItem(), record.chatTitle) }) else onOpenWatched

    // The chips over Chats and History, remembered across launches like the card layout.
    val browseSettings = remember(panelContext) { SettingsStore(panelContext) }
    val chatFilter by remember(browseSettings) { browseSettings.chatFilter.map(ChatFilter::decode) }
        .collectAsState(initial = ChatFilter.All)
    val chatSort by remember(browseSettings) { browseSettings.chatSort.map(ChatSort::decode) }
        .collectAsState(initial = ChatSort.Recent)
    val historyTab by remember(browseSettings) { browseSettings.historyTab.map(HistoryTab::decode) }
        .collectAsState(initial = HistoryTab.Continue)
    val pickFilter: (ChatFilter) -> Unit = { panelScope.launch { browseSettings.setChatFilter(it.name) } }
    val pickSort: (ChatSort) -> Unit = { panelScope.launch { browseSettings.setChatSort(it.name) } }
    val pickHistory: (HistoryTab) -> Unit = { panelScope.launch { browseSettings.setHistoryTab(it.name) } }
    // History's order, one for its three lists: most recent first, or by title (ChatSort's two).
    val historySort by remember(browseSettings) { browseSettings.historySort.map(ChatSort::decode) }
        .collectAsState(initial = ChatSort.Recent)
    val pickHistorySort: (ChatSort) -> Unit = { panelScope.launch { browseSettings.setHistorySort(it.name) } }
    val showsContinue = tab.listsVideos && historyTab == HistoryTab.Continue
    // History's Continue tab, with the copies of one film or episode as one card, as on Home.
    // Built again when the viewer says two of them are not the same video.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val continuing = remember(continueWatching, corrections, historySort) {
        HomeRows.foldCopies(continueWatching).let { list ->
            if (historySort == ChatSort.Name) com.tmplayer.data.VideoSorting.byName(list, { SeriesShelf.tileTitle(it.toMediaItem()) }) else list
        }
    }
    val laterShown = remember(watchLater, historySort) {
        if (historySort == ChatSort.Name) com.tmplayer.data.VideoSorting.byName(watchLater, { SeriesShelf.tileTitle(it.toMediaItem()) }) else watchLater
    }
    val watchedShown = remember(watchedHistory, historySort) {
        if (historySort == ChatSort.Name) com.tmplayer.data.VideoSorting.byName(watchedHistory, { SeriesShelf.tileTitle(it.toMediaItem()) }) else watchedHistory
    }
    val showsWatched = tab.listsVideos && historyTab == HistoryTab.Watched
    val showsWatchLater = tab.listsVideos && historyTab == HistoryTab.WatchLater

    // One question decides the whole shape of this screen: a permanent rail beside the listing on
    // a television, a drawer behind a hamburger on a phone. Everything below the chrome is the
    // same composition either way, told only how much room it has and how far in it may start.
    val touch = !FormFactor.isTv(LocalContext.current)
    // The phone's lists end clear of the floating bar (TouchNav), which their container already
    // lifts above the navigation bar.
    val insets = if (touch) TouchInsets.clearing(touchShellClearance(withActions = true, rail = LocalTouchRail.current)) else TvInsets
    // Fixed columns suit a screen whose size is known in advance; a phone's is not, and it turns
    // when the viewer does, so the grid is asked for a tile width instead and works out the rest.
    val tiles = if (touch) GridCells.Adaptive(TOUCH_TILE_MIN) else GridCells.Fixed(TILE_COLUMNS)

    // While a chip has the remote's focus, a list that changes under it (because OK picked that
    // chip) must not pull focus down to its first row: the viewer is still choosing.
    var chipsFocused by remember { mutableStateOf(false) }
    // Nor while the remote is on the television's top bar: picking a page there keeps it there.
    var tvBarHasFocus by remember { mutableStateOf(false) }
    // Home's carousel Play: Down from the TV top bar lands there at once, with nothing scrolled,
    // while the carousel is on screen.
    val tvPlayFocus = remember { FocusRequester() }
    val listMayTakeFocus: () -> Boolean = { !chipsFocused && !tvBarHasFocus }
    // The Chats page's chips: the order, Telegram's own filters, then each folder, which is where
    // the folders went when they left the sidebar. A folder chip is a section of its own, so the
    // folder picked is remembered the way the page is.
    val chatChips: @Composable (BrowseSection) -> Unit = { section ->
        ChipRow(
            insets = insets,
            onFocus = { chipsFocused = it },
            chips = buildList {
                if (section.isChats) {
                    add(
                        ChipSpec(
                            key = SORT_CHIP,
                            label = chatSort.label,
                            icon = chatSort.icon,
                            selected = false,
                            description = s.browseSortBy(chatSort.label),
                        ) { pickSort(chatSort.toggled) },
                    )
                }
                chatPageChips(folders, mainListPosition).forEach { chip ->
                    when (chip) {
                        is ChatPageChip.Filter -> add(
                            ChipSpec(chip.filter.name, chip.label, chip.icon, selected = chip.isSelected(section, chatFilter)) {
                                pickFilter(chip.filter)
                                onPickTab(BrowseSection.of(BrowseTab.Chats))
                            },
                        )
                        is ChatPageChip.Folder -> add(
                            ChipSpec("folder-${chip.folder.id}", chip.label, chip.icon, selected = chip.isSelected(section, chatFilter)) {
                                onPickTab(chip.folder)
                            },
                        )
                    }
                }
            },
        )
    }
    val historyChips: @Composable () -> Unit = {
        ChipRow(
            insets = insets,
            onFocus = { chipsFocused = it },
            chips = buildList {
                // The lists' order first, as the Chats page has its own: Recent or A to Z.
                add(
                    ChipSpec(
                        key = SORT_CHIP,
                        label = historySort.label,
                        icon = historySort.icon,
                        selected = false,
                        description = s.browseSortBy(historySort.label),
                    ) { pickHistorySort(historySort.toggled) },
                )
                HistoryTab.entries.forEach { entry ->
                    add(ChipSpec(entry.name, entry.label, entry.icon, selected = entry == historyTab) { pickHistory(entry) })
                }
            },
        )
    }

    // A phone lists chats as rows, always: tiles of chat pictures are for a television across the
    // room. History's videos keep the choice, and so does every list on a television.
    val chatLayout: (BrowseSection) -> CardLayout = { section -> if (touch && !section.listsVideos) CardLayout.List else layout }
    // The chat the television's list was left for is put back only on the way back from it: a
    // page with no chat list in between (Home, History) means the viewer moved on.
    LaunchedEffect(tab) { if (tab.isHome || tab.listsVideos) ChatListReturn.forget() }
    val goToChats: () -> Unit = {
        pickFilter(ChatFilter.All)
        onPickTab(BrowseSection.of(BrowseTab.Chats))
        query = ""
    }
    val paneOf: @Composable (BrowseSection) -> Unit = { tab ->
        val showsContinue = tab.listsVideos && historyTab == HistoryTab.Continue
        val showsWatched = tab.listsVideos && historyTab == HistoryTab.Watched
        val showsWatchLater = tab.listsVideos && historyTab == HistoryTab.WatchLater
        // The loader held over a cold start waits for Home only (HomeFirstPaint): another tab, an
        // error or an empty list has nothing more on its way, so it goes at once.
        val homeComing = tab.isHome && (state is UiState.Loading || state is UiState.Content)
        LaunchedEffect(homeComing) { if (!homeComing) HomeFirstPaint.markReady() }
        // Home does not wait for the chat list (HX8): with rows of its own (its cached copy, its
        // starred and found chats) it is drawn while the list is still on its way.
        val paneState = if (tab.isHome && state is UiState.Loading && homeRows.isNotEmpty()) UiState.Content(BrowseData(emptyList(), account)) else state
        androidx.compose.runtime.CompositionLocalProvider(LocalJustStarred provides justStarred) {
        StateScaffold(
            paneState,
            onRetry = onRetry,
            loading = { ChatListSkeleton(layout = chatLayout(tab), columns = if (touch) 1 else TV_CHAT_COLUMNS) },
            // "No chats yet" offers Refresh: the list may still be on its way after a sign in.
            onAction = { onRetry() },
        ) { data ->
            val visible = remember(data.chats, tab, favorites, query, chatFilter, chatSort) {
                filterChats(data.chats, tab, favorites, query, chatFilter, chatSort)
            }

            // The signed in account, for the picture in the heading's top right corner.
            val headerAccount = data.account ?: account
            Column(Modifier.fillMaxSize()) {
                    if (tab.isHome) {
                        val titles = remember(data.chats) { data.chats.associate { it.id to it.title } }
                        Box(Modifier.fillMaxSize()) {
                            HomePane(
                                rows = homeRows,
                                rowsReady = homeRowsReady,
                                carouselOn = homeCarousel,
                                watch = homeWatch,
                                art = homeArt,
                                chatTitle = { titles[it].orEmpty() },
                                start = insets.start,
                                // The safe area the page leaves to Home on a television (see the pane's column).
                                end = insets.end + if (touch) 0.dp else Tv.SafeH,
                                bottom = insets.bottom,
                                onRowShown = onHomeRowShown,
                                onArtWanted = onHomeArtWanted,
                                feedOf = homeFeedOf,
                                onResume = openResume,
                                onPlay = openMedia,
                                onPlayNow = onPlayMedia,
                                onDetails = showDetail,
                                onHoldMedia = holdMedia,
                                onSeeContinue = {
                                    pickHistory(HistoryTab.Continue)
                                    onPickTab(BrowseSection.of(BrowseTab.History))
                                    query = ""
                                },
                                onOpenChat = { id -> data.chats.firstOrNull { it.id == id }?.let(onOpenChat) },
                                // Starred chats get a row each under Recently added (2026-10-23), so
                                // with none the hint says how to add them; only once the rows are
                                // built, since it flashed up while they were still coming in.
                                noFavourites = favorites.isEmpty() && homeRowsReady,
                                onOpenSection = { onPickTab(it); query = "" },
                                onOpenWatchLater = openLater,
                                onLaterArtWanted = onHomeLaterArtWanted,
                                mayTakeFocus = listMayTakeFocus,
                                tvPlayFocus = tvPlayFocus,
                                onSeeWatchLater = {
                                    pickHistory(HistoryTab.WatchLater)
                                    onPickTab(BrowseSection.of(BrowseTab.History))
                                    query = ""
                                },
                                onDownloadSeason = { request ->
                                    panelScope.launch {
                                        queueSeason(panelContext, SettingsStore(panelContext), request) { titles[it.chatId].orEmpty() }?.let(onMessage)
                                    }
                                },
                                // The heading stands on the carousel's picture, as the phone's
                                // status bar does, and scrolls away with it.
                                // Room for the television's top bar, which stands over the carousel's picture.
                                tvHeader = if (touch) null else { _ -> Spacer(Modifier.fillMaxWidth().height(TV_BAR_HEIGHT)) },
                            )
                        }
                    } else if (tab.listsVideos) {
                        // One branch for both tabs, so the chip row stays the same composition when
                        // OK switches tab and the remote's focus stays on the chip it pressed.
                        // On a phone the heading, the count and the actions live in the app bar, so
                        // the content area starts with the content.
                        if (!touch) {
                            val historyActions: @Composable () -> Unit = {
                                LayoutAction(layout, onToggleLayout)
                                // No Refresh here: History is read off this device and cannot be
                                // behind, so the button would be one that visibly does nothing.
                                if (showsContinue && continuing.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearContinue,
                                        icon = TmIcons.Close,
                                        onClick = { confirmClearHistory = true },
                                    )
                                }
                                if (showsWatched && watchedHistory.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearWatched,
                                        icon = TmIcons.Close,
                                        onClick = { confirmClearWatched = true },
                                    )
                                }
                            }
                            // No heading under the top bar, which already names the page: the chips
                            // lead, and the page's own buttons close their line.
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { historyChips() }
                                Row(
                                    Modifier.padding(start = 12.dp, end = insets.end),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) { historyActions() }
                            }
                        } else {
                            historyChips()
                        }
                        if (showsContinue) {
                            if (continuing.isEmpty()) {
                                EmptyTab(tab, query = "", history = HistoryTab.Continue, onGoToChats = goToChats)
                            } else {
                                ContinueSection(
                                    records = continuing,
                                    byName = historySort == ChatSort.Name,
                                    layout = layout,
                                    insets = insets,
                                    tiles = tiles,
                                    autoFocus = !touch,
                                    mayTakeFocus = listMayTakeFocus,
                                    pageOpen = details.top != null,
                                    text = { it.cardText() },
                                    onResume = openResume,
                                    onHold = { mediaMenu = it },
                                    dayOf = { it.updatedAt },
                                )
                            }
                        } else if (showsWatchLater) {
                            if (watchLater.isEmpty()) {
                                EmptyTab(tab, query = "", history = HistoryTab.WatchLater, onGoToChats = goToChats)
                            } else {
                                ContinueSection(
                                    records = laterShown,
                                    byName = historySort == ChatSort.Name,
                                    text = { it.cardText() },
                                    layout = layout,
                                    insets = insets,
                                    tiles = tiles,
                                    autoFocus = !touch,
                                    mayTakeFocus = listMayTakeFocus,
                                    pageOpen = details.top != null,
                                    onResume = openLater,
                                    onHold = { laterMenu = it },
                                )
                            }
                        } else {
                            if (watchedHistory.isEmpty()) {
                                EmptyTab(tab, query = "", history = HistoryTab.Watched, onGoToChats = goToChats)
                            } else {
                                // Read once per visit rather than ticking: "5 minutes ago" going
                                // stale while the tab is open is harmless, and a clock here would
                                // recompose every card each minute for it.
                                val now = remember(watchedHistory) { System.currentTimeMillis() }
                                ContinueSection(
                                    records = watchedShown,
                                    byName = historySort == ChatSort.Name,
                                    text = { it.cardText(now) },
                                    layout = layout,
                                    insets = insets,
                                    tiles = tiles,
                                    autoFocus = !touch,
                                    mayTakeFocus = listMayTakeFocus,
                                    pageOpen = details.top != null,
                                    onResume = openWatched,
                                    onHold = { watchedMenu = it },
                                    dayOf = { it.watchedAt },
                                    dayText = { record, day -> record.cardText(now, day) },
                                )
                            }
                        }
                    } else {
                        if (!touch) {
                            val chatActions: @Composable () -> Unit = {
                                // Refresh is in the account's menu on the top bar.
                                LayoutAction(layout, onToggleLayout)
                                // Stars are added one at a time from a menu, so the only way back
                                // from a tab full of them is here, beside the tab they fill.
                                if (tab == BrowseSection.of(BrowseTab.Favorites) && favorites.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearFavourites,
                                        icon = TmIcons.Close,
                                        onClick = { confirmClearFavorites = true },
                                    )
                                }
                            }
                            // The top bar names the page; the search line carries its buttons.
                            Spacer(Modifier.height(4.dp))
                            SearchRow(query = query, insets = insets, onQuery = { query = it }, actions = chatActions)
                            Spacer(Modifier.height(if (tab.onChatsPage) 4.dp else 20.dp))
                        }
                        // Up from the top of the list reaches the chips, and Up again the search.
                        if (tab.onChatsPage) chatChips(tab)

                        if (visible.isEmpty()) {
                            EmptyTab(tab, query, chatFilter, onGoToChats = goToChats)
                        } else {
                            // A first run's chat list says how to get a first video in (CP42).
                            if (query.isBlank() && tab != BrowseSection.of(BrowseTab.Saved)) {
                                FirstVideoTip(
                                    nothingPlayed = continueWatching.isEmpty() && watchedHistory.isEmpty(),
                                    start = insets.start,
                                    end = insets.end,
                                )
                            }
                            ChatSection(
                                chats = visible,
                                favorites = favorites,
                                sort = chatSort,
                                // Recency order comes straight from Telegram, so the top of the
                                // unfiltered list already is "recent". The strip is dropped in
                                // grid view: the grid's own first row is those same chats, and the
                                // two together read as the list repeating itself.
                                recent = if (
                                    tab.isChats &&
                                    chatFilter == ChatFilter.All &&
                                    chatSort == ChatSort.Recent &&
                                    query.isBlank() &&
                                    chatLayout(tab) == CardLayout.List
                                ) {
                                    visible.take(RECENT_COUNT)
                                } else {
                                    emptyList()
                                },
                                layout = chatLayout(tab),
                                insets = insets,
                                tiles = tiles,
                                autoFocus = !touch,
                                mayTakeFocus = listMayTakeFocus,
                                onOpenChat = onOpenChat,
                                onHold = { chatMenu = it },
                                launchChatId = launchChatId,
                                paging = chatPaging,
                                onMoreChats = onMoreChats,
                            )
                        }
                    }
                }
        }
    }
    }

    val chatsNow = (state as? UiState.Content)?.value?.chats.orEmpty()
    val openDetail: @Composable () -> Unit = {
        held?.let { (item, title) ->
            MediaDetailOpened(
                item = item,
                chatTitle = title,
                watched = homeWatch.point(item),
                finished = homeWatch.finished(item),
                onPlay = { onPlayMedia(item, title) },
                onSetWatched = { onSetMediaWatched(item, title, it) },
                onDownload = {
                    panelScope.launch {
                        queueDownloads(panelContext, SettingsStore(panelContext), listOf(item)) { title }?.let(onMessage)
                    }
                },
                onOpenChat = chatsNow.firstOrNull { it.id == item.chatId }?.let { chat -> { onOpenChat(chat) } },
                onDismiss = { held = null },
                asMenu = true,
                onOpenDetails = { details.open(item to title) },
            )
        }
        details.top?.let { entry ->
            val (item, title) = entry.page
            fun titled(other: MediaItem) = other to (chatsNow.firstOrNull { it.id == other.chatId }?.title ?: "")
            MediaDetailOpened(
                item = item,
                chatTitle = title,
                watched = homeWatch.point(item),
                finished = homeWatch.finished(item),
                onPlay = { onPlayMedia(item, title) },
                onSetWatched = { onSetMediaWatched(item, title, it) },
                onDownload = {
                    panelScope.launch {
                        queueDownloads(panelContext, SettingsStore(panelContext), listOf(item)) { title }?.let(onMessage)
                    }
                },
                onOpenChat = chatsNow.firstOrNull { it.id == item.chatId }?.let { chat -> { onOpenChat(chat) } },
                onDismiss = { details.clear() },
                onOpenItem = { other -> details.push(titled(other)) },
                onTurnTo = { other -> details.turn(titled(other)) },
                onBack = if (details.canGoBack) ({ details.back() }) else null,
                returnTo = entry.returnTo,
            )
        }
    }

    // A phone's Home runs up under the status bar for its carousel (see LocalUnderStatusBar); while
    // the chat list is still loading, or failed, what stands in its place keeps below the bar.
    val pane: @Composable (BrowseSection) -> Unit = { tab ->
        val under = if (tab.isHome && state !is UiState.Content) LocalUnderStatusBar.current else 0.dp
        Box(Modifier.fillMaxSize().padding(top = under)) { paneOf(tab) }
    }
    if (touch) {
        PhoneBrowse(
            refreshOf = { page ->
                when {
                    page.isHome -> onRefreshHome
                    !page.listsVideos -> onRefresh
                    else -> null
                }
            },
            refreshingOf = { page -> page.isHome && homeRefreshing },
            tab = tab,
            account = (state as? UiState.Content)?.value?.account ?: account,
            pane = pane,
            countOf = { section ->
                val chats = (state as? UiState.Content)?.value?.chats
                when {
                    section.isHome -> 0
                    section.listsVideos -> when (historyTab) {
                        HistoryTab.Continue -> continuing.size
                        HistoryTab.Watched -> watchedHistory.size
                        HistoryTab.WatchLater -> watchLater.size
                    }
                    else -> chats?.let { filterChats(it, section, favorites, query, chatFilter, chatSort).size } ?: 0
                }
            },
            // Home is rows of tiles whatever the arrangement, and refreshes with a pull; a list of
            // chats is rows on a phone; so only History offers rows or tiles, and a page with
            // nothing to offer has no pill at all.
            actions = unlessHidden(
                tab.isHome || !(tab.listsVideos || (tab == BrowseSection.of(BrowseTab.Favorites) && favorites.isNotEmpty())),
            ) actions@{
                if (tab.listsVideos) {
                    BarIcon(
                        label = if (layout == CardLayout.Grid) s.browseShowAsRows else s.browseShowAsTiles,
                        icon = if (layout == CardLayout.Grid) {
                            TmIcons.List
                        } else {
                            TmIcons.Grid
                        },
                        onClick = onToggleLayout,
                    )
                }
                // Everything destructive goes behind the overflow. A "Clear history" button
                // sitting in the bar beside Refresh is one mis-tap from emptying the tab.
                val clearHistory = showsContinue && continueWatching.isNotEmpty()
                val clearFavorites = tab == BrowseSection.of(BrowseTab.Favorites) && favorites.isNotEmpty()
                val clearWatched = showsWatched && watchedHistory.isNotEmpty()
                if (clearHistory || clearFavorites || clearWatched) {
                    BarOverflow(
                        items = buildList {
                            if (clearHistory) {
                                add(s.browseClearContinue to { confirmClearHistory = true })
                            }
                            if (clearWatched) {
                                add(s.browseClearWatchedList to { confirmClearWatched = true })
                            }
                            if (clearFavorites) {
                                add(s.browseClearFavourites to { confirmClearFavorites = true })
                            }
                        },
                    )
                }
            },
            sections = sections,
            favourites = favorites,
            downloadCount = downloadCount,
            updateVersion = updateVersion,
            onPickTab = { onPickTab(it); query = "" },
            onHistory = { pickHistory(it); onPickTab(BrowseSection.of(BrowseTab.History)) },
            onOpenDownloads = onOpenDownloads,
            onOpenSettings = onOpenSettings,
            onUpdate = onUpdate,
            search = search,
            chats = (state as? UiState.Content)?.value?.chats.orEmpty(),
            continueWatching = continuing,
            watchLater = watchLater,
            homeArt = homeArt,
            watch = homeWatch,
            onOpenChat = onOpenChat,
            onOpenMedia = openMedia,
            onPlayMedia = onPlayMedia,
            onResume = openResume,
            onMessage = onMessage,
        )
    } else if (tvNav != null) {
        // The search across every chat: the bar's Search, and the remote's search key.
        var tvSearchOpen by rememberSaveable { mutableStateOf(false) }
        val barFocus = remember { FocusRequester() }
        val pageFocus = remember { FocusRequester() }
        // The account's picture, which Back from Settings returns the remote to.
        val profileFocus = remember { FocusRequester() }
        var barRefocus by remember { mutableIntStateOf(0) }
        LaunchedEffect(barRefocus) {
            if (barRefocus == 0) return@LaunchedEffect
            withFrameNanos { }
            runCatching { barFocus.requestFocus() }
        }
        val homeShown = !tvSearchOpen && tab.isHome
        // Back anywhere in a page goes up to the bar first, on the page's own item, and Home
        // goes back to its top as it does; only Back on Home's bar asks to leave the app.
        BackHandler(enabled = !tvBarHasFocus) {
            if (homeShown) TvBarSignals.homeToTop.tryEmit(Unit)
            runCatching { barFocus.requestFocus() }
        }
        // Back on the bar of any other page: Home, the remote still on the bar.
        BackHandler(enabled = tvBarHasFocus && !homeShown) {
            tvSearchOpen = false
            onPickTab(BrowseSection.of(BrowseTab.Home))
            query = ""
            barRefocus++
        }
        // "Search all chats" from a chat or the list's filter: the search page, on those words.
        val asked by SearchRequest.pending.collectAsState()
        LaunchedEffect(asked) {
            val text = SearchRequest.take() ?: return@LaunchedEffect
            search.submit(text)
            tvSearchOpen = true
        }
        // Search picked on the bar over Downloads.
        val barAsked by TvBarSignals.searchAsked.collectAsState()
        LaunchedEffect(barAsked) {
            if (!barAsked) return@LaunchedEffect
            tvSearchOpen = true
            TvBarSignals.searchAsked.value = false
        }
        // Back from Settings (to the account picture, whose menu opened it) or Downloads, or a tab
        // picked on Downloads' bar: the remote stays on the bar, held there against the page
        // taking focus as it loads, until a key is pressed.
        val pressedSinceArrival = remember { booleanArrayOf(false) }
        LaunchedEffect(returnToRail) {
            if (returnToRail == null) return@LaunchedEffect
            val landing = if (returnToRail == RailReturn.Profile) profileFocus else barFocus
            repeat(RETURN_HOLD_FRAMES) {
                if (pressedSinceArrival[0]) return@repeat
                withFrameNanos { }
                if (!pressedSinceArrival[0]) runCatching { landing.requestFocus() }
            }
            onReturnedToRail()
        }
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) pressedSinceArrival[0] = true
                if (event.type == KeyEventType.KeyDown && event.key == Key.Search) {
                    tvSearchOpen = true
                    true
                } else {
                    false
                }
            },
        ) {
            // Home runs up under the bar, its carousel's picture with it; every other page, and
            // Home while it loads, starts below the bar.
            val underBar = homeShown && state is UiState.Content
            val homeScrolled by TvBarSignals.homeScrolled.collectAsState()
            val barGround by androidx.compose.animation.core.animateFloatAsState(
                if (underBar && homeScrolled) 1f else 0f,
                com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                label = "barGround",
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = if (underBar) 0.dp else TV_BAR_HEIGHT, end = if (homeShown) 0.dp else Tv.SafeH)
                    // Up from Home's top row to the bar brings Home back to its top, the carousel whole.
                    .underTvBar(pageFocus, barFocus, onUpToBar = { if (homeShown) TvBarSignals.homeToTop.tryEmit(Unit) }),
            ) {
                if (tvSearchOpen) {
                    TvSearchPage(
                        search = search,
                        state = searchState,
                        chats = (state as? UiState.Content)?.value?.chats.orEmpty(),
                        favourites = favorites,
                        homeRows = homeRows,
                        watch = homeWatch,
                        onOpenChat = onOpenChat,
                        onOpenMedia = openMedia,
                        onPlayMedia = onPlayMedia,
                        onMessage = onMessage,
                        account = (state as? UiState.Content)?.value?.account ?: account,
                    )
                } else {
                    pane(tab)
                }
            }
            TvTopBar(
                nav = tvNav,
                current = when {
                    tab.isHome -> TvBarTab.Home
                    tab.onChatsPage -> TvBarTab.Chats
                    else -> null
                },
                inProfile = !tab.isHome && !tab.onChatsPage,
                searchOpen = tvSearchOpen,
                onHome = {
                    tvSearchOpen = false
                    onPickTab(BrowseSection.of(BrowseTab.Home))
                    query = ""
                },
                onChats = {
                    tvSearchOpen = false
                    query = ""
                    tvNav.onChats()
                },
                onSearch = { tvSearchOpen = true },
                beforeProfile = {
                    tvSearchOpen = false
                    query = ""
                },
                onRefresh = when {
                    tvSearchOpen -> null
                    tab.isHome -> onRefreshHome
                    tab.listsVideos -> null
                    else -> onRefresh
                },
                currentFocus = barFocus,
                contentFocus = pageFocus,
                overArt = homeShown,
                modifier = Modifier.onFocusChanged { tvBarHasFocus = it.hasFocus },
                solid = { barGround },
                onDown = { homeShown && runCatching { tvPlayFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false) },
                profileFocus = profileFocus,
            )
        }
    }

    openDetail()

    chatMenu?.let { chat ->
        val favorite = chat.id in favorites
        TvMenu(
            title = chat.title,
            subtitle = chatCaption(chat),
            onDismiss = { chatMenu = null },
            actions = buildList {
                add(
                    MenuAction(s.navOpen, TmIcons.Play) {
                        chatMenu = null
                        onOpenChat(chat)
                    },
                )
                add(
                    MenuAction(
                        label = if (favorite) s.browseRemoveFavourite else s.browseAddFavourite,
                        icon = if (favorite) TmIcons.Star else TmIcons.StarOutline,
                        detail = if (favorite) {
                            s.browseRemoveFavouriteDetail
                        } else {
                            s.browseAddFavouriteDetail
                        },
                    ) {
                        chatMenu = null
                        justStarred = if (favorite) null else chat.id
                        onToggleFavorite(chat)
                    },
                )
                // Also private to this device: the chat leaves TMPlayer's lists, never Telegram.
                add(
                    MenuAction(
                        label = s.browseHideChat,
                        icon = TmIcons.VisibilityOff,
                        detail = s.browseHideChatDetail,
                    ) {
                        chatMenu = null
                        hideChat(chat)
                    },
                )
                // Everything below this point changes the chat in Telegram itself rather than
                // only in this app, so each one says so: a star is private to this device, a pin
                // is on the viewer's phone a second later.
                add(
                    MenuAction(
                        label = if (chat.isPinned) s.browseUnpin else s.browsePin,
                        icon = TmIcons.Pin,
                        detail = s.browsePinDetail,
                    ) {
                        chatMenu = null
                        onTogglePinned(chat)
                    },
                )
                add(
                    MenuAction(
                        label = if (chat.isMuted) s.browseUnmute else s.browseMute,
                        icon = if (chat.isMuted) TmIcons.Bell else TmIcons.BellOff,
                        detail = if (chat.isMuted) {
                            s.browseUnmuteDetail
                        } else {
                            s.browseMuteDetail
                        },
                    ) {
                        chatMenu = null
                        onToggleMuted(chat)
                    },
                )
                if (chat.unreadCount > 0) {
                    add(
                        MenuAction(
                            label = s.browseMarkRead,
                            icon = TmIcons.Check,
                            detail = s.browseMarkReadDetail,
                        ) {
                            chatMenu = null
                            onMarkRead(chat)
                        },
                    )
                }
                add(
                    MenuAction(
                        label = if (chat.isArchived) s.browseUnarchive else s.browseArchive,
                        icon = TmIcons.Archive,
                        detail = if (chat.isArchived) {
                            s.browseUnarchiveDetail
                        } else {
                            s.browseArchiveDetail
                        },
                    ) {
                        chatMenu = null
                        onToggleArchived(chat)
                    },
                )
            },
        )
    }

    if (confirmClearFavorites) {
        TvConfirm(
            title = s.browseClearFavouritesTitle,
            message = s.browseClearFavouritesMessage(favorites.size),
            detail = s.browseClearFavouritesDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearFavorites = false
                onClearFavorites()
            },
            onDismiss = { confirmClearFavorites = false },
        )
    }

    if (confirmClearHistory) {
        TvConfirm(
            title = s.browseClearContinueTitle,
            // The cards the tab shows, copies of one video folded into one, as its count says.
            message = s.browseClearContinueMessage(continuing.size),
            detail = s.browseClearContinueDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearHistory = false
                onClearHistory()
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    if (confirmClearWatched) {
        TvConfirm(
            title = s.browseClearWatchedTitle,
            message = s.browseClearWatchedMessage(watchedHistory.size),
            detail = s.browseClearWatchedDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearWatched = false
                onClearWatched()
            },
            onDismiss = { confirmClearWatched = false },
        )
    }

    watchedMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = listOf(
                WatchedWhen.phrase(record.watchedAt, System.currentTimeMillis()),
                record.chatTitle,
            ).filter { it.isNotBlank() }.joinToString("  ·  "),
            onDismiss = { watchedMenu = null },
            actions = listOf(
                MenuAction(s.browsePlayAgain, TmIcons.Play, detail = s.browseFromTheStart) {
                    watchedMenu = null
                    onOpenWatched(record)
                },
                MenuAction(
                    label = s.gridMarkUnwatched,
                    icon = TmIcons.Close,
                    detail = s.browseMarkUnwatchedDetail,
                    destructive = true,
                ) {
                    watchedMenu = null
                    onMarkUnwatched(record)
                },
            ),
        )
    }

    laterMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = record.chatTitle.ifBlank { null },
            onDismiss = { laterMenu = null },
            actions = listOf(
                MenuAction(s.commonPlay, TmIcons.Play) {
                    laterMenu = null
                    onOpenWatchLater(record)
                },
                MenuAction(
                    label = s.detailWatchLaterRemove,
                    icon = TmIcons.Close,
                    destructive = true,
                ) {
                    laterMenu = null
                    onRemoveWatchLater(record)
                },
            ),
        )
    }

    mediaMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = s.browseWatchedUpTo(s.formatter.clock(record.positionMs)),
            onDismiss = { mediaMenu = null },
            actions = listOf(
                MenuAction(s.commonResume, TmIcons.Play, detail = s.browseResumeDetail) {
                    mediaMenu = null
                    onResumeMedia(record)
                },
                MenuAction(s.gridPlayFromStart, TmIcons.Refresh) {
                    mediaMenu = null
                    onRestartMedia(record)
                },
                MenuAction(
                    label = s.gridMarkWatched,
                    icon = TmIcons.Check,
                    detail = s.browseMarkWatchedDetail,
                ) {
                    mediaMenu = null
                    onMarkMediaWatched(record)
                },
                MenuAction(
                    label = s.browseForgetResume,
                    icon = TmIcons.Close,
                    detail = s.browseForgetResumeDetail,
                    destructive = true,
                ) {
                    mediaMenu = null
                    onForgetMedia(record)
                },
            ),
        )
    }
}

/**
 * One button in the pill of a page's own buttons over the floating bar: [FloatingBarIcon], in
 * the pill's ink (an [IconButton] here took the theme's content colour, black on the dark pill),
 * sinking under the finger and turning rows into tiles in place.
 */
@Composable
private fun RowScope.BarIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    com.tmplayer.ui.components.FloatingBarIcon(icon, label, onClick)
}

/** The three dots, and everything that should take two taps rather than one. */
@Composable
internal fun RowScope.BarOverflow(items: List<Pair<String, () -> Unit>>) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    com.tmplayer.ui.components.FloatingBarIcon(TmIcons.MoreVert, s.commonMoreOptions, { open = true })
    TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { (label, action) ->
            DropdownMenuItem(
                text = { M3Text(label) },
                onClick = { open = false; action() },
            )
        }
    }
}

// ---- continue watching -----------------------------------------------------------------------

/**
 * What a card in either list of videos shows: Continue watching and Previously watched draw the
 * same card, and differ only in the line under the title and how full the bar is.
 */
private class VideoCardText(
    val key: String,
    val title: String,
    /** The line under the title on a tile, and the start of it on a row. */
    val detail: String,
    val chatTitle: String,
    /** The chat the video is in, which picks the colours of its generated cover. */
    val chatId: Long,
    val fraction: Float,
    /** The video as the record keeps it, with no picture: what the card shows until Telegram answers. */
    val item: MediaItem,
)

private fun ResumeRecord.cardText() = VideoCardText(
    key = "${chatId}_$messageId",
    // The release name cleaned, as on every other tile; the stored record is left as it is.
    title = SeriesShelf.tileTitle(toMediaItem()),
    detail = buildString {
        append(L.messages.formatter.clock(positionMs))
        if (remainingMs > 0) {
            append("  ·  ")
            append(L.browseTimeLeft(L.messages.formatter.clock(remainingMs)))
        }
    },
    chatTitle = chatTitle,
    chatId = chatId,
    fraction = fraction,
    item = toMediaItem(),
)

/** A finished video: when it was finished, and a full bar, which is what "watched" looks like. */
private fun WatchedRecord.cardText(now: Long) = VideoCardText(
    key = key,
    title = SeriesShelf.tileTitle(toMediaItem()),
    detail = WatchedWhen.phrase(watchedAt, now),
    chatTitle = chatTitle,
    chatId = chatId,
    fraction = 1f,
    item = toMediaItem(),
)

/**
 * The same card under a day heading: the line does not say again what the heading says, so
 * "Watched yesterday" goes under Yesterday and "Watched today" under Today, and the card keeps
 * only what adds to it ("Watched 5 minutes ago", "Watched on 27 Sep").
 */
private fun WatchedRecord.cardText(now: Long, day: DayGroup): VideoCardText {
    val card = cardText(now)
    val repeats = when (day) {
        DayGroup.Yesterday -> true
        DayGroup.Today -> card.detail == L.watchedTodayPhrase
        DayGroup.Earlier -> false
    }
    return if (repeats) VideoCardText(card.key, card.title, "", card.chatTitle, card.chatId, card.fraction, card.item) else card
}

/** A day heading over History's Continue watching and Watched lists. */
@Composable
private fun DayHeading(day: DayGroup) {
    val s = LocalStrings.current
    Text(
        dayWord(day, s),
        style = MaterialTheme.typography.titleSmall,
        color = Tone.muted,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** A day heading's word: Today, Yesterday, Earlier. */
private fun dayWord(day: DayGroup, s: com.tmplayer.i18n.Messages): String = when (day) {
    DayGroup.Today -> s.watchedToday
    DayGroup.Yesterday -> s.watchedYesterday
    DayGroup.Earlier -> s.watchedEarlier
}

/** A video saved for later: how long it is and how big, and no bar until it is started. */
private fun WatchLaterRecord.cardText() = VideoCardText(
    key = key,
    title = SeriesShelf.tileTitle(toMediaItem()),
    detail = listOfNotNull(
        durationSec.takeIf { it > 0 }?.let { L.messages.formatter.duration(it.toLong()) },
        sizeBytes.takeIf { it > 0 }?.let { L.messages.formatter.size(it) },
    ).joinToString("  ·  "),
    chatTitle = chatTitle,
    chatId = chatId,
    fraction = 0f,
    item = toMediaItem(),
)

@Composable
private fun <T : Any> ContinueSection(
    records: List<T>,
    text: (T) -> VideoCardText,
    layout: CardLayout,
    insets: BrowseInsets,
    tiles: GridCells,
    /** Only a remote needs somewhere to stand; on a phone a stolen focus only opens the keyboard. */
    autoFocus: Boolean,
    onResume: (T) -> Unit,
    onHold: (T) -> Unit,
    /** Asked before [autoFocus] acts: false while the chips over the list hold the focus. */
    mayTakeFocus: () -> Boolean = { true },
    /**
     * When each record was played or finished, for Today, Yesterday and Earlier headings over the
     * list. Null for a list with no such time (Watch later). See [DayGroup.headings].
     */
    dayOf: ((T) -> Long)? = null,
    /** The card under a day heading, which need not repeat the heading's word. */
    dayText: ((T, DayGroup) -> VideoCardText)? = null,
    /** Listed A to Z: the fast scroller's bubble gives the letter rather than the day. */
    byName: Boolean = false,
    /** A video's page is up over the list: once it closes, the remote goes back to its card. */
    pageOpen: Boolean = false,
) {
    val first = remember { FocusRequester() }
    // The card a page was opened from, which the remote goes back to when the page closes.
    val tileReturn = rememberTileReturn()
    val now = remember(records) { System.currentTimeMillis() }
    val days = remember(records, dayOf != null) { dayOf?.let { DayGroup.headings(records, it, now) } }
    val sections: List<Pair<DayGroup?, List<T>>> = days ?: listOf(null to records)
    fun cardOf(record: T, day: DayGroup?): VideoCardText =
        if (day != null && dayText != null) dayText(record, day) else text(record)
    // The card a hold menu was opened on, and where it stood: when the menu removes it (Forget,
    // Mark unwatched, Remove from Watch later) the remote moves to the card that took its place,
    // rather than being left on nothing.
    var held by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val refocus = remember { FocusRequester() }
    val heldGone = held?.let { (key, _) -> records.none { text(it).key == key } } == true
    val standIn: T? = if (heldGone) records.getOrNull(minOf(held!!.second, records.lastIndex)) else null
    val holdThis: (T) -> Unit = { record ->
        held = text(record).key to records.indexOf(record)
        tileReturn.open(text(record).key)
        onHold(record)
    }
    val resumeThis: (T) -> Unit = { record ->
        tileReturn.open(text(record).key)
        onResume(record)
    }
    // Only the first card asks for focus, and which card that is does not change with the
    // arrangement, so the two branches can share one modifier.
    fun focusOf(record: T): Modifier {
        val base = if (record === records.firstOrNull()) Modifier.focusRequester(first) else Modifier
        return if (record === standIn) base.focusRequester(refocus) else base
    }

    // The same start inset the chat list uses, so the cards line up under their heading instead of
    // butting against the rail.
    val padding = PaddingValues(
        start = insets.start,
        end = insets.end,
        // Room above the first row for a focused tile to grow into; nothing on a phone.
        top = if (isTouch()) 0.dp else Tv.FocusClearance,
        bottom = insets.bottom,
    )

    val listState = com.tmplayer.ui.components.rememberAheadListState(column = true)
    val gridState = com.tmplayer.ui.components.rememberAheadGridState()
    Box(Modifier.fillMaxSize()) {
        when (layout) {
            // The rows of a chat's list, poster, type and spacing alike: History's list is the same
            // kind of list, and a card around each row here made it the one list that was boxed.
            CardLayout.List -> LazyColumn(
                state = listState,
                modifier = Modifier.keepTopInSight(listState, enabled = !isTouch()),
                verticalArrangement = Arrangement.spacedBy(if (isTouch()) 2.dp else 4.dp),
                contentPadding = padding,
            ) {
                sections.forEach { (day, members) ->
                if (day != null) item(key = "day-$day") { DayHeading(day) }
                items(members, key = { text(it).key }) { record ->
                    val card = cardOf(record, day)
                    VideoListRow(
                        item = card.item,
                        title = card.title,
                        detail = card.detail,
                        chatTitle = card.chatTitle,
                        fraction = card.fraction,
                        finished = card.fraction >= 1f,
                        onClick = { resumeThis(record) },
                        onHold = { holdThis(record) },
                        modifier = focusOf(record).returnTile(tileReturn, text(record).key),
                    )
                }
                }
            }

            CardLayout.Grid -> LazyVerticalGrid(
                columns = tiles,
                state = gridState,
                modifier = Modifier.keepTopInSight(gridState, enabled = !isTouch()),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sections.forEach { (day, members) ->
                if (day != null) item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) { DayHeading(day) }
                gridItems(members, key = { text(it).key }) { record ->
                    ContinueTile(
                        text = cardOf(record, day),
                        onResume = { resumeThis(record) },
                        onHold = { holdThis(record) },
                        modifier = focusOf(record).returnTile(tileReturn, text(record).key),
                    )
                }
                }
            }
        }
        when (layout) {
            CardLayout.List -> ScrollToTop(listState)
            CardLayout.Grid -> ScrollToTop(gridState)
        }
        // The hold to scroll handle: its bubble says the letter A to Z, else the day.
        val s = LocalStrings.current
        val labels = remember(sections, byName) {
            buildMap<Any, String> {
                sections.forEach { (day, members) ->
                    members.forEach { record ->
                        val said = if (byName) ScrollLabels.letter(text(record).title) else day?.let { dayWord(it, s) }
                        if (said != null) put(text(record).key, said)
                    }
                }
            }
        }
        val scrollLabel: (Any) -> String? = { labels[it] }
        when (layout) {
            CardLayout.List -> FastScroller(listState, label = scrollLabel)
            CardLayout.Grid -> FastScroller(gridState, label = scrollLabel)
        }
    }

    // This is the landing tab for anyone with a video on the go, and the remote has nowhere to go
    // until something holds focus. Re-run on a change of arrangement too: switching rebuilds the
    // list from scratch, and the card that was holding focus leaves the composition with it.
    LaunchedEffect(records.firstOrNull()?.let { text(it).key }, layout, autoFocus) {
        if (autoFocus && mayTakeFocus()) runCatching { first.requestFocus() }
    }
    // Back from a video's page, the remote lands on the card it was opened from, scrolled back
    // to where the list went while the page was up. Its lazy index counts the day headings.
    ReturnToTile(tileReturn, pageOpen) { key ->
        var index = 0
        var found = -1
        for ((day, members) in sections) {
            if (day != null) index++
            val at = members.indexOfFirst { text(it).key == key }
            if (at >= 0) {
                found = index + at
                break
            }
            index += members.size
        }
        if (found < 0) return@ReturnToTile false
        when (layout) {
            CardLayout.List -> listState.scrollToItem(found)
            CardLayout.Grid -> gridState.scrollToItem(found)
        }
        true
    }
    LaunchedEffect(standIn?.let { text(it).key }, heldGone) {
        if (!heldGone) return@LaunchedEffect
        if (autoFocus && standIn != null) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { refocus.requestFocus() }
        }
        held = null
    }
}

/**
 * A Continue watching card as a tile: the preview carries the progress bar, because there is no
 * row of text alongside it to put the bar under.
 */
@Composable
private fun ContinueTile(
    text: VideoCardText,
    onResume: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "continueTileBorder",
    )

    // The art, the bar and the two lines are the same on both screens; only what holds them
    // differs, so the tile is written once and handed to whichever container the device wants.
    val body: @Composable ColumnScope.() -> Unit = {
        Box {
            ContinueArt(text, Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge = 40.dp)
            if (text.fraction > 0f) {
                ProgressLine(text.fraction, Modifier.align(Alignment.BottomCenter))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text.title,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                // Two lines, as everywhere else a release name is shown: one line cuts the title
                // before the resolution, which is the part that tells two of them apart. Always
                // two, so a short name keeps the detail lines across the grid level.
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Under a day heading a Watched card may have nothing to add; its chat says where it is.
                text.detail.ifBlank { text.chatTitle },
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (touch) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                ),
            shape = M3MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Tone.surface),
            content = body,
        )
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Medium))
                .background(if (focused) Tone.surfaceHigh else Tone.surface)
                .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                ),
            content = body,
        )
    }
}

/**
 * Artwork for a History card (Continue watching, Watch later, Watched), with a play mark on it.
 *
 * Home's art: a record keeps no picture, so the card asks Telegram for its video once
 * ([rememberRecordItem]) and wears its thumbnail, under the show's or the film's own picture
 * where online pictures are on. The generated cover, drawn from the title and the chat alone,
 * stands in until then and for good when there is no thumbnail, so the card never waits on the
 * network and still works offline. Both are dark enough in either theme for the progress line.
 *
 * [modifier] carries the size, because the row wants a thumbnail and the tile wants the whole
 * width of its column. [badge] follows it: a 34 dp disc that reads as a play button over a 96 dp
 * thumbnail is a speck over art three times that wide.
 */
@Composable
private fun ContinueArt(text: VideoCardText, modifier: Modifier = Modifier, badge: Dp) {
    val cover = remember(text.chatId, text.title) { CoverArt.of(text.chatId, text.title) }
    val item = rememberRecordItem(text.item.chatId, text.item.messageId, text.item)
    Box(modifier.clip(RoundedCornerShape(Corner.Small)), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalOnlineArt provides true) {
            OnlineArt(item, Modifier.fillMaxSize()) {
                MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                    // No title on the cover: the card prints it beside or under the art already.
                    GeneratedCover(cover, Modifier.fillMaxSize(), compact = true, showTitle = false)
                }
            }
        }
        Icon(
            imageVector = TmIcons.PlaySolid,
            contentDescription = null,
            tint = Tone.onArt,
            modifier = Modifier.size(badge * 0.88f),
        )
    }
}



// ---- the television's top bar ----------------------------------------------------------------

/**
 * Back from Downloads, or a tab picked on its top bar: the remote goes to the browser's bar, on the
 * page's own item. Back from Settings ([Profile]): onto the account's picture, whose menu opened it.
 */
enum class RailReturn { Downloads, Settings, Profile }

/** How many frames, about a second and a half at 60 Hz, the top bar holds focus on its item. */
private const val RETURN_HOLD_FRAMES = 90

/**
 * The signed in account's picture, first on the television's top bar: Telegram's picture when
 * there is one, the initials of the name while it loads or when there is none.
 */
@Composable
internal fun AccountPicture(account: Account?) {
    Box(Modifier.size(Avatar.Compact).clip(CircleShape).background(Tone.surfaceHigh)) {
        if (account != null) {
            MediaPreview(
                miniThumbnail = account.miniThumbnail,
                thumbnailFileId = account.photoFileId,
                fallbackLabel = account.name,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

// ---- content ---------------------------------------------------------------------------------

/**
 * Rows or tiles, offered where the arrangement is: beside the list it rearranges.
 *
 * It lives here rather than in Settings because it is a matter of taste that changes with the
 * chat being looked at, and walking to another screen to change how this one looks is a long way
 * round for something the viewer can see the result of immediately.
 */
@Composable
private fun LayoutAction(layout: CardLayout, onToggle: () -> Unit) {
    val s = LocalStrings.current
    HeaderAction(
        // A grid of squares and a stack of lines are the two pictures every phone and television
        // uses for this, so the icon alone carries it.
        label = if (layout == CardLayout.Grid) s.browseAsRows else s.browseAsTiles,
        icon = if (layout == CardLayout.Grid) TmIcons.List else TmIcons.Grid,
        showLabel = false,
        onClick = onToggle,
    )
}

/**
 * A chip beside a heading, for the action that applies to the whole list under it.
 *
 * Sized and coloured like the rows below it rather than like a button, so it reads as part of the
 * heading and not as the first item of the list.
 *
 * [label] is always given, even where [showLabel] hides it: it is what a screen reader announces,
 * and an icon with no name is a button nobody can identify.
 */
@Composable
private fun HeaderAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showLabel: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surface,
        animationSpec = tween(140),
        label = "headerAction",
    )
    val foreground = if (focused) Tone.onFocusFill else Tone.text

    // A wordless chip says its name under it while the remote is on it, as the rail's icons do.
    Box {
        if (!showLabel && !isTouch()) com.tmplayer.ui.components.FocusTip(focused, label)
        Row(
            Modifier
                .clip(CircleShape)
                .background(background)
                .focusRing(focused, CircleShape)
                .clickable(interactionSource = interactions, indication = null, onClick = onClick)
                // Even padding with no words, so a wordless chip comes out round rather than wide.
                .padding(horizontal = if (showLabel) 18.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(20.dp))
            if (showLabel) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
            }
        }
    }
}


@Composable
private fun SearchRow(
    query: String,
    insets: BrowseInsets,
    onQuery: (String) -> Unit,
    /** The page's own buttons at the end of the line, where the bar has taken the heading's place. */
    actions: (@Composable () -> Unit)? = null,
) {
    val s = LocalStrings.current
    // On Fire TV the voice button opens the keyboard, which the remote's mic dictates into.
    var keyboardRequests by remember { mutableStateOf(0) }
    val startVoice = rememberVoiceSearch(
        s.browseVoicePromptChat,
        onKeyboard = { keyboardRequests++ },
        onResult = onQuery,
    )
    val searchField = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = s.browseSearchChats,
            modifier = Modifier.weight(1f).focusRequester(searchField),
            editRequests = keyboardRequests,
        )
        if (startVoice != null) {
            // A microphone on its own says it, and leaves the room to the search field.
            PillButton(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)
        }
        if (query.isNotBlank()) {
            // Clearing the query removes this pill, and a control that deletes itself while
            // focused takes the focus with it, leaving the D-pad nowhere to go. Move focus to the
            // search field first, which is where the viewer wants to be next anyway.
            PillButton(s.commonClear, TmIcons.Close) {
                runCatching { searchField.requestFocus() }
                onQuery("")
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

private const val SORT_CHIP = "sort"

/** How wide the fade is at a chip row's scrolled ends on a television. */
private val CHIP_EDGE_FADE = 40.dp

/**
 * Fades this sideways scroller out over [fade] at the start while it is scrolled from its start,
 * and at the end while there is more past it, so a chip half out of view reads as going on rather
 * than as cut off. Nothing changes on a row that fits.
 */
internal fun Modifier.fadeScrolledEdges(scroll: androidx.compose.foundation.ScrollState, fade: Dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = fade.toPx().coerceAtMost(size.width / 2)
            val rtl = layoutDirection == LayoutDirection.Rtl
            val atStart = scroll.value > 0
            val atEnd = scroll.value < scroll.maxValue
            // Left and right on screen, which in a right-to-left row are the end and the start.
            val left = if (rtl) atEnd else atStart
            val right = if (rtl) atStart else atEnd
            if (left) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = width),
                    size = androidx.compose.ui.geometry.Size(width, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            if (right) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - width, endX = size.width),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - width, 0f),
                    size = androidx.compose.ui.geometry.Size(width, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/** One choice of an [OrderChips] row. */
internal class OrderChip(val key: String, val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * A short row of chips that pick one order (the Downloads page's Newest, Name, Largest), drawn as
 * the chips over Chats and History are on each device: Material filter chips on a phone, the
 * rail's pills under a remote, where Left and Right walk them and OK picks.
 */
@Composable
internal fun OrderChips(chips: List<OrderChip>, description: String, modifier: Modifier = Modifier) {
    val touch = isTouch()
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            key(chip.key) {
                if (touch) {
                    TouchFilterChip(chip.label, chip.icon, chip.selected, onClick = chip.onClick)
                } else {
                    TvChip(ChipSpec(chip.key, chip.label, chip.icon, chip.selected, onClick = chip.onClick))
                }
            }
        }
    }
}

/**
 * One chip over Chats or History: a filter, a tab, or the sort toggle (which is never "selected",
 * since it shows the order in use rather than one of several to pick).
 */
@androidx.compose.runtime.Immutable
private class ChipSpec(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    /** What a screen reader says instead of [label], when the label alone is unclear. */
    val description: String? = null,
    val onClick: () -> Unit,
)

/**
 * The chips over Chats (the order, then All, Unread, Channels, Groups, People and Archived) and
 * over History (Continue and Watched). The choices themselves are the shared ones in :ui
 * ([ChatFilter], [ChatSort], [HistoryTab]), so the desktop offers the same chips.
 *
 * A phone gets Material filter chips that scroll sideways. A television gets 48 dp pills in the
 * rail's colours: Left and Right walk them, OK picks one, Down goes into the list and Up from the
 * list's first row comes back here.
 */
@Composable
private fun ChipRow(
    chips: List<ChipSpec>,
    insets: BrowseInsets,
    onFocus: (Boolean) -> Unit,
) {
    val touch = isTouch()
    // Coming up from the list lands on the chip in use (or the last one the remote was on), not on
    // whichever chip happens to sit nearest the row the focus left.
    val chosen = remember { FocusRequester() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scroll = rememberScrollState()
    val rowModifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { onFocus(it.hasFocus) }
        .then(if (touch || chips.none { it.selected }) Modifier else Modifier.focusRestorer(chosen))
        // On a television a chip scrolled past the start used to stop dead at the rail's edge,
        // cut in half; the row fades out at whichever end has more chips past it instead.
        .then(if (touch) Modifier else Modifier.fadeScrolledEdges(scroll, CHIP_EDGE_FADE))
        .horizontalScroll(scroll)
        .padding(
            start = insets.start,
            end = insets.end,
            top = if (touch) 4.dp else 8.dp,
            bottom = if (touch) 8.dp else 20.dp,
        )
    // On a phone one pill slides from chip to chip behind the row (ChipSlider); a television's
    // chips keep their own fills, which its focus colours replace.
    if (touch) {
        ChipSliderBox(rowModifier) { ChipRowItems(chips, insets, chosen, touch, rtl, Modifier) }
    } else {
        ChipRowItems(chips, insets, chosen, touch, rtl, rowModifier)
    }
}

@Composable
private fun ChipRowItems(
    chips: List<ChipSpec>,
    insets: BrowseInsets,
    chosen: FocusRequester,
    touch: Boolean,
    rtl: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            key(chip.key) {
                // The chosen chip scrolls into view, so a choice remembered from last time is
                // never hidden past the edge of a narrow screen.
                // The margin keeps a gap between it and the edge rather than parking it there.
                val view = remember { BringIntoViewRequester() }
                var size by remember { mutableStateOf(IntSize.Zero) }
                val margin = with(LocalDensity.current) { insets.end.toPx() }
                val onScreen = LocalPageOnScreen.current
                if (chip.selected && size != IntSize.Zero && onScreen) {
                    LaunchedEffect(Unit) {
                        runCatching { view.bringIntoView(Rect(-margin, 0f, size.width + margin, size.height.toFloat())) }
                    }
                }
                val last = chip === chips.last()
                Box(
                    Modifier
                        .bringIntoViewRequester(view)
                        .onSizeChanged { size = it }
                        .then(if (chip.selected) Modifier.focusRequester(chosen) else Modifier)
                        // The row stops at its end (Right, or Left in a right-to-left language) rather than
                        // jumping up to the heading.
                        .then(
                            when {
                                !last || touch -> Modifier
                                rtl -> Modifier.focusProperties { left = FocusRequester.Cancel }
                                else -> Modifier.focusProperties { right = FocusRequester.Cancel }
                            },
                        ),
                ) {
                    if (touch) TouchChip(chip) else TvChip(chip)
                }
            }
            // The order is a different kind of control from the filters after it, so a hairline
            // sets it apart rather than letting it read as one more filter.
            if (chip.key == SORT_CHIP) {
                Box(Modifier.width(1.dp).height(24.dp).background(Tone.outline))
            }
        }
    }
}

@Composable
private fun TouchChip(chip: ChipSpec) {
    TouchFilterChip(chip.label, chip.icon, chip.selected, chip.description, chip.onClick)
}

/**
 * One of the phone's chips, as History's tabs and the Chats page's filters draw them: also what
 * Downloads switches its tabs with, so the pages behind the picture look alike.
 */
@Composable
internal fun TouchFilterChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    description: String? = null,
    onClick: () -> Unit,
) {
    // A pick cross-fades the chip into its chosen colours rather than switching them, and the chip
    // gives a little under the finger, as the bar's buttons do.
    val press = remember { MutableInteractionSource() }
    // Material's own filter chip colours, which its chip switches between at once. In a row with
    // a sliding pill behind it ([ChipSliderBox]) the pill is the chosen chip's fill.
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val sliding = LocalChipSlider.current != null
    val container by androidx.compose.animation.animateColorAsState(
        if (selected && !sliding) scheme.secondaryContainer else Color.Transparent,
        Motion.standard(Motion.Fast),
        label = "chipBack",
    )
    val ink by androidx.compose.animation.animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        Motion.standard(Motion.Fast),
        label = "chipInk",
    )
    val glyph by androidx.compose.animation.animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.primary,
        Motion.standard(Motion.Fast),
        label = "chipIcon",
    )
    val toggleTick = com.tmplayer.ui.components.rememberToggleTick()
    androidx.compose.material3.FilterChip(
        selected = selected,
        // A chip taken is felt; pressing the one already chosen is not news.
        onClick = { if (!selected) toggleTick(true); onClick() },
        label = { M3Text(label, maxLines = 1) },
        // A pill, like the television's chips and every other chip in the app.
        shape = CircleShape,
        leadingIcon = {
            // The chosen chip's glyph fills in with a drop, as the bar's do.
            com.tmplayer.ui.components.SelectableIcon(icon, selected = selected, tint = glyph, size = 18.dp)
        },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = container,
            selectedContainerColor = container,
            labelColor = ink,
            selectedLabelColor = ink,
            iconColor = glyph,
            selectedLeadingIconColor = glyph,
        ),
        interactionSource = press,
        modifier = Modifier.chipSlot(selected).pressScale(press).then(
            description?.let { words -> Modifier.semantics { contentDescription = words } } ?: Modifier,
        ),
    )
}

/**
 * A row of [TouchFilterChip]s that scrolls sideways, spaced and inset as History's tabs are.
 */
@Composable
internal fun TouchChipStrip(content: @Composable RowScope.() -> Unit) {
    ChipSliderBox(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = TouchInsets.start, end = TouchInsets.end, top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun TvChip(chip: ChipSpec) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            focused -> Tone.focusFill
            chip.selected -> Tone.accent.copy(alpha = 0.16f)
            else -> Tone.surfaceHigh
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "chipBackground",
    )
    val foreground = when {
        focused -> Tone.onFocusFill
        chip.selected -> Tone.accent
        else -> Tone.text
    }
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .focusRing(focused, CircleShape)
            .semantics {
                selected = chip.selected
                chip.description?.let { contentDescription = it }
            }
            .clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = chip.onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        com.tmplayer.ui.components.SelectableIcon(chip.icon, selected = chip.selected, tint = foreground, size = 22.dp)
        Text(
            chip.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (chip.selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector?,
    /** False draws the icon alone; the label is still what a screen reader says. */
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surfaceHigh,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "pillBackground",
    )
    val foreground = if (focused) Tone.onFocusFill else Tone.text

    // An icon on its own says its name under it while the remote is on it.
    Box {
        if (!showLabel && !isTouch()) com.tmplayer.ui.components.FocusTip(focused, label)
        Row(
            Modifier
                .height(48.dp)
                .clip(CircleShape)
                .background(background)
                .focusRing(focused, CircleShape)
                .clickable(interactionSource = interactions, indication = null, onClick = onClick)
                // An icon on its own gets even padding, so the pill comes out round rather than wide.
                .padding(horizontal = if (showLabel) 18.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    // A wordless pill still hands its label to the screen reader; a worded one says
                    // it once, through the text.
                    contentDescription = if (showLabel) null else label,
                    tint = foreground,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (showLabel) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The chat list's foot while its older chats are read: placeholder rows the shape of a chat's,
 * or why the read failed with Try again.
 */
@Composable
private fun MoreChatsFoot(paging: com.tmplayer.ui.browse.ChatPaging, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    com.tmplayer.ui.components.PageFoot(paging.loading, paging.failure, onRetry, modifier) {
        val shimmer = com.tmplayer.ui.components.rememberShimmer()
        val touch = isTouch()
        Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(MORE_CHATS_PLACEHOLDERS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.size(if (touch) 56.dp else 64.dp), CircleShape)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(160.dp).height(14.dp))
                        com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(96.dp).height(12.dp))
                    }
                }
            }
        }
    }
}

/** Placeholder rows at the chat list's foot while its older chats are read. */
private const val MORE_CHATS_PLACEHOLDERS = 2

@Composable
private fun ChatSection(
    chats: List<ChatSummary>,
    favorites: Set<Long>,
    recent: List<ChatSummary>,
    layout: CardLayout,
    insets: BrowseInsets,
    tiles: GridCells,
    /** Only a remote needs somewhere to stand; on a phone a stolen focus only opens the keyboard. */
    autoFocus: Boolean,
    onOpenChat: (ChatSummary) -> Unit,
    onHold: (ChatSummary) -> Unit,
    launchChatId: Long,
    /** Asked before [autoFocus] acts: false while the chips over the list hold the focus. */
    mayTakeFocus: () -> Boolean = { true },
    /** The list's order, for the fast scroller's bubble: a letter when listed by name. */
    sort: ChatSort = ChatSort.Recent,
    /** Whether Telegram may hold older chats than were read, and whether they are on their way. */
    paging: com.tmplayer.ui.browse.ChatPaging = com.tmplayer.ui.browse.ChatPaging(),
    /** Reads the next chats, as the list's end comes near. */
    onMoreChats: () -> Unit = {},
) {
    val s = LocalStrings.current
    val rowsAreFullBleed = isTouch()
    // Around the strip's tiles, for the television's focus scale. A phone does not grow them.
    val clearance = if (rowsAreFullBleed) 0.dp else Tv.FocusClearance
    val first = remember { FocusRequester() }
    // The strip is what a viewer lands on when it is there, because it is the largest thing on the
    // screen; otherwise the first card takes it.
    fun focusOf(chat: ChatSummary, inStrip: Boolean): Modifier = when {
        inStrip -> if (chat === recent.firstOrNull()) Modifier.focusRequester(first) else Modifier
        recent.isNotEmpty() -> Modifier
        chat === chats.firstOrNull() -> Modifier.focusRequester(first)
        else -> Modifier
    }

    // A television lists its chats in two columns (user, 2026-10-27): one row stretched across a
    // 16:9 screen left most of every row empty, as the desktop's wide window did (237dbe2). The
    // remote moves left and right between the columns and up and down within one, by the grid's
    // own focus search, and the grid composes only the rows on screen and the next, so a 3,000
    // chat account costs what a short one does. A phone keeps its one column of full bleed rows.
    val twoColumns = !rowsAreFullBleed && layout == CardLayout.List
    // The chat this list was left for, read once as the list comes back from it (Back from that
    // chat's videos), so the remote lands on its row again rather than on the first chat.
    val back = remember { if (autoFocus) ChatListReturn.key else null }
    val backFocus = remember { FocusRequester() }
    val backOn = remember { BooleanArray(1) }
    fun backOf(key: Any): Modifier =
        if (back != null && key == back) {
            Modifier.focusRequester(backFocus).onFocusChanged { backOn[0] = it.hasFocus }
        } else {
            Modifier
        }
    val startIndex = if (back != null) ChatListReturn.firstIndex else 0
    val startOffset = if (back != null) ChatListReturn.firstOffset else 0

    val listState = com.tmplayer.ui.components.rememberAheadListState(column = true)
    val gridState = com.tmplayer.ui.components.rememberAheadGridState(
        initialFirstVisibleItemIndex = if (layout == CardLayout.Grid) startIndex else 0,
        initialFirstVisibleItemScrollOffset = if (layout == CardLayout.Grid) startOffset else 0,
    )
    val rowsState = com.tmplayer.ui.components.rememberAheadGridState(
        initialFirstVisibleItemIndex = if (twoColumns) startIndex else 0,
        initialFirstVisibleItemScrollOffset = if (twoColumns) startOffset else 0,
    )
    // The grid the remote is in, when it is in one: the tiles, or the television's two columns.
    val shownGrid: LazyGridState? = when {
        layout == CardLayout.Grid -> gridState
        twoColumns -> rowsState
        else -> null
    }
    // Opening a chat on a television notes which card it was and where the list stood.
    val open: (ChatSummary, Any) -> Unit = { chat, key ->
        if (autoFocus && shownGrid != null) {
            ChatListReturn.key = key
            ChatListReturn.firstIndex = shownGrid.firstVisibleItemIndex
            ChatListReturn.firstOffset = shownGrid.firstVisibleItemScrollOffset
        }
        onOpenChat(chat)
    }
    // The strip of recent chats is the grid's first line, across both columns.
    val stripLines = if (recent.isNotEmpty()) 1 else 0

    // Telegram's older chats read as the end of the list comes near, a screen ahead.
    val readMore = paging.more && !paging.loading && paging.failure == null
    when {
        twoColumns -> com.tmplayer.ui.components.LoadMoreAhead(rowsState, readMore, onLoadMore = onMoreChats)
        layout == CardLayout.List -> com.tmplayer.ui.components.LoadMoreAhead(listState, readMore, onLoadMore = onMoreChats)
        else -> com.tmplayer.ui.components.LoadMoreAhead(gridState, readMore, onLoadMore = onMoreChats)
    }
    // Chats pressed again on the bar: back to the top of the list.
    ScrollToTopOnReselect(TouchDestination.Chats) { animate ->
        when {
            twoColumns -> rowsState.toTop(animate)
            layout == CardLayout.Grid -> gridState.toTop(animate)
            else -> listState.toTop(animate)
        }
    }
    // The strip of recent chats, over the list on either device.
    val strip: @Composable () -> Unit = {
        // The rows below are full bleed on a phone, but a heading is not a row: with
        // the list's side padding dropped for their sake, this heading has to put it
        // back or it prints hard against the panel edge.
        Column(
            if (rowsAreFullBleed) {
                Modifier.padding(start = insets.start, end = insets.end)
            } else {
                Modifier
            },
        ) {
            Text(
                s.browseJumpBackIn,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            LazyRow(
                // Drawn [clearance] past every side and padded back in by as much, so
                // a focused tile keeps its border and the strip still lines up under its
                // heading and sits where it did.
                modifier = Modifier.bleed(clearance),
                contentPadding = PaddingValues(clearance),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(recent, key = { "r-${it.id}" }) { chat ->
                    ChatTile(
                        chat = chat,
                        favorite = chat.id in favorites,
                        opensOnLaunch = chat.id == launchChatId,
                        onClick = { open(chat, "r-${chat.id}") },
                        onHold = { onHold(chat) },
                        modifier = focusOf(chat, inStrip = true)
                            .then(backOf("r-${chat.id}"))
                            .width(RECENT_TILE_WIDTH),
                    )
                }
            }
            Text(
                // Not "All chats": that is the name of a rail tab, and repeating it as
                // a sub-heading inside a different tab reads as if the viewer moved.
                s.browseMoreChats,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                modifier = Modifier.padding(top = 32.dp, bottom = 4.dp),
            )
        }
    }
    // On a phone a chip, a sort or a search moves the rows to their new places and fades newcomers
    // in. Not past a few hundred chats, where the bookkeeping costs more than the motion is worth.
    val animateRows = rowsAreFullBleed && !(rememberReducedMotion() || Motion.reduced) && chats.size <= ANIMATED_ROWS_MAX
    Box(Modifier.fillMaxSize()) {
        when {
            twoColumns -> LazyVerticalGrid(
                columns = GridCells.Fixed(TV_CHAT_COLUMNS),
                state = rowsState,
                modifier = Modifier.fillMaxSize().keepTopInSight(rowsState),
                // The television's rows are cards, with room on every side to grow a border.
                contentPadding = PaddingValues(start = insets.start, end = insets.end, bottom = insets.bottom),
                horizontalArrangement = Arrangement.spacedBy(TV_CHAT_COLUMN_GAP),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "recent-strip", span = { GridItemSpan(maxLineSpan) }, contentType = "recent-strip") { strip() }
                }
                gridItems(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                    ChatRow(
                        chat = chat,
                        favorite = chat.id in favorites,
                        opensOnLaunch = chat.id == launchChatId,
                        onClick = { open(chat, chat.id) },
                        onHold = { onHold(chat) },
                        modifier = focusOf(chat, inStrip = false).then(backOf(chat.id)),
                    )
                }
                if (paging.loading || paging.failure != null) {
                    item(key = "more-chats", span = { GridItemSpan(maxLineSpan) }, contentType = "more-chats") {
                        MoreChatsFoot(paging, onMoreChats)
                    }
                }
            }

            layout == CardLayout.List -> LazyColumn(
                Modifier.fillMaxSize().keepTopInSight(listState, enabled = !isTouch()),
                state = listState,
                // Rows carry their own side padding on a phone so the ripple reaches both edges, and
                // they sit against each other with no gap: the list is one surface, not a stack of
                // cards. The television keeps both, because its cards need room to grow a border.
                contentPadding = if (rowsAreFullBleed) {
                    PaddingValues(bottom = insets.bottom)
                } else {
                    PaddingValues(start = insets.start, end = insets.end, bottom = insets.bottom)
                },
                verticalArrangement = Arrangement.spacedBy(if (rowsAreFullBleed) 0.dp else 14.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "recent-strip") { strip() }
                }

                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        favorite = chat.id in favorites,
                        opensOnLaunch = chat.id == launchChatId,
                        onClick = { open(chat, chat.id) },
                        onHold = { onHold(chat) },
                        modifier = focusOf(chat, inStrip = false)
                            .then(if (animateRows) Modifier.animateItem() else Modifier),
                    )
                }
                if (paging.loading || paging.failure != null) {
                    item(key = "more-chats", contentType = "more-chats") {
                        MoreChatsFoot(paging, onMoreChats, Modifier.padding(horizontal = if (rowsAreFullBleed) insets.start else 0.dp))
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns = tiles,
                state = gridState,
                modifier = Modifier.fillMaxSize().keepTopInSight(gridState, enabled = !isTouch()),
                // The end inset keeps a focused tile in the last column from having its border
                // clipped by the panel edge.
                contentPadding = PaddingValues(
                    start = insets.start,
                    end = insets.end,
                    top = if (rowsAreFullBleed) 0.dp else Tv.FocusClearance,
                    bottom = insets.bottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(chats, key = { it.id }) { chat ->
                    ChatTile(
                        chat = chat,
                        favorite = chat.id in favorites,
                        opensOnLaunch = chat.id == launchChatId,
                        onClick = { open(chat, chat.id) },
                        onHold = { onHold(chat) },
                        modifier = focusOf(chat, inStrip = false).then(backOf(chat.id)).fillMaxWidth(),
                    )
                }
                if (paging.loading || paging.failure != null) {
                    item(key = "more-chats", span = { GridItemSpan(maxLineSpan) }, contentType = "more-chats") {
                        MoreChatsFoot(paging, onMoreChats)
                    }
                }
            }
        }
        when {
            twoColumns -> ScrollToTop(rowsState)
            layout == CardLayout.List -> ScrollToTop(listState)
            else -> ScrollToTop(gridState)
        }
        // The hold to scroll handle; listed by name, its bubble gives the letter.
        val byId = remember(chats) { chats.associateBy { it.id } }
        val scrollLabel: ((Any) -> String?)? = if (sort != ChatSort.Name) null else { key -> byId[key]?.let { ScrollLabels.letter(it.title) } }
        when {
            twoColumns -> FastScroller(rowsState, label = scrollLabel)
            layout == CardLayout.List -> FastScroller(listState, label = scrollLabel)
            else -> FastScroller(gridState, label = scrollLabel)
        }
        if (rowsAreFullBleed) {
            // The rows pass under the pinned title and chips: a short fade at the top edge lets
            // them go softly rather than being cut by a hard line, once the list has moved.
            val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
            val fade by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, Motion.standard(Motion.Fast), label = "listFade")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LIST_FADE)
                    .graphicsLayer { alpha = fade }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Tone.background, Tone.background.copy(alpha = 0f)))),
            )
        }
    }

    // The remote has nowhere to go until something holds focus. Switching arrangement is included:
    // it rebuilds the list, and the card that was holding focus leaves the composition with it.
    LaunchedEffect(chats.firstOrNull()?.id, recent.firstOrNull()?.id, layout, autoFocus) {
        if (!autoFocus || !mayTakeFocus()) return@LaunchedEffect
        // Back from a chat: its card again, scrolled back into the list if the list went back to
        // its top. Held a few frames against the list's own first claim, as a video's page is
        // (TileReturn). A chat gone from the list (archived, filtered out) falls to the first.
        if (back != null && ChatListReturn.key == back && shownGrid != null) {
            val index = when (back) {
                is Long -> chats.indexOfFirst { it.id == back }.let { if (it < 0) -1 else it + stripLines }
                else -> if (recent.any { "r-${it.id}" == back }) 0 else -1
            }
            if (index >= 0) {
                var settled = 0
                for (frame in 0 until CHAT_RETURN_FRAMES) {
                    withFrameNanos { }
                    if (backOn[0]) {
                        if (++settled >= CHAT_RETURN_SETTLED_FRAMES) break
                        continue
                    }
                    settled = 0
                    val placed = runCatching { backFocus.requestFocus() }.getOrDefault(false)
                    if (!placed) runCatching { shownGrid.scrollToItem(index) }
                }
            }
            ChatListReturn.forget()
            if (backOn[0]) return@LaunchedEffect
        }
        runCatching { first.requestFocus() }
    }
}

/**
 * The chat a television's list was left for, so Back from its videos puts the remote on that card
 * again rather than on the first chat. The list leaves the composition while a chat is open, so
 * this outlives it; plain fields, read once as the list comes back, and forgotten once used or
 * once the viewer goes to a page with no chat list ([BrowseScreen]).
 */
internal object ChatListReturn {
    /** The card's list key: the chat's id, or "r-" and the id for the strip of recent chats. */
    @Volatile var key: Any? = null
    @Volatile var firstIndex: Int = 0
    @Volatile var firstOffset: Int = 0

    fun forget() {
        key = null
        firstIndex = 0
        firstOffset = 0
    }
}

/** Columns of chat rows on a television, and the gap between them. */
private const val TV_CHAT_COLUMNS = 2
private val TV_CHAT_COLUMN_GAP = 20.dp

/** How many frames after coming back from a chat its card is held against other claims. */
private const val CHAT_RETURN_FRAMES = 12

/** Frames in a row the remote has to stay on the card for it to count as back. */
private const val CHAT_RETURN_SETTLED_FRAMES = 3

/**
 * A chat as a tile: the "Jump back in" strip and the grid are the same card at two widths, so
 * [modifier] is what sets that width rather than the tile deciding for itself.
 */
@Composable
private fun ChatTile(
    chat: ChatSummary,
    favorite: Boolean,
    opensOnLaunch: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    PrefetchWhileFocused(chat.id, focused)

    val body: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(Avatar.Card).clip(CircleShape),
            )
            Spacer(Modifier.weight(1f))
            UnreadBadge(chat, small = true)
            if (favorite) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    TmIcons.Star,
                    contentDescription = s.browseFavourite,
                    tint = Tone.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            chat.title,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Ellipsis keeps the row tidy but hides the end of a long channel name. Scrolling it
            // while focused keeps the whole name readable, for that one tile only.
            modifier = Modifier.weight(1f).marqueeWhen(focused),
        )
        Text(
            when {
                focused -> HOLD_HINT
                opensOnLaunch -> s.browseOpensOnLaunch
                else -> chatCaption(chat)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (touch) {
        Card(
            modifier = modifier
                // Fixed, not wrapped: a chat whose name runs to two lines would otherwise stand
                // taller than its neighbours and leave the row visibly ragged.
                .height(RECENT_TILE_HEIGHT)
                .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),
            shape = M3MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Tone.surface),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), content = body)
        }
    } else {
        Column(
            modifier
                .height(RECENT_TILE_HEIGHT)
                .focusGrow(focused, Motion.FocusScale)
                .clip(RoundedCornerShape(Corner.Large))
                .background(if (focused) Tone.surfaceHigh else Tone.surface)
                .border(
                    width = Focus.Edge,
                    color = if (focused) Tone.accent else Color.Transparent,
                    shape = RoundedCornerShape(Corner.Large),
                )
                .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
                .padding(16.dp),
            content = body,
        )
    }
}

/**
 * Grows a card to [scale] while the remote is on it, over [Motion.Fast]: with the ring and the
 * raised fill, the third of the signals that say where focus is. Dropped when the system's
 * animations are off; the ring and the fill still say it.
 */
@Composable
private fun Modifier.focusGrow(focused: Boolean, scale: Float): Modifier {
    val still = rememberReducedMotion()
    val grown by animateFloatAsState(
        targetValue = if (focused && !still) scale else 1f,
        animationSpec = Motion.standard(Motion.Fast),
        label = "focusGrow",
    )
    return graphicsLayer {
        scaleX = grown
        scaleY = grown
    }
}

/** How much a chat row the width of the content grows under focus. */
private const val ROW_FOCUS_SCALE = 1.02f

/** Scrolls overflowing text, but only while [active]; a wall of moving labels is unreadable. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.marqueeWhen(active: Boolean): Modifier =
    if (active) basicMarquee(iterations = Int.MAX_VALUE) else this

/**
 * A chat as a full-bleed row, the way a phone's list of conversations is drawn.
 *
 * On a phone the list is rows on the window background with nothing between them, which is how
 * every phone app whose main screen is a chat list draws it, and it fits far more on a screen. The
 * television keeps cards, where focus has to be visible from a sofa and a border is how that is
 * said.
 */
/**
 * A chat the remote rests on is likely the next one opened: its first page is fetched behind
 * ([com.tmplayer.data.PageQueries]), so its grid opens with it rather than with placeholders.
 */
@Composable
private fun PrefetchWhileFocused(chatId: Long, focused: Boolean) {
    LaunchedEffect(chatId, focused) {
        if (!focused) return@LaunchedEffect
        delay(com.tmplayer.data.PageQueries.PREFETCH_DWELL_MS)
        com.tmplayer.data.PageQueries.prefetchChat(chatId)
    }
}

@Composable
private fun ChatRow(
    chat: ChatSummary,
    favorite: Boolean,
    opensOnLaunch: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    if (isTouch()) {
        TouchChatRow(chat, favorite, opensOnLaunch, onClick, onHold, modifier, interactions)
        return
    }
    PrefetchWhileFocused(chat.id, focused)

    Row(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            // 1.02, not the tiles' 1.05: a row the width of the content grown by that much would
            // reach past the safe area.
            .focusGrow(focused, ROW_FOCUS_SCALE)
            .clip(RoundedCornerShape(Corner.Large))
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .border(
                width = Focus.Edge,
                color = if (focused) Tone.accent else Color.Transparent,
                shape = RoundedCornerShape(Corner.Large),
            )
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaPreview(
            miniThumbnail = chat.miniThumbnail,
            thumbnailFileId = chat.photoFileId,
            fallbackLabel = chat.title,
            modifier = Modifier.size(Avatar.Card).clip(CircleShape),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Text(
                // The row says what it is, then what can be done to it once focus arrives. The
                // launch marker explains why this chat opens straight away on the next start.
                when {
                    focused -> "${chatCaption(chat)}  ·  $HOLD_HINT"
                    opensOnLaunch -> "${chatCaption(chat)}  ·  ${s.browseOpensOnLaunch}"
                    else -> chatCaption(chat)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnreadBadge(chat)
            ChatMarkers(chat, size = 20.dp)
            if (favorite) {
                Icon(
                    TmIcons.Star,
                    contentDescription = s.browseFavourite,
                    tint = Tone.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * What the second line of a chat row says about the chat, beyond what kind of thing it is.
 *
 * Pinned and muted are not spelled out here: [ChatMarkers] draws them as glyphs, where Telegram
 * draws them, which leaves this one line free to say what the chat is.
 */
private fun chatCaption(chat: ChatSummary): String = when (chat.kind) {
    ChatKind.Saved -> L.browseTabSavedHeading
    ChatKind.Channel -> L.browseFilterChannels
    ChatKind.Group -> L.browseFilterGroups
    ChatKind.Direct -> L.browseFilterPeople
}

/**
 * The pin and the silent bell, at the end of the row where a chat list puts them.
 *
 * Both are drawn muted rather than in the accent colour: they are facts about the row, and the one
 * thing on a chat row allowed to ask for attention is the unread count. They trail after that
 * count, which keeps the position nearest the text.
 */
@Composable
private fun ChatMarkers(chat: ChatSummary, size: Dp) {
    val s = LocalStrings.current
    if (chat.isMuted) {
        Icon(
            TmIcons.BellOff,
            contentDescription = s.browseMuted,
            tint = Tone.muted,
            modifier = Modifier.size(size),
        )
    }
    if (chat.isPinned) {
        Icon(
            TmIcons.Pin,
            contentDescription = s.browsePinned,
            tint = Tone.muted,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * The count of unread messages, drawn the way Telegram draws it.
 *
 * A muted chat keeps its count and loses its colour: it still says something arrived, without
 * asking to be looked at. Below one, nothing is drawn at all rather than a zero.
 */
@Composable
private fun UnreadBadge(chat: ChatSummary, small: Boolean = false) {
    if (chat.unreadCount < 1) return
    val fill = if (chat.isMuted) Tone.muted else Tone.accent
    Text(
        // Telegram's own ceiling. Past a few hundred the exact number stops meaning anything and
        // starts widening the pill enough to eat the chat's name.
        text = if (chat.unreadCount > UNREAD_CAP) "$UNREAD_CAP+" else chat.unreadCount.toString(),
        style = if (small) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelLarge
        },
        color = Tone.readableOn(fill),
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = if (small) 6.dp else 10.dp, vertical = if (small) 1.dp else 3.dp),
    )
}

/** Telegram stops counting out loud here too. */
private const val UNREAD_CAP = 999

/**
 * The phone's chat row, which is Material's two-line list item and nothing more.
 *
 * [ListItem] handles the avatar gap, the two type styles and the scheme colours, in a light theme
 * and a dark one alike. The container is transparent so the rows sit on the window the way a
 * phone's list of conversations does, and the height is pinned so the shimmer that stands in for
 * this row lands on the same metric.
 */
@Composable
private fun TouchChatRow(
    chat: ChatSummary,
    favorite: Boolean,
    opensOnLaunch: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier,
    interactions: MutableInteractionSource,
) {
    val s = LocalStrings.current
    ListItem(
        headlineContent = {
            // Two lines, because a channel name is regularly longer than a phone is wide.
            M3Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier
            .fillMaxWidth()
            // A floor rather than a fixed height: a one-line name keeps Material's 72dp row, and
            // only a name that needs the second line makes its row taller.
            .heightIn(min = TOUCH_ROW_HEIGHT)
            // No clip and no background: the row sits on the window, so a press ripples across
            // the whole width of the screen the way a phone's list rows do.
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),
        supportingContent = {
            M3Text(
                // No hold hint on a phone: there is no focused row for it to attach to.
                if (opensOnLaunch) {
                    "${chatCaption(chat)}  ·  ${s.browseOpensOnLaunch}"
                } else {
                    chatCaption(chat)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(TOUCH_AVATAR).clip(CircleShape),
            )
        },
        trailingContent = if (favorite || chat.unreadCount > 0 || chat.isPinned || chat.isMuted) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnreadBadge(chat)
                    ChatMarkers(chat, size = 16.dp)
                    if (favorite) {
                        // Starred a moment ago from the hold menu: the star pops in on a spring.
                        // Any other time (a scroll, a launch) it is simply there.
                        val pop = LocalJustStarred.current == chat.id && !(rememberReducedMotion() || Motion.reduced)
                        val shown = remember { androidx.compose.animation.core.MutableTransitionState(!pop).apply { targetState = true } }
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = shown,
                            enter = androidx.compose.animation.scaleIn(
                                androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                                initialScale = 0.3f,
                            ) + androidx.compose.animation.fadeIn(Motion.standard(Motion.Fast)),
                        ) {
                            M3Icon(
                                TmIcons.Star,
                                contentDescription = s.browseFavourite,
                                tint = Tone.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EmptyTab(
    tab: BrowseSection,
    query: String,
    filter: ChatFilter = ChatFilter.All,
    history: HistoryTab? = null,
    /**
     * Back to every chat, with Telegram's filters cleared: the way on from a tab that fills from
     * the chats (Favourites, Unread, Archived, the History tabs), which on a television saves a
     * trip through the rail. Null where the tab is already all of them.
     */
    onGoToChats: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    // Each empty tab says what to do about it, in its own words. A folder is the one section this
    // app has no way to fill from here: folders are made and edited in Telegram itself, so the
    // message says where to go rather than offering something to press.
    val message = when {
        query.isNotBlank() -> s.browseEmptyNoMatch(query)
        tab is BrowseSection.Folder ->
            s.browseEmptyFolder
        history == HistoryTab.Continue ->
            s.browseEmptyContinue
        history == HistoryTab.Watched ->
            s.browseEmptyWatched
        history == HistoryTab.WatchLater ->
            s.browseEmptyWatchLater
        tab == BrowseSection.of(BrowseTab.Favorites) ->
            if (isTouch()) s.browseEmptyFavouritesTouch else s.browseEmptyFavourites
        tab.isChats && filter == ChatFilter.Unread -> s.browseEmptyUnread
        tab.isChats && filter == ChatFilter.Archived -> s.browseEmptyArchived
        tab == BrowseSection.of(BrowseTab.Saved) ->
            s.browseEmptySaved
        else -> s.browseEmptyOther
    }
    // A search that came back empty is a different situation from a tab with nothing in it yet, so
    // the glyph follows whichever one the viewer is looking at, drawn for the empty page where the
    // tab's own icon would say less (an open folder, a ticked bubble for nothing unread).
    val icon = emptyTabIcon(tab, query, filter, history)
    when {
        // Nothing in the list by that name: the same words across every chat, which also asks
        // Telegram for a chat with that @username.
        query.isNotBlank() -> BigEmpty(message, icon = icon, actionLabel = s.searchAllChats, onAction = { SearchRequest.ask(query) })
        // A folder fills in Telegram and Saved Messages by forwarding, both said in the message.
        tab is BrowseSection.Folder || tab == BrowseSection.of(BrowseTab.Saved) -> BigEmpty(message, icon = icon)
        tab.isChats && filter == ChatFilter.All -> BigEmpty(message, icon = icon)
        else -> BigEmpty(message, icon = icon, actionLabel = onGoToChats?.let { s.browseGoToChats }, onAction = onGoToChats)
    }
}

private const val RECENT_COUNT = 8
private const val FOCUS_FADE_MS = 140

/**
 * Shown on the focused row only.
 *
 * A hold is invisible until someone tries it. Attached to the focused row the hint arrives exactly
 * when it is actionable, and costs no layout: it takes the place of a label the row already had.
 */
private val HOLD_HINT: String get() = L.browseHoldHint
private val RECENT_TILE_WIDTH = 224.dp
private val RECENT_TILE_HEIGHT = 154.dp

/**
 * Columns in the tile arrangement: four, as the video grid has, now the page has the screen's
 * whole width under the top bar.
 */
private const val TILE_COLUMNS = 4

/**
 * The narrowest a tile may be before the grid drops a column.
 *
 * A phone held upright has 360 to 412 dp, less the margins and the gap, so this is what puts two
 * tiles across the narrowest of them (168 dp left a 360 dp phone one tile a row) and four or five
 * across the same phone turned on its side, without either arrangement being spelled out anywhere.
 */
private val TOUCH_TILE_MIN = 150.dp

/**
 * A phone chat row, at Material's list-item metrics.
 *
 * 72 dp with a 56 dp avatar is the two-line list item, and it is what Telegram, Gmail and the
 * dialler all draw. The chat list skeleton has to match this figure.
 */
private val TOUCH_ROW_HEIGHT = 72.dp
private val TOUCH_AVATAR = Avatar.List

/**
 * How far the listing keeps from each edge.
 *
 * A television crops its outermost few percent, so the TV figures are overscan clearance and have
 * nothing to do with taste. A phone crops nothing, so it spends far less of its width on margins.
 */
/**
 * Grows a lazy row by [by] on every side without taking any more room, so it may draw that far past
 * the space it was given. Paired with a content padding of the same size.
 */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    if (extra == 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val wide = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height - extra * 2) { placeable.place(-extra, -extra) }
}

private class BrowseInsets(val start: Dp, val end: Dp, val top: Dp, val bottom: Dp) {
    /** The same insets with [extra] more at the bottom, for something floating over the end of the list. */
    fun clearing(extra: Dp) = BrowseInsets(start, end, top, bottom + extra)
}

/** Under the top bar: the page starts at the bar's first item, right under the bar. */
private val TvInsets = BrowseInsets(start = TV_START, end = Tv.FocusClearance, top = 4.dp, bottom = Tv.SafeV)
private val TouchInsets = BrowseInsets(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

/** The longest chat list whose rows move to a new order rather than being redrawn in it. */
private const val ANIMATED_ROWS_MAX = 300

/** How tall the fade is where the chat list passes under the pinned title and chips. */
private val LIST_FADE = 12.dp

/** The chat just starred from its hold menu, for its row's star to pop in. */
private val LocalJustStarred = androidx.compose.runtime.compositionLocalOf<Long?> { null }

/** [content] for a page's action pill, or none at all where [hidden]. */
private fun unlessHidden(hidden: Boolean, content: @Composable RowScope.() -> Unit): (@Composable RowScope.() -> Unit)? =
    if (hidden) null else content
