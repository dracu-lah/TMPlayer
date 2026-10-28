package com.tmplayer.desktop.ui

import com.tmplayer.ui.browse.continueCardTitle
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.BrowseTab
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Focus
import com.tmplayer.data.DiskInfo
import com.tmplayer.desktop.DesktopPaths
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed as keyCtrl
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import com.tmplayer.ui.components.TmDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.loadWhenShown
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import com.tmplayer.ui.components.FloatingChrome
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.combinedClickable
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.MetaNotice
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredReportOption
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.data.WatchPoint
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.setWatchLater
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.browse.MediaListViewModel
import com.tmplayer.ui.browse.topicMemory
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.online.OnlineArt
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.browse.CourseHeader
import com.tmplayer.ui.browse.rememberRecordItem
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import java.io.File
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.text.font.FontWeight
import com.tmplayer.data.MediaName
import com.tmplayer.data.SeriesShelf
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.ProgressLineInset
import com.tmplayer.ui.components.TileBadge
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.components.PosterArt
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

/**
 * One chat's videos as a grid of posters: the shared [MediaListViewModel] (paging, search, size
 * limits, sponsored messages), [GridCells.Adaptive] on the poster width the toolbar steps, a
 * scrollbar, and the next page fetched as the end comes into view.
 */
@Composable
fun MediaGridPage(state: ShellState, chat: ChatSummary) {
    val s = LocalStrings.current
    val toast = rememberToast()
    val minSize by state.settings.minSizeBytes.collectAsState(initial = null)
    val maxSize by state.settings.maxSizeBytes.collectAsState(initial = null)
    val min = minSize
    val max = maxSize
    // The chat's own bar at the foot takes the floating bar's place; the lists clear it.
    CompositionLocalProvider(com.tmplayer.ui.components.LocalBottomChrome provides ChatBarClearance) {
    Box(Modifier.fillMaxSize()) {
        // Whether this chat's grid was last films and shows, so its placeholders take the shape of
        // the cards about to arrive, poster or wide, when a topic or a refresh loads it again.
        var postersLast by remember(chat.id) { mutableStateOf(false) }
        if (min == null || max == null) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(ChatTopClearance))
                VideoGridSkeleton(CHAT_POSTER_WIDTH, posters = false)
            }
            ChatTopChrome(state, chat)
            return@Box
        }
        val model = rememberViewModel(Triple(chat.id, min, max)) { MediaListViewModel(chat.id, min, max, onSearched = { state.settings.addRecentSearch(it) }, downloadedIds = { DownloadIndex.presentIds(state.settings) }, topicMemory = state.settings.topicMemory()) }
        val ui by model.state.collectAsState()
        var query by remember(chat.id) { mutableStateOf("") }
        var openSeries by remember(chat.id) { mutableStateOf<com.tmplayer.data.Series?>(null) }
        // Where the grid stood when a show was opened over it: the grid is gone while the show is
        // up, so closing the show would otherwise land back at the top of the chat.
        var gridReturn by remember(chat.id) { mutableStateOf<Pair<Int, Int>?>(null) }
        // A show's seasons and episodes take the page, as they do from Home; Close comes back here.
        openSeries?.let { shown ->
            SeriesPage(state, shown, rememberSeriesWatch(state), onClose = { openSeries = null })
            return@Box
        }
        // The grid's keyboard focus, once the grid exists; the search field's Down arrow enters it.
        var gridNav by remember(chat.id) { mutableStateOf<KeyboardNav?>(null) }
        // Typing searches the chat once it rests (MediaListViewModel.type); Enter and a recent
        // search go at once.
        LaunchedEffect(model, query) { model.type(query) }
        val chatSearching by model.searching.collectAsState()
        LaunchedEffect(chat.id) { state.settings.rememberChatOpened(chat.id) }
        // This chat's own order, from the Sort menu in the header.
        val sort by remember(chat.id) { state.settings.videoSort(chat.id) }.collectAsState(initial = com.tmplayer.data.VideoSort.DEFAULT)
        val sortScope = rememberCoroutineScope()
        // Which of its videos are listed (see VideoFilter), remembered per chat beside the order.
        val filter by remember(chat.id) { state.settings.videoFilter(chat.id) }.collectAsState(initial = com.tmplayer.data.VideoFilter.DEFAULT)
        val clearFilter: () -> Unit = { sortScope.launch { state.settings.setVideoFilter(chat.id, com.tmplayer.data.VideoFilter.All) } }
        // Videos outside the size limits, said by an "n hidden" button in the bar for as long as
        // the chat is open, which changes the smallest size there. A search ignores the limits.
        val sizeHidden by model.sizeHidden.collectAsState()
        val shownHidden = if (query.isBlank()) sizeHidden else 0
        var sizePicking by remember(chat.id) { mutableStateOf(false) }
        val sizeAnchor = com.tmplayer.ui.components.rememberPopoverAnchor()

        // The search folds into the bar at the foot: "/", Ctrl+F and the bar's magnifier open it.
        var searching by remember(chat.id) { mutableStateOf(false) }
        DisposableEffect(state, chat.id) {
            state.searchOpener = {
                searching = true
                runCatching { state.searchFocus.requestFocus() }
            }
            onDispose { state.searchOpener = null }
        }
        LaunchedEffect(searching) {
            if (!searching) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            runCatching { state.searchFocus.requestFocus() }
        }
        // F5 and Ctrl+R, the desktop's pull to refresh: Telegram has new posts since the listing ran.
        RegisterRefresh(state) { model.load() }
        val favourites by state.settings.favorites.collectAsState(initial = emptySet())
        val favourite = chat.id in favourites
        var sorting by remember(chat.id) { mutableStateOf(false) }
        val sortAnchor = com.tmplayer.ui.components.rememberPopoverAnchor()
        val recent by state.settings.recentSearches.collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        val chatBar: @Composable () -> Unit = {
            ChatBar(
                state = state,
                searching = searching,
                query = query,
                onQuery = { query = it },
                onSubmit = { model.search(it) },
                busy = chatSearching,
                onOpenSearch = { searching = true },
                onCloseSearch = {
                    query = ""
                    searching = false
                },
                onSearchDown = { gridNav?.focus(0) },
                recent = recent,
                onClearRecent = { scope.launch { state.settings.clearRecentSearches() } },
                favourite = favourite,
                // The star says it, filled or not; a message about it would only repeat that.
                onToggleFavourite = { scope.launch { state.settings.toggleFavorite(chat.id) } },
                onSort = { sorting = true },
                sortAnchor = sortAnchor,
                filtered = filter != com.tmplayer.data.VideoFilter.All,
                sizeHidden = shownHidden,
                onSizeHidden = { sizePicking = true },
                sizeAnchor = sizeAnchor,
            )
        }
        val topicBar by model.topics.collectAsState()
        // The note on where posters come from floats under Back and the chat's name, with them,
        // rather than scrolling up under them as the grid's first row; not while searching.
        val metaNotice = query.isBlank() && ui is UiState.Content && metaNoticeShown(state)
        var noticeHeight by remember { mutableStateOf(0.dp) }
        val noticeRoom = if (metaNotice) noticeHeight + NOTICE_GAP else 0.dp
        val density = androidx.compose.ui.platform.LocalDensity.current
        // With no topics over it, the grid starts at the top of the page and scrolls up under
        // Back and the chat's name, which float over it as on the phone.
        val underChrome = topicBar == null
        Column(Modifier.fillMaxSize()) {
        if (!underChrome || ui !is UiState.Content) Spacer(Modifier.height(ChatTopClearance))
        topicBar?.let { bar ->
            com.tmplayer.ui.browse.TopicTabs(bar, model::selectTopic, Modifier.padding(bottom = 12.dp), edge = pageMargin())
        }
        val skeleton: @Composable () -> Unit = {
            VideoGridSkeleton(if (postersLast) CHAT_POSTER_WIDTH * POSTER_SHARE else CHAT_POSTER_WIDTH, posters = postersLast)
        }
        StateBox(
            ui,
            onRetry = { model.load() },
            onAction = { action ->
                // The same words across every chat: the palette opens on them over this chat.
                if (action == com.tmplayer.ui.components.StateAction.SearchAllChats) state.searchEverywhere(model.searchText) else model.act(action)
            },
            skeleton = skeleton,
        ) { content ->
            // Back from a show over the grid, or else where this listing was left on the last
            // visit, so coming back to a chat finds the same pages at the same place.
            val restored = remember(model) { model.restoredScroll }
            val back = gridReturn ?: restored
            val grid = com.tmplayer.ui.components.rememberAheadGridState(back.first, back.second)
            LaunchedEffect(grid) { gridReturn = null }
            DisposableEffect(model, grid) {
                onDispose { model.rememberScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }
            }
            LoadMoreNearEnd(grid, enabled = !content.endReached && !content.loadingMore) { model.loadMore() }
            val ad = content.sponsored?.messages?.firstOrNull()
            // A show as one poster that opens its seasons, and copies of one film (720p, 1080p,
            // HEVC) as one poster with the rest a pick in its details. While searching, every
            // episode stands on its own so the one searched for can be found.
            val grouping = query.isBlank()
            // The viewer's corrections redraw the grid at once; the chat's own sort applies
            // except while searching, when the listing keeps the search's ranking.
            val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
            val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
            val watchedList by state.watched.watched.collectAsState(initial = emptyMap())
            val shownSort = if (grouping) sort else com.tmplayer.data.VideoSort.DEFAULT
            val shownFilter = if (grouping) filter else com.tmplayer.data.VideoFilter.All
            val progressOf: (com.tmplayer.data.MediaItem) -> com.tmplayer.data.VideoSorting.Progress = { item ->
                val key = SettingsStore.progressKey(item.chatId, item.messageId)
                when {
                    key in watchedList -> com.tmplayer.data.VideoSorting.Progress.Watched
                    key in progress -> com.tmplayer.data.VideoSorting.Progress.InProgress
                    else -> com.tmplayer.data.VideoSorting.Progress.Untouched
                }
            }
            val sortedAll = remember(content.items, grouping, corrections, shownSort, progress, watchedList) {
                val entries = if (grouping) {
                    com.tmplayer.data.SeriesShelf.arrange(content.items, corrections)
                } else {
                    com.tmplayer.data.SeriesShelf.foldFilmCopies(content.items, corrections).map { com.tmplayer.data.ShelfEntry.File(it) }
                }
                com.tmplayer.data.VideoSorting.sort(entries, shownSort, progressOf)
            }
            val arranged = remember(sortedAll, shownFilter, progress, watchedList) {
                com.tmplayer.data.VideoFiltering.filter(sortedAll, shownFilter, progressOf)
            }
            // The newest listing of a chat the kids lock allows, for the name check in Settings.
            LaunchedEffect(content.items) { com.tmplayer.data.NameReport.remember(chat.id, content.items) }
            // Films and shows first as one block of 2:3 posters, then every other video, each in
            // the chat's own order, so tall and short cards meet in one row at most.
            val posters = remember(arranged) {
                arranged.filter(SeriesShelf::isPoster).mapTo(HashSet()) { it.key }
            }
            val cells = remember(arranged, posters) {
                (arranged.filter { it.key in posters } + arranged.filter { it.key !in posters })
                    .map { if (it is com.tmplayer.data.ShelfEntry.Show) it.series.cover else (it as com.tmplayer.data.ShelfEntry.File).item }
            }
            val posterIds = remember(arranged, posters) {
                arranged.filter { it.key in posters }.mapTo(HashSet()) {
                    if (it is com.tmplayer.data.ShelfEntry.Show) it.series.cover.id else (it as com.tmplayer.data.ShelfEntry.File).item.id
                }
            }
            // A chat or a topic that is one course of lessons says how far through it the viewer
            // is, over the posters; not while searching, when the listing is what was searched for.
            val watch = rememberSeriesWatch(state)
            // Off the main thread, as the shelf is: it reads every name through the lesson parser,
            // and runs again whenever anything is watched.
            // Until it has answered the placeholders stay up: the course card arriving after the
            // posters would push them all down a row.
            val courseRead by produceState<Computed<com.tmplayer.data.CourseProgress?>?>(null, content.items, watch, grouping) {
                value = Computed(if (grouping) withContext(Dispatchers.Default) { watch.course(content.items) } else null)
            }
            LaunchedEffect(posterIds) { postersLast = posterIds.isNotEmpty() }
            val read = courseRead ?: run {
                skeleton()
                return@StateBox
            }
            val course = read.value
            // Each cell's entry, for the fast scroller's bubble in the chat's order.
            val entryByKey: Map<Any, com.tmplayer.data.ShelfEntry> = remember(arranged) {
                arranged.associateBy {
                    "media-" + if (it is com.tmplayer.data.ShelfEntry.Show) it.series.cover.id else (it as com.tmplayer.data.ShelfEntry.File).item.id
                }
            }
            val shows = remember(arranged) {
                arranged.filterIsInstance<com.tmplayer.data.ShelfEntry.Show>().associate { it.series.cover.id to it.series }
            }
            // An order other than Newest is of the videos loaded so far while older pages remain.
            val sortedLoaded = com.tmplayer.data.VideoSorting.sortsLoadedOnly(shownSort, content.endReached)
            // A filter in force says so, with Clear filter; when it hides all, it says that instead.
            val filterNote = shownFilter != com.tmplayer.data.VideoFilter.All
            // Self-destructing videos are left out (only Telegram opens them), said in a quiet line.
            val selfDestructing by model.selfDestructing.collectAsState()
            val selfDestructNote = query.isBlank() && selfDestructing > 0
            MetaInviteDialog(state)
            VideoGrid(
                state = state,
                items = cells,
                series = shows,
                onOpenSeries = {
                    gridReturn = grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset
                    openSeries = it
                },
                posters = posterIds,
                chatTitle = chat.title,
                grid = grid,
                onNav = { gridNav = it },
                // Online metadata is on by default, so the first grid says once where posters come
                // from; not while searching, when the listing is what was searched for.
                header = if (ad == null && course == null && !sortedLoaded && !filterNote && !selfDestructNote) null else {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (filterNote) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val label = com.tmplayer.ui.browse.videoFilterLabel(shownFilter, s)
                                    Text(
                                        if (arranged.isEmpty() && sortedAll.isNotEmpty()) s.gridFilterHidesAll(sortedAll.size, label) else s.gridFilterShowing(label, arranged.size, sortedAll.size),
                                        color = Tone.muted,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    OutlinedButton(onClick = clearFilter) { Text(s.gridFilterClear) }
                                }
                            }
                            if (sortedLoaded) {
                                Text(s.gridSortLoadedNote(content.items.size), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                            }
                            if (selfDestructNote) {
                                Text(s.gridSelfDestructingHidden(selfDestructing), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                            }
                            course?.let { CourseHeader(it, topicBar?.let { bar -> bar.nameOf(bar.selected) } ?: chat.title, onPlay = { item -> state.openPlayer(item, startFromBeginning = false) }) }
                            ad?.let { SponsoredCard(it, model) }
                        }
                    }
                },
                loadingMore = content.loadingMore,
                footerSlow = { SlowAnswerLine(content.items.size, onRetry = model::retryMore) },
                scrollLabel = { key -> entryByKey[key]?.let { com.tmplayer.ui.browse.ScrollLabels.video(it, shownSort, s) } },
                // A chat lists its videos only: compressed files are not shown (user, 2026-10-19).
                footer = if (!content.moreFailed) null else {
                    {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The last page failed: said, with Try again, not a listing that stops short.
                            if (content.moreFailed) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.gridMoreFailed, color = Tone.muted)
                                    Spacer(Modifier.width(12.dp))
                                    OutlinedButton(onClick = model::retryMore) { Text(s.commonTryAgain) }
                                }
                            }
                        }
                    }
                },
                topPadding = (if (underChrome) ChatTopClearance else 8.dp) + noticeRoom,
                posterWidth = CHAT_POSTER_WIDTH,
                bottomBar = chatBar,
            )
        }
        }
        ChatTopChrome(state, chat)
        if (metaNotice) {
            MetaNoticeCard(
                state,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, end = 16.dp, top = ChatTopClearance - 4.dp)
                    .widthIn(max = NOTICE_WIDTH)
                    .onSizeChanged { noticeHeight = with(density) { it.height.toDp() } },
            )
        }
        // While there is no grid to carry it (loading, empty, an error), the bar stands on its own.
        if (ui !is UiState.Content) Box(Modifier.align(Alignment.BottomCenter)) { chatBar() }
        // The order and the Show filter in one card, as on the phone.
        com.tmplayer.ui.browse.VideoMenuPopover(
            open = sorting,
            anchor = sortAnchor,
            state = com.tmplayer.ui.browse.VideoMenuState(
                sort = sort,
                filter = filter,
                onSort = { picked ->
                    sorting = false
                    if (picked != sort) sortScope.launch { state.settings.setVideoSort(chat.id, picked) }
                },
                onFilter = { picked ->
                    sorting = false
                    if (picked != filter) sortScope.launch { state.settings.setVideoFilter(chat.id, picked) }
                },
            ),
            onDismiss = { sorting = false },
        )
        com.tmplayer.ui.components.ChoicePopover(
            open = sizePicking,
            anchor = sizeAnchor,
            choices = com.tmplayer.data.SizeFilter.quickMinimums(min).map { bytes ->
                val label = com.tmplayer.data.SizeFilter.label(bytes)
                com.tmplayer.ui.components.PopoverChoice(bytes, if (bytes == com.tmplayer.data.SizeFilter.DEFAULT_MIN) "$label (${s.gridSizeDefault})" else label)
            },
            selected = min,
            onPick = { picked ->
                sizePicking = false
                val clamped = com.tmplayer.data.SizeFilter.clampMin(picked, max)
                if (clamped != min) sortScope.launch { runCatching { state.settings.setMinSizeBytes(clamped) }.onFailure { toast(com.tmplayer.data.Failures.humanise(it)) } }
            },
            onDismiss = { sizePicking = false },
            title = s.settingsSmallest,
        )
    }
    }
}

/**
 * A chat's posters in an adaptive grid with a scrollbar, the arrow keys of [KeyboardNav] over
 * them, an optional full width [header] (the sponsored message) and a spinner row while the next
 * page loads. Split from [MediaGridPage] so the UI tests can drive it without TDLib.
 */
@Composable
internal fun VideoGrid(
    state: ShellState,
    items: List<MediaItem>,
    chatTitle: String,
    grid: LazyGridState = com.tmplayer.ui.components.rememberAheadGridState(),
    onNav: (KeyboardNav) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    loadingMore: Boolean = false,
    /** Shows by the id of their cover, the item standing in for each in [items]. */
    series: Map<String, com.tmplayer.data.Series> = emptyMap(),
    onOpenSeries: (com.tmplayer.data.Series) -> Unit = {},
    /** A video's own mark for its poster's top end corner, a "New" say, or null for none. */
    badge: (MediaItem) -> (@Composable () -> Unit)? = { null },
    /**
     * The ids of the cells drawn as 2:3 posters, films and shows. With any, every cell takes a
     * poster's width (about three quarters of [ShellState.posterWidth]), so the arrow keys still
     * move one column at a time; the videos after the posters are then smaller 16:9 cards.
     */
    posters: Set<String> = emptySet(),
    /** Room over the first row: more where the page's own buttons float over the grid's top. */
    topPadding: Dp = 8.dp,
    /** The width a tile asks for; a chat's page draws its posters larger than the other grids. */
    posterWidth: Dp = state.posterWidth,
    /** The page's own bar at the foot, which a selection's bar takes the place of while it is up. */
    bottomBar: (@Composable () -> Unit)? = null,
    /** Full width under the last cell: a failed page's Try again, the chat's archives. */
    footer: (@Composable () -> Unit)? = null,
    /** Under the next page's spinner once it runs long: [SlowAnswerLine] with its Try again. */
    footerSlow: (@Composable () -> Unit)? = null,
    /** The fast scroller's bubble for a cell's key (`media-<id>`), or null for none. */
    scrollLabel: ((Any) -> String?)? = null,
) {
    val watch = if (series.isEmpty()) null else rememberSeriesWatch(state)
    // Every file on its own, episodes included: a chat lists what was posted, one by one.
    val cells by rememberUpdatedState(items)
    val headerItems by rememberUpdatedState(if (header != null) 1 else 0)
    val nav = rememberKeyboardNav(remember(grid) { GridSurface(grid, { headerItems }, { cells.size }) })
    LaunchedEffect(nav) { onNav(nav) }
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val index = remember(history) { history.associateBy { "${it.chatId}:${it.messageId}" } }
    val selection = remember(grid) { GridSelection { cells.getOrNull(it)?.id } }
    BackHandler(enabled = selection.active) { selection.clear() }
    Box(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalGridSelection provides selection, LocalDownloadIndex provides index) {
        LazyVerticalGrid(
            columns = when {
                posters.isEmpty() -> GridCells.Adaptive(posterWidth)
                else -> GridCells.Adaptive(posterWidth * POSTER_SHARE)
            },
            state = grid,
            // On the page's margin, as the heading and the chips are; the end keeps room for the scrollbar.
            contentPadding = PaddingValues(
                start = pageMargin(),
                end = pageMargin() + 8.dp,
                top = topPadding,
                bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().navKeys(nav) { cells.size }.remembersRemoteItem(),
        ) {
            if (header != null) {
                item(key = "sponsored", span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            itemsIndexed(items, key = { _, it -> "media-${it.id}" }) { index, item ->
                val show = series[item.id]
                // A new order (a sort, a correction) moves the cards to their places.
                Box(Modifier.animateItem()) {
                    if (show != null && watch != null) {
                        SeriesPoster(show, watch, onOpen = { onOpenSeries(show) }, nav = nav, index = index, poster = item.id in posters)
                    } else {
                        MediaTile(state, item, chatTitle, nav, index, badge = badge(item), poster = item.id in posters)
                    }
                }
            }
            if (loadingMore) {
                item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        footerSlow?.invoke()
                    }
                }
            }
            if (footer != null) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
            }
        }
      }
        // The selection bar takes the bottom centre while it is up.
        if (!selection.active) ScrollToTop(grid)
        // The hold to scroll handle on a long chat, its bubble saying where in the chat's order the
        // grid is; a short one keeps the scrollbar.
        FastScroller(grid, label = scrollLabel) {
            VerticalScrollbar(
                rememberScrollbarAdapter(grid),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            )
        }
        if (selection.active) {
            SelectionBar(state, selection, items, chatTitle, index, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        } else if (bottomBar != null) {
            Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
        }
    }
}

/** Calls [loadMore] once the last few posters are on screen. */
@Composable
internal fun LoadMoreNearEnd(grid: LazyGridState, enabled: Boolean, loadMore: () -> Unit) =
    com.tmplayer.ui.components.LoadMoreAhead(grid, enabled, ahead = LOAD_AHEAD - 1, onLoadMore = loadMore)

/** The toolbar's poster size step: smaller and larger, through [POSTER_STEPS]. */
@Composable
fun PosterSizeStep(state: ShellState) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TipIconButton(TmIcons.Grid, s.browseSmallerPosters, { state.stepPoster(-1) }, enabled = state.posterWidth != POSTER_STEPS.first())
        TipIconButton(TmIcons.Add, s.browseLargerPosters, { state.stepPoster(1) }, enabled = state.posterWidth != POSTER_STEPS.last())
    }
}

/**
 * History's Continue tab: what the viewer was part way through, newest first, as posters.
 * The records keep no artwork, so each card asks Telegram for its video as Home does
 * ([rememberRecordItem]) and wears the thumbnail, with the generated cover only without one.
 * [tabs] is History's tab row, drawn under the heading.
 */
@Composable
fun ContinuePage(state: ShellState, tabs: @Composable () -> Unit = {}) {
    val s = LocalStrings.current
    val stored by state.settings.continueWatching.collectAsState(initial = null)
    // The copies of one film or episode as one card, the one watched last, as on Home.
    val records = remember(stored, state.historySort) { stored?.let(com.tmplayer.data.HomeRows::foldCopies)?.let { historyOrder(state, it) { record -> record.toMediaItem() } } }
    val scope = rememberCoroutineScope()
    // Remove from history, one card, the ticked ones or all of them: at once, with Undo.
    val toast = rememberToast()
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val removeCards: (List<ResumeRecord>) -> Unit = { cards ->
        runHistoryEdit(scope, state, undoToast, toast, { s.browseRemovedFromHistory(it) }, s.browseUndo) { st, w ->
            com.tmplayer.data.HistoryEdits.removeContinueCards(st, w, cards)
        }
    }
    val clearAll: () -> Unit = {
        runHistoryEdit(scope, state, undoToast, toast, { s.browseHistoryCleared }, s.browseUndo) { st, w -> com.tmplayer.data.HistoryEdits.clear(st, w) }
    }
    // The cards in the grid's order (the day headings' order), by the id a poster is ticked by.
    val gridOrder = remember(records) { records?.let { list -> daySections(list, System.currentTimeMillis()) { it.updatedAt }.sections.flatMap { it.second } }.orEmpty() }
    val ids = remember(gridOrder) { gridOrder.map { "${it.chatId}:${it.messageId}" } }
    val currentIds by rememberUpdatedState(ids)
    val selection = remember { GridSelection(history = true) { currentIds.getOrNull(it) } }
    LaunchedEffect(ids) { if (ids.isEmpty()) selection.clear() else selection.keepOnly(ids.toSet()) }
    BackHandler(enabled = selection.active) { selection.clear() }
    val removeTicked: () -> Unit = {
        val cards = gridOrder.filter { selection.isSelected("${it.chatId}:${it.messageId}") }
        selection.clear()
        if (cards.isNotEmpty()) removeCards(cards)
    }
    Column(Modifier.fillMaxSize().historyKeys(selection, { currentIds }, removeTicked)) {
        PageHeader(
            BrowseTab.History.heading,
            HistoryTab.Continue.blurb,
            actions = {
                if (!records.isNullOrEmpty()) {
                    if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }
                    OutlinedButton(onClick = clearAll) { Text(s.browseClearHistory) }
                }
            },
        )
        tabs()
        val list = records
        when {
            list == null -> VideoGridSkeleton(state.posterWidth * CONTINUE_WIDTH, posters = false, nameBelow = false)
            list.isEmpty() -> EmptyNote(HistoryTab.Continue.emptyIcon, s.continueEmpty, s.browseGoToChats) { state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats)) }
            else -> {
                val grid = com.tmplayer.ui.components.rememberAheadGridState()
                val records by rememberUpdatedState(list)
                // Today, Yesterday and Earlier over the cards, by when each was last played.
                val days = remember(list) { daySections(list, System.currentTimeMillis()) { it.updatedAt } }
                val currentDays by rememberUpdatedState(days)
                val nav = rememberKeyboardNav(remember(grid) { SectionedGridSurface(grid) { currentDays.gridIndex } })
                Box(Modifier.fillMaxSize()) {
                  CompositionLocalProvider(LocalGridSelection provides selection) {
                    LazyVerticalGrid(
                        // A quarter wider than a poster: the card carries its title on the art.
                        columns = GridCells.Adaptive(state.posterWidth * CONTINUE_WIDTH),
                        state = grid,
                        contentPadding = PaddingValues(start = pageMargin(), end = pageMargin() + 8.dp, top = 8.dp, bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().navKeys(nav) { records.size }.remembersRemoteItem(),
                    ) {
                        daySectionItems(days, key = { "${it.chatId}:${it.messageId}" }) { index, record, _ ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            ContinueTile(
                                state, record, nav, index,
                                onMarkWatched = {
                                    // On the page's scope: marking takes the card off this page,
                                    // which is all the answer it needs.
                                    val item = record.toMediaItem()
                                    scope.launch {
                                        runCatching { setWatched(state.watched, state.settings, item, record.chatTitle, watched = true) }
                                    }
                                },
                            ) {
                                removeCards(listOf(record))
                            }
                        }
                    }
                  }
                    if (selection.active) {
                        HistorySelectionBar(selection, ids, removeTicked, Modifier.align(Alignment.BottomCenter).padding(bottom = footClearance()))
                    } else {
                        ScrollToTop(grid)
                    }
                    // The hold to scroll handle on a long list, the scrollbar on a short one.
                    FastScroller(grid, label = days.scrollLabels({ "${it.chatId}:${it.messageId}" }, { it.title }, state.historySort == com.tmplayer.ui.browse.ChatSort.Name)) {
                        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/**
 * A video part way through, as a card a quarter wider than a poster: the art with the episode
 * ("S1 E3") and the title written on it over the tile's foot scrim, how long is left under it, and
 * the progress line at its foot. The art is Home's: the show's or the film's picture where
 * allowed, the video's thumbnail, and its [GeneratedCover] only when it has none.
 */
@Composable
private fun ContinueTile(
    state: ShellState,
    record: ResumeRecord,
    nav: KeyboardNav?,
    index: Int,
    onMarkWatched: () -> Unit,
    onForget: () -> Unit,
) {
    val s = LocalStrings.current
    // The video as Telegram describes it once it answers, for its thumbnail, as on Home.
    val item = rememberRecordItem(record.chatId, record.messageId, remember(record) { record.toMediaItem() })
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = record.chatTitle,
        progress = record.fraction,
        art = { OnHome { ContinueArt(item) } },
        // The name under the art, as on every other card, and none on the picture.
        titleText = remember(item, s) { continueCardTitle(item, s) },
        subtitle = s.continueLeft(record.chatTitle, MediaMapper.formatDuration((record.remainingMs / 1000).toInt())),
        extraMenu = { close ->
            // Marking also forgets the position, so the card leaves this page with it.
            DropdownMenuItem(text = { Text(WatchedWords.markLabel(onList = false)) }, onClick = {
                close()
                onMarkWatched()
            })
            DropdownMenuItem(text = { Text(s.browseRemoveFromHistory) }, onClick = { close(); onForget() })
        },
        markToggle = false,
    )
}

/**
 * A continue card's art: the picture, or the cover with no name on it. The name is written under
 * the card ([continueCardTitle]), so it is not on the picture as well.
 */
@Composable
private fun ContinueArt(item: MediaItem) {
    val cover = rememberCover(item)
    OnlineArt(item, Modifier.fillMaxSize()) {
        MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
            GeneratedCover(cover, Modifier.fillMaxSize(), showTitle = false)
        }
    }
}

/**
 * The art of a saved record's poster (Watched, Watch later): the thumbnail when the item carries
 * one, else the video's [GeneratedCover], rather than a play mark on a grey square.
 */
@Composable
internal fun RecordArt(item: MediaItem) {
    MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize(), cover = rememberCover(item))
}

/** A video in a chat's grid. */
@Composable
internal fun MediaTile(
    state: ShellState,
    item: MediaItem,
    chatTitle: String,
    nav: KeyboardNav? = null,
    index: Int = 0,
    /** On Home or in the search across chats, where the pane offers to go to the chat. */
    outsideChat: Boolean = false,
    /** The caller's own mark for the art's top end corner, a "New" say. */
    badge: (@Composable () -> Unit)? = null,
    /** A film's 2:3 poster card ([SeriesShelf.isFilm]); the caller says, as only it knows the grid. */
    poster: Boolean = false,
) {
    val s = LocalStrings.current
    // The grid's selection, when there is one, so the pane can start one with this poster.
    val selection = LocalGridSelection.current?.takeIf { item.canBeSaved || it.history }
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val key = SettingsStore.progressKey(item.chatId, item.messageId)
    val point: WatchPoint? = progress[key]
    val watched by state.watched.watched.collectAsState(initial = emptyMap())
    // A split film is watched once its last piece is.
    val finished = SettingsStore.progressKey(item.watchedBy.chatId, item.watchedBy.messageId) in watched
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = chatTitle,
        // Only a saved place draws the line; a finished video says so with its tick.
        progress = point?.fraction,
        finished = finished,
        badge = badge,
        poster = poster,
        onDetails = { refocus ->
            state.openDetail(
                DetailRequest(
                    item = item,
                    chatTitle = chatTitle,
                    outsideChat = outsideChat,
                    selectThis = selection?.let { picking -> { if (!picking.isSelected(item.id)) picking.toggle(index) } },
                    onClosed = refocus,
                ),
            )
        },
        art = {
            val meta = com.tmplayer.ui.online.rememberMeta(item, showOnly = true)
            Box(Modifier.fillMaxSize()) {
                if (poster) {
                    PosterArt(item.miniThumbnail, item.thumbnailFileId, rememberCover(item), meta?.posterUrl, Modifier.fillMaxSize())
                } else {
                    // On Home, the show's or the film's own picture over the frame once a match arrives.
                    com.tmplayer.ui.online.OnlineArt(item, Modifier.fillMaxSize()) {
                        MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize(), cover = rememberCover(item))
                    }
                }
                // The title's one score at the bottom end, only when it is already on the device;
                // clear of the progress line on the art's foot.
                com.tmplayer.ui.online.CardScore(meta, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (point?.fraction != null) 14.dp else 8.dp))
            }
        },
        subtitle = buildString {
            // A poster leads with the film's year and leaves the quality to the chip on its art.
            if (poster) item.parsedName.year?.let { append(it).append("  ·  ") }
            if (!poster && item.durationSec > 0) append(MediaMapper.formatDuration(item.durationSec)).append("  ·  ")
            append(if (item.versions.size > 1) s.detailVersions(item.versions.size) else com.tmplayer.data.SplitPieces.sizeFact(item, MediaMapper.formatSize(item.sizeBytes)))
            if (!poster) item.qualityTags.firstOrNull()?.let { append("  ·  ").append(it) }
            if (WatchedWords.showsWatched(point?.fraction, finished)) append("  ·  ").append(s.watchedBadge)
        },
    )
}

/**
 * The poster every grid draws: art at 16:9 with no panel round it, a progress line on its foot, a
 * line of name and a line of facts under it. Hovering for a moment lifts it by [Motion.FocusScale]
 * and shows Play and the overflow; a right click (Ctrl+click on macOS) opens the same overflow
 * where the pointer is. The line of facts goes from muted to the full text colour while lifted.
 *
 * One badge in the art's top start corner at most: selected, else the download (kept, coming, or
 * cached), else the watched tick. [badge] is the caller's own for the top end corner, which the
 * overflow button takes over while the poster is lifted.
 *
 * From the keyboard ([nav], B2.1): focus draws a ring and lifts it like a hover, the arrows move
 * between posters, Enter, Space or P plays, and the context menu key or Shift+F10 opens the
 * overflow.
 *
 * With [ShellState.detailFirst] on (the default, as on the phone and the television) a click and
 * Enter open the video's detail pane instead, which has Play on it; a double click, Space, P and
 * the Play button shown on hover still play at once.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun Poster(
    state: ShellState,
    nav: KeyboardNav?,
    index: Int,
    item: MediaItem,
    chatTitle: String,
    progress: Float?,
    art: @Composable () -> Unit,
    subtitle: String,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** On the Watched list, or marked watched: the tick, when no other badge has the corner. */
    finished: Boolean = false,
    /** False for a card that writes its title on the art itself, and keeps only [subtitle] under it. */
    titleBelow: Boolean = true,
    /** The name under the art in place of the file's own, for a card that knows better (Continue watching's "S1 E3  ·  Show"). */
    titleText: String? = null,
    badge: (@Composable () -> Unit)? = null,
    /** A film's 2:3 poster card rather than a video's 16:9 one, named without its year. */
    poster: Boolean = false,
    /** Whether the menu offers "Mark as watched" or "Mark as unwatched" by [finished]. */
    markToggle: Boolean = true,
    /** The menu's play, download and link lines; off for a page whose [extraMenu] is the menu. */
    fileMenu: Boolean = true,
    /**
     * Opens the detail pane in place of the menu: a right click, the context menu key, I and the
     * overflow button all come here. Handed a way to put focus back on this poster when it closes.
     * Null keeps the menu, as Continue watching and Watched do.
     */
    onDetails: ((refocus: () -> Unit) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The lift and Play answer the pointer at once (a 300 ms wait read as a slow app, user,
    // 2026-10-27); only the stream's warm-up waits for the pointer to stay, so one passing over
    // on its way somewhere else starts no download.
    val lifted = hovered
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(HOVER_INTENT_MS)
            state.warmStream(item)
        }
    }
    var hasFocus by remember { mutableStateOf(false) }
    // The ring, the lift and the Play button are the keyboard's: back from the detail pane or a
    // menu the poster has focus again, and after a click that must not look like a hover stuck on.
    val focused = hasFocus && com.tmplayer.ui.theme.FocusVisibility.visible
    val scale by animateFloatAsState(
        targetValue = if ((lifted || focused) && !rememberReducedMotion()) Motion.FocusScale else 1f,
        animationSpec = Motion.standard(Motion.Fast),
    )
    val ink by animateColorAsState(
        targetValue = if (lifted || focused) Tone.text else Tone.muted,
        animationSpec = Motion.standard(Motion.Fast),
    )
    var menu by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    // A video its chat will not let anyone keep cannot be picked for Download selected.
    val selection = LocalGridSelection.current?.takeIf { item.canBeSaved || it.history }
    // Set by a Ctrl or Shift press just before the click it belongs to, so that click picks the
    // poster instead of playing it.
    var picking by remember { mutableStateOf(false) }
    // On a History page in select mode a plain click ticks the card, as a tap does on the phone.
    val ticksOnClick = selection?.history == true && selection.active
    val play: () -> Unit = {
        if (picking && selection != null) {
            picking = false
        } else if (ticksOnClick) {
            selection?.toggle(index)
        } else {
            state.openPlayer(item, startFromBeginning = false)
        }
    }
    // This poster's own entry only, as the phone's tile reads it: the map changes with every
    // download's progress, and reading all of it recomposed every poster on the page each time.
    val downloads = OfflineDownloads.active.collectAsState()
    val downloadNow by remember(item.fileId) { androidx.compose.runtime.derivedStateOf { downloads.value[item.fileId] } }
    val download = downloadNow
    val record = LocalDownloadIndex.current[item.id]
    val selected = selection?.isSelected(item.id) == true
    val details: (() -> Unit)? = onDetails?.let { open -> { open { runCatching { requester.requestFocus() } } } }
    // A click's and Enter's: the detail pane first, by Settings; a page that keeps the menu for
    // its own lines (Continue watching, Watched) still opens the pane here.
    val page: () -> Unit = details ?: {
        state.openDetail(DetailRequest(item = item, chatTitle = chatTitle, outsideChat = true, onClosed = { runCatching { requester.requestFocus() } }))
    }
    val detailFirst = state.detailFirst
    val click: () -> Unit = {
        if (detailFirst && !(picking && selection != null) && !ticksOnClick) page() else play()
    }
    val openMenu: () -> Unit = {
        if (details != null) {
            details()
        } else {
            menu = true
        }
    }

    val behaviour = Modifier
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event ->
                val macContext = IS_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed || macContext) openMenu()
            }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (selection == null || !event.buttons.isPrimaryPressed) return@onPointerEvent
                val mods = event.keyboardModifiers
                val toggle = if (IS_MAC) mods.isMetaPressed else mods.isCtrlPressed
                when {
                    mods.isShiftPressed -> {
                        picking = true
                        selection.extend(index)
                    }
                    toggle -> {
                        picking = true
                        selection.toggle(index)
                    }
                    else -> picking = false
                }
            }
            .then(if (nav != null) Modifier.navCell(nav, index, requester) else Modifier.focusRequester(requester))
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                when {
                    GridNav.isMenuKey(event) -> {
                        openMenu()
                        true
                    }
                    // I for "info", the detail pane, as the remote's info key opens it on a TV.
                    details != null && event.type == KeyEventType.KeyDown && event.key == Key.I && !event.keyCtrl &&
                        !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed -> {
                        details()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key in PLAY_KEYS && !event.keyCtrl &&
                        !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed -> {
                        if (detailFirst && (event.key == Key.Enter || event.key == Key.NumPadEnter)) page() else play()
                        true
                    }
                    else -> false
                }
            }
            // No hover or press plate: the lift and the ring on the art are the feedback.
            .then(
                if (detailFirst) {
                    Modifier.combinedClickable(interactionSource = interaction, indication = null, onDoubleClick = play, onClick = click)
                } else {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = play)
                },
            )
    Column(
        behaviour,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .padding(bottom = 4.dp)
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(MaterialTheme.shapes.medium)
                .then(if (focused || selected) Modifier.border(Focus.Ring, Tone.accent, MaterialTheme.shapes.medium) else Modifier),
        ) {
            art()
            if (progress != null && progress > 0f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            }
            val corner = Modifier.align(Alignment.TopStart).padding(TileBadgeInset)
            when {
                selected -> TileBadge(s.browseBadgeSelected, corner, icon = TmIcons.CheckCircle)
                record != null -> TileBadge(s.downloadsDownloaded, corner, icon = TmIcons.CheckCircle)
                // Every stage the queue can be in, as the phone's tiles say it: a failed download
                // shows on its tile in red rather than looking like nothing was asked for.
                download != null -> TileBadge(
                    when (download.stage) {
                        OfflineDownloads.Stage.Running -> download.fraction?.let { s.formatter.percent(it.toDouble()) } ?: s.gridStageDownloading
                        OfflineDownloads.Stage.Queued -> s.gridStageQueued
                        OfflineDownloads.Stage.Paused -> s.gridStagePaused
                        OfflineDownloads.Stage.Offline -> s.gridStageWaiting
                        OfflineDownloads.Stage.NoWifi -> s.gridStageNoWifi
                        OfflineDownloads.Stage.Moving -> s.gridStageMoving
                        OfflineDownloads.Stage.Failed -> s.gridStageFailed
                    },
                    corner,
                    icon = TmIcons.Download,
                    fill = if (download.stage == OfflineDownloads.Stage.Failed) Tone.danger.copy(alpha = 0.92f) else com.tmplayer.ui.components.TileBadgeFill,
                )
                item.onDevice -> TileBadge(s.browseBadgeCached, corner, icon = TmIcons.Download)
                finished -> WatchedBadge(corner)
            }
            val showing = lifted || menu || focused
            // History's tick box: on hover, and on every card once select mode is on. A click on it
            // ticks the card without opening anything; Ctrl and Shift clicks still pick as anywhere.
            if (selection?.history == true && (showing || selection.active)) {
                TickBox(selected, Modifier.align(Alignment.TopStart).padding(TileBadgeInset)) { selection.toggle(index) }
            }
            // The top end corner: the caller's mark, else on a poster the quality its name states.
            val quality = item.qualityTags.firstOrNull()
            if (!showing) when {
                badge != null -> Box(Modifier.align(Alignment.TopEnd).padding(TileBadgeInset)) { badge() }
                // Only on its own: two chips across a narrow poster would meet in the middle.
                poster && quality != null && !selected && record == null && download == null && !item.onDevice && !finished -> TileBadge(quality, Modifier.align(Alignment.TopEnd).padding(TileBadgeInset))
            }
            if (showing) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                // The tooltip wraps the whole disc: inside it the glyph sat in a top-left Box and the
                // Play drew 2 dp up and left of the circle's middle.
                Box(Modifier.align(Alignment.Center)) {
                    BarTooltip(s.playerPlay, null) {
                        Surface(
                            shape = CircleShape,
                            color = Tone.accent,
                            modifier = Modifier.size(48.dp).clickable { state.openPlayer(item, startFromBeginning = false) },
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(TmIcons.PlaySolid, contentDescription = s.playerPlay, tint = Tone.onAccent, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    BarTooltip(s.commonMore, null) {
                        IconButton(onClick = { openMenu() }) {
                            Icon(TmIcons.MoreVert, contentDescription = s.commonMore, tint = Color.White)
                        }
                    }
                }
            }
            if (details == null) Box(Modifier.align(Alignment.TopEnd)) {
                TileMenu(state, item, chatTitle, expanded = menu, onDismiss = {
                    menu = false
                    // Back to the poster, so the arrows carry on from where the menu was opened.
                    if (hasFocus || nav?.current == index) runCatching { requester.requestFocus() }
                }, extra = extraMenu, watchedToggle = if (markToggle) finished else null, fileMenu = fileMenu)
            }
        }
        // The name cleaned of its release tags, a film of several copies by the film's own name
        // (see SeriesShelf.tileTitle); the detail pane keeps the whole file name.
        if (titleBelow) {
            val title = remember(item, poster, titleText) { titleText ?: if (poster) SeriesShelf.posterTitle(item) else SeriesShelf.tileTitle(item) }
            if (poster) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            subtitle,
            style = if (poster) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Download (12.3 GB free)", or the bare label while the disk has not answered. */
internal fun withFree(label: String, freeBytes: Long): String =
    DiskInfo.freeLabel(freeBytes)?.let { L.commonWithDetail(label, it) } ?: label

/**
 * Play, Play from start, the one download entry for where the video is (Download when it is on
 * Telegram only, Save to Downloads when it is cached, Downloading while it is queued, In Downloads
 * and Remove from Downloads once it is kept), Open in another app for a whole file, Copy link.
 */
@Composable
internal fun TileMenu(
    state: ShellState,
    item: MediaItem,
    chatTitle: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    extra: (@Composable (close: () -> Unit) -> Unit)?,
    /** Null leaves the mark line out; otherwise whether the video is on the Watched list. */
    watchedToggle: Boolean? = null,
    fileMenu: Boolean = true,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val downloads by OfflineDownloads.active.collectAsState()
    val record = LocalDownloadIndex.current[item.id]
    val row = downloads[item.fileId]
    val title = chatTitle.ifBlank { state.chatTitleOf(item.chatId) }
    val laterKeys by state.watched.watchLater.keys.collectAsState(initial = emptySet())
    val later = SettingsStore.progressKey(item.chatId, item.messageId) in laterKeys
    // Measured each time the menu opens, off the UI thread, for the "x GB free" on the download line.
    val free by produceState(0L, expanded) {
        if (expanded) value = withContext(Dispatchers.IO) { DiskInfo.of(DesktopPaths.downloadsDir).freeBytes }
    }

    // Held here rather than in the menu, which is gone by the time the prompt is answered.
    var confirmingRemove by remember { mutableStateOf<ResumeRecord?>(null) }
    confirmingRemove?.let { doomed ->
        ConfirmDialog(
            title = s.gridRemoveDownloadTitle,
            message = s.gridRemoveDownloadMessage(item.title),
            confirmLabel = s.commonRemove,
            onDismiss = { confirmingRemove = null },
            onConfirm = {
                confirmingRemove = null
                scope.launch {
                    if (DownloadIndex.delete(state.settings, doomed)) {
                        toast(s.downloadsRemoved(item.title))
                    } else {
                        toast(s.downloadsInUse)
                    }
                }
            },
        )
    }

    TmDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
      if (!fileMenu) {
        extra?.invoke(onDismiss)
      } else {
        DropdownMenuItem(text = { Text(s.playerPlay) }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = false) })
        DropdownMenuItem(text = { Text(s.playerPlayFromStart) }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = true) })
        // Beside the play lines, because it is about watching rather than about the file. Marking
        // also forgets the saved position, so the video leaves Continue watching with it.
        if (watchedToggle != null) {
            DropdownMenuItem(text = { Text(WatchedWords.markLabel(watchedToggle)) }, onClick = {
                onDismiss()
                val mark = !watchedToggle
                // Not said: the tick on the card shows it.
                scope.launch { runCatching { setWatched(state.watched, state.settings, item, title, mark) } }
            })
        }
        // Nothing is copied, so this stays for a chat that restricts saving.
        DropdownMenuItem(text = { Text(if (later) s.detailWatchLaterRemove else s.detailWatchLaterAdd) }, onClick = {
            onDismiss()
            scope.launch {
                runCatching { setWatchLater(state.watched, item, title, add = !later) }
                toast(if (later) s.detailWatchLaterRemoved(item.title) else s.detailWatchLaterAdded(item.title))
            }
        })
        when {
            record != null -> {
                DropdownMenuItem(text = { Text(s.downloadsInDownloads) }, onClick = { onDismiss(); state.go(Destination.Downloads) })
                DropdownMenuItem(text = { Text(s.downloadsRemove) }, onClick = {
                    onDismiss()
                    confirmingRemove = record
                })
            }
            row != null && row.busy -> {
                DropdownMenuItem(text = { Text(s.downloadsDownloadingEllipsis) }, onClick = { onDismiss(); state.go(Destination.Downloads) })
                DropdownMenuItem(text = { Text(s.downloadsCancel) }, onClick = {
                    onDismiss()
                    OfflineDownloads.cancel(state.downloads, item.fileId)
                })
            }
            // A chat with "restrict saving content" lets its videos be watched and nothing more,
            // so neither a download nor, below, a hand off to another app is offered.
            !item.canBeSaved -> Unit
            item.onDevice -> DropdownMenuItem(text = { Text(withFree(s.downloadsSave, free)) }, onClick = {
                onDismiss()
                // Not said: the card shows the queue's state, and the Downloads button its count.
                OfflineDownloads.start(state.downloads, item, title)
            })
            else -> DropdownMenuItem(text = { Text(withFree(s.downloadsDownload, free)) }, onClick = {
                onDismiss()
                OfflineDownloads.start(state.downloads, item, title)
            })
        }
        if (item.canBeSaved && (record != null || item.onDevice)) {
            DropdownMenuItem(text = { Text(s.commonOpenElsewhere) }, onClick = {
                onDismiss()
                scope.launch {
                    // A tile built from a saved record (Continue watching) does not know the
                    // chat's setting, so the cached copy is only handed over once Telegram agrees.
                    if (record == null && !Td.maySave(item.chatId, item.messageId)) {
                        toast(ContentProtection.NOT_SAVABLE)
                        return@launch
                    }
                    val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                        ?: runCatching { Td.localFilePath(Td.currentFileId(item.chatId, item.messageId, item.fileId)) }
                            .getOrNull()?.let(::File)
                    if (file == null) toast(s.downloadsFileGone) else OpenExternal.open(file)
                }
            })
        }
        DropdownMenuItem(text = { Text(s.commonCopyLink) }, onClick = {
            onDismiss()
            scope.launch {
                val link = runCatching {
                    Td.client.getMessageLink(item.chatId, item.messageId, 0, 0, "", false, false).valueOrNull?.link
                }.getOrNull()
                if (link.isNullOrBlank()) {
                    toast(s.commonNoLinks)
                } else {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
                    toast(s.commonLinkCopied)
                }
            }
        })
        extra?.invoke(onDismiss)
      }
    }
}

/** Telegram's sponsored message for a channel, shown above its videos as the phone shows it. */
@Composable
private fun SponsoredCard(ad: SponsoredItem, model: MediaListViewModel) {
    val s = LocalStrings.current
    val toast = rememberToast()
    var reportOptions by remember(ad.messageId) { mutableStateOf<Pair<String, List<SponsoredReportOption>>?>(null) }

    fun report(item: SponsoredItem, optionId: ByteArray) {
        model.reportSponsored(
            item = item,
            optionId = optionId,
            onResult = { outcome ->
                when (outcome) {
                    is SponsoredReportOutcome.Options -> reportOptions = outcome.title to outcome.options
                    SponsoredReportOutcome.Reported -> toast(L.sponsoredReported)
                    SponsoredReportOutcome.AdsHidden -> toast(L.sponsoredHidden)
                    SponsoredReportOutcome.PremiumRequired -> toast(L.sponsoredPremiumRequired)
                    SponsoredReportOutcome.Unavailable -> toast(L.sponsoredUnavailable)
                }
            },
            onFailure = toast,
        )
    }

    LaunchedEffect(ad.messageId) { model.markSponsoredViewed(ad.messageId) }
    Surface(shape = MaterialTheme.shapes.large, color = Tone.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ad.miniThumbnail != null || ad.thumbnailFileId > 0) {
                MediaArt(ad.miniThumbnail, ad.thumbnailFileId, Modifier.width(SPONSORED_ART).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)) {}
            }
            Column(Modifier.weight(1f)) {
                Text(ad.label.ifBlank { s.sponsoredLabel }, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
                Text(ad.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ad.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ad.sponsorUrl.isNotBlank()) {
                OutlinedButton(onClick = {
                    model.clickSponsored(ad, media = false, onSuccess = {
                        OpenExternal.browse(ad.sponsorUrl)
                    }, onFailure = toast)
                }) { Text(ad.buttonText.ifBlank { s.commonOpen }) }
            }
            if (ad.canBeReported) {
                TextButton(onClick = { report(ad, byteArrayOf()) }) { Text(s.sponsoredReport, color = Tone.muted) }
            }
        }
    }

    // Telegram answers a first report with the reasons it accepts; the chosen one is sent back
    // the same way, and may itself be answered with a narrower list.
    reportOptions?.let { (title, options) ->
        TmAlertDialog(
            onDismissRequest = { reportOptions = null },
            title = { Text(title.ifBlank { s.sponsoredReportWhy }) },
            text = {
                Column {
                    options.forEach { option ->
                        TextButton(
                            onClick = {
                                reportOptions = null
                                report(ad, option.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option.text, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reportOptions = null }) { Text(s.commonCancel) } },
        )
    }
}

/** Whether the grid's note on online metadata is due: lookups on and the note not put away. */
@Composable
private fun metaNoticeShown(state: ShellState): Boolean {
    val online = OnlineMetadata.current ?: return false
    val meta by online.settings.collectAsState()
    // True until the store answers, so the note never flashes up for somebody who put it away.
    val dismissed by state.settings.onlineMetaNoticeDismissed.collectAsState(initial = true)
    return MetaNotice.shouldShow(meta.enabled, dismissed)
}

/**
 * The one-time invite for a viewer whose online metadata is off from an older version, when off
 * was the default (see [MetaNotice.shouldInvite]), the phone's and television's OnlineMetaInvite.
 * Not over the setup after sign in. Not now, Escape or a click outside all
 * answer it, and it is never asked again.
 */
@Composable
internal fun MetaInviteDialog(state: ShellState) {
    val online = OnlineMetadata.current ?: return
    val meta by online.settings.collectAsState()
    // Seen until the stores answer, so it never flashes up for somebody who already answered.
    val seen by state.settings.onlineMetaInviteSeen.collectAsState(initial = true)
    val settingUp by state.settings.setupPending.collectAsState(initial = true)
    var answered by remember { mutableStateOf(false) }
    if (answered || settingUp || !MetaNotice.shouldInvite(meta, seen)) return
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    fun answer(turnOn: Boolean) {
        answered = true
        if (turnOn) online.setEnabled(true)
        scope.launch { runCatching { state.settings.markOnlineMetaInviteSeen(turnedOn = turnOn) } }
    }
    TmAlertDialog(
        onDismissRequest = { answer(turnOn = false) },
        icon = { Icon(TmIcons.Image, contentDescription = null, tint = Tone.accent) },
        title = { Text(s.metadataInviteTitle) },
        text = { Text(s.metadataInviteBody) },
        confirmButton = { TextButton(onClick = { answer(turnOn = true) }) { Text(s.metadataInviteTurnOn) } },
        dismissButton = { TextButton(onClick = { answer(turnOn = false) }) { Text(s.metadataInviteNotNow) } },
    )
}

/**
 * The grid's one-time note on online metadata, the phone's and television's OnlineMetaNotice:
 * posters and overviews are looked up by each video's cleaned title, and nothing else is sent.
 * Keep puts it away; Turn off switches lookups off as Settings, Online metadata does, puts it away
 * and says where to turn them back on.
 */
@Composable
internal fun MetaNoticeCard(state: ShellState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    fun dismiss() {
        scope.launch { runCatching { state.settings.setOnlineMetaNoticeDismissed() } }
    }
    // The floating chrome's own look (its shadow, fill and edge), as it floats over the posters.
    val shape = RoundedCornerShape(NOTICE_CORNER)
    Box(
        modifier
            .shadow(FloatingChrome.Shadow, shape)
            .clip(shape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), shape),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TmIcons.Image, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(20.dp))
            Text(s.gridMetaNotice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                OnlineMetadata.current?.setEnabled(false)
                dismiss()
                toast(s.gridMetaNoticeTurnedOff)
            }) { Text(s.gridMetaNoticeTurnOff) }
            TextButton(onClick = { dismiss() }) { Text(s.gridMetaNoticeKeep) }
        }
    }
}

private val PLAY_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.P)
// From OsInfo rather than os.name, which the browser build has no answer for: a Mac browser
// gets Cmd+click and Ctrl+click as the Mac app does.
private val IS_MAC: Boolean get() = com.tmplayer.desktop.os.OsInfo.isMac
private const val HOVER_INTENT_MS = 300L
private const val LOAD_AHEAD = 8

/**
 * The width a tile asks for on a chat's page: a step larger than the other grids' default, since
 * the page is the posters alone, with no size buttons of its own.
 */
internal val CHAT_POSTER_WIDTH = POSTER_STEPS[2]

/** The posters note's widest, its gap to the first row and its corners. */
private val NOTICE_WIDTH = 760.dp
private val NOTICE_GAP = 12.dp
private val NOTICE_CORNER = 20.dp

/** A film or show poster's width, as a share of the toolbar's poster size step. */
private const val POSTER_SHARE = 0.75f


/** How much wider a Continue watching card is than a poster. */
private const val CONTINUE_WIDTH = 1.25f

/** A sponsored message's picture: 16:9 like every other, about the height its three lines take. */
private val SPONSORED_ART = 112.dp

/** A value worked out off the main thread, so "not answered yet" and "answered null" differ. */
private class Computed<T>(val value: T)


/**
 * A chat's compressed files under its videos ([com.tmplayer.data.Archives]): what TMPlayer met
 * among the chat's documents and cannot play, each with why, so a chat of ZIPs does not read as
 * an empty one. A click says the reason again as a message.
 */
@Composable
internal fun ArchiveList(archives: List<com.tmplayer.data.ArchiveFile>, noVideos: Boolean, onExplain: (String) -> Unit) {
    if (archives.isEmpty()) return
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (noVideos) Text(s.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium)
        Text(s.gridArchivesHeading(archives.size), color = Tone.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        archives.forEach { file ->
            val size = file.sizeBytes.takeIf { it > 0 }?.let { s.messages.formatter.bytes(it) }
            androidx.compose.material3.Surface(
                onClick = { onExplain(file.reason) },
                shape = MaterialTheme.shapes.medium,
                color = Tone.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(TmIcons.Archive, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.fileName.ifBlank { file.kind.label }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(file.reason, color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(listOfNotNull(file.kind.label, size).joinToString(" · "), color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The way into a chat's audio over its posters ([com.tmplayer.data.ChatAudio]); a click opens the list. */
@Composable
internal fun AudioEntryRow(count: Int, noVideos: Boolean, onOpen: () -> Unit) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (noVideos) Text(s.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium)
        androidx.compose.material3.Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = Tone.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TmIcons.Audio, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.gridAudioTitle, style = MaterialTheme.typography.titleSmall)
                    Text(s.gridAudioCount(count), color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted)
            }
        }
    }
}

/**
 * A chat's audio as a list on the desktop: Play all from the oldest, or one file, each going on to
 * the next one posted after it ([com.tmplayer.data.PlayQueue]); a failed or slow page says so with
 * Try again.
 */
@Composable
internal fun AudioListSheet(state: ShellState, chat: ChatSummary, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val list = remember(chat.id) { com.tmplayer.ui.browse.ChatAudioList(chat.id, scope) }
    val shown by list.state.collectAsState()
    fun play(item: com.tmplayer.data.MediaItem) {
        onDismiss()
        state.noteChatTitle(chat.id, chat.title)
        state.openPlayer(item, startFromBeginning = false)
    }
    DesktopSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.gridAudioTitle, style = MaterialTheme.typography.headlineSmall)
            Text(chat.title, color = Tone.muted)
            Spacer(Modifier.height(8.dp))
            if (shown.items.isNotEmpty()) {
                Button(onClick = { scope.launch { list.playAll()?.let(::play) } }) {
                    Icon(TmIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.gridAudioPlayAll)
                }
            }
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                shown.items.forEach { item ->
                    val detail = listOfNotNull(
                        s.messages.formatter.duration(item.durationSec.toLong()).takeIf { item.durationSec > 0 },
                        s.messages.formatter.size(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                    ).joinToString(" · ")
                    androidx.compose.material3.Surface(onClick = { play(list.queueFrom(item)) }, shape = MaterialTheme.shapes.small, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(TmIcons.Audio, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (detail.isNotBlank()) Text(detail, color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                when {
                    shown.loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                    shown.failure != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shown.failure.orEmpty(), color = Tone.muted, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = list::loadMore) { Text(s.commonTryAgain) }
                    }
                    !shown.done -> TextButton(onClick = list::loadMore, modifier = Modifier.loadWhenShown(list::loadMore)) { Text(s.gridAudioMore) }
                    shown.items.isEmpty() -> Text(s.gridAudioNone, color = Tone.muted)
                }
            }
        }
    }
}
