package com.tmplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.AndroidTransferNotifier
import com.tmplayer.data.AuthState
import com.tmplayer.data.LegacyDownloads
import com.tmplayer.data.LocalDownloads
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.CoalescingTransferNotifier
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.RoomOnDisk
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.restore
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.data.start
import com.tmplayer.data.UpdateState
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.Updates
import com.tmplayer.data.release
import com.tmplayer.data.updateScheduler
import com.tmplayer.player.PlayerActivity
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.KeyPresses
import com.tmplayer.ui.browse.TvBrowseHint
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.HomeViewModel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.rememberHomeRows
import com.tmplayer.data.HomeRow
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.ConnectionStatus
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.onboarding.OnboardingScreen
import com.tmplayer.ui.onboarding.FirstSignIn
import com.tmplayer.ui.onboarding.FirstSignInCard
import com.tmplayer.ui.browse.DefaultGroups
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.update.LinkQrDialog
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.SupportCard
import com.tmplayer.ui.settings.LanguageDialog
import com.tmplayer.ui.settings.LanguageNoticeCard
import com.tmplayer.ui.settings.WhatsNewDialog
import com.tmplayer.ui.i18n.rememberLanguageNotice
import com.tmplayer.ui.i18n.rememberWhatsNew
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.AppLocales
import com.tmplayer.ui.settings.SupportDialog
import com.tmplayer.ui.settings.shareTmplayer
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
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
    data object About : Screen
}

/**
 * Saves which screen is open across a rotation or a process death.
 *
 * [Media] carries a whole [ChatSummary], which is not parcelable and holds a blurred preview nobody
 * needs restored, so only the fields the media screen actually reads are written down and the row
 * is rebuilt from them. The picture reappears the moment the chat list lands.
 */
private val ScreenSaver = listSaver<Screen, Any>(
    save = { screen ->
        when (screen) {
            is Screen.Chats -> listOf(SCREEN_CHATS)
            is Screen.Settings -> listOf(SCREEN_SETTINGS)
            is Screen.Downloads -> listOf(SCREEN_DOWNLOADS)
            is Screen.About -> listOf(SCREEN_ABOUT)
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
            SCREEN_ABOUT -> Screen.About
            else -> Screen.Chats
        }
    },
)

private const val SCREEN_CHATS = "chats"
private const val SCREEN_MEDIA = "media"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_DOWNLOADS = "downloads"
private const val SCREEN_ABOUT = "about"

/**
 * A video that will not fit, and what the device is holding that the viewer could do something
 * about.
 *
 * The only prompt on this path. Making room does not ask: the watch cache is one video nobody chose
 * to keep, so it goes without a word, and downloads that are not cache are never touched. This
 * covers the case where even emptying the cache is not enough, which has no "do it" answer:
 * [reclaimBytes] is what is sitting in downloads, and the way out is the screen where those are
 * deleted by hand.
 */
private data class RoomPrompt(
    val item: MediaItem,
    val reclaimBytes: Long,
    val shortfallBytes: Long,
    val chatTitle: String,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Asked for rather than inherited. API 35 and above draws behind the bars anyway, but the
        // stick and half the phones this runs on are older, so this makes the two the same
        // everywhere. Every screen already handles its own insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Td.start(this)
        // A debug or promo build can force the support card on, to check it by eye:
        // `adb shell am start -n com.tmplayer/.MainActivity --ez support_reminder true`.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SUPPORT_REMINDER, false) == true) {
            SupportReminder.forced = true
        }
        // `--ei support_rung 2` forces the second rung's card, and so on (1 to 3).
        if (BuildConfig.DEBUG) {
            intent?.getIntExtra(EXTRA_SUPPORT_RUNG, 0)?.takeIf { it > 0 }?.let { SupportReminder.forcedRung = it }
        }
        noteRequestedScreen(intent)
        setContent {
            TMPlayerTheme { Root() }
        }
        if (savedInstanceState == null) playFromHomeScreen(intent)
    }

    /**
     * An entry picked in the home screen's "Play next" row (see [WatchNextPublisher]) opens the
     * player straight away, over the app as it was. The intent's action is cleared once acted on,
     * so recreating the activity does not play it again.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun playFromHomeScreen(intent: Intent?) {
        val video = WatchNextPublisher.videoFrom(intent) ?: return
        intent?.action = Intent.ACTION_MAIN
        startActivity(PlayerActivity.intent(this, video.toMediaItem(), video.chatTitle))
    }

    /**
     * A language picked on Android's own page for this app (Android 13 and later) while it was in
     * the background becomes the in-app choice. See [AppLocales].
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val store = SettingsStore(this@MainActivity)
            runCatching { AppLocales.adopt(this@MainActivity, store.language.first())?.let { store.setLanguage(it) } }
        }
    }

    /**
     * The activity is `singleTask`, so a second launch arrives here rather than as a new instance.
     *
     * Pressing a download's notification while the app is already open is that case, and the new
     * intent is what says which screen it is asking for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteRequestedScreen(intent)
        playFromHomeScreen(intent)
    }

    private fun noteRequestedScreen(intent: Intent?) {
        val asked = intent?.getStringExtra(EXTRA_OPEN) ?: return
        requestedScreen.value = asked
    }

    companion object {
        /** Which screen a launch is asking for, when it is asking for one. */
        const val EXTRA_OPEN = "com.tmplayer.extra.OPEN"

        /** Debug and promo builds only: show the support card now. See [SupportReminder.forced]. */
        const val EXTRA_SUPPORT_REMINDER = "support_reminder"

        /** Debug and promo builds only: `--ei support_rung N` shows rung N's card (1 to 3). */
        const val EXTRA_SUPPORT_RUNG = "support_rung"

        const val OPEN_DOWNLOADS = SCREEN_DOWNLOADS

        /**
         * The ask, waiting to be read by the composition.
         *
         * A flow rather than the intent itself, because the composable that owns the current
         * screen cannot see the activity's intent. Must be cleared once acted on: left set, a
         * rotation would send the viewer back to Downloads every time they turned the phone.
         */
        internal val requestedScreen = MutableStateFlow<String?>(null)
    }
}

/**
 * Where the chat list and Home keep their view models: the process, not the activity.
 *
 * Both hold what Telegram took seconds to answer, and neither has anything to do with which
 * activity instance draws it. Owned by the activity they died with it whenever the system finished
 * the activity behind the player, so Back from a video opened on a skeleton and the first-visit
 * tip. A sign out still empties them, through the auth effect in [Root].
 */
private object ShellViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
private fun Root() {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Td.auth.collectAsStateWithLifecycle()
    val networkStatus by NetworkMonitor.status.collectAsStateWithLifecycle()
    val telegramConnected by Td.connected.collectAsStateWithLifecycle()
    val settings = remember { SettingsStore(context) }
    val watchedStore = remember { WatchedStore(context) }

    // The status and gesture bars draw their icons over the app's own background, and
    // `enableEdgeToEdge` decides their colour once at launch from the system setting. The app's
    // own theme can disagree with it, so the icon appearance is kept in step here.
    val dark = LocalDarkTheme.current
    val activity = LocalActivity.current
    LaunchedEffect(dark, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    val overviewSeen by settings.overviewSeen.collectAsStateWithLifecycle(initialValue = true)
    val favorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val watchProgress by settings.watchProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    val continueWatching by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchedVideos by watchedStore.watched.collectAsStateWithLifecycle(initialValue = emptyMap())
    val watchedHistory by watchedStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastChatId by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val minSize by settings.minSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MAX)
    val chatLayout by settings.chatLayout.collectAsStateWithLifecycle(initialValue = CardLayout.List)
    val homeWatch = remember(watchProgress, watchedVideos) {
        SeriesWatch(
            point = { watchProgress[SettingsStore.progressKey(it.chatId, it.messageId)] },
            finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedVideos },
        )
    }
    val mediaLayout by settings.mediaLayout.collectAsStateWithLifecycle(initialValue = CardLayout.Grid)

    val toast = rememberToast()

    // The update check, on every launch and before sign in too: ten seconds after the first
    // frame, then every six hours (see UpdateScheduler). Nothing is said unless there is genuinely
    // a newer release; the drawer and the rail are where it turns up.
    val updateState by Updates.state.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    val updates = remember { updateScheduler(context) }
    LaunchedEffect(Unit) {
        // Never in a build a store keeps up to date (Updates.enabled, the F-Droid build).
        if (!Updates.enabled) return@LaunchedEffect
        withFrameNanos { }
        updates.run()
    }
    // Whatever stage the update is at, the item stays and reopens the popup. A skipped version
    // that Settings re-offered stays out of the drawer and the rail.
    val offeredUpdate = updateState.release?.version
        ?.takeUnless { (updateState as? UpdateState.Available)?.skipped == true }
    // The popup follows the item, once per version, two seconds after it appears. Not over the
    // sign in screens and not over the player (another activity, which leaves this one stopped):
    // either way the wait starts again once the shell is in front.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inShell = auth is AuthState.Ready && overviewSeen && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(offeredUpdate, inShell) {
        val version = offeredUpdate ?: return@LaunchedEffect
        if (!inShell) return@LaunchedEffect
        delay(UpdateScheduler.POPUP_DELAY_MS)
        if (updates.shouldPopUp(version)) {
            updates.popupShown(version)
            showUpdate = true
        }
    }

    // The one-time "Now in Español" card, the language picker it offers, and "What's new" on the
    // first run of a version with highlights. All three wait for the shell: none of them is for
    // the tour or the sign in screens.
    val languageNotice = rememberLanguageNotice(settings)
    var pickingLanguage by remember { mutableStateOf(false) }
    val whatsNew = rememberWhatsNew(settings, BuildConfig.VERSION_NAME, hold = !inShell)
    var changelogQr by remember { mutableStateOf(false) }

    // The support ask: at most three for good, and only at a good moment (a video watched to the
    // end, a download finished) while this list or Downloads is up, never in the player. The
    // ladder and its counters are SupportReminder's. [supportRung] is the card on screen, 0 for none.
    var supportRung by remember { mutableStateOf(0) }
    var supportCounters by remember { mutableStateOf(SupportReminder.Counters()) }
    var supporting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { settings.noteSupportFirstSeen(System.currentTimeMillis()) } }
    // Finished videos are counted here, in the process, so a watch counts even while a screen is
    // being rebuilt behind the player.
    LaunchedEffect(Unit) {
        SupportReminder.finished.collect { done ->
            runCatching { settings.noteSupportCompleted(done.key, done.at) }
        }
    }

    // The downloads an earlier run of the app was in the middle of, back on the Downloads screen
    // as paused rows. Waits for TDLib, because restoring asks it how much of each file is actually
    // on disk, and a queue restored against a closed database would claim every video was at zero.
    LaunchedEffect(auth) {
        if (auth is AuthState.Ready) OfflineDownloads.restore(context)
    }

    // Downloads from before they had a folder, moved into it once, after sign in: it asks TDLib
    // where each file is. One notification for the lot while it runs, and a toast naming the
    // folder at the end. Partly downloaded ones stay where they are and say so on the Downloads
    // screen, with Resume.
    LaunchedEffect(auth) {
        if (auth !is AuthState.Ready) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                LegacyDownloads(
                    settings = settings,
                    downloadsDir = { AndroidPaths(context).downloadsDir },
                    notifier = CoalescingTransferNotifier(AndroidTransferNotifier(context)),
                ).migrateOnce()
            }.getOrNull()
        }
        LegacyDownloads.toast(result)?.let(toast)
    }

    // A download finishing, said in the window too. A television shows apps like this one no
    // notification shade, so without this the end of a download would pass unremarked there.
    LaunchedEffect(Unit) {
        AndroidTransferNotifier.completions.collect { title -> toast(s.mainDownloaded(title = title)) }
    }

    // Half-watched entries that can no longer be turned into a card are swept once per launch.
    // Left alone they are invisible: the tab skips them, so nothing the viewer can press will
    // ever clear them.
    LaunchedEffect(Unit) {
        val removed = runCatching { settings.pruneBrokenHistory() }.getOrDefault(0)
        if (removed > 0) {
            toast(
                s.mainPrunedHistory(count = removed),
            )
        }
    }

    // Given the settings store so the chat list can paint from the last sync's snapshot before
    // TDLib has finished opening its database. Held by the process rather than the activity, as is
    // Home below: the system destroys this activity behind the player whenever it is short of
    // memory (and always, with "Don't keep activities" on), and an activity-owned list came back
    // from the player as a first visit, skeleton, tip and all, then refetched every Home row.
    val chatsViewModel: ChatListViewModel = viewModel(
        viewModelStoreOwner = ShellViewModels,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ChatListViewModel(settings) as T
        },
    )
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    // Home's rows, fetched a row at a time as they come on screen, with the size limits a chat's
    // grid uses.
    val homeViewModel: HomeViewModel = viewModel(
        viewModelStoreOwner = ShellViewModels,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(sizeLimits = { settings.minSizeBytes.first() to settings.maxSizeBytes.first() }) as T
        },
    )
    // Home keeps what it fetched, badges included, so its Cached badges are read off the disk again
    // when the shell is back in front (the player, another activity, may have evicted the last
    // cached video to make room) and whenever the cache record changes, as Clear cache does.
    val cacheRecord by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = null)
    val shellInFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(shellInFront, cacheRecord) {
        if (shellInFront) homeViewModel.refreshLocalAvailability()
    }
    val accountHeader by chatsViewModel.accountHeader.collectAsStateWithLifecycle()
    val chats = (chatsState as? UiState.Content)?.value?.chats.orEmpty()

    // The one card after the first sign in (CP42): "Show everything" or "Only my folders". A sign
    // in screen arms it; it is asked once the chat list (and with it the folder list) is in, only
    // when the account has folders, and never again on this install. See FirstSignIn.
    LaunchedEffect(auth) {
        if (FirstSignIn.isSignInStep(auth)) runCatching { settings.armFirstSignInCard() }
    }
    val signInCardPending by settings.firstSignInCardPending.collectAsStateWithLifecycle(initialValue = false)
    var signInCard by remember { mutableStateOf(false) }
    // "Only my folders", from that card: the Settings switch's own prompt, favourites count and all.
    var hideGroupsPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(auth) {
        when (auth) {
            is AuthState.Ready -> chatsViewModel.load()
            // Connecting is not authorization lost: it is the first seconds of every cold start,
            // while TDLib opens its database. Resetting here erased the snapshot the first frame
            // had just painted, which put the skeletons back and made the whole launch wait on
            // TDLib, the exact thing the snapshot exists to avoid. Failed is left alone for the
            // same reason: a transient error over a drawn list is better read than a blank one.
            is AuthState.Connecting, is AuthState.Failed -> Unit
            else -> {
                chatsViewModel.reset()
                // Another account's starred chats must not stay on Home behind a sign out.
                homeViewModel.refresh()
            }
        }
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
                    homeViewModel.refresh()
                    updates.checkIfDue()
                    toast(L.mainBackOnline)
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
    // The Continue watching entry waiting on "Remove from Continue watching?".
    var forgetPrompt by remember { mutableStateOf<ResumeRecord?>(null) }
    // The machine the space message is about, since "this phone is 2 GB short" on a television
    // reads as the app talking about something else entirely.
    val device = remember { if (FormFactor.isTv(context)) "tv" else "phone" }
    // Where leaving the Downloads screen goes back to.
    var downloadsCameFrom by remember { mutableStateOf<Screen>(Screen.Chats) }
    // Whether the Downloads screen opens on its cached videos, which is how Settings' Cached
    // videos row reaches the list of them.
    var downloadsOnCached by remember { mutableStateOf(false) }
    // The Settings page open, kept here because Settings itself is gone while About or the cached
    // videos are showing, and coming back from either should land on the page that led there.
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // A launch that asked for a particular screen, which is how the download notification opens
    // the list it is about. Cleared as it is acted on, so it happens once per press.
    val requestedScreen by MainActivity.requestedScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requestedScreen) {
        if (requestedScreen != SCREEN_DOWNLOADS) return@LaunchedEffect
        // Back from the Downloads screen goes wherever the viewer already was, not to the chat
        // list: they were reading a chat, pressed a notification, and Back should return them to it.
        if (screen !is Screen.Downloads) downloadsCameFrom = screen
        downloadsOnCached = false
        screen = Screen.Downloads
        MainActivity.requestedScreen.value = null
    }
    val supportMoment by SupportReminder.moment.collectAsStateWithLifecycle()
    val supportScreen = screen is Screen.Chats || screen is Screen.Downloads
    var supportForcedShown by remember { mutableStateOf(false) }
    LaunchedEffect(supportMoment, supportScreen, inShell, showUpdate, languageNotice.language, supportRung) {
        if (!inShell || !supportScreen || showUpdate || languageNotice.language != null || supportRung != 0) {
            return@LaunchedEffect
        }
        val forced = SupportReminder.forcedRung
        if (forced > 0) {
            // A debug build asked for this rung's card: once, after the list has settled.
            if (supportForcedShown) return@LaunchedEffect
            delay(SUPPORT_CARD_DELAY_MS)
            supportForcedShown = true
            supportCounters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)
            supportRung = forced
            return@LaunchedEffect
        }
        if (supportMoment == 0L) return@LaunchedEffect
        delay(SUPPORT_CARD_DELAY_MS)
        if (!SupportReminder.takeMoment(System.currentTimeMillis())) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val counters = runCatching { settings.supportCountersNow() }.getOrNull() ?: return@LaunchedEffect
        val rung = runCatching { SupportReminder.claim(settings, now) }.getOrDefault(0)
        if (rung > 0) {
            supportCounters = counters
            supportRung = rung
        }
    }
    // Saveable, not just remembered. Playing a video puts a second activity in front of this one
    // and a 1 GB stick will kill what is behind it, so this composable is routinely rebuilt on the
    // way back. Remembered state would come back false, the jump would re-arm, and Back out of the
    // chat would drop the viewer straight into it again, leaving the rail and Settings unreachable.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    // Whether it is settled yet whether this launch jumps into a chat. Until it is, the chat list
    // must not be drawn: it would appear fully for a moment and then be replaced, which reads as
    // a glitch rather than as opening the chat the viewer asked to come back to.
    var autoOpenDecided by rememberSaveable { mutableStateOf(false) }
    // Whether a first press of Back has already been made at the top level.
    var exitArmed by remember { mutableStateOf(false) }
    // Hoisted out of BrowseScreen: opening a chat replaces that screen entirely, so a tab held
    // down there would be forgotten every time the viewer backed out of a chat.
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

    // Everything unfinished: coming down, waiting its turn, held or failed. That is the number
    // the drawer badges, because every one of them is a video the viewer is still owed. Held here
    // because the chat list is where the drawer lives, and a download is started from a different
    // screen entirely and outlives it.
    val activeDownloads by OfflineDownloads.active.collectAsStateWithLifecycle()

    /**
     * The preparation itself, split out only so [play] can wrap the whole of it in one pending
     * state without every early return having to remember to clear it.
     */
    suspend fun playNow(item: MediaItem, confirmed: Boolean, chatTitle: String) {
        // Off the main thread for the whole of the decision. Everything between here and the plan
        // is blocking work: two stat() calls to see whether the file is on disk, a statvfs() for
        // the free space, a walk of every download record, and a TDLib round trip per record to
        // measure it. Launched from a composition, each of those would resume onto the thread
        // drawing the grid. Only the decision is moved: everything after it touches Compose state
        // or starts an activity and has to be back on Main to do it.
        withContext(Dispatchers.Default) {
            // A download plays from its file in the Downloads folder, and nothing below applies
            // to it: it needs no connection, no room, and it is not the cache's to claim.
            if (LocalDownloads.fileFor(settings, item.chatId, item.messageId) != null) {
                withContext(Dispatchers.Main) {
                    context.startActivity(PlayerActivity.intent(context, item, chatTitle))
                }
                return@withContext
            }
            val local = runCatching { Td.localFileAvailability(item.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            val canReachTelegram = telegramConnected || networkStatus != NetworkStatus.Offline
            if (!canReachTelegram && local != LocalFileAvailability.Complete) {
                toast(
                    if (local == LocalFileAvailability.Partial) {
                        L.mainPartlyDownloaded
                    } else {
                        L.mainConnectToPlay
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

            // Will it fit, and what has to go first. Worked out by [RoomOnDisk], which the player
            // asks the same question of before it opens an episode reached from inside itself:
            // one decision in one place, so the answer cannot depend on which button was pressed.
            val decision = RoomOnDisk.decide(
                context = context,
                item = item,
                alreadyCached = alreadyCached,
                partialBytes = partial,
            )
            val plan = decision.plan

            // A video the viewer downloaded on purpose is not cache and must not become it: taking
            // it over as the cache slot would have the next play delete a video they chose to keep.
            val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }
                .getOrDefault(false)

            // Back on Main from here down. Everything below writes Compose state or starts an
            // activity, and neither is a thing to do from a background thread.
            withContext(Dispatchers.Main) {
                fun start() {
                // The player claims the cache for itself the moment it opens, which is what keeps
                // a series honest. This only has to leave the record alone for a kept download.
                if (!kept) scope.launch { settings.rememberCachedVideo(item, chatTitle) }
                context.startActivity(PlayerActivity.intent(context, item, chatTitle))
            }

            when (plan) {
                is CacheShelf.Plan.Proceed -> start()

                is CacheShelf.Plan.NotEnoughSpace -> {
                    roomPrompt = RoomPrompt(
                        item = item,
                        reclaimBytes = plan.reclaimBytes,
                        shortfallBytes = plan.shortfallBytes,
                        chatTitle = chatTitle,
                    )
                }

                // No question is asked. What goes is the single video the last press of Play left
                // behind, which nobody chose to keep; anything the viewer downloaded on purpose is
                // never a candidate.
                is CacheShelf.Plan.Evict -> {
                    decision.evict(context)
                    start()
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
        // A press on a video is not instant: before the player can open, this asks TDLib whether
        // the file is on disk, measures the disk, and reads the download history back one record
        // at a time. On a stick that is long enough for the viewer to conclude the press missed
        // and press again, so a second press while one is in flight is ignored.
        if (preparing && !confirmed) return
        preparing = true
        scope.launch {
            // Only said out loud when it is actually slow. On the common path the player is up
            // before this fires, and a chip that flashes on every press is worse than no chip.
            val chip = launch {
                delay(PREPARE_CHIP_AFTER_MS)
                toast(L.mainStarting(title = item.title))
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
     * Marks a video watched by hand, or takes the mark off. Marking also forgets the saved
     * position, which is what takes it out of Continue watching.
     */
    fun setWatched(item: MediaItem, chatTitle: String, watched: Boolean) {
        App.backgroundScope.launch {
            runCatching {
                if (watched) {
                    watchedStore.markWatched(
                        WatchedRecord.of(item, chatTitle, System.currentTimeMillis(), manual = true),
                    )
                    settings.clearResumePosition(item.chatId, item.messageId)
                } else {
                    watchedStore.markUnwatched(item.chatId, item.messageId)
                }
            }
        }
        toast(if (watched) L.mainMarkedWatched(title = item.title) else L.mainMarkedUnwatched(title = item.title))
    }

    /**
     * Plays a video from a list kept on this device (Continue watching, Previously watched), which
     * counts as a visit to its chat for the next launch.
     */
    fun openStored(stored: MediaItem, chatTitle: String) {
        scope.launch {
            settings.rememberChatOpened(stored.chatId)
            // In the Downloads folder: it plays from there, and asking Telegram first would only
            // be a wait, and offline a long one.
            if (LocalDownloads.fileFor(settings, stored.chatId, stored.messageId) != null) {
                play(stored, chatTitle = chatTitle)
                return@launch
            }
            // The stored row carries a file id from whichever TDLib instance wrote it, which is
            // not a number this one can necessarily use. Asking Telegram for the message again
            // returns a current id and tells TDLib where the file came from, without which it
            // will not download it. The stored row is the fallback, and it is enough whenever the
            // video is already on the device.
            val fresh = Td.refreshMedia(stored.chatId, stored.messageId)
            play(fresh ?: stored, chatTitle = chatTitle)
        }
    }

    /**
     * Resuming from the Continue watching row, which counts as a visit to the video's own chat.
     *
     * The viewer never passed through that chat's screen, but it is what they were last watching,
     * and that is the question the next launch is asking.
     */
    fun resumeMedia(record: ResumeRecord) {
        openStored(record.toMediaItem(), record.chatTitle)
    }

    // Any key on the remote puts the one-time browse hint away (see TvBrowseHint), without
    // recomposing this screen for every press.
    val remoteKeys = remember { KeyPresses() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            .onPreviewKeyEvent {
                remoteKeys.fire()
                false
            },
    ) {
        if (auth !is AuthState.Ready) {
            // Signing out drops straight back to the login screen, so forget where we were.
            LaunchedEffect(Unit) {
                screen = Screen.Chats
                autoOpened = false
                autoOpenDecided = false
            }
            if (!overviewSeen) {
                // The tour first: what the app is (with the language and the posters choice), then
                // how it works, then the sign in. How to work the player and the remote is told
                // later, once, at the moment it is useful (the first-run hints).
                OnboardingScreen(firstRun = true, onDone = { scope.launch { settings.markOverviewSeen() } })
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
            OnboardingScreen(firstRun = false, onDone = { scope.launch { settings.markOverviewSeen() } })
            return@Box
        }

        // A chat restored from the saved state carries only what could be written down, so its
        // blurred preview is missing until the real row turns up, when it is swapped back in.
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
        // on this television does it.
        if (screen is Screen.Chats) {
            val activity = LocalActivity.current
            BackHandler {
                if (exitArmed) activity?.finish() else { exitArmed = true; toast(L.mainBackAgain) }
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
                    account = accountHeader,
                    favorites = favorites,
                    continueWatching = continueWatching,
                    onRetry = chatsViewModel::load,
                    onRefresh = {
                        // Telegram answers a request made during a flood wait by extending it, so
                        // the button holds off and says how long rather than digging deeper.
                        val waiting = chatsViewModel.refreshUnlessRateLimited()
                        when {
                            waiting > 0 -> toast(
                                L.mainFloodWait(seconds = waiting),
                            )
                            networkStatus == NetworkStatus.Offline && !telegramConnected ->
                                toast(L.mainOfflineSavedChats)
                        }
                    },
                    onOpenChat = { openChat(it) },
                    onResumeMedia = { resumeMedia(it) },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenDownloads = {
                        downloadsCameFrom = Screen.Chats
                        downloadsOnCached = false
                        screen = Screen.Downloads
                    },
                    downloadCount = activeDownloads.size,
                    updateVersion = offeredUpdate,
                    onUpdate = { showUpdate = true },
                    // Each of these changes the chat in Telegram, so each says out loud what it
                    // did: the row has already moved by the time the menu closes, and a row
                    // moving on its own is not an account of anything.
                    onTogglePinned = { chat ->
                        chatsViewModel.setPinned(chat, !chat.isPinned) { toast(it) }
                        toast(
                            if (chat.isPinned) {
                                L.mainUnpinned(title = chat.title)
                            } else {
                                L.mainPinned(title = chat.title)
                            },
                        )
                    },
                    onToggleArchived = { chat ->
                        chatsViewModel.setArchived(chat, !chat.isArchived) { toast(it) }
                        toast(
                            if (chat.isArchived) {
                                L.mainUnarchived(title = chat.title)
                            } else {
                                L.mainArchived(title = chat.title)
                            },
                        )
                    },
                    onToggleMuted = { chat ->
                        chatsViewModel.setMuted(chat, !chat.isMuted) { toast(it) }
                        toast(
                            if (chat.isMuted) L.mainUnmuted(title = chat.title) else L.mainMuted(title = chat.title),
                        )
                    },
                    onMarkRead = { chat ->
                        chatsViewModel.markRead(chat) { toast(it) }
                        toast(L.mainMarkedRead(title = chat.title))
                    },
                    onToggleFavorite = { chat ->
                        scope.launch {
                            // The star lands on a row the menu was covering, and in the Favourites
                            // tab the row leaves the screen altogether, so the only account of what
                            // happened is this line.
                            val nowFavorite = settings.toggleFavorite(chat.id)
                            toast(
                                if (nowFavorite) {
                                    L.mainFavouriteAdded(title = chat.title)
                                } else {
                                    L.mainFavouriteRemoved(title = chat.title)
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
                    // Asked first: the saved place is the whole of what this forgets, and there
                    // is no getting it back short of finding the spot by hand.
                    onForgetMedia = { record -> forgetPrompt = record },
                    onClearFavorites = {
                        scope.launch {
                            val count = favorites.size
                            settings.clearFavorites()
                            toast(
                                L.mainFavouritesCleared(count = count),
                            )
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            settings.clearWatchHistory()
                            toast(L.mainHistoryCleared)
                        }
                    },
                    onMarkMediaWatched = { record ->
                        setWatched(record.toMediaItem(), record.chatTitle, watched = true)
                    },
                    watchedHistory = watchedHistory,
                    onOpenWatched = { record -> openStored(record.toMediaItem(), record.chatTitle) },
                    onMarkUnwatched = { record ->
                        setWatched(record.toMediaItem(), record.chatTitle, watched = false)
                    },
                    onClearWatched = {
                        scope.launch {
                            runCatching { watchedStore.clear() }
                            toast(L.mainWatchedCleared)
                        }
                    },
                    launchChatId = lastChatId,
                    picked = pickedTab,
                    onPickTab = { pickedTabKey = BrowseSection.encode(it) },
                    folders = folders,
                    onToggleLayout = { scope.launch { settings.setChatLayout(chatLayout.toggled()) } },
                    layout = chatLayout,
                    homeRows = rememberHomeRows(homeViewModel, chats, favorites, continueWatching),
                    homeArt = homeViewModel.art.collectAsStateWithLifecycle().value,
                    homeWatch = homeWatch,
                    onHomeRowShown = { row ->
                        when (row) {
                            is HomeRow.Chat -> homeViewModel.request(row.chatId)
                            is HomeRow.Recent -> homeViewModel.requestRecent()
                            is HomeRow.Continue -> Unit
                        }
                    },
                    onHomeArtWanted = homeViewModel::requestArt,
                    onPlayMedia = { item, chatTitle -> play(item, chatTitle = chatTitle) },
                    onRefreshHome = {
                        homeViewModel.refresh()
                        chatsViewModel.refreshUnlessRateLimited()
                    },
                    onSetMediaWatched = { item, chatTitle, watched -> setWatched(item, chatTitle, watched) },
                    onMessage = toast,
                    onSearched = { settings.addRecentSearch(it) },
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
                    watchedVideos = watchedVideos,
                    onSetWatched = { item, watched -> setWatched(item, current.chat.title, watched) },
                    onToggleFavorite = {
                        scope.launch {
                            val nowFavorite = settings.toggleFavorite(current.chat.id)
                            toast(
                                if (nowFavorite) {
                                    L.mainFavouriteAdded(title = current.chat.title)
                                } else {
                                    L.mainFavouriteRemoved(title = current.chat.title)
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
                    openOnCached = downloadsOnCached,
                )
            }

            is Screen.Settings -> {
                val leaveSettings = {
                    // Only if it has had time to go stale. Settings cannot change the chat list,
                    // so a full re-sync on the way out would be work nothing asked for.
                    chatsViewModel.refreshIfStale()
                    settingsPage = null
                    screen = Screen.Chats
                }
                BackHandler(onBack = leaveSettings)
                SettingsScreen(
                    chats = chats,
                    onLoggedOut = {
                        settingsPage = null
                        screen = Screen.Chats
                    },
                    onBack = leaveSettings,
                    onOpenCachedVideos = {
                        downloadsCameFrom = Screen.Settings
                        downloadsOnCached = true
                        screen = Screen.Downloads
                    },
                    onOpenAbout = { screen = Screen.About },
                    initialPage = settingsPage,
                    onPageChange = { settingsPage = it },
                )
            }

            is Screen.About -> {
                val leaveAbout = { screen = Screen.Settings }
                BackHandler(onBack = leaveAbout)
                AboutScreen(onBack = leaveAbout)
            }
        }

        roomPrompt?.let { pending ->
            // The cache has already been counted as room by the time this appears, so there is
            // nothing left for the app to give up on its own. It says how much short the device
            // is and offers the screen where the viewer can decide what goes.
            TvConfirm(
                title = L.mainNoRoomTitle,
                message = L.mainNoRoom(
                    title = pending.item.title,
                    size = Translator.messages.formatter.bytes(pending.item.sizeBytes),
                    device = device,
                    short = Translator.messages.formatter.bytes(pending.shortfallBytes),
                ) + " " +
                    if (pending.reclaimBytes > 0) {
                        L.mainNoRoomReclaim(size = Translator.messages.formatter.bytes(pending.reclaimBytes))
                    } else {
                        L.mainNoRoomFree(device = device)
                    },
                detail = L.mainNoRoomDetail,
                confirmLabel = L.mainManageDownloads,
                onConfirm = {
                    roomPrompt = null
                    downloadsCameFrom = screen
                    downloadsOnCached = false
                    screen = Screen.Downloads
                },
                onDismiss = { roomPrompt = null },
            )
        }

        forgetPrompt?.let { record ->
            TvConfirm(
                title = L.confirmForgetResumeTitle,
                message = L.confirmForgetResumeMessage(title = record.title),
                detail = L.browseForgetResumeDetail,
                confirmLabel = L.commonRemove,
                onConfirm = {
                    forgetPrompt = null
                    scope.launch {
                        settings.clearResumePosition(record.chatId, record.messageId)
                        toast(L.mainForgotResume(title = record.title))
                    }
                },
                onDismiss = { forgetPrompt = null },
            )
        }

        if (showUpdate) {
            UpdateDialog(onDismiss = { showUpdate = false; Updates.dismiss() })
        }

        val noticeLanguage = languageNotice.language
        if (noticeLanguage != null && inShell && screen is Screen.Chats && !showUpdate) {
            LanguageNoticeCard(
                language = noticeLanguage,
                onKeep = languageNotice.dismiss,
                onChange = {
                    languageNotice.dismiss()
                    pickingLanguage = true
                },
                modifier = Modifier
                    .align(if (FormFactor.isTv(context)) Alignment.BottomEnd else Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(if (FormFactor.isTv(context)) 40.dp else 16.dp),
            )
        }
        if (pickingLanguage) LanguageDialog(settings, onClose = { pickingLanguage = false })
        // The first sign in card, beside the language card and under the same rule: in the shell,
        // on the chat list, no update dialog up, and after the language card if both are due.
        val chatsLoaded = chatsState is UiState.Content
        LaunchedEffect(signInCardPending, chatsLoaded, folders.size, inShell) {
            if (!inShell) return@LaunchedEffect
            when (FirstSignIn.decide(signInCardPending, chatsLoaded, folders.size)) {
                FirstSignIn.Decision.Wait -> Unit
                // After the list has settled, as the support card waits, so it is not the first
                // thing to move under the viewer's eyes.
                FirstSignIn.Decision.Ask -> {
                    delay(SIGN_IN_CARD_DELAY_MS)
                    signInCard = true
                }
                // TDLib sends the folders before the chat list, but give a late one a moment:
                // a folder arriving restarts this effect, which cancels the skip.
                FirstSignIn.Decision.Skip -> {
                    delay(FOLDERS_SETTLE_MS)
                    runCatching { settings.markFirstSignInCardDone() }
                }
            }
        }
        if (signInCard && noticeLanguage == null && inShell && screen is Screen.Chats && !showUpdate) {
            fun answered() {
                signInCard = false
                scope.launch { runCatching { settings.markFirstSignInCardDone() } }
            }
            FirstSignInCard(
                onEverything = { answered() },
                onOnlyFolders = {
                    answered()
                    hideGroupsPrompt = true
                },
                modifier = Modifier
                    .align(if (FormFactor.isTv(context)) Alignment.BottomEnd else Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(if (FormFactor.isTv(context)) 40.dp else 16.dp),
            )
        }
        if (hideGroupsPrompt) {
            val unreachable = remember(favorites, chats) { DefaultGroups.unreachableFavorites(favorites, chats) }
            val words = DefaultGroups.prompt(unreachable.size, folders.size)
            TvConfirm(
                title = words.title,
                message = words.message,
                detail = words.detail,
                confirmLabel = words.confirm,
                onConfirm = {
                    hideGroupsPrompt = false
                    scope.launch {
                        settings.setHideDefaultGroups(true, unstar = unreachable)
                        toast(L.groupsHiddenToast)
                    }
                },
                onDismiss = { hideGroupsPrompt = false },
            )
        }
        // After the first sign in card rather than under it: both sit at the foot of the screen,
        // and the card asks for an answer while the hint goes at the next key press.
        if (FormFactor.isTv(context) && inShell && screen is Screen.Chats && !signInCardPending && !signInCard) {
            TvBrowseHint(
                keys = remoteKeys,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }
        if (whatsNew.showing && !showUpdate) {
            WhatsNewDialog(
                onClose = { whatsNew.showing = false },
                onChangelog = {
                    whatsNew.showing = false
                    if (!openLink(context, WhatsNew.CHANGELOG)) changelogQr = true
                },
            )
        }
        if (changelogQr) LinkQrDialog(WhatsNew.CHANGELOG, s.settingsQrChangelogPage, onClose = { changelogQr = false })

        if (supportRung > 0 && noticeLanguage == null && !signInCard && (screen is Screen.Chats || screen is Screen.Downloads) && !showUpdate) {
            val rung = supportRung
            SupportCard(
                rung = rung,
                counters = supportCounters,
                onSupport = { supportRung = 0; supporting = "card$rung" },
                onStar = {
                    supportRung = 0
                    if (!openLink(context, com.tmplayer.ui.about.About.SOURCE)) supporting = "card$rung"
                },
                onShare = { supportRung = 0; shareTmplayer(context) },
                onLater = { supportRung = 0 },
                onAlready = {
                    supportRung = 0
                    if (SupportReminder.forcedRung == 0) scope.launch { runCatching { settings.markSupporter() } }
                },
                modifier = Modifier
                    .align(if (FormFactor.isTv(context)) Alignment.BottomEnd else Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(if (FormFactor.isTv(context)) 40.dp else 16.dp),
            )
        }
        supporting?.let { from -> SupportDialog(from = from, onClose = { supporting = null }) }

        ConnectionStatus(
            notice = connectionNotice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }
}

private const val OFFLINE_SETTLE_MS = 750L

/** How long an account that seems to have no folders is given before the first sign in card is skipped. */
private const val FOLDERS_SETTLE_MS = 3_000L

/** How long the chat list is up before the first sign in card slides in. */
private const val SIGN_IN_CARD_DELAY_MS = 1_500L

/** How long the chat list is up before the support card may slide in. */
private const val SUPPORT_CARD_DELAY_MS = 4_000L

