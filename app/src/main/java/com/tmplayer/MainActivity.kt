package com.tmplayer

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import com.tmplayer.data.AuthState
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.FormFactor
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
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.auth.IntroScreen
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.ConnectionStatus
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.onboarding.OverviewScreen
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a first press of Back stays armed before it is forgotten. */
private const val EXIT_WINDOW_MS = 2_000L

/**
 * How long getting a video ready may take before the app says so.
 *
 * Long enough that the usual case, where everything asked for is already in TDLib's database and
 * the player is up almost at once, passes in silence. Short enough that a slow one is explained
 * before the viewer has decided their press was missed.
 */
private const val PREPARE_CHIP_AFTER_MS = 400L

/** Where the user is. Deliberately three screens deep and no more. */
private sealed interface Screen {
    data object Chats : Screen
    data class Media(val chat: ChatSummary) : Screen
    data object Settings : Screen
    data object Downloads : Screen
}

/**
 * Saves which screen is open across a rotation or a process death.
 *
 * A plain `remember` lost it: turning the phone inside a chat, or coming back after the system
 * reclaimed the app while a video was playing, dumped the viewer back at the chat list. [Media]
 * carries a whole [ChatSummary], which is not parcelable and holds a blurred preview nobody needs
 * restored, so only the fields the media screen actually reads are written down and the row is
 * rebuilt from them. The picture reappears the moment the chat list lands.
 */
private val ScreenSaver = listSaver<Screen, Any>(
    save = { screen ->
        when (screen) {
            is Screen.Chats -> listOf(SCREEN_CHATS)
            is Screen.Settings -> listOf(SCREEN_SETTINGS)
            is Screen.Downloads -> listOf(SCREEN_DOWNLOADS)
            is Screen.Media -> listOf(
                SCREEN_MEDIA,
                screen.chat.id,
                screen.chat.title,
                screen.chat.photoFileId,
                screen.chat.kind.name,
            )
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            SCREEN_MEDIA -> Screen.Media(
                ChatSummary(
                    id = saved[1] as Long,
                    title = saved[2] as String,
                    miniThumbnail = null,
                    photoFileId = saved[3] as Int,
                    kind = ChatKind.valueOf(saved[4] as String),
                ),
            )
            SCREEN_SETTINGS -> Screen.Settings
            SCREEN_DOWNLOADS -> Screen.Downloads
            else -> Screen.Chats
        }
    },
)

private const val SCREEN_CHATS = "chats"
private const val SCREEN_MEDIA = "media"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_DOWNLOADS = "downloads"

/** A video waiting on the viewer's answer about clearing space. */
private data class RoomPrompt(
    val item: MediaItem,
    val reclaimBytes: Long,
    val shortfallBytes: Long,
    val chatTitle: String,
    /**
     * Nothing is offered to be cleared: the video does not fit even with the shelf emptied, and
     * the way out is the Downloads screen rather than a button.
     */
    val outOfSpace: Boolean = false,
    /** How many older videos would be deleted to make room, when that is what is being asked. */
    val evicting: Int = 0,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Asked for rather than inherited. Under targetSdk 35 Android draws behind the bars on
        // API 35 and above whether or not anybody asked, but the stick and half the phones this
        // runs on are older than that, and there the window stopped at the status bar and the app
        // sat in a letterbox of system chrome. This makes the two the same everywhere, and every
        // screen already handles its own insets.
        enableEdgeToEdge()
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

    // The status and gesture bars draw their icons over the app's own background now that the app
    // has a light theme, and `enableEdgeToEdge` decided their colour once, from the system's
    // setting, at launch. Anybody who chose Light on a phone in dark mode got white icons on a
    // white bar, which is not a subtle failure: the clock and the battery simply vanish.
    val dark = LocalDarkTheme.current
    val activity = LocalActivity.current
    LaunchedEffect(dark, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

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

    // Given the settings store so the chat list can paint from the last sync's snapshot before
    // TDLib has finished opening its database.
    val chatsViewModel: ChatListViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ChatListViewModel(settings) as T
        },
    )
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

    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Chats)
    }
    // One slot for whatever the current login pane got wrong: only one of them is ever on screen.
    var signInError by rememberSaveable { mutableStateOf<String?>(null) }
    var roomPrompt by remember { mutableStateOf<RoomPrompt?>(null) }
    // Where leaving the Downloads screen goes back to.
    var downloadsCameFrom by remember { mutableStateOf<Screen>(Screen.Chats) }
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
    //
    // Saved as a string rather than as the value itself: a folder is not an enum constant, it is
    // whatever this account happens to have, so there is nothing for the default saver to write.
    var pickedTabKey by rememberSaveable { mutableStateOf("") }
    val pickedTab = remember(pickedTabKey) { BrowseSection.decode(pickedTabKey) }

    // The account's Telegram folders, which become destinations of their own. Collected here
    // rather than inside the chat list's own state because they arrive on TDLib's schedule, not
    // with the chat list, and the rail has to be able to draw before either has turned up.
    val folders by Td.folders.collectAsStateWithLifecycle()

    // Whether a video is currently being got ready to play. Not saveable: a process death in the
    // middle of it means nothing is being prepared any more, and restoring true would leave every
    // video on the screen refusing to open.
    var preparing by remember { mutableStateOf(false) }

    /**
     * The preparation itself, split out only so [play] can wrap the whole of it in one pending
     * state without every early return having to remember to clear it.
     */
    suspend fun playNow(item: MediaItem, confirmed: Boolean, chatTitle: String) {
        // Off the main thread for the whole of the decision, and this is the reason the pending
        // state above is not enough on its own. Everything between here and the plan is blocking
        // work: two stat() calls to see whether the file is on disk, a statvfs() for the free
        // space, a walk of every download record, and a TDLib round trip per record to measure it.
        // Launched from a composition, every one of those resumed onto the thread drawing the
        // grid, so the list froze for the whole of the gap between pressing OK and the player
        // opening: exactly the moment the app is meant to feel quickest. Only the decision is
        // moved. Everything that follows it touches Compose state or starts an activity and has
        // to be back on Main to do it.
        withContext(Dispatchers.Default) {
            val local = runCatching { Td.localFileAvailability(item.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            val canReachTelegram = telegramConnected || networkStatus != NetworkStatus.Offline
            if (!canReachTelegram && local != LocalFileAvailability.Complete) {
                toast(
                    if (local == LocalFileAvailability.Partial) {
                        "This video is only partly downloaded. Connect to finish it."
                    } else {
                        "Connect to the internet to play this video."
                    },
                )
                return@withContext
            }
            val alreadyCached = local == LocalFileAvailability.Complete
            val free = DiskSpace.read(context).freeBytes
            // What this same video already has on disk from an interrupted watch. Without it the
            // shelf sees a disk full of "old" media, gives some of it up, and the thing it deletes
            // is the half of the video the viewer came back to carry on from.
            val partial = if (local == LocalFileAvailability.Partial) {
                runCatching { Td.localDownloadedBytes(item.fileId) }.getOrDefault(0L)
            } else {
                0L
            }

            // Everything the shelf is holding, measured before a byte of the new video is asked
            // for, which is the whole point: the answer to "will this fit" is known while it can
            // still be acted on rather than two thirds of the way into a download.
            val keep = runCatching { settings.keepVideosNow() }
                .getOrDefault(if (FormFactor.isTv(context)) {
                    SettingsStore.TV_KEEP_VIDEOS
                } else {
                    SettingsStore.TOUCH_KEEP_VIDEOS
                })
            val held = runCatching {
                settings.downloadHistory.first().mapNotNull { record ->
                    val bytes = Td.localDownloadedBytes(record.fileId)
                    if (bytes <= 0) null else CacheShelf.Held(record.fileId, bytes, record.updatedAt)
                }
            }.getOrDefault(emptyList())

            val plan = CacheShelf.plan(
                targetFileId = item.fileId,
                targetSizeBytes = item.sizeBytes,
                targetPartialBytes = partial,
                alreadyCached = alreadyCached,
                held = held,
                freeBytes = free,
                keepCount = keep,
            )
            val ask = runCatching { settings.askBeforeClearing.first() }.getOrDefault(false)

            // Back on Main from here down. Everything below writes Compose state or starts an
            // activity, and neither is a thing to do from a background thread.
            withContext(Dispatchers.Main) {
                fun start() {
                scope.launch { settings.noteDownload(item, chatTitle) }
                context.startActivity(PlayerActivity.intent(context, item, chatTitle))
            }

            /** Deletes exactly what the plan named, and forgets the rows that went with it. */
            suspend fun evict(fileIds: List<Int>) {
                val doomed = fileIds.toSet()
                val records = runCatching { settings.downloadHistory.first() }
                    .getOrDefault(emptyList())
                    .filter { it.fileId in doomed }
                for (record in records) {
                    runCatching { Td.deleteFile(record.fileId) }
                    settings.forgetDownload(record.chatId, record.messageId)
                }
                // Anything named by the plan that no longer has a row of its own still has to go.
                for (fileId in doomed - records.map { it.fileId }.toSet()) {
                    runCatching { Td.deleteFile(fileId) }
                }
            }

            when (plan) {
                is CacheShelf.Plan.Proceed -> start()

                is CacheShelf.Plan.NotEnoughSpace -> {
                    roomPrompt = RoomPrompt(
                        item = item,
                        reclaimBytes = plan.reclaimBytes,
                        shortfallBytes = plan.shortfallBytes,
                        chatTitle = chatTitle,
                        outOfSpace = true,
                    )
                }

                is CacheShelf.Plan.Evict -> {
                    // Scraps are not worth a dialog, and neither is a viewer who has said they do
                    // not want to be asked. Everything else gets the question first.
                    val worthAsking = plan.reclaimBytes >= CacheShelf.WORTH_ASKING_BYTES
                    if (ask && worthAsking && !confirmed) {
                        roomPrompt = RoomPrompt(
                            item = item,
                            reclaimBytes = plan.reclaimBytes,
                            shortfallBytes = 0,
                            chatTitle = chatTitle,
                            evicting = plan.fileIds.size,
                        )
                    } else {
                        evict(plan.fileIds)
                        start()
                    }
                }
                }
            }
        }
    }

    /**
     * Starts playback, first clearing the previous video when there is no room for both.
     * [confirmed] is true once the viewer has answered the prompt.
     */
    fun play(item: MediaItem, confirmed: Boolean = false, chatTitle: String = "") {
        // A press on a video is not the instant thing it looks like. Before the player can open,
        // this asks TDLib whether the file is on disk, measures the disk, and reads the download
        // history back through TDLib one record at a time. On a stick that is long enough for the
        // viewer to conclude the press missed, press again, and get two of whatever happens next.
        if (preparing && !confirmed) return
        preparing = true
        scope.launch {
            // Only said out loud when it is actually slow. On the common path the player is up
            // before this fires, and a chip that flashes on every press is worse than no chip.
            val chip = launch {
                delay(PREPARE_CHIP_AFTER_MS)
                toast("Starting ${item.title}…")
            }
            try {
                playNow(item, confirmed, chatTitle)
            } finally {
                chip.cancel()
                preparing = false
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
        scope.launch {
            settings.rememberChatOpened(record.chatId)
            // The stored row carries a file id from whichever TDLib instance wrote it, which is
            // not a number this one can necessarily use. Asking Telegram for the message again
            // returns a current id and tells TDLib where the file came from, without which it
            // will not download it. The stored row is the fallback, and it is enough whenever the
            // video is already on the device, which is the case that never broke.
            val fresh = Td.refreshMedia(record.chatId, record.messageId)
            play(fresh ?: record.toMediaItem(), chatTitle = record.chatTitle)
        }
    }

    Box(Modifier.fillMaxSize().background(Tone.background)) {
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
                    onResendCode = {
                        signInError = null
                        scope.launch { signInError = Td.resendCode() }
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

        // A chat restored from the saved state carries only what could be written down, so its
        // blurred preview is missing until the real row turns up. Swapping it back in is what
        // makes a rotation inside a chat look like nothing happened at all.
        LaunchedEffect(chats, screen) {
            val open = screen as? Screen.Media ?: return@LaunchedEffect
            if (open.chat.miniThumbnail != null) return@LaunchedEffect
            chats.firstOrNull { it.id == open.chat.id }
                ?.takeIf { it.miniThumbnail != null }
                ?.let { screen = Screen.Media(it) }
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
                        // Telegram answers a request made during a flood wait by extending it, so
                        // the button holds off and says how long rather than digging deeper.
                        val waiting = chatsViewModel.refreshUnlessRateLimited()
                        when {
                            waiting > 0 -> toast(
                                "Telegram has asked us to slow down. Try again in $waiting seconds.",
                            )
                            networkStatus == NetworkStatus.Offline && !telegramConnected ->
                                toast("You're offline. Showing saved chats.")
                        }
                    },
                    onOpenChat = { openChat(it) },
                    onResumeMedia = { resumeMedia(it) },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenDownloads = {
                        downloadsCameFrom = Screen.Chats
                        screen = Screen.Downloads
                    },
                    updateVersion = (updateState as? UpdateState.Available)?.release?.version,
                    onUpdate = { showUpdate = true },
                    // Each of these three changes the chat in Telegram, so each says out loud
                    // what it did: the row itself has already moved by the time the menu closes,
                    // and a row moving on its own is not an account of anything.
                    onTogglePinned = { chat ->
                        chatsViewModel.setPinned(chat, !chat.isPinned) { toast(it) }
                        toast(
                            if (chat.isPinned) {
                                "${chat.title} unpinned"
                            } else {
                                "${chat.title} pinned to the top"
                            },
                        )
                    },
                    onToggleArchived = { chat ->
                        chatsViewModel.setArchived(chat, !chat.isArchived) { toast(it) }
                        toast(
                            if (chat.isArchived) {
                                "${chat.title} moved out of the archive"
                            } else {
                                "${chat.title} archived"
                            },
                        )
                    },
                    onMarkRead = { chat ->
                        chatsViewModel.markRead(chat) { toast(it) }
                        toast("${chat.title} marked as read")
                    },
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
                    onPickTab = { pickedTabKey = BrowseSection.encode(it) },
                    folders = folders,
                    onToggleLayout = { scope.launch { settings.setChatLayout(chatLayout.toggled()) } },
                    layout = chatLayout,
                )
            }

            is Screen.Media -> {
                val leaveChat = {
                    // Backing out of a chat is the viewer asking for the chat list. Honour that
                    // for the rest of the session rather than jumping them back in.
                    autoOpened = true
                    screen = Screen.Chats
                }
                BackHandler(onBack = leaveChat)
                MediaGridScreen(
                    onBack = leaveChat,
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

            is Screen.Downloads -> {
                // Back to whatever raised it. Reached from the drawer that is the chat list, and
                // reached from "Not enough space" it is the chat the video was in, which is where
                // somebody who has just freed space wants to be.
                val leaveDownloads = { screen = downloadsCameFrom }
                BackHandler(onBack = leaveDownloads)
                DownloadsScreen(
                    onPlay = { record -> resumeMedia(record) },
                    onBack = leaveDownloads,
                )
            }

            is Screen.Settings -> {
                val leaveSettings = {
                    // Only if it has had time to go stale. Settings cannot change the chat list,
                    // so a full re-sync on the way out was work nothing had asked for.
                    chatsViewModel.refreshIfStale()
                    screen = Screen.Chats
                }
                BackHandler(onBack = leaveSettings)
                SettingsScreen(
                    chats = chats,
                    onLoggedOut = { screen = Screen.Chats },
                    onBack = leaveSettings,
                )
            }
        }

        roomPrompt?.let { pending ->
            if (pending.outOfSpace) {
                // A phone deletes nothing on its own, so this is not a question with a "do it"
                // answer: it says how much short the phone is and offers the screen where the
                // viewer can decide what goes.
                TvConfirm(
                    title = "Not enough space",
                    message = "“${pending.item.title}” is " +
                        "${StreamStats.formatBytes(pending.item.sizeBytes)}, and this phone is " +
                        "${StreamStats.formatBytes(pending.shortfallBytes)} short. " +
                        if (pending.reclaimBytes > 0) {
                            "TMPlayer is holding " +
                                "${StreamStats.formatBytes(pending.reclaimBytes)} in downloads."
                        } else {
                            "Freeing space on the phone will let it play."
                        },
                    detail = "Nothing is deleted from Telegram, only this device's copy.",
                    confirmLabel = "Manage downloads",
                    onConfirm = {
                        roomPrompt = null
                        downloadsCameFrom = screen
                        screen = Screen.Downloads
                    },
                    onDismiss = { roomPrompt = null },
                )
                return@let
            }

            // The only question left: older videos have to go, and this says how many and how
            // much comes back before anything is deleted.
            val count = pending.evicting.coerceAtLeast(1)
            TvConfirm(
                title = "Make room for this video?",
                message = if (count == 1) {
                    "Playing this deletes the video you watched longest ago and frees " +
                        "${StreamStats.formatBytes(pending.reclaimBytes)}."
                } else {
                    "Playing this deletes the $count videos you watched longest ago and frees " +
                        "${StreamStats.formatBytes(pending.reclaimBytes)}."
                },
                detail = "Nothing is deleted from Telegram, only this device's copy.",
                confirmLabel = "Make room and play",
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
