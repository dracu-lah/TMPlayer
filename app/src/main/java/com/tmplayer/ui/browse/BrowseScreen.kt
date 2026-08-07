package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FilmLookup
import com.tmplayer.data.Tmdb
import com.tmplayer.data.Updates
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.ChatListSkeleton
import com.tmplayer.ui.components.NetworkImage
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.data.ResumeRecord
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Caution
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
    onToggleFavorite: (ChatSummary) -> Unit = {},
    onRestartFilm: (ResumeRecord) -> Unit = {},
    onForgetFilm: (ResumeRecord) -> Unit = {},
    /** Empties Continue watching in one go, rather than one held-OK menu per film. */
    onClearHistory: () -> Unit = {},
    /** Unstars every chat in one go, the counterpart to the star in each chat's menu. */
    onClearFavorites: () -> Unit = {},
    /** The chat that reopens on launch, marked on its row so the jump is never unexplained. */
    launchChatId: Long = 0L,
    picked: BrowseTab? = null,
    onPickTab: (BrowseTab) -> Unit = {},
    /**
     * Rows or tiles, chosen in Settings. One arrangement covers every tab: they are all lists of
     * the same card.
     */
    layout: CardLayout = CardLayout.List,
    /** The newer version on GitHub, if there is one. Shown on the rail, in amber. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
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
    // What the viewer held OK on. Only ever one at a time, so two nullable slots cover both lists.
    var chatMenu by remember { mutableStateOf<ChatSummary?>(null) }
    var filmMenu by remember { mutableStateOf<ResumeRecord?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearFavorites by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        NavRail(
            account = (state as? UiState.Content)?.value?.account,
            selected = tab,
            favoriteCount = favorites.size,
            onSelect = { onPickTab(it); query = "" },
            onOpenSettings = onOpenSettings,
            updateVersion = updateVersion,
            onUpdate = onUpdate,
        )

        Column(Modifier.fillMaxSize().padding(end = Tv.SafeH)) {
            StateScaffold(
                state,
                onRetry = onRetry,
                loading = { ChatListSkeleton(layout = layout) },
            ) { data ->
                val visible = remember(data.chats, tab, favorites, query) {
                    filterChats(data.chats, tab, favorites, query)
                }

                Column(Modifier.fillMaxSize()) {
                    if (tab == BrowseTab.Continue) {
                        Header(tab, continueWatching.size) {
                            if (continueWatching.isNotEmpty()) {
                                HeaderAction(
                                    label = "Clear history",
                                    icon = Icons.Filled.Close,
                                    onClick = { confirmClearHistory = true },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        if (continueWatching.isEmpty()) {
                            EmptyTab(tab, query = "")
                        } else {
                            ContinueSection(
                                records = continueWatching,
                                layout = layout,
                                onResume = onResumeFilm,
                                onHold = { filmMenu = it },
                            )
                        }
                    } else {
                        Header(tab, visible.size) {
                            // Stars are added one at a time from a menu, so the only way back from
                            // a tab full of them is here, beside the tab they fill.
                            if (tab == BrowseTab.Favorites && favorites.isNotEmpty()) {
                                HeaderAction(
                                    label = "Clear favourites",
                                    icon = Icons.Filled.Close,
                                    onClick = { confirmClearFavorites = true },
                                )
                            }
                        }
                        SearchRow(query = query, onQuery = { query = it })
                        Spacer(Modifier.height(20.dp))

                        if (visible.isEmpty()) {
                            EmptyTab(tab, query)
                        } else {
                            ChatSection(
                                chats = visible,
                                favorites = favorites,
                                // Recency order comes straight from Telegram, so the top of the
                                // unfiltered list already is "recent", with no sorting to go stale.
                                // Tiles are dropped from the strip in grid view: the grid's own
                                // first row is those same chats, and the two together read as the
                                // list having repeated itself rather than as a shortcut.
                                recent = if (
                                    tab == BrowseTab.Recent &&
                                    query.isBlank() &&
                                    layout == CardLayout.List
                                ) {
                                    visible.take(RECENT_COUNT)
                                } else {
                                    emptyList()
                                },
                                layout = layout,
                                onOpenChat = onOpenChat,
                                onHold = { chatMenu = it },
                                launchChatId = launchChatId,
                            )
                        }
                    }
                }
            }
        }
    }

    chatMenu?.let { chat ->
        val favorite = chat.id in favorites
        TvMenu(
            title = chat.title,
            subtitle = chat.kind.label,
            onDismiss = { chatMenu = null },
            actions = listOf(
                MenuAction("Open", Icons.Filled.PlayArrow) {
                    chatMenu = null
                    onOpenChat(chat)
                },
                MenuAction(
                    label = if (favorite) "Remove from favourites" else "Add to favourites",
                    icon = if (favorite) Icons.Filled.Star else TmIcons.StarOutline,
                    detail = if (favorite) {
                        "Takes it out of the Favourites tab"
                    } else {
                        "Keeps it one press away in the Favourites tab"
                    },
                ) {
                    chatMenu = null
                    onToggleFavorite(chat)
                },
            ),
        )
    }

    if (confirmClearFavorites) {
        TvConfirm(
            title = "Clear favourites?",
            message = "All ${favorites.size} chats lose their star and this tab empties.",
            detail = "The chats themselves stay where they are, in Recent and All chats.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClearFavorites = false
                onClearFavorites()
            },
            onDismiss = { confirmClearFavorites = false },
        )
    }

    if (confirmClearHistory) {
        TvConfirm(
            title = "Clear Continue watching?",
            message = "All ${continueWatching.size} films are forgotten and the tab empties. " +
                "Nothing is deleted from Telegram.",
            detail = "You can still find each film in the chat it came from.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClearHistory = false
                onClearHistory()
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    filmMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = StreamStats.formatClock(record.positionMs) + " watched",
            onDismiss = { filmMenu = null },
            actions = listOf(
                MenuAction("Resume", Icons.Filled.PlayArrow, detail = "Carry on where you stopped") {
                    filmMenu = null
                    onResumeFilm(record)
                },
                MenuAction("Play from the start", Icons.Filled.Refresh) {
                    filmMenu = null
                    onRestartFilm(record)
                },
                MenuAction(
                    label = "Remove from Continue watching",
                    icon = Icons.Filled.Close,
                    detail = "The film stays in its chat",
                    destructive = true,
                ) {
                    filmMenu = null
                    onForgetFilm(record)
                },
            ),
        )
    }
}

// ---- continue watching -----------------------------------------------------------------------

@Composable
private fun ContinueSection(
    records: List<ResumeRecord>,
    layout: CardLayout,
    onResume: (ResumeRecord) -> Unit,
    onHold: (ResumeRecord) -> Unit,
) {
    val first = remember { FocusRequester() }
    // Only the first card asks for focus, and which card that is does not change with the
    // arrangement, so the two branches can share one modifier.
    fun focusOf(record: ResumeRecord): Modifier =
        if (record === records.firstOrNull()) Modifier.focusRequester(first) else Modifier

    // The same start inset the chat list uses, so the cards line up under their heading instead of
    // butting against the rail.
    val padding = PaddingValues(start = 28.dp, end = 4.dp, bottom = Tv.SafeV)

    when (layout) {
        CardLayout.List -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = padding,
        ) {
            items(records, key = { "${it.chatId}_${it.messageId}" }) { record ->
                ContinueCard(
                    record = record,
                    onResume = onResume,
                    onHold = onHold,
                    modifier = focusOf(record),
                )
            }
        }

        CardLayout.Grid -> LazyVerticalGrid(
            columns = GridCells.Fixed(TILE_COLUMNS),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(records, key = { "${it.chatId}_${it.messageId}" }) { record ->
                ContinueTile(
                    record = record,
                    onResume = onResume,
                    onHold = onHold,
                    modifier = focusOf(record),
                )
            }
        }
    }

    // This is the landing tab for anyone with a film on the go, and the remote has nowhere to go
    // until something holds focus. Re-run on a change of arrangement too: switching rebuilds the
    // list from scratch, and the card that was holding focus leaves the composition with it.
    LaunchedEffect(records.firstOrNull()?.messageId, layout) {
        runCatching { first.requestFocus() }
    }
}

/**
 * A Continue watching card as a tile: the art carries the progress bar, the way a film poster in
 * the grid does, because there is no longer a row of text alongside it to put the bar under.
 */
@Composable
private fun ContinueTile(
    record: ResumeRecord,
    onResume: (ResumeRecord) -> Unit,
    onHold: (ResumeRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "continueTileBorder",
    )

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(14.dp))
            .holdable(
                interactionSource = interactions,
                onClick = { onResume(record) },
                onHold = { onHold(record) },
            ),
    ) {
        Box {
            ContinueArt(record, Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge = 40.dp)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(record.fraction.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(Accent),
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                record.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(StreamStats.formatClock(record.positionMs))
                    if (record.remainingMs > 0) {
                        append("  ·  ")
                        append(StreamStats.formatClock(record.remainingMs))
                        append(" left")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContinueCard(
    record: ResumeRecord,
    onResume: (ResumeRecord) -> Unit,
    onHold: (ResumeRecord) -> Unit,
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
            .holdable(
                interactionSource = interactions,
                onClick = { onResume(record) },
                onHold = { onHold(record) },
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContinueArt(
            record,
            Modifier.width(THUMBNAIL_WIDTH).height(THUMBNAIL_HEIGHT),
            badge = 34.dp,
        )
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
                    // The chat name gives way to the hint while focused. Both are tail information
                    // on a single line, and only one of them is worth saying to the row the
                    // viewer is standing on.
                    if (focused) {
                        append("  ·  ")
                        append(HOLD_HINT)
                    } else if (record.chatTitle.isNotBlank()) {
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
 * Film art for a Continue watching card, with a play badge over it.
 *
 * There is no Telegram thumbnail to fall back on here: a resume record stores only enough to
 * reopen the file, so that a film stays resumable even when its chat has not been loaded this
 * session. TMDB is therefore the only source of art, and when it has nothing (no key, no match,
 * no network) the card falls back to the play icon it used before. That is a complete card, not a
 * broken one, so the failure is never mentioned.
 *
 * [modifier] carries the size, because the row wants a thumbnail and the tile wants the whole
 * width of its column. [badge] follows it: a 34 dp disc that reads as a play button over a 96 dp
 * thumbnail is a speck over art three times that wide.
 */
@Composable
private fun ContinueArt(record: ResumeRecord, modifier: Modifier = Modifier, badge: Dp) {
    val art by produceState<String?>(initialValue = null, key1 = record.title) {
        value = (Tmdb.lookup(record.title) as? FilmLookup.Found)?.details?.backdropUrl
    }

    Box(
        modifier
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
                    modifier = Modifier.size(badge * 0.88f),
                )
            },
        )
        if (art != null) {
            // Over real artwork the icon needs its own backing to stay legible.
            Box(
                Modifier
                    .size(badge)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(badge * 0.65f),
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
    /** The version on GitHub, when it beats the one running. Null the rest of the time. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
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
        if (updateVersion != null) {
            RailItem(
                label = "Update",
                icon = Icons.Filled.Refresh,
                badge = updateVersion,
                selected = false,
                onClick = onUpdate,
                accent = Caution,
            )
            Spacer(Modifier.height(4.dp))
        }
        RailItem(
            label = "Settings",
            icon = Icons.Filled.Settings,
            // Which build this is, riding along with Settings rather than on a line of its own:
            // the rail is already as tall as the screen with the tabs it has to hold, and a
            // tenth row was simply cut off at the bottom edge.
            badge = "v${Updates.installedVersion}",
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
    // Amber for the update item, so it is the one thing on the rail that is not the app's own
    // blue and reads as "look at this" without a second glance.
    accent: Color = Accent,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    // Cross-faded rather than switched: an instant colour flip as focus sweeps down the rail
    // reads as flicker on a large screen.
    val background by animateColorAsState(
        targetValue = when {
            focused -> accent
            selected -> accent.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railBackground",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> accent
            else -> if (accent == Accent) TextMuted else accent
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

/**
 * A chip beside a heading, for the action that applies to the whole list under it.
 *
 * Sized and coloured like the rows below it rather than like a button, so it reads as part of the
 * heading and not as the first item of the list.
 */
@Composable
private fun HeaderAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceDark,
        animationSpec = tween(140),
        label = "headerAction",
    )
    val foreground = if (focused) Color.White else TextPrimary

    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
    }
}

@Composable
private fun Header(tab: BrowseTab, count: Int, action: @Composable () -> Unit = {}) {
    // A bare number after the blurb leaves the viewer to guess what was counted, so it always
    // carries its unit, and this one tab counts films rather than chats.
    val unit = when {
        tab == BrowseTab.Continue && count == 1 -> "film"
        tab == BrowseTab.Continue -> "films"
        count == 1 -> "chat"
        else -> "chats"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, top = Tv.SafeV, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.heading, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Text(
                if (count > 0) "${tab.blurb}  ·  $count $unit" else tab.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }
        action()
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
            // A microphone on its own says it: the word beside it was only taking up room the
            // search field could use.
            PillButton(label = null, icon = TmIcons.Mic, onClick = startVoice)
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
private fun PillButton(label: String?, icon: ImageVector?, onClick: () -> Unit) {
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
            // An icon on its own gets even padding, so the pill comes out round rather than as a
            // wide one with a gap where the words used to be.
            .padding(horizontal = if (label == null) 13.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                // The label is the only description there was, so a wordless pill hands it to the
                // screen reader instead of dropping it.
                contentDescription = label,
                tint = foreground,
                modifier = Modifier.size(22.dp),
            )
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChatSection(
    chats: List<ChatSummary>,
    favorites: Set<Long>,
    recent: List<ChatSummary>,
    layout: CardLayout,
    onOpenChat: (ChatSummary) -> Unit,
    onHold: (ChatSummary) -> Unit,
    launchChatId: Long,
) {
    val first = remember { FocusRequester() }
    // The strip is what a viewer lands on when it is there, because it is the largest thing on the
    // screen; otherwise the first card takes it.
    fun focusOf(chat: ChatSummary, inStrip: Boolean): Modifier = when {
        inStrip -> if (chat === recent.firstOrNull()) Modifier.focusRequester(first) else Modifier
        recent.isNotEmpty() -> Modifier
        chat === chats.firstOrNull() -> Modifier.focusRequester(first)
        else -> Modifier
    }

    when (layout) {
        CardLayout.List -> LazyColumn(
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
                                ChatTile(
                                    chat = chat,
                                    favorite = chat.id in favorites,
                                    opensOnLaunch = chat.id == launchChatId,
                                    onClick = { onOpenChat(chat) },
                                    onHold = { onHold(chat) },
                                    modifier = focusOf(chat, inStrip = true)
                                        .width(RECENT_TILE_WIDTH),
                                )
                            }
                        }
                        Text(
                            // Not "All chats": that is the name of a rail tab, and repeating it as
                            // a sub-heading inside a different tab reads as if the viewer moved.
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
                    opensOnLaunch = chat.id == launchChatId,
                    onClick = { onOpenChat(chat) },
                    onHold = { onHold(chat) },
                    modifier = focusOf(chat, inStrip = false),
                )
            }
        }

        CardLayout.Grid -> LazyVerticalGrid(
            columns = GridCells.Fixed(TILE_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            // The end inset keeps a focused tile in the last column from having its border
            // clipped by the panel edge, which the rows never had to worry about.
            contentPadding = PaddingValues(start = 28.dp, end = 4.dp, bottom = Tv.SafeV),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(chats, key = { it.id }) { chat ->
                ChatTile(
                    chat = chat,
                    favorite = chat.id in favorites,
                    opensOnLaunch = chat.id == launchChatId,
                    onClick = { onOpenChat(chat) },
                    onHold = { onHold(chat) },
                    modifier = focusOf(chat, inStrip = false).fillMaxWidth(),
                )
            }
        }
    }

    // The remote has nowhere to go until something holds focus. Switching arrangement is included:
    // it rebuilds the list, and the card that was holding focus goes with the old one.
    LaunchedEffect(chats.firstOrNull()?.id, recent.firstOrNull()?.id, layout) {
        runCatching { first.requestFocus() }
    }
}

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
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    Column(
        modifier
            // Fixed, not wrapped: a chat whose name runs to two lines would otherwise stand
            // taller than its neighbours and leave the row visibly ragged.
            .height(RECENT_TILE_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(
                width = 3.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Poster(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(64.dp).clip(CircleShape),
            )
            Spacer(Modifier.weight(1f))
            if (favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favourite",
                    tint = Accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
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
            when {
                focused -> HOLD_HINT
                opensOnLaunch -> "Opens on launch"
                else -> chat.kind.label
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    opensOnLaunch: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
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
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
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
                // The row says what it is, then what can be done to it once focus arrives. The
                // launch marker is what explains the jump straight into a chat on the previous
                // start, which is otherwise the app appearing to skip a screen on its own.
                when {
                    focused -> "${chat.kind.label}  ·  $HOLD_HINT"
                    opensOnLaunch -> "${chat.kind.label}  ·  Opens on launch"
                    else -> chat.kind.label
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            "No favourites yet. Hold OK on any chat to add it here."
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

/**
 * Shown on the focused row only.
 *
 * A hold is invisible until someone tries it, and a line of standing instructions above the list
 * would be read once and then become furniture. Attached to the focused row it arrives exactly
 * when it is actionable, and it costs no layout because it takes the place of a label that row
 * was already carrying.
 */
private const val HOLD_HINT = "Hold OK for options"
/** Horizontal padding every rail child carries, which is what keeps them out of the overscan. */
private val RAIL_INSET = 16.dp
private val RECENT_TILE_WIDTH = 224.dp
private val RECENT_TILE_HEIGHT = 154.dp

/**
 * Columns in the tile arrangement.
 *
 * Three, not the film grid's four: the rail takes 196 dp off a 960 dp screen before this starts,
 * and a fourth column would leave each tile too narrow for a chat name to survive it.
 */
private const val TILE_COLUMNS = 3
