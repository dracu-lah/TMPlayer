package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.tmplayer.ui.theme.Focus
import com.tmplayer.data.SettingsStore
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.tmplayer.ui.components.TmDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.ChatListSkeleton
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedWhen
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.AppMark
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.delay
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.focusScale
import com.tmplayer.ui.theme.Tv

/**
 * What the line under the app bar's title counts.
 *
 * Every tab but two is a list of chats; Continue watching and Previously watched are lists of
 * videos, so they count videos.
 */
private fun countLabel(section: BrowseSection, count: Int): String =
    if (section.listsVideos) L.browseVideosCount(count) else L.browseChatsCount(count)

// BrowseTab, BrowseSection, browseSections and filterChats live in :ui (BrowseSections.kt).

@Composable
fun BrowseScreen(
    state: UiState<BrowseData>,
    /**
     * The header, delivered separately from [state] so a cold start can draw the name and face
     * off the snapshot while the chat list is still loading. Falls back to what [state] carries,
     * so callers without the separate flow (previews, fixtures) change nothing.
     */
    account: Account? = null,
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
    /**
     * The Downloads screen, from the drawer on a phone and from the rail on a television. Both
     * form factors keep the downloads the viewer asked for by name until told otherwise; only
     * the watch cache is the television's one-video, replaced-on-next-play arrangement, so a TV
     * accumulates a list worth managing exactly as a phone does.
     */
    onOpenDownloads: () -> Unit = {},
    /** How many downloads are running, for the badge on that row of the drawer and the rail. */
    downloadCount: Int = 0,
    onToggleFavorite: (ChatSummary) -> Unit = {},
    /**
     * These write to Telegram rather than to this device. Defaulted to nothing so a preview, or
     * the screenshot fixture, can draw this screen without a TDLib client behind it.
     */
    onTogglePinned: (ChatSummary) -> Unit = {},
    onToggleArchived: (ChatSummary) -> Unit = {},
    onToggleMuted: (ChatSummary) -> Unit = {},
    onMarkRead: (ChatSummary) -> Unit = {},
    onRestartMedia: (ResumeRecord) -> Unit = {},
    onForgetMedia: (ResumeRecord) -> Unit = {},
    /** Empties Continue watching in one go, rather than one held-OK menu per video. */
    onClearHistory: () -> Unit = {},
    /**
     * Puts a Continue watching video on the Watched list, which also takes it off this one. For a
     * video the viewer finished somewhere else, or gave up on and wants out of the way.
     */
    onMarkMediaWatched: (ResumeRecord) -> Unit = {},
    /** Previously watched, newest first: what the player saw to the end, and what was marked. */
    watchedHistory: List<WatchedRecord> = emptyList(),
    /** Opens a finished video in the player again, from the start. */
    onOpenWatched: (WatchedRecord) -> Unit = {},
    onMarkUnwatched: (WatchedRecord) -> Unit = {},
    /** Empties Previously watched in one go, behind a confirmation. */
    onClearWatched: () -> Unit = {},
    /** Unstars every chat in one go, the counterpart to the star in each chat's menu. */
    onClearFavorites: () -> Unit = {},
    /** The chat that reopens on launch, marked on its row so the jump is never unexplained. */
    launchChatId: Long = 0L,
    picked: BrowseSection? = null,
    onPickTab: (BrowseSection) -> Unit = {},
    /** The viewer's Telegram folders, which become rail items of their own. Empty until TDLib says. */
    folders: List<ChatFolderSummary> = emptyList(),
    /**
     * Rows or tiles, chosen from the pill beside the tab name and remembered from then on. One
     * arrangement covers every tab: they are all lists of the same card.
     */
    layout: CardLayout = CardLayout.List,
    onToggleLayout: () -> Unit = {},
    /** The newer version on GitHub, if there is one. Shown on the rail, in amber. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
    /** Home's rows, built by [com.tmplayer.data.HomeRows] from what has loaded so far. */
    homeRows: List<HomeRow> = emptyList(),
    /** Continue watching videos as Telegram describes them, by progress key, for their pictures. */
    homeArt: Map<String, MediaItem> = emptyMap(),
    homeWatch: SeriesWatch = SeriesWatch.None,
    /** A row came on screen without its videos: fetch them. */
    onHomeRowShown: (HomeRow) -> Unit = {},
    onHomeArtWanted: (ResumeRecord) -> Unit = {},
    /** Plays a video from one of Home's rows; the second argument is its chat's name. */
    onPlayMedia: (MediaItem, String) -> Unit = { _, _ -> },
    onRefreshHome: () -> Unit = {},
) {
    val s = LocalStrings.current
    // An unfinished video wins the landing tab, otherwise Recent, so the first screen is never
    // empty. Those are read from disk after the first frame, so the tab settles once they arrive;
    // an explicit pick always wins. [picked] is hoisted rather than remembered here because
    // opening a chat swaps this screen out of the composition.
    val sections = remember(folders) { browseSections(folders, withWatched = true) }
    // How many chats have something unread in them, not how many messages are unread across them:
    // the rail badge sits beside "Unread", which names a list of chats.
    val allChats = (state as? UiState.Content)?.value?.chats
    val unreadChats = remember(allChats) {
        allChats.orEmpty().count { it.unreadCount > 0 && !it.isArchived }
    }
    // The saved section is matched back against the live folder list, so the rail never highlights
    // a folder deleted elsewhere and the heading never keeps a stale name.
    val tab = picked?.let { chosen ->
        when (chosen) {
            is BrowseSection.Tab -> chosen
            is BrowseSection.Folder -> sections.filterIsInstance<BrowseSection.Folder>()
                .firstOrNull { it.id == chosen.id }
                ?: chosen.takeIf { folders.isEmpty() }
        }
    // Home is the first destination on every device: it leads with what is half watched, so it
    // is never a worse landing than Continue watching was, and it is never empty for long.
    } ?: BrowseSection.of(BrowseTab.Home)
    var query by remember { mutableStateOf("") }
    // What the viewer held OK on. Only ever one at a time, so two nullable slots cover both lists.
    var chatMenu by remember { mutableStateOf<ChatSummary?>(null) }
    var mediaMenu by remember { mutableStateOf<ResumeRecord?>(null) }
    var watchedMenu by remember { mutableStateOf<WatchedRecord?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearWatched by remember { mutableStateOf(false) }
    var confirmClearFavorites by remember { mutableStateOf(false) }

    // One question decides the whole shape of this screen: a permanent rail beside the listing on
    // a television, a drawer behind a hamburger on a phone. Everything below the chrome is the
    // same composition either way, told only how much room it has and how far in it may start.
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
                    if (tab.isHome) {
                        if (!touch) {
                            TabHeading(tab, 0, insets) { RefreshAction(onRefreshHome) }
                        }
                        val titles = remember(data.chats) { data.chats.associate { it.id to it.title } }
                        HomePane(
                            rows = homeRows,
                            watch = homeWatch,
                            art = homeArt,
                            chatTitle = { titles[it].orEmpty() },
                            start = insets.start,
                            end = insets.end,
                            bottom = insets.bottom,
                            onRowShown = onHomeRowShown,
                            onArtWanted = onHomeArtWanted,
                            onResume = onResumeMedia,
                            onHoldRecord = { mediaMenu = it },
                            onPlay = onPlayMedia,
                            onSeeContinue = { onPickTab(BrowseSection.of(BrowseTab.Continue)); query = "" },
                            onOpenChat = { id -> data.chats.firstOrNull { it.id == id }?.let(onOpenChat) },
                        )
                    } else if (tab.isContinue) {
                        // On a phone the heading, the count and the actions live in the app bar, so
                        // the content area starts with the content.
                        if (!touch) {
                            TabHeading(tab, continueWatching.size, insets) {
                                LayoutAction(layout, onToggleLayout)
                                // No Refresh here: this tab is read off this device and cannot be
                                // behind, so the button would be one that visibly does nothing.
                                if (continueWatching.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearHistory,
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
                                text = { it.cardText() },
                                onResume = onResumeMedia,
                                onHold = { mediaMenu = it },
                            )
                        }
                    } else if (tab.isWatched) {
                        if (!touch) {
                            TabHeading(tab, watchedHistory.size, insets) {
                                LayoutAction(layout, onToggleLayout)
                                if (watchedHistory.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearWatched,
                                        icon = Icons.Filled.Close,
                                        onClick = { confirmClearWatched = true },
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        if (watchedHistory.isEmpty()) {
                            EmptyTab(tab, query = "")
                        } else {
                            // Read once per visit rather than ticking: "5 minutes ago" going stale
                            // while the tab is open is harmless, and a clock here would recompose
                            // every card each minute for it.
                            val now = remember(watchedHistory) { System.currentTimeMillis() }
                            ContinueSection(
                                records = watchedHistory,
                                text = { it.cardText(now) },
                                layout = layout,
                                insets = insets,
                                tiles = tiles,
                                autoFocus = !touch,
                                onResume = onOpenWatched,
                                onHold = { watchedMenu = it },
                            )
                        }
                    } else {
                        if (!touch) {
                            TabHeading(tab, visible.size, insets) {
                                LayoutAction(layout, onToggleLayout)
                                RefreshAction(onRefresh)
                                // Stars are added one at a time from a menu, so the only way back
                                // from a tab full of them is here, beside the tab they fill.
                                if (tab == BrowseSection.of(BrowseTab.Favorites) && favorites.isNotEmpty()) {
                                    HeaderAction(
                                        label = s.browseClearFavourites,
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
                                // unfiltered list already is "recent". The strip is dropped in
                                // grid view: the grid's own first row is those same chats, and the
                                // two together read as the list repeating itself.
                                recent = if (
                                    tab == BrowseSection.of(BrowseTab.Recent) &&
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
        val voiceSearch = rememberVoiceSearch(s.browseVoicePromptChat) { query = it }
        // Continue watching lists videos held on this device, so it counts those instead. Keep the
        // chat count remembered: the same filter and fuzzy ranking already run inside the pane
        // below, and an unremembered copy here costs a second full pass on every keystroke.
        val chats = (state as? UiState.Content)?.value?.chats
        val count = if (tab.isHome) {
            0
        } else if (tab.isContinue) {
            continueWatching.size
        } else if (tab.isWatched) {
            watchedHistory.size
        } else {
            remember(chats, tab, favorites, query) {
                chats?.let { filterChats(it, tab, favorites, query).size } ?: 0
            }
        }
        TouchBrowseShell(
            account = (state as? UiState.Content)?.value?.account ?: account,
            selected = tab,
            sections = sections,
            favoriteCount = favorites.size,
            unreadCount = unreadChats,
            onSelect = { onPickTab(it); query = "" },
            onOpenSettings = onOpenSettings,
            onOpenDownloads = onOpenDownloads,
            downloadCount = downloadCount,
            updateVersion = updateVersion,
            onUpdate = onUpdate,
            title = tab.heading,
            // Continue watching is a list of videos held on this device, and the box searches
            // chats. Offering it there would be a field that filters nothing.
            searchQuery = if (tab.listsVideos || tab.isHome) null else query,
            onSearchQueryChange = { query = it },
            onVoiceSearch = voiceSearch,
            actions = actions@{
                // Home is rows of tiles whatever the arrangement, so it only offers a refresh.
                if (tab.isHome) {
                    BarIcon(s.commonRefresh, Icons.Filled.Refresh, onRefreshHome)
                    return@actions
                }
                BarIcon(
                    label = if (layout == CardLayout.Grid) s.browseShowAsRows else s.browseShowAsTiles,
                    icon = if (layout == CardLayout.Grid) {
                        Icons.AutoMirrored.Filled.List
                    } else {
                        TmIcons.Grid
                    },
                    onClick = onToggleLayout,
                )
                if (!tab.listsVideos) {
                    BarIcon(s.commonRefresh, Icons.Filled.Refresh, onRefresh)
                }
                // Everything destructive goes behind the overflow. A "Clear history" button
                // sitting in the bar beside Refresh is one mis-tap from emptying the tab.
                val clearHistory = tab.isContinue && continueWatching.isNotEmpty()
                val clearFavorites = tab == BrowseSection.of(BrowseTab.Favorites) && favorites.isNotEmpty()
                val clearWatched = tab.isWatched && watchedHistory.isNotEmpty()
                if (clearHistory || clearFavorites || clearWatched) {
                    BarOverflow(
                        items = buildList {
                            if (clearHistory) {
                                add(s.browseClearContinue to { confirmClearHistory = true })
                            }
                            if (clearWatched) {
                                add(s.browseClearWatchedList to { confirmClearWatched = true })
                            }
                            if (clearFavorites) {
                                add(s.browseClearFavourites to { confirmClearFavorites = true })
                            }
                        },
                    )
                }
            },
            content = {
                // The tab's count under the title, where a phone's app bar puts a subtitle.
                // Nowhere else on the screen says how much is in the tab.
                Column(Modifier.fillMaxSize()) {
                    if (count > 0 && query.isBlank()) {
                        M3Text(
                            countLabel(tab, count),
                            style = M3MaterialTheme.typography.bodySmall,
                            color = Tone.muted,
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
                account = (state as? UiState.Content)?.value?.account ?: account,
                selected = tab,
                sections = sections,
                favoriteCount = favorites.size,
                unreadCount = unreadChats,
                onSelect = { onPickTab(it); query = "" },
                onOpenSettings = onOpenSettings,
                onOpenDownloads = onOpenDownloads,
                downloadCount = downloadCount,
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
            subtitle = chatCaption(chat),
            onDismiss = { chatMenu = null },
            actions = buildList {
                add(
                    MenuAction(s.navOpen, Icons.Filled.PlayArrow) {
                        chatMenu = null
                        onOpenChat(chat)
                    },
                )
                add(
                    MenuAction(
                        label = if (favorite) s.browseRemoveFavourite else s.browseAddFavourite,
                        icon = if (favorite) Icons.Filled.Star else TmIcons.StarOutline,
                        detail = if (favorite) {
                            s.browseRemoveFavouriteDetail
                        } else {
                            s.browseAddFavouriteDetail
                        },
                    ) {
                        chatMenu = null
                        onToggleFavorite(chat)
                    },
                )
                // Everything below this point changes the chat in Telegram itself rather than
                // only in this app, so each one says so: a star is private to this device, a pin
                // is on the viewer's phone a second later.
                add(
                    MenuAction(
                        label = if (chat.isPinned) s.browseUnpin else s.browsePin,
                        icon = TmIcons.Pin,
                        detail = s.browsePinDetail,
                    ) {
                        chatMenu = null
                        onTogglePinned(chat)
                    },
                )
                add(
                    MenuAction(
                        label = if (chat.isMuted) s.browseUnmute else s.browseMute,
                        icon = if (chat.isMuted) TmIcons.Bell else TmIcons.BellOff,
                        detail = if (chat.isMuted) {
                            s.browseUnmuteDetail
                        } else {
                            s.browseMuteDetail
                        },
                    ) {
                        chatMenu = null
                        onToggleMuted(chat)
                    },
                )
                if (chat.unreadCount > 0) {
                    add(
                        MenuAction(
                            label = s.browseMarkRead,
                            icon = Icons.Filled.Check,
                            detail = s.browseMarkReadDetail,
                        ) {
                            chatMenu = null
                            onMarkRead(chat)
                        },
                    )
                }
                add(
                    MenuAction(
                        label = if (chat.isArchived) s.browseUnarchive else s.browseArchive,
                        icon = TmIcons.Archive,
                        detail = if (chat.isArchived) {
                            s.browseUnarchiveDetail
                        } else {
                            s.browseArchiveDetail
                        },
                    ) {
                        chatMenu = null
                        onToggleArchived(chat)
                    },
                )
            },
        )
    }

    if (confirmClearFavorites) {
        TvConfirm(
            title = s.browseClearFavouritesTitle,
            message = s.browseClearFavouritesMessage(favorites.size),
            detail = s.browseClearFavouritesDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearFavorites = false
                onClearFavorites()
            },
            onDismiss = { confirmClearFavorites = false },
        )
    }

    if (confirmClearHistory) {
        TvConfirm(
            title = s.browseClearContinueTitle,
            message = s.browseClearContinueMessage(continueWatching.size),
            detail = s.browseClearContinueDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearHistory = false
                onClearHistory()
            },
            onDismiss = { confirmClearHistory = false },
        )
    }

    if (confirmClearWatched) {
        TvConfirm(
            title = s.browseClearWatchedTitle,
            message = s.browseClearWatchedMessage(watchedHistory.size),
            detail = s.browseClearWatchedDetail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirmClearWatched = false
                onClearWatched()
            },
            onDismiss = { confirmClearWatched = false },
        )
    }

    watchedMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = listOf(
                WatchedWhen.phrase(record.watchedAt, System.currentTimeMillis()),
                record.chatTitle,
            ).filter { it.isNotBlank() }.joinToString("  ·  "),
            onDismiss = { watchedMenu = null },
            actions = listOf(
                MenuAction(s.browsePlayAgain, Icons.Filled.PlayArrow, detail = s.browseFromTheStart) {
                    watchedMenu = null
                    onOpenWatched(record)
                },
                MenuAction(
                    label = s.gridMarkUnwatched,
                    icon = Icons.Filled.Close,
                    detail = s.browseMarkUnwatchedDetail,
                    destructive = true,
                ) {
                    watchedMenu = null
                    onMarkUnwatched(record)
                },
            ),
        )
    }

    mediaMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = s.browseWatchedUpTo(s.formatter.clock(record.positionMs)),
            onDismiss = { mediaMenu = null },
            actions = listOf(
                MenuAction(s.commonResume, Icons.Filled.PlayArrow, detail = s.browseResumeDetail) {
                    mediaMenu = null
                    onResumeMedia(record)
                },
                MenuAction(s.gridPlayFromStart, Icons.Filled.Refresh) {
                    mediaMenu = null
                    onRestartMedia(record)
                },
                MenuAction(
                    label = s.gridMarkWatched,
                    icon = Icons.Filled.Check,
                    detail = s.browseMarkWatchedDetail,
                ) {
                    mediaMenu = null
                    onMarkMediaWatched(record)
                },
                MenuAction(
                    label = s.browseForgetResume,
                    icon = Icons.Filled.Close,
                    detail = s.browseForgetResumeDetail,
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
 * Icon-only and stock: [IconButton] brings the 48 dp touch target and the ripple for free.
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
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        M3Icon(Icons.Filled.MoreVert, contentDescription = s.commonMoreOptions)
    }
    TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { (label, action) ->
            DropdownMenuItem(
                text = { M3Text(label) },
                onClick = { open = false; action() },
            )
        }
    }
}

// ---- continue watching -----------------------------------------------------------------------

/**
 * What a card in either list of videos shows: Continue watching and Previously watched draw the
 * same card, and differ only in the line under the title and how full the bar is.
 */
private class VideoCardText(
    val key: String,
    val title: String,
    /** The line under the title on a tile, and the start of it on a row. */
    val detail: String,
    val chatTitle: String,
    val fraction: Float,
)

private fun ResumeRecord.cardText() = VideoCardText(
    key = "${chatId}_$messageId",
    title = title,
    detail = buildString {
        append(L.messages.formatter.clock(positionMs))
        if (remainingMs > 0) {
            append("  ·  ")
            append(L.browseTimeLeft(L.messages.formatter.clock(remainingMs)))
        }
    },
    chatTitle = chatTitle,
    fraction = fraction,
)

/** A finished video: when it was finished, and a full bar, which is what "watched" looks like. */
private fun WatchedRecord.cardText(now: Long) = VideoCardText(
    key = key,
    title = title,
    detail = WatchedWhen.phrase(watchedAt, now),
    chatTitle = chatTitle,
    fraction = 1f,
)

@Composable
private fun <T : Any> ContinueSection(
    records: List<T>,
    text: (T) -> VideoCardText,
    layout: CardLayout,
    insets: BrowseInsets,
    tiles: GridCells,
    /** Only a remote needs somewhere to stand; on a phone a stolen focus only opens the keyboard. */
    autoFocus: Boolean,
    onResume: (T) -> Unit,
    onHold: (T) -> Unit,
) {
    val first = remember { FocusRequester() }
    // Only the first card asks for focus, and which card that is does not change with the
    // arrangement, so the two branches can share one modifier.
    fun focusOf(record: T): Modifier =
        if (record === records.firstOrNull()) Modifier.focusRequester(first) else Modifier

    // The same start inset the chat list uses, so the cards line up under their heading instead of
    // butting against the rail.
    val padding = PaddingValues(
        start = insets.start,
        end = insets.end,
        // Room above the first row for a focused tile to grow into; nothing on a phone.
        top = if (isTouch()) 0.dp else Tv.FocusClearance,
        bottom = insets.bottom,
    )

    when (layout) {
        CardLayout.List -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = padding,
        ) {
            items(records, key = { text(it).key }) { record ->
                ContinueCard(
                    text = text(record),
                    onResume = { onResume(record) },
                    onHold = { onHold(record) },
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
            gridItems(records, key = { text(it).key }) { record ->
                ContinueTile(
                    text = text(record),
                    onResume = { onResume(record) },
                    onHold = { onHold(record) },
                    modifier = focusOf(record),
                )
            }
        }
    }

    // This is the landing tab for anyone with a video on the go, and the remote has nowhere to go
    // until something holds focus. Re-run on a change of arrangement too: switching rebuilds the
    // list from scratch, and the card that was holding focus leaves the composition with it.
    LaunchedEffect(records.firstOrNull()?.let { text(it).key }, layout, autoFocus) {
        if (autoFocus) runCatching { first.requestFocus() }
    }
}

/**
 * A Continue watching card as a tile: the preview carries the progress bar, because there is no
 * row of text alongside it to put the bar under.
 */
@Composable
private fun ContinueTile(
    text: VideoCardText,
    onResume: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "continueTileBorder",
    )

    // The art, the bar and the two lines are the same on both screens; only what holds them
    // differs, so the tile is written once and handed to whichever container the device wants.
    val body: @Composable ColumnScope.() -> Unit = {
        Box {
            ContinueArt(Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge = 40.dp)
            ResumeProgress(
                fraction = text.fraction,
                touch = touch,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                overArt = true,
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text.title,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                // Two lines, as everywhere else a release name is shown: one line cuts the title
                // before the resolution, which is the part that tells two of them apart.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (touch) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                ),
            shape = M3MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Tone.surface),
            content = body,
        )
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .focusScale(focused)
                .clip(RoundedCornerShape(Corner.Medium))
                .background(if (focused) Tone.surfaceHigh else Tone.surface)
                .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                ),
            content = body,
        )
    }
}

/**
 * How far through a video is.
 *
 * A phone gets the platform's own indicator, so the bar picks up the scheme, the stop mark and the
 * rounded cap every other progress bar on the device has. A television gets two painted boxes, to
 * sit quietly under the focus border. [overArt] is the difference between a bar lying on a
 * thumbnail, which needs a dark track to stay visible, and one on a card, which does not.
 */
@Composable
private fun ResumeProgress(
    fraction: Float,
    touch: Boolean,
    modifier: Modifier = Modifier,
    overArt: Boolean,
) {
    val progress = fraction.coerceIn(0.01f, 1f)
    if (touch) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            color = Tone.accent,
            trackColor = if (overArt) Color.Black.copy(alpha = 0.55f) else Tone.surfaceHigh,
        )
        return
    }

    Box(
        modifier
            .height(if (overArt) 6.dp else 4.dp)
            .then(if (overArt) Modifier else Modifier.clip(CircleShape))
            .background(
                if (overArt) Color.Black.copy(alpha = 0.55f) else Tone.muted.copy(alpha = 0.25f),
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(Tone.accent),
        )
    }
}

@Composable
private fun ContinueCard(
    text: VideoCardText,
    onResume: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(140),
        label = "continueBorder",
    )

    val body: @Composable RowScope.() -> Unit = {
        ContinueArt(
            Modifier.width(THUMBNAIL_WIDTH).height(THUMBNAIL_HEIGHT),
            badge = 34.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text.title,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                // Two lines, as everywhere else a release name is shown: one line cuts the title
                // before the resolution, which is the part that tells two of them apart.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Text(
                buildString {
                    append(text.detail)
                    // The chat name gives way to the hold hint while focused: both are tail
                    // information on one line, and only the hint is worth saying to the row the
                    // viewer is standing on.
                    if (focused) {
                        append("  ·  ")
                        append(HOLD_HINT)
                    } else if (text.chatTitle.isNotBlank()) {
                        append("  ·  ")
                        append(text.chatTitle)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The bar is the point of the row: it says at a glance that this is a video part way
            // through rather than one not started.
            ResumeProgress(
                fraction = text.fraction,
                touch = touch,
                modifier = Modifier.fillMaxWidth(),
                overArt = false,
            )
        }
    }

    if (touch) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                ),
            shape = M3MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Tone.surface),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = body,
            )
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Large))
                .background(Tone.surface)
                .border(Focus.Edge, border, RoundedCornerShape(Corner.Large))
                .holdable(
                    interactionSource = interactions,
                    onClick = onResume,
                    onHold = onHold,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = body,
        )
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
            .background(Tone.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            // The mark is the app's colour on the raised surface it sits on, which is a pairing
            // the scheme guarantees is readable whichever way round the phone's theme is.
            tint = Tone.accent,
            modifier = Modifier.size(badge * 0.88f),
        )
    }
}

private val THUMBNAIL_WIDTH = 96.dp
private val THUMBNAIL_HEIGHT = 54.dp


// ---- navigation rail -------------------------------------------------------------------------

@Composable
private fun NavRail(
    account: Account?,
    selected: BrowseSection,
    sections: List<BrowseSection>,
    favoriteCount: Int,
    unreadCount: Int,
    onSelect: (BrowseSection) -> Unit,
    onOpenSettings: () -> Unit,
    /** The Downloads screen: the videos kept on this device and the queue still fetching them. */
    onOpenDownloads: () -> Unit,
    /** Downloads in flight, badged on the Downloads item so a fetch is never invisible. */
    downloadCount: Int,
    /** The version on GitHub, when it beats the one running. Null the rest of the time. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
) {
    val s = LocalStrings.current
    Column(
        Modifier
            // 180dp of room for the items, whatever the overscan margin takes on the left.
            .width(180.dp + Tv.SafeH - RAIL_INSET)
            .fillMaxHeight()
            .background(Tone.surface)
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
        RailBrand()
        Spacer(Modifier.height(14.dp))
        AccountBadge(account)
        Spacer(Modifier.height(10.dp))

        val context = LocalContext.current
        val settings = remember(context) { SettingsStore(context) }
        val groups = rememberNavGroups(settings, current = navGroupOf(selected))

        // Scrolls, and takes whatever height is left after the bottom cluster: Settings and the
        // update item. A plain Column clips what it cannot fit, so with every group open and a
        // few folders anything past the fold would be unreachable.
        //
        // The remote never has to think about it: focus moving down inside a scrolling column
        // brings the focused item into view, so the rail scrolls as a side effect of pressing down.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Watch, Chats and the account's folders, each folding under a heading OK toggles.
            // The grouping is the shared one in :ui, so the phone's drawer and the desktop's side
            // bar fold the same entries the same way. A folded group's rows are not composed, so
            // D-pad down from its heading goes straight on to the next heading.
            navGroups(sections).forEach { (group, entries) ->
                val open = groups.isOpen(group)
                RailGroupHeading(
                    label = group.label,
                    open = open,
                    toggleable = groups.canToggle(group),
                    onToggle = { groups.toggle(group) },
                )
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        entries.forEach { entry ->
                            when (entry) {
                                NavEntry.Downloads -> RailItem(
                                    label = s.navDownloads,
                                    icon = TmIcons.Download,
                                    // How many videos are coming down right now. A download outlives
                                    // the screen it was started from, and a television has no
                                    // notification shade to say one is still running, so this mark
                                    // is the only place that says it at all.
                                    badge = downloadCount.takeIf { it > 0 }?.toString(),
                                    selected = false,
                                    onClick = onOpenDownloads,
                                )
                                is NavEntry.Section -> RailItem(
                                    label = entry.section.label,
                                    icon = entry.section.icon,
                                    badge = when {
                                        entry.section == BrowseSection.of(BrowseTab.Favorites) && favoriteCount > 0 ->
                                            favoriteCount.toString()
                                        entry.section == BrowseSection.of(BrowseTab.Unread) && unreadCount > 0 ->
                                            unreadCount.toString()
                                        else -> null
                                    },
                                    selected = entry.section == selected,
                                    onClick = { onSelect(entry.section) },
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = RAIL_INSET, end = 4.dp, top = 6.dp, bottom = 6.dp),
            color = Tone.outline.copy(alpha = 0.5f),
        )
        if (updateVersion != null) {
            RailItem(
                label = s.navUpdate,
                icon = Icons.Filled.Refresh,
                badge = updateVersion,
                selected = false,
                onClick = onUpdate,
                accent = Caution,
            )
        }
        // The version no longer rides on Settings: it sits beside the name at the top, where a
        // long one has room and "Settings" keeps its whole word.
        RailItem(
            label = s.navSettings,
            icon = Icons.Filled.Settings,
            badge = null,
            selected = false,
            onClick = onOpenSettings,
        )
    }
}

/**
 * The logo, the name and the version, at the top of the rail.
 *
 * Two lines beside the mark rather than one, because the rail is 180 dp wide and the version has
 * to stay at a readable 14 sp from across the room. Not focusable: it is a label, not a place.
 */
@Composable
private fun RailBrand() {
    Row(
        Modifier.padding(start = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(RAIL_MARK)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "TMPlayer",
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
            )
            Text(
                "v${Updates.installedVersion}",
                style = MaterialTheme.typography.labelLarge,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The heading over a group of rail items, which OK folds and unfolds.
 *
 * Quiet like the old unfocusable headings, muted text and a small chevron, until focus lands on it,
 * when it fills the way a rail item does. The group holding the current tab cannot fold, so its
 * heading is a plain label the remote passes straight over, exactly as every heading used to be.
 */
@Composable
private fun RailGroupHeading(
    label: String,
    open: Boolean,
    toggleable: Boolean,
    onToggle: () -> Unit,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Color.Transparent,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railHeadingBackground",
    )
    val foreground by animateColorAsState(
        targetValue = if (focused) Tone.onFocusFill else Tone.muted,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railHeadingForeground",
    )
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "railHeadingChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .height(RAIL_ROW)
            .clip(RoundedCornerShape(Corner.Small))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Small))
            .then(
                if (toggleable) {
                    Modifier
                        .clickable(interactionSource = interactions, indication = null, onClick = onToggle)
                        .semantics { stateDescription = if (open) s.navOpen else s.navFolded }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (toggleable) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(22.dp).rotate(turn),
            )
        }
    }
}

@Composable
private fun AccountBadge(account: Account?) {
    val s = LocalStrings.current
    Row(
        Modifier.padding(start = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Avatar.Compact).clip(CircleShape).background(Tone.surfaceHigh)) {
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
                account?.name ?: s.browseYourAccount,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!account?.username.isNullOrBlank()) {
                Text(
                    "@${account.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
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
    accent: Color = Tone.accent,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    // The app's own blue fills a focused row with the role Material means for a filled area, which
    // is a pale blue in daylight rather than the near-black tone 40 that primary has to be. The
    // amber item carries its own colour and has no such pair, so it fills with what it was given.
    val own = accent == Tone.accent
    val fill = if (own) Tone.focusFill else accent

    // Cross-faded rather than switched: an instant colour flip as focus sweeps down the rail
    // reads as flicker on a large screen.
    val background by animateColorAsState(
        targetValue = when {
            focused -> fill
            selected -> accent.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railBackground",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            focused -> if (own) Tone.onFocusFill else Tone.readableOn(accent)
            selected -> accent
            else -> if (own) Tone.muted else accent
        },
        animationSpec = tween(FOCUS_FADE_MS),
        label = "railForeground",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(RAIL_ROW)
            .clip(RoundedCornerShape(Corner.Small))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Small))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = RAIL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
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
            // Clamped, because the badge measures before the label: an unusually long badge, such
            // as a suffixed version name, would shorten "Settings" to "S...".
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
    val s = LocalStrings.current
    HeaderAction(
        // A grid of squares and a stack of lines are the two pictures every phone and television
        // uses for this, so the icon alone carries it.
        label = if (layout == CardLayout.Grid) s.browseAsRows else s.browseAsTiles,
        icon = if (layout == CardLayout.Grid) Icons.AutoMirrored.Filled.List else TmIcons.Grid,
        showLabel = false,
        onClick = onToggle,
    )
}

/**
 * Fetches the chat list again.
 *
 * The list is built once when the screen opens, so a chat that moves, is joined, or is renamed
 * while it is up does not appear until something rebuilds it.
 */
@Composable
private fun RefreshAction(onRefresh: () -> Unit) {
    val s = LocalStrings.current
    HeaderAction(
        label = s.commonRefresh,
        // Icon-only, here as everywhere: a heading whose chips are two pictures and one
        // picture-with-a-word reads as three unrelated controls. The name still reaches a screen
        // reader through [label].
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
        targetValue = if (focused) Tone.focusFill else Tone.surface,
        animationSpec = tween(140),
        label = "headerAction",
    )
    val foreground = if (focused) Tone.onFocusFill else Tone.text

    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            // Even padding with no words, so a wordless chip comes out round rather than wide.
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
 * Deliberately not called `Header`: the media grid one file away has a composable of that name in
 * this same package, and the clash resolves to whichever is private, breaking an unrelated import.
 */
@Composable
private fun TabHeading(
    tab: BrowseSection,
    count: Int,
    insets: BrowseInsets,
    action: @Composable () -> Unit = {},
) {
    // The number always carries its unit, and the two video tabs count videos rather than chats.
    val s = LocalStrings.current
    val counted = if (tab.listsVideos) s.browseVideosCount(count) else s.browseChatsCount(count)
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
                color = Tone.text,
            )
            Text(
                if (count > 0) "${tab.blurb}  ·  $counted" else tab.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
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
        if (!isTouch()) {
            Spacer(Modifier.width(20.dp))
            TvStatusCluster()
        }
    }
}

/**
 * The clock, the date and the app's mark, in the top right corner of the television.
 *
 * A phone has a status bar, so an app that drew its own would draw the time twice. A television
 * has none, and this app fills the panel with the system bars hidden, so the clock has to come
 * from here. The app mark beside it says which app is talking on a shared screen.
 *
 * The tick is aligned to the wall clock rather than run every minute from whenever the screen
 * happened to open, so the minute changes when the minute changes.
 */
@Composable
private fun TvStatusCluster() {
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            val date = java.util.Date()
            now = date
            delay(MS_PER_MINUTE - (date.time % MS_PER_MINUTE))
        }
    }
    // Read through the configuration so a change of system language recomposes the cluster.
    val locale = LocalConfiguration.current.locales[0]
    // The viewer's own choice of 12 or 24 hour, which is a setting on the device and not a taste
    // this app has any business overriding.
    val clock = remember(locale) { java.text.SimpleDateFormat("h:mm a", locale) }
    val clock24 = remember(locale) { java.text.SimpleDateFormat("HH:mm", locale) }
    val day = remember(locale) { java.text.SimpleDateFormat("EEE d MMM", locale) }
    val twentyFour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (twentyFour) clock24 else clock).format(now),
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
            )
            Text(
                day.format(now),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(14.dp))
        AppMark(size = 36.dp)
    }
}

private const val MS_PER_MINUTE = 60_000L

@Composable
private fun SearchRow(query: String, insets: BrowseInsets, onQuery: (String) -> Unit) {
    val s = LocalStrings.current
    val startVoice = rememberVoiceSearch(s.browseVoicePromptChat, onQuery)
    val searchField = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = s.browseSearchChats,
            modifier = Modifier.weight(1f).focusRequester(searchField),
        )
        if (startVoice != null) {
            // A microphone on its own says it, and leaves the room to the search field.
            PillButton(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)
        }
        if (query.isNotBlank()) {
            // Clearing the query removes this pill, and a control that deletes itself while
            // focused takes the focus with it, leaving the D-pad nowhere to go. Move focus to the
            // search field first, which is where the viewer wants to be next anyway.
            PillButton(s.commonClear, Icons.Filled.Close) {
                runCatching { searchField.requestFocus() }
                onQuery("")
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector?,
    /** False draws the icon alone; the label is still what a screen reader says. */
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.focusFill else Tone.surfaceHigh,
        animationSpec = tween(FOCUS_FADE_MS),
        label = "pillBackground",
    )
    val foreground = if (focused) Tone.onFocusFill else Tone.text

    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            // An icon on its own gets even padding, so the pill comes out round rather than wide.
            .padding(horizontal = if (showLabel) 18.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                // A wordless pill still hands its label to the screen reader; a worded one says
                // it once, through the text.
                contentDescription = if (showLabel) null else label,
                tint = foreground,
                modifier = Modifier.size(22.dp),
            )
        }
        if (showLabel) {
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
    val s = LocalStrings.current
    val rowsAreFullBleed = isTouch()
    // Around the strip's tiles, for the television's focus scale. A phone does not grow them.
    val clearance = if (rowsAreFullBleed) 0.dp else Tv.FocusClearance
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
                    // the list's side padding dropped for their sake, this heading has to put it
                    // back or it prints hard against the panel edge.
                    Column(
                        if (rowsAreFullBleed) {
                            Modifier.padding(start = insets.start, end = insets.end)
                        } else {
                            Modifier
                        },
                    ) {
                        Text(
                            s.browseJumpBackIn,
                            style = MaterialTheme.typography.titleLarge,
                            color = Tone.text,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                        LazyRow(
                            // Drawn [clearance] past every side and padded back in by as much, so
                            // a grown tile keeps its border and the strip still lines up under its
                            // heading and sits where it did.
                            modifier = Modifier.bleed(clearance),
                            contentPadding = PaddingValues(clearance),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
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
                            s.browseMoreChats,
                            style = MaterialTheme.typography.titleLarge,
                            color = Tone.text,
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
            // clipped by the panel edge.
            contentPadding = PaddingValues(
                start = insets.start,
                end = insets.end,
                top = if (rowsAreFullBleed) 0.dp else Tv.FocusClearance,
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
    // it rebuilds the list, and the card that was holding focus leaves the composition with it.
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
    val s = LocalStrings.current
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    val body: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(Avatar.Card).clip(CircleShape),
            )
            Spacer(Modifier.weight(1f))
            UnreadBadge(chat, small = true)
            if (favorite) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = s.browseFavourite,
                    tint = Tone.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            chat.title,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Ellipsis keeps the row tidy but hides the end of a long channel name. Scrolling it
            // while focused keeps the whole name readable, for that one tile only.
            modifier = Modifier.weight(1f).marqueeWhen(focused),
        )
        Text(
            when {
                focused -> HOLD_HINT
                opensOnLaunch -> s.browseOpensOnLaunch
                else -> chatCaption(chat)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (touch) {
        Card(
            modifier = modifier
                // Fixed, not wrapped: a chat whose name runs to two lines would otherwise stand
                // taller than its neighbours and leave the row visibly ragged.
                .height(RECENT_TILE_HEIGHT)
                .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),
            shape = M3MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Tone.surface),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), content = body)
        }
    } else {
        Column(
            modifier
                .height(RECENT_TILE_HEIGHT)
                .focusScale(focused)
                .clip(RoundedCornerShape(Corner.Large))
                .background(if (focused) Tone.surfaceHigh else Tone.surface)
                .border(
                    width = Focus.Edge,
                    color = if (focused) Tone.accent else Color.Transparent,
                    shape = RoundedCornerShape(Corner.Large),
                )
                .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
                .padding(16.dp),
            content = body,
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
 * On a phone the list is rows on the window background with nothing between them, which is how
 * every phone app whose main screen is a chat list draws it, and it fits far more on a screen. The
 * television keeps cards, where focus has to be visible from a sofa and a border is how that is
 * said.
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
    val s = LocalStrings.current
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
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .border(
                width = Focus.Edge,
                color = if (focused) Tone.accent else Color.Transparent,
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
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.marqueeWhen(focused),
            )
            Text(
                // The row says what it is, then what can be done to it once focus arrives. The
                // launch marker explains why this chat opens straight away on the next start.
                when {
                    focused -> "${chatCaption(chat)}  ·  $HOLD_HINT"
                    opensOnLaunch -> "${chatCaption(chat)}  ·  ${s.browseOpensOnLaunch}"
                    else -> chatCaption(chat)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnreadBadge(chat)
            ChatMarkers(chat, size = 20.dp)
            if (favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = s.browseFavourite,
                    tint = Tone.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * What the second line of a chat row says about the chat, beyond what kind of thing it is.
 *
 * Pinned and muted are not spelled out here: [ChatMarkers] draws them as glyphs, where Telegram
 * draws them, which leaves this one line free to say what the chat is.
 */
private fun chatCaption(chat: ChatSummary): String = when (chat.kind) {
    ChatKind.Saved -> L.browseTabSavedHeading
    ChatKind.Channel -> L.browseTabChannels
    ChatKind.Group -> L.browseTabGroups
    ChatKind.Direct -> L.browseTabPeople
}

/**
 * The pin and the silent bell, at the end of the row where a chat list puts them.
 *
 * Both are drawn muted rather than in the accent colour: they are facts about the row, and the one
 * thing on a chat row allowed to ask for attention is the unread count. They trail after that
 * count, which keeps the position nearest the text.
 */
@Composable
private fun ChatMarkers(chat: ChatSummary, size: Dp) {
    val s = LocalStrings.current
    if (chat.isMuted) {
        Icon(
            TmIcons.BellOff,
            contentDescription = s.browseMuted,
            tint = Tone.muted,
            modifier = Modifier.size(size),
        )
    }
    if (chat.isPinned) {
        Icon(
            TmIcons.Pin,
            contentDescription = s.browsePinned,
            tint = Tone.muted,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * The count of unread messages, drawn the way Telegram draws it.
 *
 * A muted chat keeps its count and loses its colour: it still says something arrived, without
 * asking to be looked at. Below one, nothing is drawn at all rather than a zero.
 */
@Composable
private fun UnreadBadge(chat: ChatSummary, small: Boolean = false) {
    if (chat.unreadCount < 1) return
    val fill = if (chat.isMuted) Tone.muted else Tone.accent
    Text(
        // Telegram's own ceiling. Past a few hundred the exact number stops meaning anything and
        // starts widening the pill enough to eat the chat's name.
        text = if (chat.unreadCount > UNREAD_CAP) "$UNREAD_CAP+" else chat.unreadCount.toString(),
        style = if (small) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelLarge
        },
        color = Tone.readableOn(fill),
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = if (small) 6.dp else 10.dp, vertical = if (small) 1.dp else 3.dp),
    )
}

/** Telegram stops counting out loud here too. */
private const val UNREAD_CAP = 999

/**
 * The phone's chat row, which is Material's two-line list item and nothing more.
 *
 * [ListItem] handles the avatar gap, the two type styles and the scheme colours, in a light theme
 * and a dark one alike. The container is transparent so the rows sit on the window the way a
 * phone's list of conversations does, and the height is pinned so the shimmer that stands in for
 * this row lands on the same metric.
 */
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
    val s = LocalStrings.current
    ListItem(
        headlineContent = {
            // Two lines, because a channel name is regularly longer than a phone is wide.
            M3Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier
            .fillMaxWidth()
            // A floor rather than a fixed height: a one-line name keeps Material's 72dp row, and
            // only a name that needs the second line makes its row taller.
            .heightIn(min = TOUCH_ROW_HEIGHT)
            // No clip and no background: the row sits on the window, so a press ripples across
            // the whole width of the screen the way a phone's list rows do.
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),
        supportingContent = {
            M3Text(
                // No hold hint on a phone: there is no focused row for it to attach to.
                if (opensOnLaunch) {
                    "${chatCaption(chat)}  ·  ${s.browseOpensOnLaunch}"
                } else {
                    chatCaption(chat)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(TOUCH_AVATAR).clip(CircleShape),
            )
        },
        trailingContent = if (favorite || chat.unreadCount > 0 || chat.isPinned || chat.isMuted) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnreadBadge(chat)
                    ChatMarkers(chat, size = 16.dp)
                    if (favorite) {
                        M3Icon(
                            Icons.Filled.Star,
                            contentDescription = s.browseFavourite,
                            tint = Tone.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EmptyTab(tab: BrowseSection, query: String) {
    val s = LocalStrings.current
    // Each empty tab says what to do about it, in its own words. A folder is the one section this
    // app has no way to fill from here: folders are made and edited in Telegram itself, so the
    // message says where to go rather than offering something to press.
    val message = when {
        query.isNotBlank() -> s.browseEmptyNoMatch(query)
        tab is BrowseSection.Folder ->
            s.browseEmptyFolder
        tab == BrowseSection.of(BrowseTab.Continue) ->
            s.browseEmptyContinue
        tab == BrowseSection.of(BrowseTab.Watched) ->
            s.browseEmptyWatched
        tab == BrowseSection.of(BrowseTab.Favorites) ->
            s.browseEmptyFavourites
        tab == BrowseSection.of(BrowseTab.Unread) -> s.browseEmptyUnread
        tab == BrowseSection.of(BrowseTab.Archived) -> s.browseEmptyArchived
        tab == BrowseSection.of(BrowseTab.Saved) ->
            s.browseEmptySaved
        else -> s.browseEmptyOther
    }
    // A search that came back empty is a different situation from a tab with nothing in it yet, so
    // the glyph follows whichever one the viewer is looking at.
    BigEmpty(message, icon = if (query.isNotBlank()) Icons.Filled.Search else tab.icon)
}

private const val RECENT_COUNT = 8
private const val FOCUS_FADE_MS = 140

/**
 * Shown on the focused row only.
 *
 * A hold is invisible until someone tries it. Attached to the focused row the hint arrives exactly
 * when it is actionable, and costs no layout: it takes the place of a label the row already had.
 */
private val HOLD_HINT: String get() = L.browseHoldHint
/**
 * One rail row, heading or item, back to back. The old 44 dp item plus a 4 dp gap had the same
 * pitch; this gives the whole of it to the target, the 48 dp floor of the v1 design.
 */
private val RAIL_ROW = 48.dp

/** The mark beside the name and version at the top of the rail. */
private val RAIL_MARK = 32.dp

/** Horizontal padding every rail child carries, which is what keeps them out of the overscan. */
private val RAIL_INSET = 16.dp

/** Enough for a version name, and never enough to eat the item's own label. */
private val RAIL_BADGE_MAX = 64.dp
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
 * dialler all draw. The chat list skeleton has to match this figure.
 */
private val TOUCH_ROW_HEIGHT = 72.dp
private val TOUCH_AVATAR = Avatar.List

/**
 * How far the listing keeps from each edge.
 *
 * A television crops its outermost few percent, so the TV figures are overscan clearance and have
 * nothing to do with taste. A phone crops nothing, so it spends far less of its width on margins.
 */
/**
 * Grows a lazy row by [by] on every side without taking any more room, so it may draw that far past
 * the space it was given. Paired with a content padding of the same size.
 */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    if (extra == 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val wide = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height - extra * 2) { placeable.place(-extra, -extra) }
}

private class BrowseInsets(val start: Dp, val end: Dp, val top: Dp, val bottom: Dp)

private val TvInsets = BrowseInsets(start = 28.dp, end = Tv.FocusClearance, top = Tv.SafeV, bottom = Tv.SafeV)
private val TouchInsets = BrowseInsets(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
