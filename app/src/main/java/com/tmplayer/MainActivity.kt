package com.tmplayer

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import com.tmplayer.data.AuthState
import com.tmplayer.data.CachePolicy
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.MediaItem
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.data.UpdateState
import com.tmplayer.data.Updates
import com.tmplayer.player.PlayerActivity
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.auth.IntroScreen
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.ConnectionStatus
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.onboarding.OverviewScreen
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long a first press of Back stays armed before it is forgotten. */
private const val EXIT_WINDOW_MS = 2_000L

/** Where the user is. Deliberately three screens deep and no more. */
private sealed interface Screen {
    data object Chats : Screen
    data class Media(val chat: ChatSummary) : Screen
    data object Settings : Screen
}

/** A video waiting on the viewer's answer about clearing space. */
private data class RoomPrompt(
    val item: MediaItem,
    val reclaimBytes: Long,
    val shortfallBytes: Long,
    val chatTitle: String,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Td.start(this)
        setContent {
            TMPlayerTheme { Root() }
        }
    }
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
private fun Root() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Td.auth.collectAsStateWithLifecycle()
    val networkStatus by NetworkMonitor.status.collectAsStateWithLifecycle()
    val telegramConnected by Td.connected.collectAsStateWithLifecycle()
    val settings = remember { SettingsStore(context) }

    val introSeen by settings.introSeen.collectAsStateWithLifecycle(initialValue = true)
    val overviewSeen by settings.overviewSeen.collectAsStateWithLifecycle(initialValue = true)
    val favorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val watchProgress by settings.watchProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    val continueWatching by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastChatId by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val minSize by settings.minSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MAX)
    val chatLayout by settings.chatLayout.collectAsStateWithLifecycle(initialValue = CardLayout.List)
    val mediaLayout by settings.mediaLayout.collectAsStateWithLifecycle(initialValue = CardLayout.Grid)

    val toast = rememberToast()

    // One quiet ask per launch. Nothing is said unless there is genuinely a newer release, and
    // the rail is where it turns up.
    val updateState by Updates.state.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(auth) {
        if (auth is AuthState.Ready) Updates.check(quiet = true)
    }

    // Half-watched entries that can no longer be turned into a card are swept once per launch.
    // Left alone they are invisible: the tab skips them, so nothing the viewer can press will
    // ever clear them. The message is only worth showing when something actually went.
    LaunchedEffect(Unit) {
        val removed = runCatching { settings.pruneBrokenHistory() }.getOrDefault(0)
        if (removed > 0) {
            toast(
                if (removed == 1) {
                    "Removed a video TMPlayer can no longer open from Continue watching"
                } else {
                    "Removed $removed videos TMPlayer can no longer open from Continue watching"
                },
            )
        }
    }

    val chatsViewModel: ChatListViewModel = viewModel()
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    val chats = (chatsState as? UiState.Content)?.value?.chats.orEmpty()

    LaunchedEffect(auth) {
        if (auth is AuthState.Ready) chatsViewModel.load() else chatsViewModel.reset()
    }

    var connectionNotice by remember { mutableStateOf(ConnectionNotice.Hidden) }
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(networkStatus, telegramConnected, auth) {
        val effectivelyOffline = networkStatus == NetworkStatus.Offline && !telegramConnected
        when {
            effectivelyOffline -> {
                delay(OFFLINE_SETTLE_MS)
                wasOffline = true
                connectionNotice = ConnectionNotice.Offline
            }
            wasOffline && auth is AuthState.Ready && !telegramConnected -> {
                connectionNotice = ConnectionNotice.Reconnecting
            }
            wasOffline -> {
                connectionNotice = ConnectionNotice.Hidden
                wasOffline = false
                if (auth is AuthState.Ready) {
                    chatsViewModel.load()
                    Updates.check(quiet = true)
                    toast("Back online. Library updated.")
                }
            }
            else -> connectionNotice = ConnectionNotice.Hidden
        }
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Chats) }
    // One slot for whatever the current login pane got wrong: only one of them is ever on screen.
    var signInError by remember { mutableStateOf<String?>(null) }
    var roomPrompt by remember { mutableStateOf<RoomPrompt?>(null) }
    // Saveable, not just remembered. Playing a video puts a second activity in front of this one,
    // and a 1 GB stick will happily kill what is behind it, so this composable is routinely
    // rebuilt on the way back. Remembered state would come back false, the jump would re-arm, and
    // Back out of the chat would drop the viewer straight into it again: the navigation rail and
    // Settings become unreachable, including the switch that turns this behaviour off.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    // Whether it is settled yet whether this launch jumps into a chat. Until it is, the chat list
    // must not be drawn: it would appear fully for a moment and then be replaced, which reads as
    // a glitch rather than as opening the chat the viewer asked to come back to.
    var autoOpenDecided by rememberSaveable { mutableStateOf(false) }
    // Whether a first press of Back has already been made at the top level.
    var exitArmed by remember { mutableStateOf(false) }
    // Hoisted out of BrowseScreen: opening a chat replaces that screen entirely, so a tab held
    // down there would be forgotten every time the viewer backed out of a video.
    var pickedTab by rememberSaveable { mutableStateOf<BrowseTab?>(null) }

    /**
     * Starts playback, first clearing the previous video when there is no room for both.
     * [confirmed] is true once the viewer has answered the prompt.
     */
    fun play(item: MediaItem, confirmed: Boolean = false, chatTitle: String = "") {
        scope.launch {
            val local = runCatching { Td.localFileAvailability(item.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            val canReachTelegram = telegramConnected || networkStatus != NetworkStatus.Offline
            if (!canReachTelegram && local != LocalFileAvailability.Complete) {
                toast(
                    if (local == LocalFileAvailability.Partial) {
                        "This video is only partly on this TV. Connect to finish downloading it."
                    } else {
                        "Connect to the internet to play this video."
                    },
                )
                return@launch
            }
            val alreadyCached = local == LocalFileAvailability.Complete
            val cacheBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
            val free = DiskSpace.read(context).freeBytes
            val decision = CachePolicy.decide(item.sizeBytes, alreadyCached, cacheBytes, free)
            val ask = runCatching { settings.askBeforeClearing.first() }.getOrDefault(false)

            when (decision) {
                is CachePolicy.Decision.Proceed ->
                    context.startActivity(PlayerActivity.intent(context, item, chatTitle))

                is CachePolicy.Decision.FreeUp -> {
                    if (ask && !confirmed) {
                        roomPrompt = RoomPrompt(item, decision.reclaimBytes, 0, chatTitle)
                    } else {
                        runCatching { Td.clearMediaCache() }
                        context.startActivity(PlayerActivity.intent(context, item, chatTitle))
                    }
                }

                is CachePolicy.Decision.TooLarge -> {
                    if (!confirmed) {
                        roomPrompt = RoomPrompt(item, decision.reclaimBytes, decision.shortfallBytes, chatTitle)
                    } else {
                        runCatching { Td.clearMediaCache() }
                        context.startActivity(PlayerActivity.intent(context, item, chatTitle))
                    }
                }
            }
        }
    }

    /** Opening a chat is what makes it the one to reopen on the next launch. */
    fun openChat(chat: ChatSummary) {
        screen = Screen.Media(chat)
        scope.launch { settings.rememberChatOpened(chat.id) }
    }

    /**
     * Resuming from the Continue watching row, which counts as a visit to the video's own chat.
     *
     * The viewer never passed through that chat's screen, but it is what they were last watching,
     * and that is the question the next launch is asking.
     */
    fun resumeMedia(record: ResumeRecord) {
        scope.launch { settings.rememberChatOpened(record.chatId) }
        play(record.toMediaItem(), chatTitle = record.chatTitle)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (auth !is AuthState.Ready) {
            // Signing out drops straight back to the login screen, so forget where we were.
            LaunchedEffect(Unit) {
                screen = Screen.Chats
                autoOpened = false
                autoOpenDecided = false
            }
            if (!introSeen) {
                IntroScreen(onContinue = { scope.launch { settings.markIntroSeen() } })
            } else if (!overviewSeen) {
                // Between the two: what the app is allowed to do, then how to work it, then the
                // QR code. A beginner has seen the whole shape of it before they sign in.
                OverviewScreen(onDone = { scope.launch { settings.markOverviewSeen() } })
            } else {
                LoginScreen(
                    state = auth,
                    submitError = signInError,
                    onSubmitPassword = { password ->
                        signInError = null
                        scope.launch { signInError = Td.submitPassword(password) }
                    },
                    onStartOver = {
                        signInError = null
                        scope.launch { Td.restartSignIn() }
                    },
                    onChooseMethod = { method ->
                        signInError = null
                        scope.launch { Td.chooseSignInMethod(method) }
                    },
                    onSubmitPhoneNumber = { number ->
                        signInError = null
                        scope.launch { signInError = Td.submitPhoneNumber(number) }
                    },
                    onSubmitCode = { code ->
                        signInError = null
                        scope.launch { signInError = Td.submitCode(code) }
                    },
                    onCancelPhoneEntry = {
                        signInError = null
                        scope.launch { Td.cancelPhoneEntry() }
                    },
                )
            }
            ConnectionStatus(
                notice = connectionNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            )
            return@Box
        }

        // Asked for again from Settings. It covers the screen it is describing, which is the only
        // place a walkthrough of this app makes sense.
        if (!overviewSeen) {
            OverviewScreen(onDone = { scope.launch { settings.markOverviewSeen() } })
            return@Box
        }

        // Straight back into whatever was being watched last. Not routed through openChat: this
        // is not the viewer picking a chat, and rewriting the record it just read would be noise.
        LaunchedEffect(chatsState, autoOpened) {
            if (autoOpened) {
                autoOpenDecided = true
                return@LaunchedEffect
            }
            // The chat list is still arriving, and which chat is in it is the whole question.
            if (chatsState is UiState.Loading) return@LaunchedEffect

            autoOpened = true
            val target = settings.autoOpenTarget()
            chats.firstOrNull { it.id == target }?.let { screen = Screen.Media(it) }
            autoOpenDecided = true
        }

        // At the top level Back would leave the app outright, and on a remote it sits right next
        // to the D-pad and is very easy to hit by accident. Two presses, the way every other app
        // on this television does it: a dialog for something this ordinary was too much ceremony,
        // and it had to be read and answered before the viewer could carry on.
        if (screen is Screen.Chats) {
            val activity = LocalActivity.current
            BackHandler {
                if (exitArmed) activity?.finish() else { exitArmed = true; toast("Press Back again to leave") }
            }
            // The second press has to follow the first, not arrive ten minutes later on a screen
            // the viewer has long since forgotten pressing Back on.
            LaunchedEffect(exitArmed) {
                if (exitArmed) {
                    delay(EXIT_WINDOW_MS)
                    exitArmed = false
                }
            }
        }

        when (val current = screen) {
            is Screen.Chats -> {
                BrowseScreen(
                    // Held on its own loading state until the jump has been decided, so the
                    // launch looks like one screen loading rather than two screens fighting.
                    state = if (autoOpenDecided) chatsState else UiState.Loading(),
                    favorites = favorites,
                    continueWatching = continueWatching,
                    onRetry = chatsViewModel::load,
                    onRefresh = {
                        chatsViewModel.load()
                        if (networkStatus == NetworkStatus.Offline && !telegramConnected) {
                            toast("You're offline. Showing saved chats.")
                        }
                    },
                    onOpenChat = { openChat(it) },
                    onResumeMedia = { resumeMedia(it) },
                    onOpenSettings = { screen = Screen.Settings },
                    updateVersion = (updateState as? UpdateState.Available)?.release?.version,
                    onUpdate = { showUpdate = true },
                    onToggleFavorite = { chat ->
                        scope.launch {
                            // The star lands on a row the menu was covering, and in the Favourites
                            // tab the row leaves the screen altogether, so the only account of what
                            // happened is this line.
                            val nowFavorite = settings.toggleFavorite(chat.id)
                            toast(
                                if (nowFavorite) {
                                    "${chat.title} added to Favourites"
                                } else {
                                    "${chat.title} removed from Favourites"
                                },
                            )
                        }
                    },
                    onRestartMedia = { record ->
                        scope.launch {
                            settings.clearResumePosition(record.chatId, record.messageId)
                            resumeMedia(record)
                        }
                    },
                    onForgetMedia = { record ->
                        scope.launch {
                            settings.clearResumePosition(record.chatId, record.messageId)
                            toast("${record.title} removed from Continue watching")
                        }
                    },
                    onClearFavorites = {
                        scope.launch {
                            val count = favorites.size
                            settings.clearFavorites()
                            toast(
                                if (count == 1) {
                                    "Favourite cleared"
                                } else {
                                    "$count favourites cleared"
                                },
                            )
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            settings.clearWatchHistory()
                            toast("Continue watching cleared")
                        }
                    },
                    launchChatId = lastChatId,
                    picked = pickedTab,
                    onPickTab = { pickedTab = it },
                    onToggleLayout = { scope.launch { settings.setChatLayout(chatLayout.toggled()) } },
                    layout = chatLayout,
                )
            }

            is Screen.Media -> {
                BackHandler {
                    // Videos posted while the list was open only appear after a fresh search.
                    chatsViewModel.load()
                    // Backing out of a chat is the viewer asking for the chat list. Honour that
                    // for the rest of the session rather than jumping them back in.
                    autoOpened = true
                    screen = Screen.Chats
                }
                MediaGridScreen(
                    chatId = current.chat.id,
                    chatTitle = current.chat.title,
                    chatPhotoFileId = current.chat.photoFileId,
                    chatMiniThumbnail = current.chat.miniThumbnail,
                    isFavorite = current.chat.id in favorites,
                    minSizeBytes = minSize,
                    maxSizeBytes = maxSize,
                    watchProgress = watchProgress,
                    onToggleFavorite = {
                        scope.launch {
                            val nowFavorite = settings.toggleFavorite(current.chat.id)
                            toast(
                                if (nowFavorite) {
                                    "${current.chat.title} added to Favourites"
                                } else {
                                    "${current.chat.title} removed from Favourites"
                                },
                            )
                        }
                    },
                    onPlay = { play(it, chatTitle = current.chat.title) },
                    onToggleLayout = { scope.launch { settings.setMediaLayout(mediaLayout.toggled()) } },
                    telegramConnected = telegramConnected,
                    offline = networkStatus == NetworkStatus.Offline && !telegramConnected,
                    onOfflineAction = toast,
                    connectionNotice = connectionNotice,
                    layout = mediaLayout,
                )
            }

            is Screen.Settings -> {
                BackHandler {
                    chatsViewModel.load()
                    screen = Screen.Chats
                }
                SettingsScreen(
                    chats = chats,
                    onLoggedOut = { screen = Screen.Chats },
                )
            }
        }

        roomPrompt?.let { pending ->
            val tooLarge = pending.shortfallBytes > 0
            TvConfirm(
                title = if (tooLarge) "This video may not fit" else "Make room for this video?",
                message = if (tooLarge) {
                    // State what it can free, not the shortfall.
                    val canFree = (pending.item.sizeBytes - pending.shortfallBytes).coerceAtLeast(0)
                    "“${pending.item.title}” is ${StreamStats.formatBytes(pending.item.sizeBytes)}. " +
                        "This TV can only free up ${StreamStats.formatBytes(canFree)}, so the video " +
                        "may stop partway through."
                } else {
                    "TMPlayer keeps one video on this TV at a time. Playing this deletes the last " +
                        "one and frees ${StreamStats.formatBytes(pending.reclaimBytes)}."
                },
                detail = "Nothing is deleted from Telegram, only this device's copy.",
                confirmLabel = if (tooLarge) "Play anyway" else "Make room and play",
                onConfirm = {
                    val item = pending.item
                    val chat = pending.chatTitle
                    roomPrompt = null
                    play(item, confirmed = true, chatTitle = chat)
                },
                onDismiss = { roomPrompt = null },
            )
        }

        if (showUpdate) {
            UpdateDialog(onDismiss = { showUpdate = false; Updates.dismiss() })
        }

        ConnectionStatus(
            notice = connectionNotice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }
}

private const val OFFLINE_SETTLE_MS = 750L
