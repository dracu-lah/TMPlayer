package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FilmLookup
import com.tmplayer.data.Tmdb
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.ChatListSkeleton
import com.tmplayer.ui.components.NetworkImage
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.data.ResumeRecord
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv

/** The rail's sections, in the order they appear. */
enum class BrowseTab(
    val label: String,
    val heading: String,
    val blurb: String,
    val icon: ImageVector,
) {
    Continue("Continue", "Continue watching", "Pick up where you left off", Icons.Filled.PlayArrow),
    Favorites("Favourites", "Favourites", "Chats you've starred", Icons.Filled.Star),
    // A clock, not the circular-arrow reload glyph: that one is the film grid's genuine refresh
    // action, so the same picture stood for two unrelated things.
    Recent("Recent", "Recent", "Chats with something new", TmIcons.Clock),
    Channels("Channels", "Channels", "Broadcast channels you follow", TmIcons.Channel),
    Groups("Groups", "Groups", "Groups you're in", TmIcons.Group),
    People("People", "People", "Your one-to-one chats", Icons.Filled.Person),
    All("All chats", "All chats", "Everything, newest first", Icons.Filled.List),
}

@Composable
fun BrowseScreen(
    state: UiState<BrowseData>,
    favorites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    onRetry: () -> Unit,
    onOpenChat: (ChatSummary) -> Unit,
    onResumeFilm: (ResumeRecord) -> Unit,
    onOpenSettings: () -> Unit,
    picked: BrowseTab? = null,
    onPickTab: (BrowseTab) -> Unit = {},
    /**
     * Drawn above the content, beside the navigation rail rather than above it.
     * A banner stacked over the whole screen pushes the rail down far enough to shove Settings
     * off the bottom edge, which leaves the viewer no way to reach it.
     */
    banner: @Composable () -> Unit = {},
) {
    // An unfinished film wins the landing tab, then Favourites, then Recent, so the first screen
    // is never empty. Both are read from disk after the first frame, so the tab has to settle
    // once they arrive; an explicit pick always wins over them. [picked] is hoisted rather than
    // remembered here because opening a chat swaps this screen out of the composition.
    val tab = picked ?: when {
        continueWatching.isNotEmpty() -> BrowseTab.Continue
        favorites.isEmpty() -> BrowseTab.Recent
        else -> BrowseTab.Favorites
    }
    var query by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        NavRail(
            account = (state as? UiState.Content)?.value?.account,
            selected = tab,
            favoriteCount = favorites.size,
            onSelect = { onPickTab(it); query = "" },
            onOpenSettings = onOpenSettings,
        )

        Column(Modifier.fillMaxSize().padding(end = Tv.SafeH)) {
            banner()
            StateScaffold(state, onRetry = onRetry, loading = { ChatListSkeleton() }) { data ->
                val visible = remember(data.chats, tab, favorites, query) {
                    filterChats(data.chats, tab, favorites, query)
                }

                Column(Modifier.fillMaxSize()) {
                    if (tab == BrowseTab.Continue) {
                        Header(tab, continueWatching.size)
                        Spacer(Modifier.height(20.dp))
                        if (continueWatching.isEmpty()) {
                            EmptyTab(tab, query = "")
                        } else {
                            ContinueList(continueWatching, onResumeFilm)
                        }
                    } else {
                        Header(tab, visible.size)
                        SearchRow(query = query, onQuery = { query = it })
                        Spacer(Modifier.height(20.dp))

                        if (visible.isEmpty()) {
                            EmptyTab(tab, query)
                        } else {
                            ChatList(
                                chats = visible,
                                favorites = favorites,
                                // Recency order comes straight from Telegram, so the top of the
                                // unfiltered list already is "recent", with no sorting to go stale.
                                recent = if (tab == BrowseTab.Recent && query.isBlank()) {
                                    visible.take(RECENT_COUNT)
                                } else {
                                    emptyList()
                                },
                                onOpenChat = onOpenChat,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- continue watching -----------------------------------------------------------------------

@Composable
private fun ContinueList(
    records: List<ResumeRecord>,
    onResume: (ResumeRecord) -> Unit,
) {
    val first = remember { FocusRequester() }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // The same start inset the chat list uses, so the cards line up under their heading
        // instead of butting against the rail.
        contentPadding = PaddingValues(start = 28.dp, bottom = Tv.SafeV),
    ) {
        items(records, key = { "${it.chatId}_${it.messageId}" }) { record ->
            ContinueCard(
                record = record,
                onResume = onResume,
                modifier = if (record === records.firstOrNull()) {
                    Modifier.focusRequester(first)
                } else {
                    Modifier
                },
            )
        }
    }

    // This is the landing tab for anyone with a film on the go, and the remote has nowhere to go
    // until something holds focus.
    LaunchedEffect(records.firstOrNull()?.messageId) {
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun ContinueCard(
    record: ResumeRecord,
    onResume: (ResumeRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(140),
        label = "continueBorder",
    )

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(2.dp, border, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactions, indication = null) { onResume(record) }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContinueThumbnail(record)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                record.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Text(
                buildString {
                    append(StreamStats.formatClock(record.positionMs))
                    if (record.remainingMs > 0) {
                        append("  ·  ")
                        append(StreamStats.formatClock(record.remainingMs))
                        append(" left")
                    }
                    if (record.chatTitle.isNotBlank()) {
                        append("  ·  ")
                        append(record.chatTitle)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The bar is the whole point of the row: it is what tells the viewer, at a glance,
            // that this is a film they are part way through rather than one they have not started.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextMuted.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(record.fraction.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(Accent),
                )
            }
        }
    }
}

/**
 * Film art for a Continue watching row, with a play badge over it.
 *
 * There is no Telegram thumbnail to fall back on here: a resume record stores only enough to
 * reopen the file, so that a film stays resumable even when its chat has not been loaded this
 * session. TMDB is therefore the only source of art, and when it has nothing (no key, no match,
 * no network) the row falls back to the play icon it used before. That is a complete row, not a
 * broken one, so the failure is never mentioned.
 */
@Composable
private fun ContinueThumbnail(record: ResumeRecord) {
    val art by produceState<String?>(initialValue = null, key1 = record.title) {
        value = (Tmdb.lookup(record.title) as? FilmLookup.Found)?.details?.backdropUrl
    }

    Box(
        Modifier
            .width(THUMBNAIL_WIDTH)
            .height(THUMBNAIL_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        NetworkImage(
            url = art,
            modifier = Modifier.fillMaxSize(),
            placeholder = {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(30.dp),
                )
            },
        )
        if (art != null) {
            // Over real artwork the icon needs its own backing to stay legible.
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private val THUMBNAIL_WIDTH = 96.dp
private val THUMBNAIL_HEIGHT = 54.dp

private fun filterChats(
    chats: List<ChatSummary>,
    tab: BrowseTab,
    favorites: Set<Long>,
    query: String,
): List<ChatSummary> {
    val byTab = when (tab) {
        BrowseTab.Favorites -> chats.filter { it.id in favorites }
        BrowseTab.Channels -> chats.filter { it.kind == ChatKind.Channel }
        BrowseTab.Groups -> chats.filter { it.kind == ChatKind.Group }
        BrowseTab.People -> chats.filter { it.kind == ChatKind.Direct }
        // Continue watching is a list of films, not chats; it never reaches this filter.
        BrowseTab.Continue, BrowseTab.Recent, BrowseTab.All -> chats
    }
    if (query.isBlank()) return byTab
    return byTab.filter { it.title.contains(query.trim(), ignoreCase = true) }
}

// ---- navigation rail -------------------------------------------------------------------------

@Composable
private fun NavRail(
    account: Account?,
    selected: BrowseTab,
    favoriteCount: Int,
    onSelect: (BrowseTab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(SurfaceDark)
            // The rail is the leftmost thing on the screen, so it alone decides whether the app
            // clears the TV's overscan crop. Its children each add [RAIL_INSET] of their own, so
            // the column only has to make up the difference and nothing starts before Tv.SafeH.
            .padding(
                start = Tv.SafeH - RAIL_INSET,
                end = 12.dp,
                top = Tv.SafeV,
                bottom = Tv.SafeV,
            ),
    ) {
        AccountBadge(account)
        Spacer(Modifier.height(18.dp))

        BrowseTab.entries.forEach { entry ->
            RailItem(
                label = entry.label,
                icon = entry.icon,
                badge = if (entry == BrowseTab.Favorites && favoriteCount > 0) {
                    favoriteCount.toString()
                } else {
                    null
                },
                selected = entry == selected,
                onClick = { onSelect(entry) },
            )
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.weight(1f))
        RailItem(
            label = "Settings",
            icon = Icons.Filled.Settings,
            badge = null,
            selected = false,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun AccountBadge(account: Account?) {
    Row(
        Modifier.padding(start = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(SurfaceRaised)) {
            if (account != null) {
                Poster(
                    miniThumbnail = account.miniThumbnail,
                    thumbnailFileId = account.photoFileId,
                    fallbackLabel = account.name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                // Not "Signing in…": the viewer is already signed in by the time this draws, and
                // only the name and picture are still on their way.
                account?.name ?: "Your account",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!account?.username.isNullOrBlank()) {
                Text(
                    "@${account.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Built from `clickable` rather than a TV Material Card.
 *
 * Those cards enlarge on focus, and anything that fills its width visibly bursts past the screen
 * edge when it grows. A rail item signals focus with colour instead, which cannot overflow.
 */
@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    // Cross-faded rather than switched: an instant colour flip as focus sweeps down the rail
    // reads as flicker on a large screen.
    val background by animateColorAsState(
        targetValue = when {
            focused -> Accent
            selected -> Accent.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railBackground",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> Accent
            else -> TextMuted
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railForeground",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Text(badge, style = MaterialTheme.typography.bodyMedium, color = foreground)
        }
    }
}

// ---- content ---------------------------------------------------------------------------------

@Composable
private fun Header(tab: BrowseTab, count: Int) {
    // A bare number after the blurb leaves the viewer to guess what was counted, so it always
    // carries its unit, and this one tab counts films rather than chats.
    val unit = when {
        tab == BrowseTab.Continue && count == 1 -> "film"
        tab == BrowseTab.Continue -> "films"
        count == 1 -> "chat"
        else -> "chats"
    }
    Column(Modifier.padding(start = 28.dp, top = Tv.SafeV, bottom = 12.dp)) {
        Text(tab.heading, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Text(
            if (count > 0) "${tab.blurb}  ·  $count $unit" else tab.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit) {
    val startVoice = rememberVoiceSearch("Say a chat name", onQuery)
    val searchField = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search chats",
            modifier = Modifier.weight(1f).focusRequester(searchField),
        )
        if (startVoice != null) {
            PillButton("Voice search", TmIcons.Mic, startVoice)
        }
        if (query.isNotBlank()) {
            // Clearing the query is what removes this pill from the screen, and a control that
            // deletes itself while focused takes the focus with it. The next press of the D-pad
            // then goes nowhere. Focus moves to the search field first, which is where the
            // viewer wants to be next anyway.
            PillButton("Clear", Icons.Filled.Close) {
                runCatching { searchField.requestFocus() }
                onQuery("")
            }
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector?, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceRaised,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "pillBackground",
    )
    val foreground = if (focused) Color.White else TextPrimary

    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChatList(
    chats: List<ChatSummary>,
    favorites: Set<Long>,
    recent: List<ChatSummary>,
    onOpenChat: (ChatSummary) -> Unit,
) {
    val first = remember { FocusRequester() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, bottom = Tv.SafeV),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (recent.isNotEmpty()) {
            item(key = "recent-strip") {
                Column {
                    Text(
                        "Jump back in",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(recent, key = { "r-${it.id}" }) { chat ->
                            RecentTile(
                                chat = chat,
                                onClick = { onOpenChat(chat) },
                                modifier = if (chat === recent.firstOrNull()) {
                                    Modifier.focusRequester(first)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                    Text(
                        // Not "All chats": that is the name of a rail tab, and repeating it as a
                        // sub-heading inside a different tab reads as if the viewer moved.
                        "More chats",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 32.dp, bottom = 4.dp),
                    )
                }
            }
        }

        items(chats, key = { it.id }) { chat ->
            ChatRow(
                chat = chat,
                favorite = chat.id in favorites,
                onClick = { onOpenChat(chat) },
                modifier = if (recent.isEmpty() && chat === chats.firstOrNull()) {
                    Modifier.focusRequester(first)
                } else {
                    Modifier
                },
            )
        }
    }

    // The remote has nowhere to go until something holds focus. The target is the first tile of
    // the "Jump back in" strip whenever that strip is on screen: it is the largest thing there,
    // and it is what a viewer with no favourites and no unfinished film lands on.
    LaunchedEffect(chats.firstOrNull()?.id, recent.firstOrNull()?.id) {
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun RecentTile(chat: ChatSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    Column(
        modifier
            .width(RECENT_TILE_WIDTH)
            // Fixed, not wrapped: a chat whose name runs to two lines would otherwise stand
            // taller than its neighbours and leave the row visibly ragged.
            .height(RECENT_TILE_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(
                width = 3.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Poster(
            miniThumbnail = chat.miniThumbnail,
            thumbnailFileId = chat.photoFileId,
            fallbackLabel = chat.title,
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            chat.title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Ellipsis is enough to keep the row tidy, but it also hides the end of a long
            // channel name. Scrolling it while focused means the whole name is still readable,
            // and only for the one tile the viewer is actually looking at.
            modifier = Modifier.weight(1f).marqueeWhen(focused),
        )
        Text(
            chat.kind.label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            maxLines = 1,
        )
    }
}

/** Scrolls overflowing text, but only while [active]; a wall of moving labels is unreadable. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.marqueeWhen(active: Boolean): Modifier =
    if (active) basicMarquee(iterations = Int.MAX_VALUE) else this

@Composable
private fun ChatRow(
    chat: ChatSummary,
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    Row(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(
                width = 3.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(
            miniThumbnail = chat.miniThumbnail,
            thumbnailFileId = chat.photoFileId,
            fallbackLabel = chat.title,
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Text(
                chat.kind.label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
            )
        }
        if (favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourite",
                tint = Accent,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun EmptyTab(tab: BrowseTab, query: String) {
    // Each empty tab says what to do about it, in its own words.
    val message = when {
        query.isNotBlank() -> "Nothing matches “$query”."
        tab == BrowseTab.Continue -> "You haven't started a film yet. Open a chat and pick one."
        tab == BrowseTab.Favorites ->
            "No favourites yet. Open a chat and press Favourite to add it here."
        else -> "Nothing here yet."
    }
    Column(
        Modifier.fillMaxSize().padding(start = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A search that came back empty is a different situation from a tab that has nothing in
        // it yet, so the glyph follows whichever one the viewer is actually looking at.
        Icon(
            imageVector = if (query.isNotBlank()) Icons.Filled.Search else tab.icon,
            contentDescription = null,
            tint = TextMuted.copy(alpha = 0.55f),
            modifier = Modifier.size(56.dp),
        )
        Text(message, style = MaterialTheme.typography.titleLarge, color = TextMuted)
    }
}

private const val RECENT_COUNT = 8
private const val FOCUS_FADE_MS = 140
/** Horizontal padding every rail child carries, which is what keeps them out of the overscan. */
private val RAIL_INSET = 16.dp
private val RECENT_TILE_WIDTH = 224.dp
private val RECENT_TILE_HEIGHT = 154.dp
