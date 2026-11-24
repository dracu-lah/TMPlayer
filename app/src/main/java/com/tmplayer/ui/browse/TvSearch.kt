package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.focusScale
import com.tmplayer.ui.theme.focusStrokeWidth
import com.tmplayer.ui.components.dropStaleArrowRepeats
import com.tmplayer.ui.components.keepTopInSight
import com.tmplayer.ui.components.pageKeys
import com.tmplayer.ui.components.pageTile
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.components.TvKeyboard
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import com.tmplayer.ui.components.SlowAnswerNotice

/**
 * The television's search across every chat (CP73), the page the rail's Search opens and the
 * remote's search key goes to.
 *
 * Laid out for a remote: the microphone first and focused, since speaking is the quick way to
 * type on a television (on Fire TV, where dictation belongs to the keyboard, it opens the keyboard
 * instead), then the field, which opens the system keyboard on OK. Before anything is typed the
 * row under them offers the recent searches, or failing those, titles from the starred chats'
 * newest videos; once results are in it holds the filters. Then the chats whose names match, as
 * avatar cards, and the videos five across as posters, each one card however many chats it was
 * posted in. They all scroll as one list, which brings a focused poster in whole. Under it a strip
 * of fixed height says more about the poster the remote is on.
 */
@Composable
internal fun TvSearchScreen(
    state: GlobalSearchState,
    text: String,
    onText: (String) -> Unit,
    onSubmit: (String) -> Unit,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    /** Hidden while the kids lock is on, and while searches are not remembered. */
    recentSearches: List<String>,
    /** Titles of the starred chats' newest videos, offered before anything is typed. */
    suggestions: List<String>,
    progress: (MediaItem) -> Float?,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenGroup: (ResultGroup) -> Unit,
    /** Long OK on a film: plays it, from its saved point when it has one. */
    onPlayGroup: (ResultGroup) -> Unit = {},
    onFilters: (SearchFilters) -> Unit,
    onSort: (SearchSort) -> Unit,
    onLoadMore: () -> Unit,
    /** The Try again pill after a page failed to load. */
    onRetryMore: () -> Unit = {},
    onRetry: () -> Unit,
    /** "Search my other chats" (HX62): the same words over the chats that were not added, once. */
    onSearchOthers: () -> Unit = {},
    /** The picker, for "Add more chats" when nothing was found in the added ones. */
    onAddChats: () -> Unit = { com.tmplayer.ui.browse.AddChatsRequest.ask() },
    /** The poster a result's page was opened from, which the remote goes back to when the page closes. */
    tileReturn: TileReturn = rememberTileReturn(),
    /** A result's page is up over the list (the shell's page, or a show's episodes). */
    pageOpen: Boolean = false,
    /** Whose Telegram this is, for the picture beside the clock every rail page carries. */
    account: com.tmplayer.data.Account? = null,
    /** A topic found by its name: its chat, opened on it ([openOnTopic]). */
    onOpenTopic: (com.tmplayer.data.TopicHit) -> Unit = {},
    /** The topics' own Try again ([GlobalSearchViewModel.retryTopics]). */
    onRetryTopics: () -> Unit = {},
    /** "Where to watch" for a title not in the viewer's chats (HX41), its close, and the titles' Try again. */
    onWhereToWatch: (com.tmplayer.online.PosterChoice) -> Unit = {},
    onCloseWhereToWatch: () -> Unit = {},
    onRetryTitles: () -> Unit = {},
) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    // The title pill Where to watch was opened from, which the remote goes back to when it closes.
    val titleFocus = remember { HashMap<String, FocusRequester>() }
    var watchFrom by remember { mutableStateOf<String?>(null) }
    val watchOpen = state.watching != null
    LaunchedEffect(watchOpen) {
        val from = watchFrom
        if (!watchOpen && from != null) {
            withFrameNanos { }
            runCatching { titleFocus[from]?.requestFocus() }
            watchFrom = null
        }
    }
    var keyboardRequests by remember { mutableIntStateOf(0) }
    val voice = rememberVoiceSearch(
        s.browseVoicePromptChat,
        onKeyboard = { keyboardRequests++ },
        onResult = { heard -> onText(heard); onSubmit(heard) },
    )
    val micFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val firstKey = remember { FocusRequester() }
    // "Look up titles again" pressed: its row stays while TMDB is asked, so the remote is not left
    // on nothing, and once the answer is in the remote goes somewhere on purpose: the first title,
    // the pill again if it failed again, else the field.
    val titleRetryFocus = remember { FocusRequester() }
    var titleRetrying by remember { mutableStateOf(false) }
    val titlesNow = state.titleHits
    LaunchedEffect(state.titleLookup, titlesNow.isEmpty()) {
        if (!titleRetrying || state.titleLookup == TitleLookup.Looking) return@LaunchedEffect
        titleRetrying = false
        withFrameNanos { }
        val first = titlesNow.firstOrNull()?.title?.onlineKey?.let { titleFocus[it] }
        val landed = runCatching {
            when {
                state.titleLookup == TitleLookup.Unavailable -> titleRetryFocus.requestFocus()
                first != null -> first.requestFocus()
                else -> fieldFocus.requestFocus()
            }
        }.isSuccess
        // A pill not laid out yet: the field, never nowhere.
        if (!landed) runCatching { fieldFocus.requestFocus() }
    }
    // Clear: the words, the filters and the sort go at once, and the remote returns to the field.
    val clear: () -> Unit = {
        onText("")
        onFilters(SearchFilters())
        onSort(SearchSort.BestMatch)
        // From a poster the field is scrolled out of the list and cannot take the remote: the
        // keys beside the list always can, so the remote is never left on nothing.
        if (!runCatching { fieldFocus.requestFocus() }.getOrDefault(false)) runCatching { firstKey.requestFocus() }
    }
    // Back with words in the field clears them first; the next Back leaves as it always did.
    com.tmplayer.ui.nav.BackHandler(enabled = text.isNotEmpty(), onBack = clear)
    // A frame first: the field is an item of the results' list now, composed as the list is laid out.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { if (voice != null) micFocus.requestFocus() else firstKey.requestFocus() }
    }

    // The viewer's own chats only (HX62): no username is looked up on Telegram.
    val chatMatches = remember(chats, text, favourites) {
        if (text.isBlank()) GlobalSearch.recentChats(chats, favourites, limit = 10)
        else GlobalSearch.localChats(chats, text, favourites).take(10)
    }
    // A typed post or invite link (CP79), as a pill beside the field.
    val offer = rememberLinkOffer(text)
    val briefs = remember(state.chats, chats) { state.chats + chats.associate { it.id to ChatBrief.of(it) } }
    var focusedGroup by remember { mutableStateOf<ResultGroup?>(null) }
    val grid = com.tmplayer.ui.components.rememberAheadGridState()
    // The results a Page or Channel key can land on (WP-12), by their lazy item key.
    val pageTiles = com.tmplayer.ui.components.rememberPageTiles()
    // Back from a result's page the remote lands on the poster it was opened from, as in a chat's
    // grid: results arriving meanwhile must not leave it on the field (Mi TV, 2026-10-23). A poster
    // scrolled out of the list comes back through its index, which follows the header items.
    ReturnToTile(tileReturn, pageOpen) { key ->
        val at = state.shown.indexOfFirst { it.key == key }
        if (at < 0) return@ReturnToTile false
        val tail = (if (state.loadingMore) 1 else 0) + (if (state.moreFailed && !state.loadingMore) 1 else 0)
        val head = grid.layoutInfo.totalItemsCount - state.shown.size - tail
        if (head < 0) return@ReturnToTile false
        grid.scrollToItem(head + at)
        true
    }

    // The field, the pills, the chats and the results scroll as one list, with the details strip
    // fixed under it. They used to be a header over a grid of whatever height was left, which on
    // a television (960 by 540 dp at 1080p and at 720p alike) was less than one poster: the row
    // the remote was on stood cut in half above the strip. Now moving down into the results
    // scrolls the header away, and up from the first row brings it back.
    val shown = state.shown
    val pills = recentSearches.take(GlobalSearch.RECENT_SHOWN).ifEmpty { suggestions }
    val showFilters = text.isNotBlank() &&
        (state.groups.isNotEmpty() || !state.filters.isDefault || state.sort != SearchSort.BestMatch)
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    // The states every result tile reads, collected once for the page rather than per tile.
    val tileShared = com.tmplayer.ui.components.rememberTileShared()
    androidx.compose.runtime.CompositionLocalProvider(com.tmplayer.ui.components.LocalTileShared provides tileShared) {
    Row(Modifier.fillMaxSize().padding(start = TV_START, bottom = Tv.SafeV)) {
        // The keyboard is a column of its own beside the results, not the system's keyboard over
        // them: half the screen was lost to it on a television, and the results it was typing for
        // could not be seen (user, 2026-11-23). The results fill in here as each letter lands.
        TvKeyboard(
            text = text,
            onText = onText,
            onSearch = { onSubmit(text) },
            modifier = Modifier.width(KEYBOARD_WIDTH).padding(top = 4.dp),
            firstKey = firstKey,
            // Another script or voice typing: the system keyboard, as OK on the field opens it.
            onSystemKeyboard = { keyboardRequests++ },
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
        WholeCardInView(grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().keepTopInSight(grid).dropStaleArrowRepeats()
                    .pageKeys(grid, pageTiles),
                // Under the top bar, which names the page and carries the clock, the field leads.
                contentPadding = PaddingValues(end = Tv.SafeH, top = 4.dp, bottom = CARD_CLEARANCE),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = full, key = "field") {
                    Row(
                        // The row reaches as far as it did over the old grid, past the results' margin.
                        Modifier.bleedEnd(Tv.SafeH - Tv.FocusClearance),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (voice != null) MicOrb(onClick = voice, modifier = Modifier.focusRequester(micFocus))
                        TvSearchField(
                            value = text,
                            onValueChange = onText,
                            placeholder = s.searchFieldHint,
                            modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                            onSubmit = { onSubmit(text) },
                            editRequests = keyboardRequests,
                        )
                        if (text.isNotEmpty()) {
                            com.tmplayer.ui.components.IconTip(s.browseClearSearch) {
                                com.tmplayer.ui.components.ClearButton(s.browseClearSearch, target = 56.dp, disc = 40.dp, onClick = clear)
                            }
                        }
                        offer?.let { found -> TvPill(found.label, TmIcons.Send) { LinkRequests.ask(found.link) } }
                    }
                }

                // Recent searches, else suggestions, before typing; the filters after.
                if (text.isBlank() && pills.isNotEmpty()) {
                    item(span = full, key = "pills") {
                        Column(Modifier.bleedEnd(Tv.SafeH)) {
                            Text(
                                if (recentSearches.isNotEmpty()) s.browseRecentSearches else s.searchSuggestions,
                                style = MaterialTheme.typography.titleSmall,
                                color = Tone.muted,
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = Tv.SafeH)) {
                                items(pills, key = { it }) { pill ->
                                    TvPill(pill, if (recentSearches.isNotEmpty()) TmIcons.History else TmIcons.Search, plain = true) {
                                        onText(pill)
                                        onSubmit(pill)
                                    }
                                }
                            }
                        }
                    }
                } else if (showFilters) {
                    item(span = full, key = "filters") {
                        LazyRow(
                            Modifier.bleedEnd(Tv.SafeH),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = Tv.SafeH),
                        ) {
                            items(SearchChatType.entries, key = { it.name }) { type ->
                                TvPill(type.label, null, chosen = state.filters.chatType == type) { onFilters(state.filters.copy(chatType = type)) }
                            }
                            item(key = "quality") {
                                // OK steps through the qualities, which on a remote is one press per choice.
                                TvPill(state.filters.quality.label, TmIcons.FilterHorizontal, chosen = state.filters.quality != SearchQuality.Any) {
                                    val next = SearchQuality.entries[(state.filters.quality.ordinal + 1) % SearchQuality.entries.size]
                                    onFilters(state.filters.copy(quality = next))
                                }
                            }
                            item(key = "sort") {
                                TvPill(state.sort.label, TmIcons.Sort, chosen = state.sort != SearchSort.BestMatch) {
                                    onSort(SearchSort.entries[(state.sort.ordinal + 1) % SearchSort.entries.size])
                                }
                            }
                            item(key = "small") {
                                TvPill(s.searchFilterHideSmall, null, chosen = state.filters.hideSmall) { onFilters(state.filters.copy(hideSmall = !state.filters.hideSmall)) }
                            }
                            item(key = "archived") {
                                TvPill(s.searchFilterIncludeArchived, TmIcons.Archive, chosen = state.filters.includeArchived) {
                                    onFilters(state.filters.copy(includeArchived = !state.filters.includeArchived))
                                }
                            }
                        }
                    }
                }

                if (chatMatches.isNotEmpty()) {
                    item(span = full, key = "chats") {
                        LazyRow(
                            Modifier.bleedEnd(Tv.SafeH),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(end = Tv.SafeH),
                        ) {
                            items(chatMatches, key = { it.id }) { chat -> ChatCard(chat, reduced) { onOpenChat(chat) } }
                        }
                    }
                }

                // Topics found by their names (U14): one row of pills under the chats, each
                // "Chat > Topic"; Left and Right walk it, OK opens the chat on that topic.
                if (text.isNotBlank() && (state.topics.isNotEmpty() || state.topicsError != null)) {
                    item(span = full, key = "topics") {
                        Column(Modifier.bleedEnd(Tv.SafeH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.searchTopicsHeading, style = MaterialTheme.typography.titleSmall, color = Tone.muted)
                            state.topicsError?.let { failed ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(s.searchTopicsFailed(failed), style = MaterialTheme.typography.bodyLarge, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
                                    TvPill(s.commonRetry, TmIcons.Refresh, onClick = onRetryTopics)
                                    com.tmplayer.ui.settings.SendLogsButton()
                                }
                            }
                            if (state.topics.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = Tv.SafeH)) {
                                    items(state.topics, key = { it.key }) { hit ->
                                        TvPill(
                                            if (hit.topic.hasVideos) topicHitLabel(hit, s) else "${topicHitLabel(hit, s)}, ${s.searchTopicNoVideos}",
                                            if (hit.topic.isClosed) TmIcons.Lock else if (hit.topic.isPinned) TmIcons.Pin else TmIcons.ChatsMany,
                                            plain = !hit.topic.hasVideos,
                                        ) { onOpenTopic(hit) }
                                    }
                                }
                            }
                            if (state.topicsPartial && !state.topicsSearching) {
                                Text(s.searchTopicsPartial(com.tmplayer.data.TopicSearch.CHATS_PER_SEARCH), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                            }
                        }
                    }
                }

                // Any film or show the words name (HX41): one row of pills, each a title and what
                // the viewer has of it. OK on one with copies opens its best card; on one not in the
                // chats, Where to watch, whose panel also holds the search of the other chats.
                val titleHits = state.titleHits
                val retryRow = state.titleLookup == TitleLookup.Unavailable || (titleRetrying && state.titleLookup == TitleLookup.Looking)
                if (text.isNotBlank() && (titleHits.isNotEmpty() || retryRow)) {
                    item(span = full, key = "titles") {
                        Column(Modifier.bleedEnd(Tv.SafeH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.searchTitlesHeading, style = MaterialTheme.typography.titleSmall, color = Tone.muted)
                            if (retryRow) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(s.searchTitlesUnavailable, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
                                    TvPill(
                                        s.searchTitlesRetry,
                                        TmIcons.Refresh,
                                        modifier = Modifier.focusRequester(titleRetryFocus).testTag("titles-retry"),
                                    ) {
                                        if (!titleRetrying) {
                                            titleRetrying = true
                                            onRetryTitles()
                                        }
                                    }
                                }
                            }
                            if (titleHits.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = Tv.SafeH)) {
                                    items(titleHits, key = { it.title.onlineKey }) { hit ->
                                        val key = hit.title.onlineKey
                                        val focus = titleFocus.getOrPut(key) { FocusRequester() }
                                        TvPill(
                                            "${titleName(hit.title)}, ${titleHitStatus(hit)}",
                                            if (hit.notInChats) TmIcons.SearchRemove else TmIcons.Play,
                                            plain = hit.notInChats,
                                            modifier = Modifier.focusRequester(focus).testTag("title-$key"),
                                        ) {
                                            val best = hit.best
                                            if (best != null) {
                                                onOpenGroup(best)
                                            } else {
                                                watchFrom = key
                                                onWhereToWatch(hit.title)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                searchStatus(state)?.let { status ->
                    item(span = full, key = "status") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(status, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                            if (state.error != null || state.offline) TvPill(s.commonRetry, TmIcons.Refresh, onClick = onRetry)
                        }
                    }
                }

                // The count line, the way to the chats that were not added, and what was covered
                // when nothing was added (HX62): one row the remote walks to like the pills.
                if (text.isNotBlank()) {
                    val count = searchCountLine(state)
                    if (count != null || state.everyChatNote) {
                        item(span = full, key = "count") {
                            Row(
                                Modifier.testTag("search-count-row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                // The pill leads the row, over the first poster, so Up from the posters lands on it.
                                if (state.canSearchOthers) {
                                    TvPill(s.searchOtherChats, TmIcons.Search, modifier = Modifier.testTag("search-other-chats"), onClick = onSearchOthers)
                                }
                                if (state.everyChatNote) {
                                    TvPill(s.pickerAddMore, TmIcons.PersonAdd, onClick = onAddChats)
                                    Text(s.searchEveryChatNote, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
                                }
                                if (count != null) Text(count, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, modifier = Modifier.testTag("search-count"))
                            }
                        }
                    }
                }

                when {
                    text.isBlank() -> Unit
                    shown.isEmpty() && state.searching -> {
                        items(COLUMNS, key = { "skeleton$it" }) { SearchResultSkeleton(posterWidth = 140.dp) }
                        item(span = full, key = "slow") {
                            SlowAnswerNotice(key = state.query, onRetry = onRetry, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    shown.isEmpty() && !state.busy && state.error == null -> item(span = full, key = "empty") {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val hidden = state.hiddenBy
                            // The empty pages' picture, never focusable: the remote stays on the keyboard
                            // and the pills below, in the order they always had.
                            com.tmplayer.ui.components.EmptyArt(
                                if (hidden.isNotEmpty()) TmIcons.FilterHorizontal else TmIcons.SearchRemove,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            if (hidden.isNotEmpty()) {
                                hidden.forEach { Text(it.message(state), style = MaterialTheme.typography.titleMedium, color = Tone.text) }
                                TvPill(s.searchClearFilters, TmIcons.Close) { onFilters(SearchFilters()) }
                            } else {
                                Text(s.searchNone(state.query.text), style = MaterialTheme.typography.titleMedium, color = Tone.text)
                                Text(s.searchNoneDetail, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                                // Nothing in the chats the viewer chose: the way to choose more (HX62).
                                if (state.addedOnly) Text(s.searchNoneAdded, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                                // One main way on, tinted, and the others plain text beside it (Raju pass 2, 4.7):
                                // the order is unchanged, so the remote still walks them down in it.
                                if (state.canSearchOthers) {
                                    TvPill(s.searchOtherChats, TmIcons.Search, chosen = true, modifier = Modifier.testTag("search-other-chats"), onClick = onSearchOthers)
                                }
                                // With All chats on every chat counts: nothing to add, so no way to offer.
                                if (state.canAddChats) {
                                    TvPill(s.pickerAddMore, TmIcons.PersonAdd, chosen = !state.canSearchOthers, plain = state.canSearchOthers, onClick = onAddChats)
                                }
                                AllChatsSearch.fallbackQuery(state.query.server)?.let { word ->
                                    TvPill(s.searchSearchFor(word), TmIcons.Search, plain = true) { onText(word); onSubmit(word) }
                                }
                            }
                        }
                    }
                    else -> {
                        items(shown, key = { it.key }) { group ->
                            PosterCard(
                                group = group,
                                chats = briefs,
                                fraction = group.copies.firstNotNullOfOrNull { progress(it) },
                                reduced = reduced,
                                tileReturn = tileReturn,
                                pageTiles = pageTiles,
                                onFocused = {
                                    focusedGroup = group
                                    // The next page well before the last row.
                                    if (shown.indexOf(group) >= shown.size - COLUMNS * 2) onLoadMore()
                                },
                                onClick = { onOpenGroup(group) },
                                onHold = { if (!group.isShow) onPlayGroup(group) },
                            )
                        }
                        if (state.loadingMore) {
                            item(span = full, key = "more") {
                                Text(s.gridLoadingMore, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                            }
                        }
                        // The last page failed: said under the grid, with Try again for the remote.
                        if (state.moreFailed && !state.loadingMore) {
                            item(span = full, key = "more-failed") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(s.gridMoreFailed, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                                    TvPill(s.commonTryAgain, TmIcons.Refresh, onClick = onRetryMore)
                                }
                            }
                        }
                    }
                }
            }
        }
        // A fixed height, so the list above never moves as the remote does.
        DetailsStrip(focusedGroup?.takeIf { g -> shown.any { it.key == g.key } }, briefs)
        }
    }
    }
    // Where to watch (HX41), in the television's own panel: focus starts on Close, and the other
    // button is the honest way on: search the chats not added this once, else choose more chats,
    // or try again when TMDB could not be reached.
    state.watching?.let { (title, answer) ->
        val retry = answer == com.tmplayer.online.WhereToWatch.Unavailable
        com.tmplayer.ui.components.TvConfirm(
            title = s.searchWhereToWatch,
            message = titleName(title),
            confirmLabel = when {
                retry -> s.commonTryAgain
                state.canSearchOthers -> s.searchOtherChats
                else -> s.pickerAddMore
            },
            onConfirm = {
                when {
                    retry -> onWhereToWatch(title)
                    state.canSearchOthers -> { onCloseWhereToWatch(); onSearchOthers() }
                    else -> { onCloseWhereToWatch(); onAddChats() }
                }
            },
            onDismiss = onCloseWhereToWatch,
            cancelLabel = s.commonClose,
            destructive = false,
            icon = TmIcons.Info,
            extra = { WhereToWatchLines(answer) },
        )
    }
}

/**
 * Lays a row out [extra] wider than the cell it is in, the extra on the end side, so a row in the
 * results' list keeps the reach it had before the list took a margin for its posters. The list
 * draws into its own padding, so nothing is cut.
 */
private fun Modifier.bleedEnd(extra: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val more = extra.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + more, maxWidth = constraints.maxWidth + more),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Where the results' list scrolls for the remote: a poster already wholly in view moves nothing,
 * and one that is not comes in just far enough to show all of it, its focus ring and its grown
 * size included ([CARD_CLEARANCE]), at the top or the foot of the list. The television's own rule
 * stands every focused item a third of the way down, which with the details strip under the
 * list is where a poster's foot was cut. A poster taller than the list (a very large font) is
 * shown from its top. Rows inside the list (the pills, the chats) ask with their width and keep
 * the platform's rule.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WholeCardInView(grid: LazyGridState, content: @Composable () -> Unit) {
    val platform = LocalBringIntoViewSpec.current
    val clearance = with(LocalDensity.current) { CARD_CLEARANCE.toPx() }
    val spec = remember(grid, platform, clearance) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val viewport = grid.layoutInfo.viewportSize.height.toFloat()
                if (viewport <= 0f || containerSize != viewport) {
                    return platform.calculateScrollDistance(offset, size, containerSize)
                }
                return WholeInView.distance(offset, size, containerSize, clearance)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** [WholeCardInView]'s arithmetic, apart from composition so a test can ask it directly. */
internal object WholeInView {
    /**
     * How far to scroll (positive is down the list) so an item at [offset] of [size] stands wholly
     * in a list of [container] with [clearance] above and below it: nothing when it already does.
     */
    fun distance(offset: Float, size: Float, container: Float, clearance: Float): Float {
        val top = offset - clearance
        val foot = offset + size + clearance
        return when {
            top >= 0f && foot <= container -> 0f
            // Taller than the list, or above it: its top at the list's top.
            foot - top > container || top < 0f -> top
            // Below the list: its foot at the list's foot.
            else -> foot - container
        }
    }
}

/** The microphone, round and large: the first thing focused, since speaking is the quick way to type here. */
@Composable
private fun MicOrb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    // The focus fill every other control on a television takes, not the accent: in daylight the
    // accent is a dark blue that made this the one control lit differently from the rest.
    val fill by animateColorAsState(if (focused) Tone.focusFill else Tone.surfaceHigh, Motion.standard(Motion.Fast), label = "micOrb")
    com.tmplayer.ui.components.IconTip(s.browseSearchByVoice, modifier, side = com.tmplayer.ui.components.TipSide.Below) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice, tint = if (focused) Tone.onFocusFill else Tone.text, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * A round pill a remote can land on: a recent search, a suggestion, a filter. [chosen] fills it with
 * the accent; [plain] (the recent searches and suggestions) is words and an icon with no box until
 * the remote is on it, as the phone draws them.
 */
@Composable
private fun TvPill(label: String, icon: ImageVector?, chosen: Boolean = false, plain: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val fill by animateColorAsState(
        when {
            focused -> Tone.focusFill
            chosen -> Tone.accent.copy(alpha = 0.22f)
            plain -> Color.Transparent
            else -> Tone.surfaceHigh
        },
        Motion.standard(Motion.Fast),
        label = "tvPill",
    )
    val ink = if (focused) Tone.onFocusFill else Tone.text
    // The pill the remote lands on grows as every control does on a television (one scale, FocusStyle), so nothing draws past its bounds.
    val lessMotion = rememberReducedMotion() || Motion.reduced
    val scale by animateFloatAsState(if (focused && !lessMotion) focusScale else 1f, Motion.standard(Motion.Fast), label = "tvPillScale")
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(48.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            // A filter that is on says so to a screen reader, not by its fill alone (WP-90).
            .semantics { if (chosen && !plain) selected = true }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (plain && !focused) Tone.muted else ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A chat as a card: its picture large, its name under it. */
@Composable
private fun ChatCard(chat: ChatSummary, reduced: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && !reduced) focusScale else 1f, Motion.standard(Motion.Fast), label = "chatCard")
    Column(
        Modifier
            .width(112.dp)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChatAvatar(
            ChatBrief.of(chat),
            80.dp,
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(if (focused) focusStrokeWidth(3.dp) else 0.dp, if (focused) Tone.accent else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            chat.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) Tone.text else Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One result as a 2:3 poster, with the chats it is in stacked over its foot and its copies counted in a corner. */
@Composable
private fun PosterCard(
    group: ResultGroup,
    chats: Map<Long, ChatBrief>,
    fraction: Float?,
    reduced: Boolean,
    tileReturn: TileReturn,
    pageTiles: com.tmplayer.ui.components.PageTiles,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onHold: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && !reduced) focusScale else 1f, Motion.standard(Motion.Fast), label = "poster")
    val item = remember(group) { group.asItem() }
    val title = remember(group) { GlobalSearch.titleOf(group) }
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .border(if (focused) focusStrokeWidth(3.dp) else 0.dp, if (focused) Tone.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .pageTile(pageTiles, group.key)
            .returnTile(tileReturn, group.key)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = title }
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),
    ) {
        ResultPoster(item, Modifier.fillMaxWidth(), fraction)
        // Files, as the page counts its versions: the same file in two chats is one.
        val files = remember(group.copies) { group.fileCount }
        if (files > 1 && !group.isShow) CopiesChip(files, Modifier.align(Alignment.TopEnd).padding(6.dp), onArt = true)
        AvatarStack(
            group.chatIds,
            chats,
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(Tone.scrim).padding(4.dp),
            size = 22.dp,
        )
    }
}

/** What the remote is on, said in full under the grid: the whole title, its facts, the chats it is in. */
@Composable
private fun DetailsStrip(group: ResultGroup?, chats: Map<Long, ChatBrief>) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().height(STRIP).padding(top = 12.dp, end = Tv.SafeH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (group == null) return@Column
        Text(GlobalSearch.titleOf(group), style = MaterialTheme.typography.titleLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(resultFacts(group), style = MaterialTheme.typography.bodyLarge, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val names = group.chatIds.mapNotNull { chats[it]?.title }.joinToString(", ") { "⁨$it⁩" }
        if (names.isNotEmpty()) {
            Text(s.searchInChats(names), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Three posters across beside the keyboard: the television's grid of films and shows. */
private const val COLUMNS = 3

/** The keyboard's column: six keys of about 40 dp across, with their gaps. */
private val KEYBOARD_WIDTH = 288.dp

/** The details strip's height, fixed so the grid never jumps. */
private val STRIP = 96.dp

/** Room kept round a focused poster as the list brings it in: its grown size and its focus ring. */
private val CARD_CLEARANCE = 16.dp

/**
 * [TvSearchScreen] with what it needs gathered: the model's state, the recent searches (none
 * while the kids lock is on or searches are not kept), the suggestions from the starred chats'
 * newest videos on Home, and what opening a result does: checked first, since a video may have
 * been deleted or its chat left since the search, then the show's page or the video's.
 */
@Composable
internal fun TvSearchPage(
    search: GlobalSearchViewModel,
    state: GlobalSearchState,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    homeRows: List<com.tmplayer.data.HomeRow>,
    watch: SeriesWatch,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenMedia: (MediaItem, String) -> Unit,
    onPlayMedia: (MediaItem, String) -> Unit,
    onMessage: (String) -> Unit,
    /** A video's page is up over the search (the shell's detail page). */
    pageOpen: Boolean = false,
    account: com.tmplayer.data.Account? = null,
) {
    val s = LocalStrings.current
    val tileReturn = rememberTileReturn()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.tmplayer.data.SettingsStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query.text) }
    // Words, filters and sort start fresh after two minutes away or a restore into a new process.
    SearchFreshness {
        text = ""
        search.setFilters(com.tmplayer.data.SearchFilters())
        search.setSort(com.tmplayer.data.SearchSort.BestMatch)
    }
    LaunchedEffect(text) { search.type(text) }
    val recent by remember(settings) { settings.recentSearches }.collectAsState(initial = emptyList())
    val keeps by remember(settings) { settings.rememberSearches }.collectAsState(initial = true)
    val allowed by com.tmplayer.data.ChatGate.allowed.collectAsState()
    val suggestions = remember(homeRows, favourites) {
        homeRows.filterIsInstance<com.tmplayer.data.HomeRow.Chat>()
            .filter { it.chatId in favourites }
            .flatMap { row -> row.entries.mapNotNull { (it as? com.tmplayer.data.ShelfEntry.File)?.item } }
            .sortedByDescending { it.date }
            .map { com.tmplayer.data.SeriesShelf.posterTitle(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(GlobalSearch.RECENT_SHOWN)
    }
    var opened by remember { mutableStateOf<Pair<com.tmplayer.data.Series, String>?>(null) }
    val titleOf: (Long) -> String = { id -> state.chats[id]?.title ?: chats.firstOrNull { it.id == id }?.title.orEmpty() }
    // Which groups the search by topic name may ask, in the chat list's order (U14).
    LaunchedEffect(chats) { search.chatList = chats }
    val topicMemory = remember(settings) { settings.topicMemory() }

    TvSearchScreen(
        state = state,
        text = text,
        onText = { text = it },
        onSubmit = { search.submit(it) },
        chats = chats,
        favourites = favourites,
        recentSearches = if (allowed != null || !keeps) emptyList() else recent,
        suggestions = if (allowed != null) emptyList() else suggestions,
        progress = { item -> watch.point(item)?.fraction },
        onOpenChat = { chat -> search.remember(); onOpenChat(chat) },
        onOpenGroup = { group ->
            scope.launch {
                search.openResult(group.lead, GlobalSearch.titleOf(group), onGone = { onMessage(s.searchNoLongerThere) }) {
                    search.remember()
                    // Only once it opens: a result that turned out gone leaves nothing to come back to.
                    tileReturn.open(group.key)
                    val series = if (group.isShow) {
                        com.tmplayer.data.SeriesShelf.arrange(group.copies).firstNotNullOfOrNull { (it as? com.tmplayer.data.ShelfEntry.Show)?.series }
                    } else {
                        null
                    }
                    if (series != null) opened = series to titleOf(group.lead.chatId) else onOpenMedia(group.asItem(), titleOf(group.lead.chatId))
                }
            }
        },
        onPlayGroup = { group ->
            scope.launch {
                val copy = group.copies.firstOrNull { (watch.point(it)?.fraction ?: 0f) > 0f } ?: group.lead
                search.openResult(copy, GlobalSearch.titleOf(group), onGone = { onMessage(s.searchNoLongerThere) }) {
                    search.remember()
                    onPlayMedia(copy, titleOf(copy.chatId))
                }
            }
        },
        onFilters = search::setFilters,
        onSort = search::setSort,
        onLoadMore = search::loadMore,
        onRetryMore = search::retryMore,
        onRetry = search::retry,
        onSearchOthers = search::searchOtherChats,
        tileReturn = tileReturn,
        pageOpen = pageOpen || opened != null,
        account = account,
        onOpenTopic = { hit ->
            chats.firstOrNull { it.id == hit.chatId }?.let { chat ->
                scope.launch {
                    openOnTopic(topicMemory, hit.chatId, hit.topic.id)
                    search.remember()
                    onOpenChat(chat)
                }
            }
        },
        onRetryTopics = search::retryTopics,
        onWhereToWatch = search::openWhereToWatch,
        onCloseWhereToWatch = search::closeWhereToWatch,
        onRetryTitles = search::retryTitles,
    )
    opened?.let { (series, chatTitle) ->
        SeriesOpened(
            series = series, watch = watch, onPlay = { onPlayMedia(it, chatTitle) }, onDismiss = { opened = null }, chatTitle = chatTitle,
            onOpenChat = chats.firstOrNull { it.id == series.cover.chatId }?.let { chat -> { opened = null; search.remember(); onOpenChat(chat) } },
        )
    }
}
