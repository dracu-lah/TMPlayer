package com.tmplayer.desktop.ui

import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.ChatSort
import com.tmplayer.ui.browse.ChatFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.DesktopConnectivity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import com.tmplayer.data.Td
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.BrowseTab
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.NavBrand
import com.tmplayer.ui.browse.NavEntry
import com.tmplayer.ui.browse.NavGroupBody
import com.tmplayer.ui.browse.NavGroupHeading
import com.tmplayer.ui.browse.browseSections
import com.tmplayer.ui.browse.navGroups
import com.tmplayer.ui.browse.DefaultGroups
import com.tmplayer.ui.onboarding.FirstSignIn
import com.tmplayer.ui.browse.rememberNavGroups
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.LocalToastHost
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.onboarding.Entry
import com.tmplayer.ui.onboarding.Onboarding
import com.tmplayer.ui.onboarding.OnboardingTour

/**
 * What the player draws for a [PlayRequest], given a way to close itself.
 *
 * The player lives in `com.tmplayer.desktop.player` and is passed in from `Main.kt`; until then
 * the shell draws [PlayerPlaceholder].
 */
typealias PlayerContent = @Composable (request: PlayRequest, onClose: () -> Unit) -> Unit

/**
 * The desktop window's content (B2.1): sign in until Telegram says Ready, then a sidebar above
 * 1000 dp of width (an icon rail below it), the page, and the player over the page when something
 * is playing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(
    state: ShellState,
    player: PlayerContent = { request, onClose -> PlayerPlaceholder(request, onClose) },
) {
    val toasts = remember { SnackbarHostState() }
    CompositionLocalProvider(
        LocalBackStack provides state.backStack,
        LocalToastHost provides toasts,
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                // The mouse's back button, wherever the pointer is.
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Back) state.backStack.handleBack()
                },
            color = Tone.background,
        ) {
            val auth by Td.auth.collectAsState()
            // Null until the store has answered, so a first run does not flash the sign in screen.
            val overviewSeen by state.settings.overviewSeen.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                val seen = overviewSeen
                if (seen != null) {
                    val signedIn = auth == AuthState.Ready
                    // A sign in screen arms the one card after the first sign in (FirstSignIn).
                    LaunchedEffect(auth) {
                        if (FirstSignIn.isSignInStep(auth)) runCatching { state.settings.armFirstSignInCard() }
                    }
                    val done: () -> Unit = { scope.launch { state.settings.markOverviewSeen() } }
                    // Signed in, Browse stays composed under a tour Settings asked for, so the
                    // chat list is where it was when the tour ends.
                    if (signedIn) Browse(state, player)
                    when (Onboarding.entry(signedIn, seen)) {
                        Entry.Tour -> Surface(Modifier.fillMaxSize(), color = Tone.background) {
                            OnboardingTour(state.settings, onDone = done, firstRun = !signedIn)
                        }
                        Entry.SignIn -> SignInScreen(auth)
                        Entry.App -> Unit
                    }
                }
                SnackbarHost(toasts, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun Browse(state: ShellState, player: PlayerContent) {
    val s = LocalStrings.current
    // One chat list for the session, the same view model the phone uses. Its store is cleared when
    // the account goes, which cancels everything it was loading.
    val chats = rememberViewModel(Unit) { ChatListViewModel(state.settings) }
    DisposableEffect(chats) { onDispose { chats.reset() } }
    LaunchedEffect(Unit) { chats.refreshIfStale() }
    // "Only my folders", mirrored into the shell for every page to read.
    LaunchedEffect(state) {
        state.settings.hideDefaultGroups.collect {
            state.hideDefaultGroups = it
            // The folders are then the only way into the chats, so they are not left folded.
            if (it) com.tmplayer.ui.browse.NavGroupState.unfold(com.tmplayer.ui.browse.NavGroup.Folders)
        }
    }
    // A section the option has hidden is not left on screen: the Chats page moves to the first
    // folder, or with none, the window goes Home. The chip over Chats is kept as it was.
    val shellFolders by Td.folders.collectAsState()
    LaunchedEffect(state.hideDefaultGroups, shellFolders, state.chatSection) {
        if (!state.hideDefaultGroups || !DefaultGroups.isDefault(state.chatSection)) return@LaunchedEffect
        val first = shellFolders.firstOrNull()
        val onChats = state.destination == Destination.Chats
        if (first != null) {
            state.pickChatSection(BrowseSection.Folder(first.id, first.title))
        } else if (onChats) {
            state.go(Destination.Home)
        }
    }
    // The chips over Chats and History: the saved choice first, then every change written back.
    LaunchedEffect(state) {
        runCatching {
            state.chatFilter = ChatFilter.decode(state.settings.chatFilter.first())
            state.chatSort = ChatSort.decode(state.settings.chatSort.first())
            state.historyTab = HistoryTab.decode(state.settings.historyTab.first())
        }
        snapshotFlow { Triple(state.chatFilter, state.chatSort, state.historyTab) }.drop(1).collect { (filter, sort, tab) ->
            runCatching {
                state.settings.setChatFilter(filter.name)
                state.settings.setChatSort(sort.name)
                state.settings.setHistoryTab(tab.name)
            }
        }
    }
    // "Open the last chat on launch": once the list has it, open it, once per sign in.
    LaunchedEffect(Unit) {
        val target = runCatching { state.settings.autoOpenTarget() }.getOrNull() ?: return@LaunchedEffect
        val chat = chats.state
            .mapNotNull { (it as? UiState.Content)?.value?.chats?.firstOrNull { chat -> chat.id == target } }
            .first()
        if (state.openChat == null && (state.destination == Destination.Home || state.destination == Destination.Chats)) state.openChat(chat)
    }

    // Housekeeping once signed in: the strays streaming left behind, after the first screen has
    // settled. The update check is not here: it runs from Main.kt, sign in or not.
    LaunchedEffect(Unit) {
        delay(HOUSEKEEPING_DELAY_MS)
        runCatching { com.tmplayer.desktop.DesktopStorage.housekeeping(state.extras.watchCache) }
    }
    val update = rememberNavUpdate(state)
    UpdatePopupTrigger(state, update)
    // The one card after the first sign in, "Show everything" or "Only my folders" (FirstSignIn).
    val signInCardPending by state.settings.firstSignInCardPending.collectAsState(initial = false)
    var signInCard by remember { mutableStateOf(false) }
    var hideGroupsPrompt by remember { mutableStateOf(false) }
    val chatsUi by chats.state.collectAsState()
    LaunchedEffect(signInCardPending, chatsUi is UiState.Content, shellFolders.size) {
        when (FirstSignIn.decide(signInCardPending, chatsUi is UiState.Content, shellFolders.size)) {
            FirstSignIn.Decision.Wait -> Unit
            FirstSignIn.Decision.Ask -> signInCard = true
            // A folder that turns up late restarts this effect, which cancels the skip.
            FirstSignIn.Decision.Skip -> {
                delay(FOLDERS_SETTLE_MS)
                runCatching { state.settings.markFirstSignInCardDone() }
            }
        }
    }
    DownloadToasts(state)
    // Half watched entries that can no longer become a card are swept once per sign in, as on the
    // phone. Left alone they are invisible: the page skips them, so nothing can ever clear them.
    val toast = rememberToast()
    LaunchedEffect(Unit) {
        val removed = runCatching { state.settings.pruneBrokenHistory() }.getOrDefault(0)
        if (removed > 0) {
            toast(
                s.continuePruned(removed),
            )
        }
    }

    // The offline pill, and the refresh once both the network and Telegram are back.
    val browseScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DesktopConnectivity.start() }
    val network by DesktopConnectivity.status.collectAsState()
    val telegramConnected by Td.connected.collectAsState()
    val connection = rememberConnectionNotice(network, telegramConnected) {
        chats.load()
        browseScope.launch { runCatching { state.extras.updates?.checkIfDue() } }
        toast(s.connectionBackOnline)
    }

    BackHandler(enabled = state.openChat != null) { state.closeChat() }
    // Back from any page lands on Home, the first destination, as on the phone and the TV.
    BackHandler(enabled = state.openChat == null && state.destination != Destination.Home) {
        state.go(Destination.Home)
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= SIDEBAR_FROM
            Row(Modifier.fillMaxSize()) {
                if (wide) Sidebar(state, update) else Rail(state, update)
                VerticalDivider(color = Tone.outline)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val open = state.openChat
                    when {
                        open != null -> MediaGridPage(state, open)
                        else -> when (state.destination) {
                            Destination.Home -> HomePage(state, chats)
                            Destination.Chats -> ChatsPage(state, chats, favouritesOnly = false, showSections = !wide)
                            Destination.Favourites -> ChatsPage(state, chats, favouritesOnly = true)
                            Destination.History -> HistoryPage(state)
                            Destination.Downloads -> DownloadsPage(state)
                            Destination.Settings -> SettingsPage(
                                state,
                                BuildInfo.VERSION,
                                chatList = (chatsUi as? UiState.Content)?.value?.chats.orEmpty(),
                            )
                        }
                    }
                    // Over the page only, so the side bar stays reachable while a pane is open.
                    DetailPaneHost(state)
                }
            }
        }

        UpdatePopupHost(state)
        ShellNotices(state, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        if (signInCard && state.nowPlaying == null && !state.updatePopup) {
            FirstSignInCard(
                onEverything = {
                    signInCard = false
                    browseScope.launch { runCatching { state.settings.markFirstSignInCardDone() } }
                },
                onOnlyFolders = {
                    signInCard = false
                    hideGroupsPrompt = true
                    browseScope.launch { runCatching { state.settings.markFirstSignInCardDone() } }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }
        if (hideGroupsPrompt) {
            val favourites by state.settings.favorites.collectAsState(initial = emptySet())
            val loaded = (chatsUi as? UiState.Content)?.value?.chats.orEmpty()
            val unreachable = remember(favourites, loaded) { DefaultGroups.unreachableFavorites(favourites, loaded) }
            val words = DefaultGroups.prompt(unreachable.size, shellFolders.size)
            ConfirmDialog(
                title = words.title,
                message = words.message,
                detail = words.detail,
                confirmLabel = words.confirm,
                onConfirm = {
                    hideGroupsPrompt = false
                    browseScope.launch {
                        state.settings.setHideDefaultGroups(true, unstar = unreachable)
                        toast(s.groupsHiddenToast)
                    }
                },
                onDismiss = { hideGroupsPrompt = false },
            )
        }
        if (SupportReminder.enabled) {
            SupportCardHost(state, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }

        ConnectionBanner(connection, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))


        // Over the page rather than instead of it, so the page keeps its scroll position and its
        // search, and closing the player is simply taking this away.
        val playing = state.nowPlaying
        if (playing != null) {
            Box(Modifier.fillMaxSize().background(Tone.background)) {
                BackHandler { state.closePlayer() }
                player(playing) { state.closePlayer() }
            }
        }
    }
}

private val SIDEBAR_FROM = 1000.dp
private const val HOUSEKEEPING_DELAY_MS = 20_000L

/** How long an account that seems to have no folders is given before the first sign in card is skipped. */
private const val FOLDERS_SETTLE_MS = 3_000L

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Home -> Icons.Filled.Home
    Destination.Chats -> BrowseTab.Chats.icon
    Destination.Favourites -> Icons.Filled.Star
    Destination.History -> BrowseTab.History.icon
    Destination.Downloads -> TmIcons.Download
    Destination.Settings -> Icons.Filled.Settings
}

/**
 * The wide side bar: the name and version, then Watch, Chats and the account's folders folding
 * under their headings, with Settings and Update pinned at the bottom. The grouping is the shared
 * one in :ui, so the phone's drawer and the television's rail fold the same entries the same way.
 */
@Composable
internal fun Sidebar(
    state: ShellState,
    update: NavUpdate? = null,
    folders: List<ChatFolderSummary> = Td.folders.collectAsState().value,
) {
    val s = LocalStrings.current
    val groups = rememberNavGroups(state.currentGroup)
    val sections = remember(folders, state.hideDefaultGroups) { browseSections(folders, state.hideDefaultGroups) }
    val inFlight = rememberDownloadsInFlight()
    Column(Modifier.width(240.dp).fillMaxHeight().padding(12.dp)) {
        NavBrand(BuildInfo.VERSION, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            navGroups(sections).forEach { (group, entries) ->
                val open = groups.isOpen(group)
                if (group.headed) NavGroupHeading(
                    group = group,
                    open = open,
                    toggleable = groups.canToggle(group),
                    onToggle = { groups.toggle(group) },
                    height = SIDEBAR_ROW,
                )
                NavGroupBody(open) {
                    entries.forEach { entry ->
                        val target = entry.destination()
                        SidebarItem(
                            label = if (entry is NavEntry.Section) entry.section.label else s.navDownloads,
                            icon = target?.icon() ?: (entry as NavEntry.Section).section.icon,
                            badge = if (entry == NavEntry.Downloads && inFlight > 0) inFlight.toString() else null,
                            selected = when {
                                target != null -> state.destination == target
                                else -> state.destination == Destination.Chats &&
                                    state.chatSection == (entry as NavEntry.Section).section
                            },
                            onClick = {
                                if (target != null) state.go(target) else state.showChats((entry as NavEntry.Section).section)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Tone.outline)
        // Above Settings, as on the phone and the TV, rather than among the pages: it opens the popup.
        if (update != null) {
            SidebarItem(
                label = update.label,
                icon = Icons.Filled.Refresh,
                badge = update.version,
                selected = false,
                onClick = { state.updatePopup = true },
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedIconColor = Tone.caution,
                    unselectedTextColor = Tone.caution,
                    unselectedBadgeColor = Tone.caution,
                ),
            )
        }
        SidebarItem(
            label = Destination.Settings.label,
            icon = Destination.Settings.icon(),
            badge = null,
            selected = state.destination == Destination.Settings,
            onClick = { state.go(Destination.Settings) },
        )
    }
}

/** The page an entry opens when it is a page of its own; null for a slice of the chat list. */
private fun NavEntry.destination(): Destination? = when (this) {
    NavEntry.Downloads -> Destination.Downloads
    is NavEntry.Section -> when (section) {
        BrowseSection.of(BrowseTab.Home) -> Destination.Home
        BrowseSection.of(BrowseTab.History) -> Destination.History
        BrowseSection.of(BrowseTab.Favorites) -> Destination.Favourites
        else -> null
    }
}

/** A side bar row, 40 dp rather than Material's 56: a pointer needs no thumb sized target. */
@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    colors: androidx.compose.material3.NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        badge = badge?.let { { Text(it) } },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(SIDEBAR_ROW),
        colors = colors,
    )
}

private val SIDEBAR_ROW = 40.dp

@Composable
private fun Rail(state: ShellState, update: NavUpdate?) {
    val s = LocalStrings.current
    NavigationRail(containerColor = Tone.background) {
        Image(
            AppLogo.Mark,
            contentDescription = null,
            modifier = Modifier.padding(vertical = 12.dp).size(28.dp),
        )
        val inFlight = rememberDownloadsInFlight()
        val folders by Td.folders.collectAsState()
        val hidden = state.hideDefaultGroups
        Destination.entries.forEach { destination ->
            // With the default groups hidden the Chats page lists only the folders, so it is named
            // for them, and with no folders there is nothing for it to show.
            if (destination == Destination.Chats && hidden && folders.isEmpty()) return@forEach
            val label = if (destination == Destination.Chats && hidden) s.navFolders else destination.label
            NavigationRailItem(
                selected = state.destination == destination,
                onClick = { state.go(destination) },
                icon = {
                    if (destination == Destination.Downloads && inFlight > 0) {
                        BadgedBox(badge = { Badge { Text(inFlight.toString()) } }) {
                            Icon(destination.icon(), contentDescription = s.navInProgress(destination.label, inFlight))
                        }
                    } else {
                        val icon = if (destination == Destination.Chats && hidden) TmIcons.Folder else destination.icon()
                        Icon(icon, contentDescription = label)
                    }
                },
                label = { Text(label.substringBefore(' ')) },
            )
        }
        // The rail cuts labels at the first space, so the version rides in a badge on the icon.
        if (update != null) {
            NavigationRailItem(
                selected = false,
                onClick = { state.updatePopup = true },
                icon = {
                    BadgedBox(badge = {
                        Badge(containerColor = Tone.caution, contentColor = Tone.readableOn(Tone.caution)) {
                            Text(update.version)
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "${update.label} ${update.version}")
                    }
                },
                label = { Text(update.label.substringBefore(' ')) },
                colors = NavigationRailItemDefaults.colors(
                    unselectedIconColor = Tone.caution,
                    unselectedTextColor = Tone.caution,
                ),
            )
        }
    }
}
