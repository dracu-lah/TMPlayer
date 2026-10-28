package com.tmplayer.ui.browse

import com.tmplayer.ui.components.keepTopInSight
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
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
    onFilters: (SearchFilters) -> Unit,
    onSort: (SearchSort) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** Whose Telegram this is, for the picture beside the clock every rail page carries. */
    account: com.tmplayer.data.Account? = null,
) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    var keyboardRequests by remember { mutableIntStateOf(0) }
    val voice = rememberVoiceSearch(
        s.browseVoicePromptChat,
        onKeyboard = { keyboardRequests++ },
        onResult = { heard -> onText(heard); onSubmit(heard) },
    )
    val micFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    // A frame first: the field is an item of the results' list now, composed as the list is laid out.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { if (voice != null) micFocus.requestFocus() else fieldFocus.requestFocus() }
    }

    val byUsername = rememberUsernameChat(text)
    val chatMatches = remember(chats, text, favourites, byUsername) {
        if (text.isBlank()) GlobalSearch.recentChats(chats, favourites, limit = 10)
        else withUsernameChat(GlobalSearch.localChats(chats, text, favourites), byUsername).take(10)
    }
    // A typed post or invite link, or a public chat to join (CP79), as a pill beside the field.
    val offer = rememberLinkOffer(text, byUsername)
    val briefs = remember(state.chats, chats) { state.chats + chats.associate { it.id to ChatBrief.of(it) } }
    var focusedGroup by remember { mutableStateOf<ResultGroup?>(null) }
    val grid = com.tmplayer.ui.components.rememberAheadGridState()

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
    Column(Modifier.fillMaxSize().padding(start = TV_START, bottom = Tv.SafeV)) {
        WholeCardInView(grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().keepTopInSight(grid),
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
                            TvPill(s.commonClear, TmIcons.Close) {
                                runCatching { fieldFocus.requestFocus() }
                                onText("")
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

                searchStatus(state)?.let { status ->
                    item(span = full, key = "status") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(status, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                            if (state.error != null || state.offline) TvPill(s.commonRetry, TmIcons.Refresh, onClick = onRetry)
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
                                AllChatsSearch.fallbackQuery(state.query.server)?.let { word ->
                                    TvPill(s.searchSearchFor(word), TmIcons.Search) { onText(word); onSubmit(word) }
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
                                onFocused = {
                                    focusedGroup = group
                                    // The next page well before the last row.
                                    if (shown.indexOf(group) >= shown.size - COLUMNS * 2) onLoadMore()
                                },
                                onClick = { onOpenGroup(group) },
                            )
                        }
                        if (state.loadingMore) {
                            item(span = full, key = "more") {
                                Text(s.gridLoadingMore, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
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
                .focusRing(focused, CircleShape)
                .clickable(interactionSource = interactions, indication = null, onClick = onClick),
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
private fun TvPill(label: String, icon: ImageVector?, chosen: Boolean = false, plain: Boolean = false, onClick: () -> Unit) {
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
    // The pill the remote lands on grows a touch, as the chat header's pills do.
    val lessMotion = rememberReducedMotion() || Motion.reduced
    val scale by animateFloatAsState(if (focused && !lessMotion) 1.06f else 1f, Motion.standard(Motion.Fast), label = "tvPillScale")
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(48.dp)
            .clip(CircleShape)
            .background(fill)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
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
    val scale by animateFloatAsState(if (focused && !reduced) Motion.FocusScale else 1f, Motion.standard(Motion.Fast), label = "chatCard")
    Column(
        Modifier
            .width(112.dp)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChatAvatar(
            ChatBrief.of(chat),
            80.dp,
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(if (focused) 3.dp else 0.dp, if (focused) Tone.accent else Color.Transparent, CircleShape),
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
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && !reduced) Motion.FocusScale else 1f, Motion.standard(Motion.Fast), label = "poster")
    val item = remember(group) { group.asItem() }
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .border(if (focused) 3.dp else 0.dp, if (focused) Tone.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
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

/** Five posters across: the television's grid of films and shows. */
private const val COLUMNS = 5

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
    account: com.tmplayer.data.Account? = null,
) {
    val s = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.tmplayer.data.SettingsStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query.text) }
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
                if (!search.check(group.lead)) {
                    onMessage(s.searchNoLongerThere)
                    return@launch
                }
                search.remember()
                val series = if (group.isShow) {
                    com.tmplayer.data.SeriesShelf.arrange(group.copies).firstNotNullOfOrNull { (it as? com.tmplayer.data.ShelfEntry.Show)?.series }
                } else {
                    null
                }
                if (series != null) opened = series to titleOf(group.lead.chatId) else onOpenMedia(group.asItem(), titleOf(group.lead.chatId))
            }
        },
        onFilters = search::setFilters,
        onSort = search::setSort,
        onLoadMore = search::loadMore,
        onRetry = search::retry,
        account = account,
    )
    opened?.let { (series, chatTitle) ->
        SeriesOpened(
            series = series, watch = watch, onPlay = { onPlayMedia(it, chatTitle) }, onDismiss = { opened = null }, chatTitle = chatTitle,
            onOpenChat = chats.firstOrNull { it.id == series.cover.chatId }?.let { chat -> { opened = null; search.remember(); onOpenChat(chat) } },
        )
    }
}
