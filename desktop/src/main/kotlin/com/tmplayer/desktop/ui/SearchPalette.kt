package com.tmplayer.desktop.ui

import com.tmplayer.ui.components.FastScroller
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.ui.components.swallowClicks
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.ChatAvatar
import com.tmplayer.ui.browse.topicMemory
import androidx.compose.ui.draw.alpha
import com.tmplayer.ui.browse.GlobalSearchState
import com.tmplayer.ui.browse.GlobalSearchViewModel
import com.tmplayer.ui.browse.SearchLifetime
import com.tmplayer.ui.browse.SearchResultCard
import com.tmplayer.ui.browse.SearchResultSkeleton
import com.tmplayer.ui.browse.label
import com.tmplayer.ui.browse.message
import com.tmplayer.ui.browse.openResult
import com.tmplayer.ui.browse.searchStatus
import com.tmplayer.ui.browse.searchCountLine
import com.tmplayer.ui.components.TmDropdownMenu
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.launch

/**
 * One line of the palette's list. The list is one column the arrow keys walk from top to bottom,
 * whatever each line is: a heading is drawn and skipped, everything else can be picked.
 */
private sealed interface PaletteRow {
    val key: String
    val pickable: Boolean get() = true

    data class Heading(val text: String) : PaletteRow {
        override val key get() = "h-$text"
        override val pickable get() = false
    }

    data class Recent(val query: String) : PaletteRow {
        override val key get() = "r-$query"
    }

    data class Chat(val chat: ChatSummary) : PaletteRow {
        override val key get() = "c-${chat.id}"
    }

    data class MoreChats(val count: Int) : PaletteRow {
        override val key get() = "more-chats"
    }

    /** A topic found by its name (U14): "Chat > Topic", opening the chat on it. */
    data class Topic(val hit: com.tmplayer.data.TopicHit) : PaletteRow {
        override val key get() = "t-${hit.key}"
    }

    data class MoreTopics(val count: Int) : PaletteRow {
        override val key get() = "more-topics"
    }

    /** Every group asked for topics refused: the reason, Try again and the log buttons. */
    data class TopicsFailed(val text: String) : PaletteRow {
        override val key get() = "topics-failed"
        override val pickable get() = false
    }

    data class Video(val group: ResultGroup) : PaletteRow {
        override val key get() = "v-${group.key}"
    }

    /**
     * A film or show TMDB named for the words (HX41): Enter opens its best card (Ctrl+Enter plays
     * it), or, for one not in the viewer's chats, Where to watch.
     */
    data class Title(val hit: com.tmplayer.data.TitleHit) : PaletteRow {
        override val key get() = "ti-${hit.title.onlineKey}"
    }

    data class Skeleton(val index: Int) : PaletteRow {
        override val key get() = "s-$index"
        override val pickable get() = false
    }

    data class Note(val text: String, val retry: Boolean = false) : PaletteRow {
        override val key get() = "n-$text"
        override val pickable get() = false
    }

    /** The empty state's one taps: clear the filters, or search one word of the query. */
    data class Action(val text: String, val run: () -> Unit) : PaletteRow {
        override val key get() = "a-$text"
    }

    data class GoTo(val place: Place) : PaletteRow {
        override val key get() = "g-${place.name}"
    }

    /** A pasted post or invite link, or a public chat to join (CP79): first in the list. */
    data class Link(val offer: com.tmplayer.ui.browse.LinkOffer) : PaletteRow {
        override val key get() = "l-${offer.link.url}"
    }
}

/** The pages "Go to" offers: the side bar's places. */
private enum class Place(val icon: ImageVector) {
    Home(TmIcons.Home),
    History(BrowseTab.History.icon),
    Favourites(TmIcons.StarOutline),
    Chats(BrowseTab.Chats.icon),
    Downloads(TmIcons.Download),
    Settings(TmIcons.Settings),
    ;

    val label: String get() = when (this) {
        Home -> Destination.Home.label
        History -> Destination.History.label
        Favourites -> Destination.Favourites.label
        Chats -> Destination.Chats.label
        Downloads -> Destination.Downloads.label
        Settings -> Destination.Settings.label
    }

    fun go(state: ShellState) = when (this) {
        Home -> state.go(Destination.Home)
        History -> state.go(Destination.History)
        Favourites -> state.go(Destination.Favourites)
        Chats -> state.showChats(BrowseSection.of(BrowseTab.Chats))
        Downloads -> state.go(Destination.Downloads)
        Settings -> state.go(Destination.Settings)
    }
}

/**
 * The search across every chat, as a palette over the window (CP73): Ctrl+K (Cmd+K on a Mac)
 * opens it from any page. Esc empties the field first (with its filters) and closes on an empty one;
 * Ctrl+K again, or a click outside, closes it with the query kept, and opening it again within two
 * minutes brings the query back selected, so typing replaces it and Enter runs it again. After more
 * than two minutes, and in a new window, it opens empty ([SearchLifetime]).
 *
 * A field, the filter chips once there is something to filter, and one list the arrow keys walk:
 * the chats whose names match (three, then "Show all"), the videos ([SearchResultCard], one card
 * per video with every copy across chats folded in), and the pages to go to. Enter opens what is
 * picked, a video's page first as a poster's click does; Ctrl+Enter (Cmd+Enter) plays it. With
 * nothing typed it lists the recent searches and chats, starred chats first.
 *
 * [chats] is the loaded chat list; the search itself is [GlobalSearchViewModel], the phone's and
 * the television's too.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.SearchPalette(
    state: ShellState,
    search: GlobalSearchViewModel,
    chats: List<ChatSummary>,
    onOpenSeries: (com.tmplayer.data.Series) -> Unit,
) {
    val s = LocalStrings.current
    val ui by search.state.collectAsState()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val recents by state.settings.recentSearches.collectAsState(initial = emptyList())
    val allowed by ChatGate.allowed.collectAsState()
    val watch = rememberSeriesWatch(state)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val field = remember { FocusRequester() }
    var allChats by remember { mutableStateOf(false) }
    var allTopics by remember { mutableStateOf(false) }
    // The highlighted line, by its key: rows that land above it (the titles answer after the
    // videos, HX41) never move the highlight onto another line under Enter.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // Which groups the search by topic name may ask, in the chat list's order (U14).
    LaunchedEffect(chats) { search.chatList = chats }
    val topicMemory = remember(state.settings) { state.settings.topicMemory() }
    val text = state.paletteQuery
    val query = ui.query

    // Esc (and Back) empties the field first, with its filters and sort, and closes on an empty one.
    fun clearAll() {
        state.paletteQuery = TextFieldValue("")
        search.type("")
        search.setFilters(com.tmplayer.data.SearchFilters())
        search.setSort(SearchSort.BestMatch)
        selectedKey = null
        runCatching { field.requestFocus() }
    }
    BackHandler(enabled = true) { if (state.paletteQuery.text.isNotEmpty()) clearAll() else state.paletteOpen = false }
    // Closed for more than two minutes (or never opened in this process): the old words are gone.
    // Esc and Ctrl+K again within that bring the last query back, selected, as before.
    remember {
        if (!SearchLifetime.keeps(SearchLifetime.processId, state.paletteClosedAtMs, System.currentTimeMillis()) && !state.paletteSubmit) {
            state.paletteQuery = TextFieldValue("")
            search.setFilters(com.tmplayer.data.SearchFilters())
            search.setSort(SearchSort.BestMatch)
            search.type("")
        }
    }
    DisposableEffect(Unit) { onDispose { state.paletteClosedAtMs = System.currentTimeMillis() } }
    LaunchedEffect(Unit) {
        // Back with the last query selected whole, so typing starts a new one.
        if (state.paletteSubmit) {
            // Opened by "Search all chats": the words are the search, asked at once.
            state.paletteSubmit = false
            search.submit(text.text)
        } else {
            state.paletteQuery = text.copy(selection = TextRange(0, text.text.length))
        }
        runCatching { field.requestFocus() }
    }
    LaunchedEffect(query.text) { allChats = false; allTopics = false }

    fun close() {
        state.paletteOpen = false
    }

    fun openVideo(group: ResultGroup, play: Boolean) {
        search.remember()
        ui.chats.values.forEach { state.noteChatTitle(it.id, it.title) }
        if (group.isShow && !play) {
            val series = SeriesShelf.arrange(group.copies).firstNotNullOfOrNull { (it as? ShelfEntry.Show)?.series }
            if (series != null) {
                close()
                onOpenSeries(series)
                return
            }
        }
        scope.launch {
            val lead = group.lead
            search.openResult(lead, GlobalSearch.titleOf(group), onGone = { toast(s.searchNoLongerThere) }) {
                close()
                val item = group.asItem()
                if (play || !state.detailFirst) {
                    state.openPlayer(lead, startFromBeginning = false)
                } else {
                    state.openDetail(DetailRequest(item = item, chatTitle = state.chatTitleOf(lead.chatId), outsideChat = true))
                }
            }
        }
    }

    // The viewer's own chats only (HX62): no username is looked up on Telegram.
    val offer = com.tmplayer.ui.browse.rememberLinkOffer(query.text)
    // A first page that will not come says so, with Retry, after the app's usual wait.
    var slow by remember(query) { mutableStateOf(false) }
    LaunchedEffect(query, ui.searching) {
        slow = false
        if (ui.searching) {
            kotlinx.coroutines.delay(com.tmplayer.ui.components.SlowAnswer.AFTER_MS)
            slow = true
        }
    }
    val rows = remember(ui, chats, favourites, recents, allowed, allChats, allTopics, query.text, slow, offer) {
        listOfNotNull(offer?.let { PaletteRow.Link(it) }) + buildRows(ui, chats, favourites, if (allowed != null) emptyList() else recents, allChats, search, onSearchWord = { word ->
            state.paletteQuery = TextFieldValue(word, TextRange(word.length))
            search.submit(word)
        }, onAddChats = { close(); com.tmplayer.ui.browse.AddChatsRequest.ask() }, slow = slow, allTopics = allTopics)
    }

    fun pick(row: PaletteRow, play: Boolean = false) {
        when (row) {
            is PaletteRow.Recent -> {
                state.paletteQuery = TextFieldValue(row.query, TextRange(row.query.length))
                search.submit(row.query)
            }
            is PaletteRow.Chat -> {
                search.remember()
                close()
                state.openChat(row.chat)
            }
            is PaletteRow.MoreChats -> allChats = true
            is PaletteRow.Topic -> chats.firstOrNull { it.id == row.hit.chatId }?.let { chat ->
                scope.launch {
                    com.tmplayer.ui.browse.openOnTopic(topicMemory, row.hit.chatId, row.hit.topic.id)
                    search.remember()
                    close()
                    state.openChat(chat)
                }
            }
            is PaletteRow.MoreTopics -> allTopics = true
            is PaletteRow.Video -> openVideo(row.group, play)
            is PaletteRow.Title -> row.hit.best?.let { openVideo(it, play) } ?: search.openWhereToWatch(row.hit.title)
            is PaletteRow.Action -> row.run()
            is PaletteRow.Link -> {
                close()
                com.tmplayer.ui.browse.LinkRequests.ask(row.offer.link)
            }
            is PaletteRow.GoTo -> {
                close()
                row.place.go(state)
            }
            is PaletteRow.Heading, is PaletteRow.Skeleton, is PaletteRow.Note, is PaletteRow.TopicsFailed -> Unit
        }
    }

    val pickable = rows.indices.filter { rows[it].pickable }
    val selected = rows.indexOfFirst { it.pickable && it.key == selectedKey }
    // The selection stays on its line as the list changes around it, and goes to the first line
    // only once its own line has gone (or nothing was picked yet).
    LaunchedEffect(rows) {
        if (selected < 0) selectedKey = pickable.firstOrNull()?.let { rows[it].key }
    }
    val list = rememberLazyListState()
    LaunchedEffect(selected) {
        if (selected < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (selected <= first || selected >= last) list.animateScrollToItem((selected - 2).coerceAtLeast(0))
    }
    // The next page well before the end, as the grids page.
    com.tmplayer.ui.components.LoadMoreAhead(list, enabled = !ui.loadingMore && !ui.endReached && !ui.moreFailed, ahead = 5) { search.loadMore() }

    fun move(by: Int) {
        if (pickable.isEmpty()) return
        val at = pickable.indexOf(selected).takeIf { it >= 0 } ?: if (by > 0) -1 else pickable.size
        selectedKey = rows[pickable[(at + by).coerceIn(0, pickable.lastIndex)]].key
    }

    Box(
        Modifier
            .matchParentSize()
            .background(FloatingTone.scrim)
            .scrimDismiss(::close),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 72.dp, start = 24.dp, end = 24.dp, bottom = 48.dp)
                .widthIn(max = PALETTE_WIDTH)
                .fillMaxWidth()
                .floatingSurface(FloatingTone.dialog, Floating.DialogShape)
                // A click inside the card is not a click on the scrim.
                .swallowClicks(),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOnFloating provides true) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(TmIcons.Search, contentDescription = null, tint = Tone.muted)
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            state.paletteQuery = it
                            search.type(it.text)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = Tone.text),
                        cursorBrush = SolidColor(Tone.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search.submit(text.text) }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(field)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val command = if (OsInfo.isMac) event.isMetaPressed else event.isCtrlPressed
                                when (event.key) {
                                    Key.DirectionDown -> { move(1); true }
                                    Key.DirectionUp -> { move(-1); true }
                                    Key.PageDown -> { move(PAGE); true }
                                    Key.PageUp -> { move(-PAGE); true }
                                    Key.Escape -> { if (text.text.isNotEmpty()) clearAll() else close(); true }
                                    Key.Enter, Key.NumPadEnter -> {
                                        val row = rows.getOrNull(selected)
                                        if (row != null) pick(row, play = command) else search.submit(text.text)
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            Box {
                                if (text.text.isEmpty()) {
                                    Text(s.searchFieldHint, style = MaterialTheme.typography.titleMedium, color = Tone.muted, maxLines = 1)
                                }
                                inner()
                            }
                        },
                    )
                    if (text.text.isNotEmpty()) {
                        BarTooltip(s.browseClearSearch, null, below = true) {
                            com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = ::clearAll)
                        }
                    }
                }
                if (!query.isEmpty && (ui.groups.isNotEmpty() || !ui.filters.isDefault || ui.sort != SearchSort.BestMatch)) {
                    FilterRow(ui, search)
                }
                androidx.compose.material3.HorizontalDivider(color = Tone.outline)
                Box(Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        PaletteLine(
                            row = row,
                            ui = ui,
                            selected = index == selected,
                            progress = { watch.point(it)?.fraction },
                            onHover = { if (row.pickable) selectedKey = row.key },
                            onClick = { pick(row) },
                            onRemove = (row as? PaletteRow.Recent)?.let { r -> { scope.launch { state.settings.removeRecentSearch(r.query) } } },
                            onRetry = search::retry,
                            onRetryTopics = search::retryTopics,
                        )
                    }
                }
                // A long run of results is one hold away from its end.
                FastScroller(list)
                }
                Text(
                    s.searchPaletteHint(if (OsInfo.isMac) s.keysCmd else s.keysCtrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
    // Where to watch (HX41) over the palette: Esc or Close goes back to the field, words kept.
    ui.watching?.let { (title, answer) ->
        com.tmplayer.ui.browse.WhereToWatchDialog(
            title = title,
            answer = answer,
            canSearchOthers = ui.canSearchOthers,
            onSearchOthers = search::searchOtherChats,
            onRetry = { search.openWhereToWatch(title) },
            onClose = {
                search.closeWhereToWatch()
                runCatching { field.requestFocus() }
            },
        )
    }
}

/** The list for what is on screen now; see [SearchPalette]. */
private fun buildRows(
    ui: GlobalSearchState,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    recents: List<String>,
    allChats: Boolean,
    search: GlobalSearchViewModel,
    onSearchWord: (String) -> Unit,
    /** Closes the palette and opens the picker, for "Add more chats" (HX62). */
    onAddChats: () -> Unit = {},
    /** The first page has been on its way past [com.tmplayer.ui.components.SlowAnswer.AFTER_MS]. */
    slow: Boolean = false,
    /** Every topic found is listed, not only the first few. */
    allTopics: Boolean = false,
): List<PaletteRow> = buildList {
    val s = com.tmplayer.i18n.L
    val query = ui.query
    if (query.text.isBlank()) {
        if (recents.isNotEmpty()) {
            add(PaletteRow.Heading(s.browseRecentSearches))
            recents.take(GlobalSearch.RECENT_SHOWN).forEach { add(PaletteRow.Recent(it)) }
        }
        val recentChats = GlobalSearch.recentChats(chats, favourites, limit = RECENT_CHATS)
        if (recentChats.isNotEmpty()) {
            add(PaletteRow.Heading(s.searchChatsHeading))
            recentChats.forEach { add(PaletteRow.Chat(it)) }
        }
        add(PaletteRow.Heading(s.searchGoTo))
        Place.entries.forEach { add(PaletteRow.GoTo(it)) }
        return@buildList
    }
    val matching = GlobalSearch.localChats(chats, query.text, favourites)
    if (matching.isNotEmpty()) {
        add(PaletteRow.Heading(s.searchChatsHeading))
        val shown = if (allChats) matching else matching.take(GlobalSearch.CHAT_PREVIEW)
        shown.forEach { add(PaletteRow.Chat(it)) }
        if (shown.size < matching.size) add(PaletteRow.MoreChats(matching.size))
    }
    // Topics found by their names (U14), a few, then Show all; a group that holds no videos says so.
    if (ui.topics.isNotEmpty() || ui.topicsError != null) {
        add(PaletteRow.Heading(s.searchTopicsHeading))
        val shownTopics = if (allTopics) ui.topics else ui.topics.take(GlobalSearch.CHAT_PREVIEW)
        shownTopics.forEach { add(PaletteRow.Topic(it)) }
        if (shownTopics.size < ui.topics.size) add(PaletteRow.MoreTopics(ui.topics.size))
        ui.topicsError?.let { add(PaletteRow.TopicsFailed(s.searchTopicsFailed(it))) }
        if (ui.topicsPartial && !ui.topicsSearching) add(PaletteRow.Note(s.searchTopicsPartial(com.tmplayer.data.TopicSearch.CHATS_PER_SEARCH)))
    }
    // Any film or show the words name (HX41), with what the viewer has of each.
    val titles = ui.titleHits
    if (titles.isNotEmpty() || ui.titleLookup == com.tmplayer.ui.browse.TitleLookup.Unavailable) {
        add(PaletteRow.Heading(s.searchTitlesHeading))
        titles.forEach { add(PaletteRow.Title(it)) }
        if (ui.titleLookup == com.tmplayer.ui.browse.TitleLookup.Unavailable) {
            add(PaletteRow.Note(s.searchTitlesUnavailable))
            add(PaletteRow.Action(s.searchTitlesRetry) { search.retryTitles() })
        }
    }
    val videos = ui.shown
    if (videos.isNotEmpty() || ui.searching) add(PaletteRow.Heading(s.searchVideosHeading))
    // Offline too: the search runs again once back, and Retry asks sooner.
    searchStatus(ui)?.let { add(PaletteRow.Note(it, retry = ui.error != null || ui.offline)) }
    // What was covered (HX62): the count, the one-off look at the chats not added, and, when none
    // was added yet, that every chat was searched, with the way to choose.
    searchCountLine(ui)?.let { add(PaletteRow.Note(it)) }
    if (ui.canSearchOthers && videos.isNotEmpty()) add(PaletteRow.Action(s.searchOtherChats) { search.searchOtherChats() })
    if (ui.everyChatNote) {
        add(PaletteRow.Note(s.searchEveryChatNote))
        add(PaletteRow.Action(s.pickerAddMore) { onAddChats() })
    }
    if (slow && ui.searching && videos.isEmpty()) add(PaletteRow.Note(com.tmplayer.ui.components.SlowAnswer.MESSAGE, retry = true))
    when {
        videos.isNotEmpty() -> videos.forEach { add(PaletteRow.Video(it)) }
        ui.searching -> repeat(SKELETONS) { add(PaletteRow.Skeleton(it)) }
        !ui.busy && ui.error == null && ui.floodWait == 0 -> {
            val hidden = ui.hiddenBy
            if (hidden.isEmpty()) {
                add(PaletteRow.Note(s.searchNone(query.text)))
                add(PaletteRow.Note(s.searchNoneDetail))
                if (ui.addedOnly) add(PaletteRow.Note(s.searchNoneAdded))
            } else {
                hidden.forEach { add(PaletteRow.Note(it.message(ui))) }
            }
            if (!ui.filters.isDefault) add(PaletteRow.Action(s.searchClearFilters) { search.clearFilters() })
            if (ui.canSearchOthers) add(PaletteRow.Action(s.searchOtherChats) { search.searchOtherChats() })
            if (ui.canAddChats) add(PaletteRow.Action(s.pickerAddMore) { onAddChats() })
            AllChatsSearch.fallbackQuery(query.server)?.let { word ->
                add(PaletteRow.Action(s.searchSearchFor(word)) { onSearchWord(word) })
            }
        }
    }
    if (ui.loadingMore) add(PaletteRow.Skeleton(SKELETONS))
    // The last page failed: said, with Try again, not a list that stops short (the chat grid's footer).
    if (ui.moreFailed && !ui.loadingMore) {
        add(PaletteRow.Note(s.gridMoreFailed))
        add(PaletteRow.Action(s.commonTryAgain) { search.retryMore() })
    }
    val places = Place.entries.filter { Fuzzy.score(it.label, query.text) > 0 }
    if (places.isNotEmpty()) {
        add(PaletteRow.Heading(s.searchGoTo))
        places.forEach { add(PaletteRow.GoTo(it)) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaletteLine(
    row: PaletteRow,
    ui: GlobalSearchState,
    selected: Boolean,
    progress: (com.tmplayer.data.MediaItem) -> Float?,
    onHover: () -> Unit,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onRetry: () -> Unit,
    onRetryTopics: () -> Unit = onRetry,
) {
    val s = LocalStrings.current
    val pickable = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(Corner.Medium))
        .onPointerEvent(PointerEventType.Enter) { onHover() }
        // The field keeps the keyboard (arrows pick, Enter opens): a row is no Tab stop with nothing to show for it.
        .focusProperties { canFocus = false }
        .clickable(role = Role.Button, onClick = onClick)
    when (row) {
        is PaletteRow.Heading -> Text(
            row.text,
            style = MaterialTheme.typography.labelLarge,
            color = Tone.muted,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
        is PaletteRow.Video -> SearchResultCard(
            group = row.group,
            chats = ui.chats,
            progress = progress,
            highlighted = selected,
            modifier = pickable,
            posterWidth = 52.dp,
        )
        is PaletteRow.Title -> com.tmplayer.ui.browse.SearchTitleCard(
            hit = row.hit,
            chats = ui.chats,
            highlighted = selected,
            modifier = pickable,
            posterWidth = 40.dp,
        )
        is PaletteRow.Skeleton -> SearchResultSkeleton(posterWidth = 52.dp)
        is PaletteRow.Note -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
            if (row.retry) TextButton(onClick = onRetry) { Text(s.commonRetry) }
        }
        is PaletteRow.Recent -> SimpleLine(TmIcons.History, row.query, selected, pickable) {
            if (onRemove != null) {
                BarTooltip(s.searchRemoveRecent(row.query), null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(TmIcons.Close, contentDescription = s.searchRemoveRecent(row.query), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        is PaletteRow.Chat -> Row(
            pickable.background(if (selected) Tone.focusFill.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChatAvatar(ChatBrief.of(row.chat), 32.dp)
            Column(Modifier.weight(1f)) {
                Text(row.chat.title, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.chat.kind.label, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
            }
        }
        is PaletteRow.MoreChats -> SimpleLine(TmIcons.ChevronDown, s.searchShowAllChats(row.count), selected, pickable)
        is PaletteRow.Topic -> {
            val empty = !row.hit.topic.hasVideos
            Row(
                pickable.background(if (selected) Tone.focusFill.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val chat = ui.chats[row.hit.chatId]
                Box(Modifier.size(32.dp).alpha(if (empty) 0.5f else 1f), contentAlignment = Alignment.Center) {
                    if (chat != null) ChatAvatar(chat, 32.dp) else Icon(TmIcons.ChatsMany, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        com.tmplayer.ui.browse.topicHitLabel(row.hit, s),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (empty) Tone.muted else Tone.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (empty) Text(s.searchTopicNoVideos, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
                }
                if (row.hit.topic.isPinned) Icon(TmIcons.Pin, contentDescription = s.gridTopicPinned, tint = Tone.muted, modifier = Modifier.size(16.dp))
                if (row.hit.topic.isClosed) Icon(TmIcons.Lock, contentDescription = s.gridTopicClosed, tint = Tone.muted, modifier = Modifier.size(16.dp))
            }
        }
        is PaletteRow.MoreTopics -> SimpleLine(TmIcons.ChevronDown, s.searchShowAllTopics(row.count), selected, pickable)
        is PaletteRow.TopicsFailed -> Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetryTopics) { Text(s.commonRetry) }
            }
            SendLogsActions()
        }
        is PaletteRow.Action -> SimpleLine(TmIcons.Search, row.text, selected, pickable)
        is PaletteRow.GoTo -> SimpleLine(row.place.icon, row.place.label, selected, pickable)
        is PaletteRow.Link -> SimpleLine(TmIcons.Send, row.offer.label, selected, pickable) {
            Text(row.offer.detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
        }
    }
}

@Composable
private fun SimpleLine(
    icon: ImageVector,
    text: String,
    selected: Boolean,
    modifier: Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.background(if (selected) Tone.focusFill.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        trailing()
    }
}

/**
 * The chips over the results: the kind of chat and the archive (each asks Telegram again), the
 * picture, small clips and the order (each only screens or reorders what came back).
 */
@Composable
private fun FilterRow(ui: GlobalSearchState, search: GlobalSearchViewModel) {
    val s = LocalStrings.current
    val filters = ui.filters
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchChatType.entries.forEach { type ->
            FilterChip(
                selected = filters.chatType == type,
                onClick = { search.setFilters(filters.copy(chatType = type)) },
                label = { Text(type.label) },
            )
        }
        Spacer(Modifier.width(4.dp))
        MenuChip(filters.quality.label, filters.quality != SearchQuality.Any, SearchQuality.entries.map { it.label to { search.setFilters(filters.copy(quality = it)) } })
        FilterChip(
            selected = filters.hideSmall,
            onClick = { search.setFilters(filters.copy(hideSmall = !filters.hideSmall)) },
            label = { Text(s.searchFilterHideSmall) },
        )
        FilterChip(
            selected = filters.includeArchived,
            onClick = { search.setFilters(filters.copy(includeArchived = !filters.includeArchived)) },
            label = { Text(s.searchFilterIncludeArchived) },
        )
        MenuChip(ui.sort.label, ui.sort != SearchSort.BestMatch, SearchSort.entries.map { it.label to { search.setSort(it) } }, icon = TmIcons.Sort)
    }
}

/** A chip that opens a short menu of choices. */
@Composable
private fun MenuChip(label: String, selected: Boolean, choices: List<Pair<String, () -> Unit>>, icon: ImageVector = TmIcons.FilterHorizontal) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (text, choose) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; choose() })
            }
        }
    }
}

/** The card's width: room for the title, the facts and the chats, and no wider than a glance. */
private val PALETTE_WIDTH = 640.dp

/** The list's height before it scrolls, which keeps the card off the window's foot. */
private val LIST_HEIGHT = 520.dp

private const val PAGE = 6
private const val SKELETONS = 3
private const val RECENT_CHATS = 5
