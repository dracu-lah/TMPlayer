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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.border
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.components.TmDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
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
fun ChatsPage(
    state: ShellState,
    model: ChatListViewModel,
    favouritesOnly: Boolean,
    /** False beside the wide side bar, which lists the same sections in its Chats group. */
    showSections: Boolean = true,
) {
    val ui by model.state.collectAsState()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val folders by Td.folders.collectAsState()
    var query by rememberSaveable(favouritesOnly) { mutableStateOf("") }
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val listState = if (favouritesOnly) androidx.compose.foundation.lazy.rememberLazyListState() else state.chatListState
    val nav = rememberKeyboardNav(remember(listState) { ListSurface(listState) })

    // The Continue tab lists videos and has a page of its own; Favourites has its own page too.
    val sections = remember(folders) {
        browseSections(folders).filterNot { it.isContinue || it == BrowseSection.of(BrowseTab.Favorites) }
    }
    val section = if (favouritesOnly) {
        BrowseSection.of(BrowseTab.Favorites)
    } else {
        state.chatSection
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = if (favouritesOnly) "Favourites" else section.heading,
            subtitle = if (favouritesOnly) "Chats you've starred" else section.blurb,
            actions = {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    placeholder = "Search chats",
                    focus = state.searchFocus,
                    modifier = Modifier.width(320.dp),
                    onDown = { nav.focus(0) },
                )
                IconButton(onClick = {
                    val waiting = model.refreshUnlessRateLimited()
                    if (waiting > 0) toast("Telegram asked to wait $waiting s before refreshing again")
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )
        if (!favouritesOnly && showSections) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sections, key = { BrowseSection.encode(it) }) { entry ->
                    FilterChip(
                        selected = entry == section,
                        onClick = { state.showChats(entry) },
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
                ChatList(
                    chats = visible,
                    favourites = favourites,
                    listState = listState,
                    nav = nav,
                    onOpen = state::openChat,
                    onStar = { chat -> scope.launch { state.settings.toggleFavorite(chat.id) } },
                    // Each says out loud what it did, as on the phone: the row has already moved
                    // by the time the toast shows, and a row moving on its own explains nothing.
                    actions = ChatRowActions(
                        onTogglePinned = { chat ->
                            model.setPinned(chat, !chat.isPinned) { toast(it) }
                            toast(if (chat.isPinned) "${chat.title} unpinned" else "${chat.title} pinned to the top")
                        },
                        onToggleMuted = { chat ->
                            model.setMuted(chat, !chat.isMuted) { toast(it) }
                            toast(if (chat.isMuted) "${chat.title} unmuted" else "${chat.title} muted")
                        },
                        onToggleArchived = { chat ->
                            model.setArchived(chat, !chat.isArchived) { toast(it) }
                            toast(if (chat.isArchived) "${chat.title} moved out of the archive" else "${chat.title} archived")
                        },
                        onMarkRead = { chat ->
                            model.markRead(chat) { toast(it) }
                            toast("${chat.title} marked as read")
                        },
                    ),
                )
            }
        }
    }
}

/**
 * What a chat row can do besides open and star: the four that change the chat in Telegram itself,
 * on every device, which the phone and TV keep behind a long press. Each is handed the row as it
 * was drawn, so a toggle reads its current state off it.
 */
internal class ChatRowActions(
    val onTogglePinned: (ChatSummary) -> Unit = {},
    val onToggleMuted: (ChatSummary) -> Unit = {},
    val onToggleArchived: (ChatSummary) -> Unit = {},
    val onMarkRead: (ChatSummary) -> Unit = {},
)

/**
 * The rows themselves, with a scrollbar and the keyboard of [KeyboardNav]: Up and Down a row,
 * Home and End, Page Up and Page Down, Enter opens, S stars, P pins, M mutes, A archives, R marks
 * read, and the menu key or Shift+F10 opens the row's menu (as a right click does). Split from
 * [ChatsPage] so the UI tests can drive it without TDLib.
 */
@Composable
internal fun ChatList(
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    listState: LazyListState,
    nav: KeyboardNav,
    onOpen: (ChatSummary) -> Unit,
    onStar: (ChatSummary) -> Unit,
    actions: ChatRowActions = ChatRowActions(),
) {
    Box(Modifier.fillMaxSize()) {
        val count by rememberUpdatedState(chats.size)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp).navKeys(nav) { count },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(chats, key = { _, it -> it.id }) { index, chat ->
                ChatRow(
                    chat = chat,
                    favourite = chat.id in favourites,
                    modifier = Modifier.navCell(nav, index),
                    onOpen = { onOpen(chat) },
                    onStar = { onStar(chat) },
                    actions = actions,
                )
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ChatRow(
    chat: ChatSummary,
    favourite: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    modifier: Modifier = Modifier,
    actions: ChatRowActions = ChatRowActions(),
) {
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(if (focused) Modifier.border(Focus.Edge, Tone.accent, MaterialTheme.shapes.medium) else Modifier)
            .onPointerEvent(PointerEventType.Press) { event ->
                val macContext = CHATS_ON_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed || macContext) menu = true
            }
            .then(modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // Enter opens (the click). The letters are the row's menu, one key each.
                if (GridNav.isMenuKey(event)) {
                    menu = true
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed ||
                    event.isAltPressed || event.isShiftPressed
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.S -> onStar()
                    Key.P -> actions.onTogglePinned(chat)
                    Key.M -> actions.onToggleMuted(chat)
                    Key.A -> actions.onToggleArchived(chat)
                    // Nothing to clear is not an error, and the key is still this row's.
                    Key.R -> if (chat.unreadCount > 0) actions.onMarkRead(chat)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
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
        Box {
            ChatRowMenu(chat, favourite, menu, onDismiss = { menu = false }, onOpen, onStar, actions)
        }
    }
}

/**
 * The row's menu, the phone's long press menu with the key for each entry beside it. The star is
 * private to this computer; everything under it changes the chat in Telegram, on every device.
 */
@Composable
private fun ChatRowMenu(
    chat: ChatSummary,
    favourite: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    actions: ChatRowActions,
) {
    @Composable
    fun entry(label: String, key: String?, icon: ImageVector, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = key?.let { { Text(it, color = Tone.muted, style = MaterialTheme.typography.labelMedium) } },
            onClick = {
                onDismiss()
                action()
            },
        )
    }
    TmDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        entry("Open", "Enter", TmIcons.Folder, onOpen)
        entry(
            if (favourite) "Remove from favourites" else "Add to favourites",
            "S",
            if (favourite) Icons.Filled.Star else TmIcons.StarOutline,
            onStar,
        )
        HorizontalDivider()
        entry(if (chat.isPinned) "Unpin" else "Pin to the top", "P", TmIcons.Pin) { actions.onTogglePinned(chat) }
        entry(
            if (chat.isMuted) "Unmute" else "Mute",
            "M",
            if (chat.isMuted) TmIcons.Bell else TmIcons.BellOff,
        ) { actions.onToggleMuted(chat) }
        if (chat.unreadCount > 0) {
            entry("Mark as read", "R", Icons.Filled.Check) { actions.onMarkRead(chat) }
        }
        entry(
            if (chat.isArchived) "Move out of the archive" else "Archive",
            "A",
            TmIcons.Archive,
        ) { actions.onToggleArchived(chat) }
    }
}

private val CHATS_ON_MAC = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
