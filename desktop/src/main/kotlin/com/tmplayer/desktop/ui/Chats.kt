package com.tmplayer.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Td
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.browseSections
import com.tmplayer.ui.browse.filterChats
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The chat list: the shared [ChatListViewModel], the phone's tabs and the account's own Telegram
 * folders as chips, and a search that ranks with the same fuzzy matcher. With [favouritesOnly] it
 * is the Favourites page: the starred chats and no chips.
 */
@Composable
fun ChatsPage(state: ShellState, model: ChatListViewModel, favouritesOnly: Boolean) {
    val ui by model.state.collectAsState()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val folders by Td.folders.collectAsState()
    var query by rememberSaveable(favouritesOnly) { mutableStateOf("") }
    var sectionKey by rememberSaveable { mutableStateOf(BrowseSection.encode(BrowseSection.of(BrowseTab.All))) }
    val toast = rememberToast()
    val scope = rememberCoroutineScope()

    // The Continue tab lists videos and has a page of its own; Favourites has its own page too.
    val sections = remember(folders) {
        browseSections(folders).filterNot { it.isContinue || it == BrowseSection.of(BrowseTab.Favorites) }
    }
    val section = if (favouritesOnly) {
        BrowseSection.of(BrowseTab.Favorites)
    } else {
        BrowseSection.decode(sectionKey) ?: BrowseSection.of(BrowseTab.All)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = if (favouritesOnly) "Favourites" else "Chats",
            subtitle = if (favouritesOnly) "Chats you've starred" else section.blurb,
            actions = {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    placeholder = "Search chats",
                    focus = state.searchFocus,
                    modifier = Modifier.width(320.dp),
                )
                IconButton(onClick = {
                    val waiting = model.refreshUnlessRateLimited()
                    if (waiting > 0) toast("Telegram asked to wait $waiting s before refreshing again")
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )
        if (!favouritesOnly) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sections, key = { BrowseSection.encode(it) }) { entry ->
                    FilterChip(
                        selected = entry == section,
                        onClick = { sectionKey = BrowseSection.encode(entry) },
                        label = { Text(entry.label) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    )
                }
            }
        }
        StateBox(ui, onRetry = { model.load() }) { data ->
            LaunchedEffect(data.chats) { state.noteChatTitles(data.chats) }
            val visible = remember(data.chats, section, favourites, query) {
                filterChats(data.chats, section, favourites, query)
            }
            if (visible.isEmpty()) {
                Centred {
                    Text(
                        when {
                            query.isNotBlank() -> "No chat matches \"$query\"."
                            favouritesOnly -> "Star a chat in the list and it will wait for you here."
                            else -> "Nothing in ${section.label}."
                        },
                        color = Tone.muted,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    val listState = if (favouritesOnly) androidx.compose.foundation.lazy.rememberLazyListState() else state.chatListState
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(visible, key = { it.id }) { chat ->
                            ChatRow(
                                chat = chat,
                                favourite = chat.id in favourites,
                                onOpen = { state.openChat(chat) },
                                onStar = { scope.launch { state.settings.toggleFavorite(chat.id) } },
                            )
                        }
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(listState),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRow(chat: ChatSummary, favourite: Boolean, onOpen: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChatAvatar(chat.miniThumbnail, chat.photoFileId, chat.title, Avatar.Compact + 8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chat.isPinned) Icon(TmIcons.Pin, contentDescription = "Pinned", tint = Tone.muted, modifier = Modifier.width(14.dp))
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(chat.kind.label, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        }
        if (chat.unreadCount > 0) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (chat.isMuted) Tone.surfaceHigh else Tone.accent)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (chat.unreadCount > 999) "999+" else chat.unreadCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chat.isMuted) Tone.muted else Tone.onAccent,
                )
            }
        }
        IconButton(onClick = onStar) {
            Icon(
                if (favourite) Icons.Filled.Star else TmIcons.StarOutline,
                contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                tint = if (favourite) Tone.caution else Tone.muted,
            )
        }
    }
}
