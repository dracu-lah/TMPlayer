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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.data.NetworkStatus
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.AuthState
import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.DesktopConnectivity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import com.tmplayer.data.Td
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.BrowseTab
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.NavBrand
import com.tmplayer.ui.browse.NavEntry
import com.tmplayer.ui.browse.navSection
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.browse.browseSections
import com.tmplayer.ui.browse.navGroups
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.Setup
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.LocalToastHost
import com.tmplayer.ui.components.ToastHost
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.components.BrandLoaderHandOff

/**
 * What the player draws for a [PlayRequest], given a way to close itself.
 *
 * The player lives in `com.tmplayer.desktop.player` and is passed in from `Main.kt`.
 */
typealias PlayerContent = @Composable (request: PlayRequest, onClose: () -> Unit) -> Unit

/**
 * True where the player's picture is drawn under the window's content rather than in it: the
 * old browser build, whose video element sat under the page's canvas. Nothing sets it any more.
 * While a video plays, the shell then paints nothing of its own over it but the player's
 * controls. The desktop draws mpv's picture into the window, so it stays false there.
 */
val LocalVideoBehindPage = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The desktop window's content (B2.1): sign in until Telegram says Ready, then the page with the
 * floating bar at its foot ([NavFrame]), and the player over the page when something is playing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(
    state: ShellState,
    player: PlayerContent,
) {
    val toasts = remember { SnackbarHostState() }
    // The arrows nothing on the page took move focus from here in the big-screen mode (ShellState.onKey).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.SideEffect { state.focusManager = focusManager }
    // Offline (R4): the line down, held 2 s each way so a flaky link never flashes, and while it is,
    // the downloads whose file is really there, which are what plays then.
    LaunchedEffect(Unit) { DesktopConnectivity.start() }
    val network by DesktopConnectivity.status.collectAsState()
    val telegramConnected by Td.connected.collectAsState()
    val offline = com.tmplayer.ui.browse.rememberSettledOffline(network == NetworkStatus.Offline && !telegramConnected)
    // Keyed on the open profile's shell (HX88, review D3): a switch never shows the last one's.
    val offlineHome = rememberOfflineHome(offline, owner = state) {
        state.settings.downloadsNow().filter { com.tmplayer.desktop.DownloadIndex.state(it) == com.tmplayer.desktop.DownloadIndex.FileState.Present }
    }
    CompositionLocalProvider(
        com.tmplayer.ui.browse.LocalOfflineHome provides offlineHome,
        LocalBackStack provides state.backStack,
        LocalToastHost provides toasts,
        // A video's copies open as a centred card over the window, the phone's sheet on a desktop.
        com.tmplayer.ui.components.LocalSheetHost provides DesktopSheetHost,
        // The big-screen mode's dialogs take the remote as they open and walk with the arrows.
        com.tmplayer.ui.components.LocalRemoteArrows provides BigScreen.on,
        // The window's own buttons cover the top end of whatever has no header of its own: the setup's and the tour's
        // Skip all keep clear of them.
        com.tmplayer.ui.components.LocalWindowControlsInset provides com.tmplayer.ui.components.windowControlsInset(
            LocalWindowChrome.current?.let { if (it.closeOnly) 1 else if (it.onMinimize != null) 3 else 2 } ?: 0,
        ),
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                // The mouse's back button, wherever the pointer is.
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Back) state.backStack.handleBack()
                },
            color = if (LocalVideoBehindPage.current && state.playerUp) androidx.compose.ui.graphics.Color.Transparent else Tone.background,
        ) {
            val auth by Td.auth.collectAsState()
            // The profiles (HX88): Who's watching? at launch with two or more, the open profile's
            // PIN when it has one, then everything below keyed on the open profile. With one
            // profile and no PIN the gate draws this window's content and nothing else.
            val profiles = remember { com.tmplayer.desktop.profiles.DesktopProfiles.flow() }
            LaunchedEffect(profiles) { com.tmplayer.desktop.profiles.DesktopProfiles.messages.collect { toasts.showSnackbar(it) } }
            val shellShown = com.tmplayer.ui.lock.profileShellShown(profiles)
            // The window's keys act on the shell only while it is what shows (HX88, review P3).
            androidx.compose.runtime.SideEffect { state.shellShown = shellShown }
            Box(Modifier.fillMaxSize()) {
            GatedShell(
                profiles,
                signInStep = Setup.isSignInStep(auth),
                lists = state.watched.lists,
                onMessage = { state.notices.tryEmit(it) },
                onOpenList = { state.openLists(it) },
            ) {
            Box(Modifier.fillMaxSize()) {
                // The QR code stays up for a moment after it is scanned, marked "Scanned".
                val shownAuth = com.tmplayer.ui.auth.rememberScanHold(auth)
                val signedIn = shownAuth == AuthState.Ready
                // A sign in screen arms the setup that follows it (CP67, see Setup).
                LaunchedEffect(auth) {
                    // First a session Telegram ended (HX23): unlike Sign out, the viewer chose
                    // nothing, so watched, Watch later, bookmarks and preferences stay; only the
                    // old session's cache and cold start snapshot go.
                    com.tmplayer.ui.connect.handleSessionEnd(auth, state.settings) {
                        // The video the old session was playing goes with it, not back on screen
                        // after the next sign in.
                        state.closePlayer()
                        runCatching { Td.clearMediaCache() }
                        // Telegram ended the session: the database it signs in to next is a new
                        // one (HX88), so nothing stored with this one's file ids is resumed on it.
                        runCatching { Td.renewDatabaseMarker() }
                        runCatching { state.settings.clearColdStartSnapshots() }
                    }
                    if (Setup.isSignInStep(auth)) {
                        // The setup is for a new start: after an ended session the preferences stay.
                        if (!Td.sessionEnded.value) runCatching { state.settings.armSetup() }
                        // The account is gone, however it went (signed out from Telegram's
                        // Devices list is the way back from a forgotten PIN): so is its lock.
                        // Unless other profiles share the computer (HX88): the lock is the
                        // device's, and a new profile's sign in, or one profile's ended login,
                        // is not theirs.
                        val others = com.tmplayer.data.AccountSwitcher.current()?.profiles?.value?.profiles?.size ?: 1
                        if (others <= 1) runCatching { state.settings.clearAppLock() }
                    }
                }
                val appLock by state.settings.appLock.collectAsState(initial = null)
                val appUnlocked by com.tmplayer.data.AppLockSession.unlocked.collectAsState()
                // "Ask when the app opens" (CP57): the pad in place of the window's content
                // until the PIN is given. Closing the window is the way out.
                val askOnOpen = signedIn && appLock?.onOpen == true && !appUnlocked
                if (askOnOpen) {
                    Box(Modifier.fillMaxSize().windowDrag()) {
                        PinPad(
                            settings = state.settings,
                            purpose = PinPurpose.Check,
                            title = LocalStrings.current.lockEnterForApp,
                            brand = true,
                            onDone = {
                                com.tmplayer.data.AppLockSession.unlock()
                                state.settingsUnlocked = true
                            },
                            onCancel = null,
                        )
                    }
                } else if (signedIn) {
                    // The setup after sign in sits over Browse (SetupOverlay), so the chat list
                    // loads under it and is where it was when the setup ends.
                    Browse(state, player)
                } else {
                    SignInScreen(shownAuth)
                    // Telegram ended the session (HX23): why the sign in is back.
                    if (!com.tmplayer.ui.connect.signInBannerShown()) {
                        com.tmplayer.ui.connect.SessionEndedNote(
                            Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                }
                // Telegram out of reach on a screen already drawn: a sign in pane with no line yet,
                // or a session whose network is up and Telegram is not answering (blocked, most
                // likely). The offline pill covers a network that is really gone.
                val unreachable by Td.unreachable.collectAsState()
                if ((!signedIn && com.tmplayer.ui.connect.signInBannerShown()) || (signedIn && unreachable)) {
                    com.tmplayer.ui.connect.ConnectBanner(
                        onOpenProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 24.dp, end = 24.dp),
                    )
                }
                // The loader after launch, over the window until Telegram has something to ask
                // or the session is open, then fading out over what is drawn under it. Its second
                // line is the live step (ConnectingHelp), and past the long wait, help.
                // Once signed in it waits on for Home's first whole screen (rows and the carousel's
                // first picture), at most HOLD_MAX_MS, so Home appears finished rather than filling in.
                var homeHoldOver by remember { mutableStateOf(false) }
                LaunchedEffect(signedIn) {
                    if (!signedIn) return@LaunchedEffect
                    kotlinx.coroutines.delay(com.tmplayer.ui.browse.HomeFirstPaint.HOLD_MAX_MS)
                    homeHoldOver = true
                }
                val holdForHome = signedIn && !askOnOpen && !homeHoldOver && !com.tmplayer.ui.browse.HomeFirstPaint.ready
                BrandLoaderHandOff(
                    label = if (auth == AuthState.Connecting || auth == AuthState.ChooseMethod) {
                        LocalStrings.current.signinConnecting
                    } else if (holdForHome) {
                        LocalStrings.current.loginGettingReady
                    } else {
                        null
                    },
                    modifier = Modifier.windowDrag(),
                ) {
                    // Composed only while the loader is up: over Home's header, held under it.
                    CoverWindowControls()
                    com.tmplayer.ui.connect.FirstOpenPromise(state.settings)
                    com.tmplayer.ui.connect.ConnectingHelp(
                        onOpenProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                        sendLogs = { SendLogsActions(Modifier.padding(top = 8.dp)) },
                    )
                }
                if (com.tmplayer.ui.connect.ProxyScreenState.open) CoverWindowControls()
                com.tmplayer.ui.connect.ProxyScreenHost()
            }
            }
                // At the foot, centred ToastLayout.Gap above the floating bar or a chat's bar, by
                // the bar's measured top (ToastFloor), as a desktop app's messages sit: the top of
                // a window is Back, the chat's name and the window's own buttons, which a message
                // there covered. In the big-screen mode it also keeps the TV's 5 per cent margin.
                // Only while the open profile's shell is what shows (HX88): never over its PIN or
                // the picker. The flow's own lines wait until then.
                if (shellShown) {
                    val windowHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
                    val safe = if (BigScreen.on) with(androidx.compose.ui.platform.LocalDensity.current) { (windowHeight * 0.05f).toDp() } else 0.dp
                    ToastHost(toasts, safeBottom = safe)
                }
            }

        }
    }
}

/**
 * The profiles' gate around the window's content, and what must live inside it (HX88, review P1):
 * "Add to a list" (HX43), opened from any menu or pane, is held under the gate, which draws its
 * content only while the open profile's shell shows and keys it on that profile. A lock (the window
 * away more than a minute) or a switch takes an open picker down with it, so the video's title and
 * the locked profile's list names are never drawn over the PIN pad, and nothing writes to them.
 */
@Composable
internal fun GatedShell(
    profiles: com.tmplayer.ui.lock.ProfileFlow?,
    signInStep: Boolean,
    lists: com.tmplayer.data.ListsStore,
    onMessage: (String) -> Unit,
    onOpenList: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    com.tmplayer.desktop.profiles.DesktopProfilesGate(profiles, signInStep = signInStep) {
        com.tmplayer.ui.browse.ListPickerHost(lists, onMessage = onMessage, onOpenList = onOpenList, content = content)
    }
}

/**
 * What Home shows while offline: the downloads whose file is really there, read for the open
 * profile's shell ([owner]) and forgotten the moment it changes (HX88, review D3), so a switch while
 * offline never shows the last profile's downloads, a PIN profile's titles included. [settled]
 * stays false until the new profile's own read is in.
 */
@Composable
internal fun rememberOfflineHome(
    offline: Boolean,
    owner: Any?,
    read: suspend () -> List<com.tmplayer.data.ResumeRecord>,
): com.tmplayer.data.OfflineHome {
    var offlineDownloads by remember(owner) { mutableStateOf<List<com.tmplayer.data.ResumeRecord>>(emptyList()) }
    var offlineRead by remember(owner) { mutableStateOf(false) }
    val reader by androidx.compose.runtime.rememberUpdatedState(read)
    LaunchedEffect(offline, owner) {
        offlineRead = false
        offlineDownloads = if (offline) {
            com.tmplayer.data.runCatchingCancellable { reader() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        offlineRead = true
    }
    return remember(offline, offlineDownloads, offlineRead) {
        com.tmplayer.data.OfflineHome(offline, offlineDownloads, settled = !offline || offlineRead)
    }
}

/**
 * Settings behind the app lock's PIN (CP57), asked once per visit. With no PIN set there is
 * nothing to ask, and a PIN set from inside Settings leaves the page open.
 */
@Composable
private fun LockedSettings(state: ShellState, content: @Composable () -> Unit) {
    val lock by state.settings.appLock.collectAsState(initial = null)
    val known = lock ?: return
    LaunchedEffect(known.pinSet) { if (!known.pinSet) state.settingsUnlocked = true }
    if (known.pinSet && !state.settingsUnlocked) {
        PinPad(
            settings = state.settings,
            purpose = PinPurpose.Check,
            title = LocalStrings.current.lockEnterForSettings,
            onDone = { state.settingsUnlocked = true },
            onCancel = { if (state.modalOpen) state.closeModal() else state.go(Destination.Home) },
        )
    } else {
        content()
    }
}

@Composable
private fun Browse(state: ShellState, player: PlayerContent) {
    val s = LocalStrings.current
    // One chat list for the session, the same view model the phone uses. Its store is cleared when
    // the account goes, which cancels everything it was loading.
    val chats = rememberViewModel(Unit) { ChatListViewModel(state.settings, com.tmplayer.data.PageQueries.cache) }
    DisposableEffect(chats) { onDispose { chats.reset() } }
    LaunchedEffect(Unit) { chats.refreshIfStale() }
    LaunchedEffect(state) { state.settings.detailFirst.collect { state.detailFirst = it } }
    // New videos in the starred chats and the sponsor reminder, for as long as the app runs.
    DesktopChecks(state)
    // "Only my folders" is retired, and the folders themselves moved into Add chats (HX29): the
    // old switch is read once and dropped, and nothing is hidden because of it.
    LaunchedEffect(Unit) { runCatching { state.settings.takeOnlyFolders() } }
    // The chips over Chats and History: the saved choice first, then every change written back.
    LaunchedEffect(state) {
        runCatching {
            state.chatFilter = ChatFilter.decode(state.settings.chatFilter.first())
            state.chatSort = ChatSort.decode(state.settings.chatSort.first())
            state.historyTab = HistoryTab.decode(state.settings.historyTab.first())
            state.historySort = ChatSort.decode(state.settings.historySort.first())
        }
        launch {
            snapshotFlow { state.historySort }.drop(1).collect { runCatching { state.settings.setHistorySort(it.name) } }
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
            .mapNotNull { (it as? UiState.Content)?.value?.withStarred?.firstOrNull { chat -> chat.id == target } }
            .firstOrNull() ?: return@LaunchedEffect
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
    val setupPending by state.settings.setupPending.collectAsState(initial = false)
    // The mandatory chat choice (ChatsGate), after the setup: while it is up (or briefly waits for
    // the chat list) it covers the window, and no notice, tour or support prompt shows with it.
    val setupReplay by state.settings.setupReplay.collectAsState(initial = false)
    val gateMove = com.tmplayer.ui.browse.rememberChatsGate(chats, setupPending)
    // While the setup is armed it is the one question (SetupFlow), shown through the same gate
    // bookkeeping: the question is a shown gate, every wait a wait.
    val setupFlow = com.tmplayer.ui.browse.rememberSetupFlow(chats, setupPending, setupReplay)
    val chatsGate = if (setupPending) com.tmplayer.data.SetupFlow.toMove(setupFlow) else gateMove
    val gateUp = com.tmplayer.data.ChatsGate.up(chatsGate)
    // The Choose chats button and its key stand down while the setup or the gate (the chooser
    // itself) is up.
    androidx.compose.runtime.DisposableEffect(setupPending, gateUp) {
        state.chooseChatsAllowed = !setupPending && !gateUp
        onDispose { state.chooseChatsAllowed = false }
    }
    val chatsUi by chats.state.collectAsState()
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
    // A kept video Telegram says is gone for good leaves Continue watching, Watched and Watch
    // later, unless its download still plays, and the window says so (HX21), as on the phone.
    LaunchedEffect(Unit) {
        com.tmplayer.data.GoneVideos.follow(
            settings = state.settings,
            watched = state.watched,
            keptOnDevice = { chatId, messageId -> com.tmplayer.desktop.DownloadIndex.fileFor(state.settings, chatId, messageId) != null },
        ) { forgotten -> toast(com.tmplayer.data.GoneVideos.message(forgotten)) }
    }
    LaunchedEffect(Unit) { state.notices.collect { toast(it) } }
    // "3 downloads were paused" with Resume, once after a restore brought a queue back paused
    // (WP-46). Resume goes through the same start as the Downloads page's Resume all, so space and
    // metered rules apply as there.
    val actionToast = com.tmplayer.ui.components.rememberActionToast()
    val pausedRunner = state.downloads as? com.tmplayer.desktop.DesktopDownloadRunner
    if (pausedRunner != null) {
        LaunchedEffect(pausedRunner) {
            pausedRunner.pausedAtLaunch.collect { count ->
                if (count <= 0) return@collect
                actionToast(s.downloadsPausedAtLaunch(count = count), s.downloadsResume) {
                    com.tmplayer.data.OfflineDownloads.resumeAll(pausedRunner)
                }
                pausedRunner.noticeShown()
            }
        }
    }
    // The chosen storage location could not be used at launch, so this run is on the default
    // folders (#35): said once per run, the first time the window is up.
    LaunchedEffect(Unit) {
        com.tmplayer.desktop.DesktopPaths.takeUnusableRootNotice()?.let { toast(s.storageUnreachableAtStart(it.path)) }
    }

    // The offline card, and the refresh once both the network and Telegram are back.
    val browseScope = rememberCoroutineScope()
    val telegramConnected by Td.connected.collectAsState()
    val offline = com.tmplayer.ui.browse.LocalOfflineHome.current.offline
    // Back online is not said: the card going is the news, and the lists refresh in place.
    val connection = rememberConnectionNotice(offline, telegramConnected) {
        chats.load()
        browseScope.launch { runCatching { state.extras.updates?.checkIfDue() } }
    }

    // The corner's line for catching up, a wait Telegram asked for and a sync that needs the viewer (WP-100): a
    // connection state above wins, and a locked profile sees none.
    val syncStatus by state.settings.resumeSyncStatus.collectAsState(initial = null)
    val hintsSeen by state.settings.hintsSeen.collectAsState(initial = emptySet())
    val statusDay = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) }
    // Filed as Failed once three runs in a row failed (WP-97, WP-100): that line has Try again and Send logs.
    val syncHintId = com.tmplayer.data.GlobalStatusRules.hintKind(syncStatus)?.let { com.tmplayer.data.GlobalStatusRules.syncHintId(it, statusDay) }
    val globalLine = com.tmplayer.ui.components.rememberGlobalLine(
        active = connection == ConnectionNotice.Hidden && !com.tmplayer.data.ProfileUnlock.hiddenNow(),
        syncKind = syncStatus?.kind,
        syncDismissed = syncHintId != null && syncHintId in hintsSeen,
        syncStopped = com.tmplayer.data.SyncStreak.stopped(syncStatus),
    )
    val syncToast = com.tmplayer.ui.components.rememberToast()

    BackHandler(enabled = state.openChat != null) { state.closeChat() }
    // Back from any page lands on Home, the first destination, as on the phone and the TV; in the
    // big-screen mode by way of the page's place on the bar (ShellState.backFromPage).
    BackHandler(enabled = state.openChat == null && state.destination != Destination.Home) {
        state.backFromPage()
    }
    // The end of a video puts the remote back on the item it was started from, unless a detail
    // pane came back with it, which takes the keys itself.
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(state.playerUp) {
        val playing = state.playerUp
        if (wasPlaying && !playing && BigScreen.on && state.detail == null) {
            androidx.compose.runtime.withFrameNanos { }
            state.focusPage()
        }
        wasPlaying = playing
    }

    // A chat opened from a page takes that page's place, so the page is kept here while the chat
    // is open: back from the chat finds its search, its tab and its scroll as they were. Going
    // to another page from the floating bar forgets them, as before.
    val pages = rememberSaveableStateHolder()
    LaunchedEffect(state.destination) {
        Destination.entries.filter { it != state.destination && it != state.pageDestination }.forEach { pages.removeState(it.name) }
    }

    val account by chats.accountHeader.collectAsState()
    val shownPage: @Composable (Destination) -> Unit = { destination ->
        when (destination) {
            Destination.Home -> HomePage(state, chats)
            Destination.Chats -> ChatsPage(state, chats, favouritesOnly = false)
            Destination.Favourites -> ChatsPage(state, chats, favouritesOnly = true)
            Destination.History -> HistoryPage(state)
            Destination.Downloads -> DownloadsPage(state)
            Destination.Settings -> LockedSettings(state) {
                SettingsPage(
                    state,
                    BuildInfo.VERSION,
                    chatList = (chatsUi as? UiState.Content)?.value?.chats.orEmpty(),
                    account = account,
                    initialCategory = state.settingsAsk,
                    onInitialTaken = { state.settingsAsk = null },
                )
            }
        }
    }
    // With the picture under the window (LocalVideoBehindPage), the page is kept but not drawn
    // while a video plays, so the picture shows through around the player's controls.
    val seeThrough = LocalVideoBehindPage.current && state.playerUp
    // Whether Compose's focus is in this page or the player over it, rather than in a dialog or a
    // popup over the window: a text field here is behind the player, so its keys are the player's.
    var pageFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        com.tmplayer.desktop.player.PlayerKeyRoute.pageHasFocus = { pageFocused }
        onDispose { com.tmplayer.desktop.player.PlayerKeyRoute.pageHasFocus = { false } }
    }
    Box(Modifier.fillMaxSize().onFocusChanged { pageFocused = it.hasFocus }) {
        Box(Modifier.fillMaxSize().then(if (seeThrough) Modifier.drawWithContent { } else Modifier)) {
            // The detail sheet floats over the page (DetailPaneHost), so the page keeps its size
            // and its place under it. A chat brings its own bar, which takes the floating one's place.
            NavFrame(state, update, account, Modifier.fillMaxSize(), barShown = state.openChat == null) {
                val open = state.openChat
                when {
                    // One page per chat (WP-94): the scroll, the shelf and the focus of the chat left never show under the next one's header.
                    open != null -> androidx.compose.runtime.key(open.id) { MediaGridPage(state, open) }
                    else -> pages.SaveableStateProvider(state.pageDestination.name) {
                        shownPage(state.pageDestination)
                    }
                }
            }
        }
        // Settings and Downloads: a panel over the page above, not a page stretched across the window.
        Box(Modifier.fillMaxSize().then(if (seeThrough) Modifier.drawWithContent { } else Modifier)) {
            // The panel's page, remembered: on the frame it closes, destination is already the page
            // underneath, and composing that here too used the page's saved-state key twice.
            var panelPage by remember { mutableStateOf(state.destination) }
            if (state.destination.isModal) panelPage = state.destination
            ModalPageHost(state) {
                pages.SaveableStateProvider("modal-" + panelPage.name) { shownPage(panelPage) }
            }
        }

        // The Add chats picker (HX27), a full page over Settings and the rest, under the search.
        // Not while the mandatory chat choice is up, which is a picker of its own over everything.
        if (!gateUp) AddChatsPage(state, chats)

        // The search across every chat (Ctrl+K), over the page and the floating bar alike.
        val searchModel = rememberViewModel(Unit) {
            com.tmplayer.ui.browse.GlobalSearchViewModel(onSearched = { state.settings.addRecentSearch(it) })
        }
        // Copies named for the viewer's preferred audio language lead a search result (EG-F5).
        LaunchedEffect(state.settings, searchModel) {
            state.settings.trackPreferences.collect { searchModel.audioLanguage = com.tmplayer.player.TrackLanguages.normalise(it.audioLanguage) }
        }
        // What the device already holds is searched as well as what Telegram answers.
        // Followed in an effect rather than read here: a video playing saves its place every ten
        // seconds, and reading the list in the shell recomposed the whole window each time.
        LaunchedEffect(state.settings, searchModel) {
            kotlinx.coroutines.flow.combine(state.settings.continueWatching, state.settings.downloadHistory) { resumed, downloaded ->
                resumed + downloaded
            }.collect { known -> searchModel.alsoKnown = known.map { it.toMediaItem() } }
        }
        LaunchedEffect(telegramConnected) { if (telegramConnected) searchModel.reconnected() }
        // Resume across devices, when turned on (ResumeSync): in and out once Telegram is reachable.
        // The desktop has no metered state to read, so Save mobile data never holds it back here.
        LaunchedEffect(Unit) {
            com.tmplayer.data.ResumeSync.install(state.watched, connectivity = null, television = false, appVersion = com.tmplayer.desktop.BuildInfo.VERSION, bookmarks = state.bookmarks)
        }
        LaunchedEffect(telegramConnected) { if (telegramConnected) com.tmplayer.data.ResumeSync.syncSoon(state.settings) }
        var searchSeries by remember { mutableStateOf<com.tmplayer.data.Series?>(null) }
        searchSeries?.let { shown ->
            Box(Modifier.fillMaxSize().background(Tone.background)) {
                SeriesPage(state, shown, rememberSeriesWatch(state), onClose = { searchSeries = null })
            }
        }
        // Over the page and over a show opened from the search, whose episodes open it too.
        DetailPaneHost(state)
        if (state.shortcutsOpen && !state.playerUp) ShellShortcutsDialog(onClose = { state.shortcutsOpen = false })
        if (state.paletteOpen && !state.playerUp) {
            SearchPalette(
                state = state,
                search = searchModel,
                chats = (chatsUi as? UiState.Content)?.value?.chats.orEmpty(),
                onOpenSeries = { searchSeries = it },
            )
        }

        // Telegram links from the palette and pinned posts (CP79), and videos off the grids (CP81).
        DesktopLinkHost(state)
        UpdatePopupHost(state)
        LowStorageQuestionHost(state)
        // The language card and What's new wait for the setup, as on the phone.
        // Over the floating bar or a chat's bar: at the window's smallest width the card reaches
        // in over the bar's right end, and would hide the picture and its menu.
        if (!setupPending && !gateUp) {
            val foot = (if (BigScreen.on) 24.dp else maxOf(NavBarClearance, ChatBarClearance))
            ShellNotices(state, Modifier.align(Alignment.BottomEnd).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = foot))
        }
        if (setupPending) {
            SetupOverlay(state, chats, setupFlow, setupReplay)
        } else if (gateUp) {
            ChatsGateOverlay(state, chats, waiting = chatsGate == com.tmplayer.data.ChatsGate.Move.Wait)
        }
        // The quick tour on its own (CP69): taken from the offer, or opened from Settings.
        val tour by state.settings.tourState.collectAsState(initial = com.tmplayer.data.SettingsStore.TourState.Done)
        if (!setupPending && !gateUp && tour == com.tmplayer.data.SettingsStore.TourState.Pending) {
            val tourScope = rememberCoroutineScope()
            CoverWindowControls()
            Surface(Modifier.fillMaxSize().windowDrag(), color = Tone.background) {
                com.tmplayer.ui.onboarding.TourOnly(
                    onDone = { tourScope.launch { runCatching { state.settings.markTourDone() } } },
                    onSkip = { tourScope.launch { runCatching { state.settings.markTourSkipped() } } },
                )
            }
        }
        // The first run's support step (the desktop has no notification step: the system handles
        // those), once after the chats question, with Skip, before Home. Never armed for an update.
        val onboarding by state.settings.onboardingSteps.collectAsState(initial = com.tmplayer.data.OnboardingSteps.State())
        val supportStep = !setupPending && !gateUp && tour != com.tmplayer.data.SettingsStore.TourState.Pending &&
            com.tmplayer.data.OnboardingSteps.next(
                onboarding,
                com.tmplayer.data.OnboardingSteps.Inputs(supportOffered = SupportReminder.enabled),
            ) == com.tmplayer.data.OnboardingSteps.Step.Support
        if (supportStep) {
            val stepScope = rememberCoroutineScope()
            val mark: (com.tmplayer.data.OnboardingSteps.Outcome) -> Unit = { outcome ->
                stepScope.launch {
                    runCatching { state.settings.markOnboardingStep(com.tmplayer.data.OnboardingSteps.Step.Support, outcome) }
                }
            }
            CoverWindowControls()
            Surface(Modifier.fillMaxSize().windowDrag(), color = Tone.background) {
                com.tmplayer.ui.onboarding.SupportStepScreen(
                    onOpenPage = {
                        com.tmplayer.desktop.os.OpenExternal.browse(com.tmplayer.ui.about.About.supportUrl(com.tmplayer.ui.onboarding.SOURCE))
                        mark(com.tmplayer.data.OnboardingSteps.Outcome.Opened)
                    },
                    onSkip = { mark(com.tmplayer.data.OnboardingSteps.Outcome.Skipped) },
                )
            }
        }
        if (SupportReminder.enabled && !setupPending && !gateUp) {
            SupportAskHost(state)
        }

        ConnectionBanner(connection, Modifier.align(Alignment.TopCenter).padding(top = 12.dp), onOpenDownloads = { state.go(Destination.Downloads) })
        com.tmplayer.ui.components.GlobalStatusPill(
            line = globalLine,
            syncStatus = syncStatus,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            onRetry = { browseScope.launch { Td.retryConnecting() } },
            // The viewer's own run: the only thing that tries again once the sync has stopped.
            onTryAgainSync = {
                syncHintId?.let { id -> browseScope.launch { runCatching { state.settings.markHintSeen(id) } } }
                com.tmplayer.platform.Background.scope.launch { runCatching { com.tmplayer.data.ResumeSync.sync(state.settings, manual = true) } }
            },
            onSendLogs = {
                browseScope.launch {
                    val file = SendLogs.save()
                    if (file == null) {
                        syncToast(com.tmplayer.i18n.L.logsFailed)
                    } else {
                        syncToast(com.tmplayer.i18n.L.logsSavedTo(file.absolutePath))
                        com.tmplayer.desktop.os.OpenExternal.reveal(file)
                    }
                }
            },
            onOpenSettings = {
                syncHintId?.let { id -> browseScope.launch { runCatching { state.settings.markHintSeen(id) } } }
                state.go(Destination.Settings)
            },
            onDismiss = { syncHintId?.let { id -> browseScope.launch { runCatching { state.settings.markHintSeen(id) } } } },
        )

        // Over the page rather than instead of it, so the page keeps its scroll position and its
        // search, and closing the player is simply taking this away.
        val playing = state.nowPlaying
        if (playing != null) {
            Box(Modifier.fillMaxSize().then(if (seeThrough) Modifier else Modifier.background(Tone.background))) {
                BackHandler { state.closePlayer() }
                player(playing) { state.closePlayer() }
            }
        }
    }
}

private const val HOUSEKEEPING_DELAY_MS = 20_000L

/**
 * The setup after sign in (R3) over the whole window: one question, "Which chats have your
 * videos?", the chat picker in its required form (no Back, Esc does nothing, Done only with a chat
 * ticked), then Home. While the list is on its way it shows the spinner with "Loading your chats"
 * under it, or the offline card; nothing else is asked. Run again from Settings it is the
 * ordinary picker and closes itself.
 */
@Composable
private fun SetupOverlay(
    state: ShellState,
    chats: com.tmplayer.ui.browse.ChatListViewModel,
    flow: com.tmplayer.data.SetupFlow.Next,
    replay: Boolean,
) {
    val scope = rememberCoroutineScope()
    CoverWindowControls()
    Surface(Modifier.fillMaxSize().windowDrag(), color = Tone.background) {
        com.tmplayer.ui.onboarding.SetupFlowEffects(state.settings, flow, replay)
        when (flow) {
            com.tmplayer.data.SetupFlow.Next.Choose -> {
                val model = rememberViewModel(Unit) { com.tmplayer.ui.browse.newAddChatsViewModel(state.settings) }
                com.tmplayer.ui.browse.AddChatsFed(
                    settings = state.settings,
                    chatsViewModel = chats,
                    model = model,
                    onClose = { scope.launch { runCatching { state.settings.markSetupDone() } } },
                    required = com.tmplayer.data.SetupFlow.required(replay),
                    sendLogs = { SendLogsActions() },
                )
            }
            com.tmplayer.data.SetupFlow.Next.Offline -> com.tmplayer.ui.browse.SetupOffline()
            com.tmplayer.data.SetupFlow.Next.Wait -> com.tmplayer.ui.browse.ChatsGateWaiting()
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Tone.accent) }
        }
    }
}

/**
 * The mandatory chat choice (ChatsGate) over the whole window, built like [SetupOverlay]: the
 * picker in its required mode, with no Back (Esc does nothing here: there is nothing behind it),
 * and gone by itself once Done writes the choice. [waiting] is the short wait for the chat list.
 * Its own view model, made once for the window, so it never shares ticks with Settings' picker.
 */
@Composable
private fun ChatsGateOverlay(state: ShellState, chats: com.tmplayer.ui.browse.ChatListViewModel, waiting: Boolean) {
    CoverWindowControls()
    Surface(Modifier.fillMaxSize().windowDrag(), color = Tone.background) {
        if (waiting) {
            com.tmplayer.ui.browse.ChatsGateWaiting()
            return@Surface
        }
        val model = rememberViewModel(Unit) { com.tmplayer.ui.browse.newAddChatsViewModel(state.settings) }
        com.tmplayer.ui.browse.AddChatsFed(
            settings = state.settings,
            chatsViewModel = chats,
            model = model,
            onClose = {},
            required = true,
            sendLogs = { SendLogsActions() },
        )
    }
}
