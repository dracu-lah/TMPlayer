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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.ChatListSkeleton
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.data.ResumeRecord
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
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
    // A clock, not the circular-arrow reload glyph: that one is the video grid's genuine refresh
    // action, so the same picture stood for two unrelated things.
    Recent("Recent", "Recent", "Chats with something new", TmIcons.Clock),
    Channels("Channels", "Channels", "Broadcast channels you follow", TmIcons.Channel),
    Groups("Groups", "Groups", "Groups you're in", TmIcons.Group),
    People("People", "People", "Your one-to-one chats", Icons.Filled.Person),
    All("All chats", "All chats", "Everything, newest first", Icons.AutoMirrored.Filled.List),
}

@Composable
fun BrowseScreen(
    state: UiState<BrowseData>,
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
    onToggleFavorite: (ChatSummary) -> Unit = {},
    onRestartMedia: (ResumeRecord) -> Unit = {},
    onForgetMedia: (ResumeRecord) -> Unit = {},
    /** Empties Continue watching in one go, rather than one held-OK menu per video. */
    onClearHistory: () -> Unit = {},
    /** Unstars every chat in one go, the counterpart to the star in each chat's menu. */
    onClearFavorites: () -> Unit = {},
    /** The chat that reopens on launch, marked on its row so the jump is never unexplained. */
    launchChatId: Long = 0L,
    picked: BrowseTab? = null,
    onPickTab: (BrowseTab) -> Unit = {},
    /**
     * Rows or tiles, chosen from the pill beside the tab name and remembered from then on. One
     * arrangement covers every tab: they are all lists of the same card.
     */
    layout: CardLayout = CardLayout.List,
    onToggleLayout: () -> Unit = {},
    /** The newer version on GitHub, if there is one. Shown on the rail, in amber. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
) {
    // An unfinished video wins the landing tab, then Favourites, then Recent, so the first screen
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
    var mediaMenu by remember { mutableStateOf<ResumeRecord?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearFavorites by remember { mutableStateOf(false) }

    // One question, answered by the shared detector, decides the whole shape of this screen: a
    // permanent rail beside the listing on a television, a drawer behind a hamburger on a phone.
    // Everything below the chrome is the same composition either way, told only how much room it
    // has and how far in from the edge it may start.
    val touch = !FormFactor.isTv(LocalContext.current)
    val insets = if (touch) TouchInsets else TvInsets
    // Fixed columns suit a screen whose size is known in advance; a phone's is not, and it turns
    // when the viewer does, so the grid is asked for a tile width instead and works out the rest.
    val tiles = if (touch) GridCells.Adaptive(TOUCH_TILE_MIN) else GridCells.Fixed(TILE_COLUMNS)

    val pane: @Composable () -> Unit = {
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
                        // On a phone the heading, the count and the chips all live in the app bar
                        // now, so the content area starts with the content.
                        if (!touch) {
                            TabHeading(tab, continueWatching.size, insets) {
                                LayoutAction(layout, onToggleLayout)
                                // No Refresh here: this tab is read off this device and cannot be
                                // behind, so the button would be one that visibly does nothing.
                                if (continueWatching.isNotEmpty()) {
                                    HeaderAction(
                                        label = "Clear history",
                                        icon = Icons.Filled.Close,
                                        onClick = { confirmClearHistory = true },
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        if (continueWatching.isEmpty()) {
                            EmptyTab(tab, query = "")
                        } else {
                            ContinueSection(
                                records = continueWatching,
                                layout = layout,
                                insets = insets,
                                tiles = tiles,
                                autoFocus = !touch,
                                onResume = onResumeMedia,
                                onHold = { mediaMenu = it },
                            )
                        }
                    } else {
                        if (!touch) {
                            TabHeading(tab, visible.size, insets) {
                                LayoutAction(layout, onToggleLayout)
                                RefreshAction(onRefresh)
                                // Stars are added one at a time from a menu, so the only way back
                                // from a tab full of them is here, beside the tab they fill.
                                if (tab == BrowseTab.Favorites && favorites.isNotEmpty()) {
                                    HeaderAction(
                                        label = "Clear favourites",
                                        icon = Icons.Filled.Close,
                                        onClick = { confirmClearFavorites = true },
                                    )
                                }
                            }
                            SearchRow(query = query, insets = insets, onQuery = { query = it })
                            Spacer(Modifier.height(20.dp))
                        }

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
                                insets = insets,
                                tiles = tiles,
                                autoFocus = !touch,
                                onOpenChat = onOpenChat,
                                onHold = { chatMenu = it },
                                launchChatId = launchChatId,
                            )
                        }
                    }
                }
        }
    }

    if (touch) {
        val voiceSearch = rememberVoiceSearch("Say a chat name") { query = it }
        val count = (state as? UiState.Content)?.value?.chats?.let {
            filterChats(it, tab, favorites, query).size
        } ?: 0
        TouchBrowseShell(
            account = (state as? UiState.Content)?.value?.account,
            selected = tab,
            favoriteCount = favorites.size,
            onSelect = { onPickTab(it); query = "" },
            onOpenSettings = onOpenSettings,
            updateVersion = updateVersion,
            onUpdate = onUpdate,
            title = tab.heading,
            // Continue watching is a list of videos held on this device, and the box searches
            // chats. Offering it there would be a field that filters nothing.
            searchQuery = if (tab == BrowseTab.Continue) null else query,
            onSearchQueryChange = { query = it },
            onVoiceSearch = voiceSearch,
            actions = {
                BarIcon(
                    label = if (layout == CardLayout.Grid) "Show as rows" else "Show as tiles",
                    icon = if (layout == CardLayout.Grid) {
                        Icons.AutoMirrored.Filled.List
                    } else {
                        TmIcons.Grid
                    },
                    onClick = onToggleLayout,
                )
                if (tab != BrowseTab.Continue) {
                    BarIcon("Refresh", Icons.Filled.Refresh, onRefresh)
                }
                // Everything destructive goes behind the overflow. A "Clear history" button
                // sitting in the bar beside Refresh is one mis-tap from emptying the tab.
                val clearHistory = tab == BrowseTab.Continue && continueWatching.isNotEmpty()
                val clearFavorites = tab == BrowseTab.Favorites && favorites.isNotEmpty()
                if (clearHistory || clearFavorites) {
                    BarOverflow(
                        items = buildList {
                            if (clearHistory) {
                                add("Clear Continue watching" to { confirmClearHistory = true })
                            }
                            if (clearFavorites) {
                                add("Clear favourites" to { confirmClearFavorites = true })
                            }
                        },
                    )
                }
            },
            content = {
                // The count the heading used to carry, now under the title where a phone's app bar
                // puts a subtitle. Nowhere else on the screen says how much is in the tab.
                Column(Modifier.fillMaxSize()) {
                    if (count > 0 && query.isBlank()) {
                        M3Text(
                            "$count ${if (count == 1) "chat" else "chats"}",
                            style = M3MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    pane()
                }
            },
        )
    } else {
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

            Column(Modifier.fillMaxSize().padding(end = Tv.SafeH)) { pane() }
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
            message = "All ${continueWatching.size} videos are forgotten and the tab empties. " +
                "Nothing is deleted from Telegram.",
            detail = "You can still find each video in the chat it came from.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClearHistory = false
                onClearHistory()
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    mediaMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = StreamStats.formatClock(record.positionMs) + " watched",
            onDismiss = { mediaMenu = null },
            actions = listOf(
                MenuAction("Resume", Icons.Filled.PlayArrow, detail = "Carry on where you stopped") {
                    mediaMenu = null
                    onResumeMedia(record)
                },
                MenuAction("Play from the start", Icons.Filled.Refresh) {
                    mediaMenu = null
                    onRestartMedia(record)
                },
                MenuAction(
                    label = "Remove from Continue watching",
                    icon = Icons.Filled.Close,
                    detail = "The video stays in its chat",
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
 * One button in the phone's app bar.
 *
 * Icon-only and stock: [IconButton] brings the 48 dp target and the ripple, both of which the
 * hand-built chips this replaced had to be told about and one of which they never got.
 */
@Composable
private fun RowScope.BarIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        M3Icon(icon, contentDescription = label)
    }
}

/** The three dots, and everything that should take two taps rather than one. */
@Composable
internal fun RowScope.BarOverflow(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        M3Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { (label, action) ->
            DropdownMenuItem(
                text = { M3Text(label) },
                onClick = { open = false; action() },
            )
        }
    }
}

// ---- continue watching -----------------------------------------------------------------------

@Composable
private fun ContinueSection(
    records: List<ResumeRecord>,
    layout: CardLayout,
    insets: BrowseInsets,
    tiles: GridCells,
    /** Only a remote needs somewhere to stand; on a phone a stolen focus only opens the keyboard. */
    autoFocus: Boolean,
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
    val padding = PaddingValues(
        start = insets.start,
        end = insets.end,
        bottom = insets.bottom,
    )

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
            columns = tiles,
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

    // This is the landing tab for anyone with a video on the go, and the remote has nowhere to go
    // until something holds focus. Re-run on a change of arrangement too: switching rebuilds the
    // list from scratch, and the card that was holding focus leaves the composition with it.
    LaunchedEffect(records.firstOrNull()?.messageId, layout, autoFocus) {
        if (autoFocus) runCatching { first.requestFocus() }
    }
}

/**
 * A Continue watching card as a tile: the preview carries the progress bar, the way the grid
 * does, because there is no longer a row of text alongside it to put the bar under.
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
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(Corner.Medium))
            .holdable(
                interactionSource = interactions,
                onClick = { onResume(record) },
                onHold = { onHold(record) },
            ),
    ) {
        Box {
            ContinueArt(Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge = 40.dp)
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
            .clip(RoundedCornerShape(Corner.Large))
            .background(SurfaceDark)
            .border(2.dp, border, RoundedCornerShape(Corner.Large))
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
            // that this is a video they are part way through rather than one they have not started.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
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
 * Neutral artwork for a Continue watching card.
 *
 * A resume record deliberately contains no remote artwork URL. The play mark therefore remains
 * useful offline, discloses no selected media to another service, and never blocks this screen.
 *
 * [modifier] carries the size, because the row wants a thumbnail and the tile wants the whole
 * width of its column. [badge] follows it: a 34 dp disc that reads as a play button over a 96 dp
 * thumbnail is a speck over art three times that wide.
 */
@Composable
private fun ContinueArt(modifier: Modifier = Modifier, badge: Dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(Corner.Small))
            .background(SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(badge * 0.88f),
        )
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
        // Continue watching is a list of videos, not chats; it never reaches this filter.
        BrowseTab.Continue, BrowseTab.Recent, BrowseTab.All -> chats
    }
    // Ranked rather than filtered: a chat whose title is exactly what was typed belongs at the
    // top, and a plain `contains` had no opinion about order at all. It is also what forgives the
    // accent nobody types and the letter the remote's keyboard put in the wrong place.
    return Fuzzy.rank(byTab, query) { it.title }
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
        Box(Modifier.size(Avatar.Compact).clip(CircleShape).background(SurfaceRaised)) {
            if (account != null) {
                MediaPreview(
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
            .clip(RoundedCornerShape(Corner.Small))
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
            // Clamped, because the badge measures before the label and an unusually long one
            // (a version name with a suffix on it) shortened "Settings" to "S...".
            Text(
                badge,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = RAIL_BADGE_MAX),
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
    HeaderAction(
        // A grid of squares and a stack of lines are the two pictures every phone and television
        // uses for this, so the words beside them were only saying it twice.
        label = if (layout == CardLayout.Grid) "As rows" else "As tiles",
        icon = if (layout == CardLayout.Grid) Icons.AutoMirrored.Filled.List else TmIcons.Grid,
        showLabel = false,
        onClick = onToggle,
    )
}

/**
 * Fetches the chat list again.
 *
 * The list is built once when the screen opens, so a chat that moves, is joined, or is renamed
 * while it is up does not appear until something else rebuilds it. This keeps its word, unlike
 * the arrangement toggle: a circular arrow on its own could be a retry, a replay or an update,
 * and this app already draws that same glyph for two of those.
 */
@Composable
private fun RefreshAction(onRefresh: () -> Unit) {
    HeaderAction(
        label = "Refresh",
        // Icon-only, here as everywhere. The word was kept on the television on the grounds that
        // the same glyph means three things there and the screen is wide, but a heading whose
        // chips are two pictures and one picture-with-a-word reads as three unrelated controls,
        // and the name is still what a screen reader announces.
        icon = Icons.Filled.Refresh,
        showLabel = false,
        onClick = onRefresh,
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
 *
 * [touch] only sets a floor under the height. The chip is sized by its padding for a remote, which
 * lands it a few dp short of the 48 dp a fingertip needs, and nothing else about it has to change.
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
        targetValue = if (focused) Accent else SurfaceDark,
        animationSpec = tween(140),
        label = "headerAction",
    )
    val foreground = if (focused) Color.White else TextPrimary

    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            // Even padding with no words, so the chip comes out round rather than as a wide one
            // with a gap where the label used to be.
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

/**
 * The heading over a tab's listing.
 *
 * Not called `Header`, which is what the media grid's own heading is called one file away. Both
 * live in this package, and an explicit import of the name from outside the package resolved to
 * this private one instead of the internal one it meant, which fails to compile somewhere that
 * has nothing to do with either.
 */
@Composable
private fun TabHeading(
    tab: BrowseTab,
    count: Int,
    insets: BrowseInsets,
    action: @Composable () -> Unit = {},
) {
    // A bare number after the blurb leaves the viewer to guess what was counted, so it always
    // carries its unit, and this one tab counts videos rather than chats.
    val unit = when {
        tab == BrowseTab.Continue && count == 1 -> "video"
        tab == BrowseTab.Continue -> "videos"
        count == 1 -> "chat"
        else -> "chats"
    }
    Row(
        Modifier.fillMaxWidth().padding(
            start = insets.start,
            end = insets.end,
            top = insets.top,
            bottom = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tab.heading,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                if (count > 0) "${tab.blurb}  ·  $count $unit" else tab.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        // A heading can carry several actions, and butted together they read as one wide control
        // with a seam down it. The gap is what makes them separate buttons.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            action()
        }
    }
}

@Composable
private fun SearchRow(query: String, insets: BrowseInsets, onQuery: (String) -> Unit) {
    val startVoice = rememberVoiceSearch("Say a chat name", onQuery)
    val searchField = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end),
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
            .clip(CircleShape)
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
    insets: BrowseInsets,
    tiles: GridCells,
    /** Only a remote needs somewhere to stand; on a phone a stolen focus only opens the keyboard. */
    autoFocus: Boolean,
    onOpenChat: (ChatSummary) -> Unit,
    onHold: (ChatSummary) -> Unit,
    launchChatId: Long,
) {
    val rowsAreFullBleed = isTouch()
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
                item(key = "recent-strip") {
                    // The rows below are full bleed on a phone, but a heading is not a row: with
                    // the list's side padding dropped for their sake, "Jump back in" was printed
                    // hard against the panel edge with nothing between it and the glass.
                    Column(
                        if (rowsAreFullBleed) {
                            Modifier.padding(start = insets.start, end = insets.end)
                        } else {
                            Modifier
                        },
                    ) {
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
            columns = tiles,
            modifier = Modifier.fillMaxSize(),
            // The end inset keeps a focused tile in the last column from having its border
            // clipped by the panel edge, which the rows never had to worry about.
            contentPadding = PaddingValues(
                start = insets.start,
                end = insets.end,
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
                    onClick = { onOpenChat(chat) },
                    onHold = { onHold(chat) },
                    modifier = focusOf(chat, inStrip = false).fillMaxWidth(),
                )
            }
        }
    }

    // The remote has nowhere to go until something holds focus. Switching arrangement is included:
    // it rebuilds the list, and the card that was holding focus goes with the old one.
    LaunchedEffect(chats.firstOrNull()?.id, recent.firstOrNull()?.id, layout, autoFocus) {
        if (autoFocus) runCatching { first.requestFocus() }
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
            .clip(RoundedCornerShape(Corner.Large))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(
                width = 3.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(Corner.Large),
            )
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(Avatar.Card).clip(CircleShape),
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

/**
 * A chat as a full-bleed row, the way a phone's list of conversations is drawn.
 *
 * The card this replaces was 84 dp tall with a 16 dp radius, a border, and a 14 dp gap to the next
 * one: eight chats to a screen, each announcing itself as a separate object. Telegram, and every
 * other phone app whose main screen is a list of chats, draws the list as rows on the window
 * background with a divider's worth of nothing between them, and fits half again as many in. The
 * television keeps its cards, where focus has to be visible from a sofa and a border is how that
 * is said.
 */
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
    if (isTouch()) {
        TouchChatRow(chat, favorite, opensOnLaunch, onClick, onHold, modifier, interactions)
        return
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(Corner.Large))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(
                width = 3.dp,
                color = if (focused) Accent else Color.Transparent,
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
private fun TouchChatRow(
    chat: ChatSummary,
    favorite: Boolean,
    opensOnLaunch: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier,
    interactions: MutableInteractionSource,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TOUCH_ROW_HEIGHT)
            // No clip and no background: the row sits on the window, so a press ripples across
            // the whole width of the screen the way a phone's list rows do.
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaPreview(
            miniThumbnail = chat.miniThumbnail,
            thumbnailFileId = chat.photoFileId,
            fallbackLabel = chat.title,
            modifier = Modifier.size(TOUCH_AVATAR).clip(CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // No hold hint on a phone: there is no focused row for it to attach to, and a
                // line of instructions on every row is furniture within one screenful.
                if (opensOnLaunch) "${chat.kind.label}  ·  Opens on launch" else chat.kind.label,
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
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyTab(tab: BrowseTab, query: String) {
    // Each empty tab says what to do about it, in its own words.
    val message = when {
        query.isNotBlank() -> "Nothing matches “$query”."
        tab == BrowseTab.Continue -> "You haven't started a video yet. Open a chat and pick one."
        tab == BrowseTab.Favorites ->
            "No favourites yet. Hold OK on any chat to add it here."
        else -> "Nothing here yet."
    }
    // The app's one empty state, which this used to be a second copy of. A search that came back
    // empty is a different situation from a tab with nothing in it yet, so the glyph follows
    // whichever one the viewer is actually looking at; everything else about the two is the same
    // and is now said in one place.
    BigEmpty(message, icon = if (query.isNotBlank()) Icons.Filled.Search else tab.icon)
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

/** Enough for a version name, and never enough to eat the item's own label. */
private val RAIL_BADGE_MAX = 84.dp
private val RECENT_TILE_WIDTH = 224.dp
private val RECENT_TILE_HEIGHT = 154.dp

/**
 * Columns in the tile arrangement.
 *
 * Three, not the video grid's four: the rail takes 196 dp off a 960 dp screen before this starts,
 * and a fourth column would leave each tile too narrow for a chat name to survive it.
 */
private const val TILE_COLUMNS = 3

/**
 * The narrowest a tile may be before the grid drops a column.
 *
 * A phone held upright has about 400 dp to spend, so this is what puts two tiles across it and
 * four or five across the same phone turned on its side, without either arrangement being spelled
 * out anywhere.
 */
private val TOUCH_TILE_MIN = 168.dp

/**
 * A phone chat row, at Material's list-item metrics.
 *
 * 72 dp with a 56 dp avatar is the two-line list item, and it is what Telegram, Gmail and the
 * dialler all draw. The card it replaced was 84 dp plus a 14 dp gap, so this fits three more
 * chats on a screen while looking less busy.
 */
private val TOUCH_ROW_HEIGHT = 72.dp
private val TOUCH_AVATAR = Avatar.List

/**
 * How far the listing keeps from each edge.
 *
 * A television crops its outermost few percent, so the TV figures are overscan clearance and have
 * nothing to do with taste. A phone crops nothing, and spending 32 dp a side of a 400 dp screen on
 * a margin that exists to defeat a cathode ray tube is a third of a tile thrown away.
 */
private class BrowseInsets(val start: Dp, val end: Dp, val top: Dp, val bottom: Dp)

private val TvInsets = BrowseInsets(start = 28.dp, end = 4.dp, top = Tv.SafeV, bottom = Tv.SafeV)
private val TouchInsets = BrowseInsets(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
