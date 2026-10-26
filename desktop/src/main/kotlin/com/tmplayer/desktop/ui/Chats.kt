package com.tmplayer.desktop.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import com.tmplayer.ui.browse.ChatFilter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.Color
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
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
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
import com.tmplayer.ui.browse.ChatPageChip
import com.tmplayer.ui.browse.chatPageChips
import com.tmplayer.ui.browse.isSelected
import com.tmplayer.ui.browse.filterChats
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The chat list: the shared [ChatListViewModel], the Chats filter chips (the order, then All,
 * Unread, Channels, Groups, People and Archived, the same [ChatFilter]s as the phone and the TV),
 * and a search that ranks with the same fuzzy matcher. In a narrow window, which has no side bar,
 * a second row above picks Chats, Saved Messages or a folder. With [favouritesOnly] it is the
 * Favourites page: the starred chats and no chips.
 */
@Composable
fun ChatsPage(
    state: ShellState,
    model: ChatListViewModel,
    favouritesOnly: Boolean,
) {
    val s = LocalStrings.current
    val ui by model.state.collectAsState()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val folders by Td.folders.collectAsState()
    var query by rememberSaveable(favouritesOnly) { mutableStateOf("") }
    val toast = rememberToast()
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val scope = rememberCoroutineScope()
    val listState = if (favouritesOnly) androidx.compose.foundation.lazy.rememberLazyListState() else state.chatListState
    val nav = rememberKeyboardNav(remember(listState) { ListSurface(listState) })
    // Back from a chat, the keyboard carries on from that chat's row rather than from nowhere.
    var refocus by remember { mutableStateOf(state.takeChatToRefocus()) }

    // Home, History and Favourites have pages of their own.
    // Telegram's own folder tab bar (All chats where Telegram puts it, then each folder in
    // Telegram's order), then the filters no folder already is: one row the Chats page and every
    // folder page share.
    val mainListPosition by Td.mainListPosition.collectAsState()
    val chips = remember(folders, mainListPosition) { chatPageChips(folders, mainListPosition) }
    val section = if (favouritesOnly) {
        BrowseSection.of(BrowseTab.Favorites)
    } else {
        state.chatSection
    }

    Column(Modifier.fillMaxSize()) {
        // The phone pulls the list down to refresh it; here F5, Ctrl+R and the small button do.
        val spin = com.tmplayer.ui.components.rememberRefreshSpin()
        val refreshing by model.refreshing.collectAsState()
        val refresh = {
            spin.kick()
            val waiting = model.refreshUnlessRateLimited()
            if (waiting > 0) toast(s.chatsRefreshWait(waiting))
        }
        RegisterRefresh(state, refresh)
        // The page's name alone, at the start like every other page's: the chips under it
        // already say which slice of the list this is, and a line under the name said it again.
        PageHeader(
            title = if (favouritesOnly) s.navFavourites else section.heading,
            actions = {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    placeholder = s.chatsSearch,
                    focus = state.searchFocus,
                    modifier = Modifier.widthIn(min = 160.dp, max = 280.dp),
                    onDown = { nav.focus(0) },
                )
                BarTooltip(s.commonRefresh, if (OsInfo.isMac) "⌘R" else "F5", below = true) {
                    androidx.compose.material3.IconButton(onClick = refresh) {
                        com.tmplayer.ui.components.RefreshGlyph(spin, s.commonRefresh, spinning = refreshing)
                    }
                }
            },
        )
        if (!favouritesOnly) {
            ChoiceChips(
                buildList {
                    if (section.isChats) {
                        val sort = state.chatSort
                        add(
                            Choice("sort", sort.label, sort.icon, selected = false, description = s.browseSortBy(sort.label)) {
                                state.chatSort = sort.toggled
                            },
                        )
                    }
                    chips.forEach { chip ->
                        val selected = chip.isSelected(section, state.chatFilter)
                        add(
                            when (chip) {
                                // A filter is a filter of the Chats list, so from a folder it goes back there.
                                is ChatPageChip.Filter -> Choice(chip.filter.name, chip.label, chip.icon, selected) {
                                    state.chatFilter = chip.filter
                                    state.showChats(BrowseSection.of(BrowseTab.Chats))
                                }
                                is ChatPageChip.Folder -> Choice(BrowseSection.encode(chip.folder), chip.label, chip.icon, selected) {
                                    state.showChats(chip.folder)
                                }
                            },
                        )
                    }
                },
            )
        }
        StateBox(ui, onRetry = { model.load() }, onAction = { model.load() }, skeleton = { ChatListSkeleton() }) { data ->
            LaunchedEffect(data.chats) { state.noteChatTitles(data.chats) }
            val filter = state.chatFilter
            val sort = state.chatSort
            val visible = remember(data.chats, section, favourites, query, filter, sort) {
                filterChats(data.chats, section, favourites, query, filter, sort)
            }
            if (visible.isEmpty()) {
                EmptyNote(
                    icon = when {
                        favouritesOnly && query.isBlank() -> TmIcons.StarOutline
                        else -> com.tmplayer.ui.browse.emptyTabIcon(section, query, filter)
                    },
                    text = when {
                        query.isNotBlank() -> s.chatsNoMatch(query)
                        favouritesOnly -> s.chatsFavouritesEmpty
                        section.isChats && filter != ChatFilter.All -> s.chatsSectionEmpty(filter.label)
                        else -> s.chatsSectionEmpty(section.label)
                    },
                    // No chat by that name in the list: the same words across every chat, which
                    // also asks Telegram for a chat with that @username.
                    actionLabel = when {
                        query.isNotBlank() -> s.searchAllChats
                        // An empty filter or folder of the chats: back to all of them.
                        section.isChats && filter != ChatFilter.All -> s.browseGoToChats
                        favouritesOnly -> s.browseGoToChats
                        else -> null
                    },
                    onAction = {
                        if (query.isNotBlank()) {
                            state.searchEverywhere(query)
                        } else {
                            state.chatFilter = ChatFilter.All
                            state.showChats(BrowseSection.of(BrowseTab.Chats))
                        }
                    },
                )
            } else {
                LaunchedEffect(Unit) {
                    val back = refocus ?: return@LaunchedEffect
                    refocus = null
                    nav.focus(visible.indexOfFirst { it.id == back })
                }
                ChatList(
                    chats = visible,
                    favourites = favourites,
                    listState = listState,
                    byName = sort == com.tmplayer.ui.browse.ChatSort.Name,
                    paging = model.paging.collectAsState().value,
                    onMoreChats = model::loadMoreChats,
                    nav = nav,
                    onOpen = state::openChat,
                    onStar = { chat -> scope.launch { state.settings.toggleFavorite(chat.id) } },
                    // Pin, archive and read are said only when Telegram refuses them, as on the
                    // phone: done, the row shows it (moving to the top, leaving, the badge
                    // clearing). Muting still says so, since here a row without unread messages
                    // looks the same either way.
                    actions = ChatRowActions(
                        onTogglePinned = { chat -> model.setPinned(chat, !chat.isPinned) { toast(it) } },
                        onToggleMuted = { chat ->
                            model.setMuted(chat, !chat.isMuted) { toast(it) }
                            toast(if (chat.isMuted) s.chatsUnmuted(chat.title) else s.chatsMuted(chat.title))
                        },
                        onToggleArchived = { chat -> model.setArchived(chat, !chat.isArchived) { toast(it) } },
                        onMarkRead = { chat -> model.markRead(chat) { toast(it) } },
                        onHide = { chat ->
                            // Gone from the list is the answer; no toast (user, 2026-10-22). Settings,
                            // Library, Hidden chats shows it again.
                            scope.launch { state.settings.hideChat(chat.id, chat.title) }
                        },
                    ),
                )
            }
        }
    }
}

/** One chip in a [ChoiceChips] row. */
@androidx.compose.runtime.Immutable
internal class Choice(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    /** What a screen reader says instead of [label], when the label alone is unclear. */
    val description: String? = null,
    val onClick: () -> Unit,
)

/**
 * A row of chips under a page's heading: the Chats filters and order, History's two tabs, and in
 * a narrow window the chat sections. Scrolls sideways when the window is too narrow for all of it.
 */
@Composable
internal fun ChoiceChips(choices: List<Choice>) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = pageMargin()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(choices, key = { it.key }) { choice ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A pick cross-fades the chip into its chosen colours rather than switching them,
                // and the chip gives a little under the press, as on the phone.
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val scheme = MaterialTheme.colorScheme
                val container by androidx.compose.animation.animateColorAsState(
                    if (choice.selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                    label = "chipBack",
                )
                val ink by androidx.compose.animation.animateColorAsState(
                    if (choice.selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                    label = "chipInk",
                )
                val glyph by androidx.compose.animation.animateColorAsState(
                    if (choice.selected) scheme.onSecondaryContainer else scheme.primary,
                    com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                    label = "chipIcon",
                )
                FilterChip(
                    selected = choice.selected,
                    onClick = choice.onClick,
                    label = { Text(choice.label, maxLines = 1) },
                    // The chosen chip's glyph fills in with a drop, as the bar's do.
                    leadingIcon = { com.tmplayer.ui.components.SelectableIcon(choice.icon, selected = choice.selected, tint = glyph, size = 18.dp) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = container,
                        selectedContainerColor = container,
                        labelColor = ink,
                        selectedLabelColor = ink,
                        iconColor = glyph,
                        selectedLeadingIconColor = glyph,
                    ),
                    interactionSource = press,
                    modifier = Modifier.pressScale(press).then(
                        choice.description?.let { words -> Modifier.semantics { contentDescription = words } } ?: Modifier,
                    ),
                )
                // The order is a different kind of control from the filters after it.
                if (choice.key == "sort") Box(Modifier.width(1.dp).height(24.dp).background(Tone.outline))
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
    /** Hides the chat from TMPlayer's lists (see HiddenChats); null leaves the entry out. */
    val onHide: ((ChatSummary) -> Unit)? = null,
)

/**
 * The rows themselves, with a scrollbar and the keyboard of [KeyboardNav]: Up and Down a row,
 * Home and End, Page Up and Page Down, Enter opens, S stars, H hides, P pins, M mutes, A archives, R marks
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
    /** Listed by name: the fast scroller's bubble gives the letter. */
    byName: Boolean = false,
    /** Whether Telegram may hold older chats than were read, and whether they are on their way. */
    paging: com.tmplayer.ui.browse.ChatPaging = com.tmplayer.ui.browse.ChatPaging(),
    /** Reads the next chats, as the list's end comes near. */
    onMoreChats: () -> Unit = {},
) {
    // Telegram's older chats read as the end of the list comes near, a screen ahead.
    com.tmplayer.ui.components.LoadMoreAhead(listState, paging.more && !paging.loading && paging.failure == null, onLoadMore = onMoreChats)
    Box(Modifier.fillMaxSize()) {
        val count by rememberUpdatedState(chats.size)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp).navKeys(nav) { count }.remembersRemoteItem(),
            // The row's own 12 dp inset plus this is the page margin, so the avatars line up with
            // the heading and the chips above them. The rows sit 2 dp apart, so a hovered row and
            // the focused one beside it never run into one block.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = pageMargin() - ChatRowInset,
                end = pageMargin() - ChatRowInset,
                top = 8.dp,
                // Clear of the floating bar, so the last chat can be scrolled out from under it.
                bottom = 8.dp + com.tmplayer.ui.components.LocalBottomChrome.current,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(chats, key = { _, it -> it.id }) { index, chat ->
                ChatRow(
                    chat = chat,
                    favourite = chat.id in favourites,
                    // A chat that moves (pinned, a new post) slides to its place rather than jumping.
                    modifier = Modifier.animateItem().navCell(nav, index),
                    onOpen = { onOpen(chat) },
                    onStar = { onStar(chat) },
                    actions = actions,
                )
            }
            if (paging.loading || paging.failure != null) {
                item(key = "more-chats", contentType = "more-chats") {
                    com.tmplayer.ui.components.PageFoot(paging.loading, paging.failure, onMoreChats, Modifier.padding(horizontal = ChatRowInset)) {
                        MoreChatsSkeleton()
                    }
                }
            }
        }
        ScrollToTop(listState)
        // The hold to scroll handle on a long list (the letter in its bubble when listed by name),
        // the scrollbar on a short one.
        val byId = remember(chats) { chats.associateBy { it.id } }
        FastScroller(listState, label = if (byName) ({ key -> byId[key]?.let { com.tmplayer.ui.browse.ScrollLabels.letter(it.title) } }) else null) {
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
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
    val s = LocalStrings.current
    var hasFocus by remember { mutableStateOf(false) }
    // Drawn for the keyboard only: a click opens the chat, and coming back to a ringed row read
    // as a row still selected.
    val focused = hasFocus && FocusVisibility.visible
    var menu by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // A row the pointer or the keyboard rests on is likely the next chat opened: its first page is
    // fetched behind, so the grid opens with it ([com.tmplayer.data.PageQueries]).
    LaunchedEffect(chat.id, hovered || hasFocus) {
        if (!hovered && !hasFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(com.tmplayer.data.PageQueries.PREFETCH_DWELL_MS)
        com.tmplayer.data.PageQueries.prefetchChat(chat.id)
    }
    // No card at rest: the row is the avatar and the words on the page's ground. Under the pointer
    // or the keyboard it lifts onto the step above, and the keyboard's row also takes the ring.
    val fill = when {
        focused -> Tone.surfaceHigh
        hovered -> Tone.surface
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(fill)
            .hoverable(hover)
            .then(if (focused) Modifier.border(Focus.Edge, Tone.accent, MaterialTheme.shapes.medium) else Modifier)
            .onPointerEvent(PointerEventType.Press) { event ->
                val macContext = CHATS_ON_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed || macContext) menu = true
            }
            .then(modifier)
            .onFocusChanged { hasFocus = it.isFocused }
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
                    Key.H -> actions.onHide?.invoke(chat) ?: return@onPreviewKeyEvent false
                    // Nothing to clear is not an error, and the key is still this row's.
                    Key.R -> if (chat.unreadCount > 0) actions.onMarkRead(chat)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .clickable(onClick = onOpen)
            .padding(horizontal = ChatRowInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChatAvatar(chat.miniThumbnail, chat.photoFileId, chat.title, Avatar.Compact + 8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chat.isPinned) Icon(TmIcons.Pin, contentDescription = s.chatsPinnedLabel, tint = Tone.muted, modifier = Modifier.width(14.dp))
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
        TipIconButton(
            if (favourite) TmIcons.Star else TmIcons.StarOutline,
            if (favourite) s.chatsUnfavourite else s.chatsFavourite,
            onStar,
            shortcut = "S",
            tint = if (favourite) Tone.caution else Tone.muted,
        )
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
    val s = LocalStrings.current
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
        entry(s.commonOpen, s.keysEnter, TmIcons.Folder, onOpen)
        entry(
            if (favourite) s.chatsUnfavourite else s.chatsFavourite,
            "S",
            if (favourite) TmIcons.Star else TmIcons.StarOutline,
            onStar,
        )
        // Private to this computer too: the chat leaves TMPlayer's lists, never Telegram.
        actions.onHide?.let { hide -> entry(s.browseHideChat, "H", TmIcons.VisibilityOff) { hide(chat) } }
        HorizontalDivider()
        entry(if (chat.isPinned) s.chatsUnpin else s.chatsPin, "P", TmIcons.Pin) { actions.onTogglePinned(chat) }
        entry(
            if (chat.isMuted) s.chatsUnmute else s.chatsMute,
            "M",
            if (chat.isMuted) TmIcons.Bell else TmIcons.BellOff,
        ) { actions.onToggleMuted(chat) }
        if (chat.unreadCount > 0) {
            entry(s.chatsMarkRead, "R", TmIcons.Check) { actions.onMarkRead(chat) }
        }
        entry(
            if (chat.isArchived) s.chatsUnarchive else s.chatsArchive,
            "A",
            TmIcons.Archive,
        ) { actions.onToggleArchived(chat) }
    }
}

/** How far a chat row's avatar sits in from the row's own edge. */
private val ChatRowInset = 12.dp

private val CHATS_ON_MAC: Boolean get() = OsInfo.isMac
