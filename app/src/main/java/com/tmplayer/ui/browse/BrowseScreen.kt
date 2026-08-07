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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.StateScaffold
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
    Favorites("Favourites", "Favourites", "The chats you watch from most", Icons.Filled.Star),
    Recent("Recent", "Recent", "Newest activity across Telegram", Icons.Filled.Refresh),
    Channels("Channels", "Channels", "Broadcast channels you follow", TmIcons.Channel),
    Groups("Groups", "Groups", "Groups you are a member of", TmIcons.Group),
    People("People", "People", "Direct chats", Icons.Filled.Person),
    All("All chats", "All chats", "Everything, newest first", Icons.Filled.List),
}

@Composable
fun BrowseScreen(
    state: UiState<BrowseData>,
    favorites: Set<Long>,
    onRetry: () -> Unit,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Favourites is the landing tab, but only when there is something in it — an empty first
    // screen is a worse greeting than a full one.
    var tab by remember(favorites.isEmpty()) {
        mutableStateOf(if (favorites.isEmpty()) BrowseTab.Recent else BrowseTab.Favorites)
    }
    var query by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        NavRail(
            account = (state as? UiState.Content)?.value?.account,
            selected = tab,
            favoriteCount = favorites.size,
            onSelect = { tab = it; query = "" },
            onOpenSettings = onOpenSettings,
        )

        Column(Modifier.fillMaxSize().padding(end = Tv.SafeH)) {
            StateScaffold(state, onRetry = onRetry) { data ->
                val visible = remember(data.chats, tab, favorites, query) {
                    filterChats(data.chats, tab, favorites, query)
                }

                Column(Modifier.fillMaxSize()) {
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
                            // unfiltered list already is "recent" — no extra sorting to go stale.
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
        BrowseTab.Recent, BrowseTab.All -> chats
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
            .padding(horizontal = 12.dp, vertical = 20.dp),
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
    Row(verticalAlignment = Alignment.CenterVertically) {
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
                account?.name ?: "Signing in…",
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
 * Deliberately built from `clickable` rather than a TV Material Card.
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
            .padding(horizontal = 16.dp),
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
    Column(Modifier.padding(start = 28.dp, top = Tv.SafeV, bottom = 12.dp)) {
        Text(tab.heading, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Text(
            if (count > 0) "${tab.blurb}  ·  $count" else tab.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit) {
    val startVoice = rememberVoiceSearch("Say a chat name", onQuery)
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search chats",
            modifier = Modifier.weight(1f),
        )
        if (startVoice != null) {
            PillButton("Speak", TmIcons.Mic, startVoice)
        }
        if (query.isNotBlank()) {
            PillButton("Clear", Icons.Filled.Close) { onQuery("") }
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
                            RecentTile(chat) { onOpenChat(chat) }
                        }
                    }
                    Text(
                        "All chats",
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

    // The remote has nowhere to go until something holds focus.
    LaunchedEffect(chats.firstOrNull()?.id, recent.isEmpty()) {
        if (recent.isEmpty()) runCatching { first.requestFocus() }
    }
}

@Composable
private fun RecentTile(chat: ChatSummary, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    Column(
        Modifier
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

/** Scrolls overflowing text, but only while [active] — a wall of moving labels is unreadable. */
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
    val message = when {
        query.isNotBlank() -> "Nothing matches “$query”."
        tab == BrowseTab.Favorites ->
            "No favourites yet. Open a chat and press Favourite to pin it here."
        else -> "Nothing in ${tab.heading.lowercase()}."
    }
    Box(Modifier.fillMaxSize().padding(start = 28.dp), contentAlignment = Alignment.TopStart) {
        Text(message, style = MaterialTheme.typography.titleLarge, color = TextMuted)
    }
}

private const val RECENT_COUNT = 8
private const val FOCUS_FADE_MS = 140
private val RECENT_TILE_WIDTH = 224.dp
private val RECENT_TILE_HEIGHT = 154.dp
