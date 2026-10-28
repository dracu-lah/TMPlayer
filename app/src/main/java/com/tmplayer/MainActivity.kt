package com.tmplayer

import com.tmplayer.ui.components.TopMessageHost
import androidx.compose.ui.zIndex
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
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
import com.tmplayer.data.HomeRows
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.AndroidTransferNotifier
import com.tmplayer.data.AuthState
import com.tmplayer.data.LegacyDownloads
import com.tmplayer.data.LocalDownloads
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.CoalescingTransferNotifier
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.FormFactor
import com.tmplayer.data.CopyHandoff
import com.tmplayer.data.MediaItem
import com.tmplayer.data.LowStorageOffers
import com.tmplayer.data.RoomOnDisk
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.restore
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.CrashReports
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.data.TvChannelsPublisher
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
import androidx.compose.ui.graphics.toArgb
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.TouchChrome
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.browse.TouchNav
import com.tmplayer.ui.browse.TouchNavHost
import com.tmplayer.ui.browse.TouchPage
import com.tmplayer.ui.browse.TouchRoutes
import com.tmplayer.ui.browse.LocalTouchPageKey
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.ui.components.noteTouchOrigin
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.browse.KeyPresses
import com.tmplayer.ui.browse.TvBrowseHint
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HomeViewModel
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.rememberHomeRows
import com.tmplayer.ui.browse.HomeLaunch
import com.tmplayer.ui.browse.TvHomeChannels
import com.tmplayer.data.HomeRow
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.settings.TickRow
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.ConnectionStatus
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.onboarding.Setup
import com.tmplayer.ui.onboarding.SetupAndTour
import com.tmplayer.ui.onboarding.TourOnly
import com.tmplayer.ui.update.UpdateDialog
import com.tmplayer.ui.update.LinkQrDialog
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.CoffeeDialog
import com.tmplayer.ui.settings.LanguageDialog
import com.tmplayer.ui.settings.LanguageNoticeCard
import com.tmplayer.ui.settings.WhatsNewDialog
import com.tmplayer.ui.i18n.rememberLanguageNotice
import com.tmplayer.ui.i18n.rememberWhatsNew
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.AppLocales
import com.tmplayer.ui.settings.SupportDialog
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.tmplayer.data.guardedLifecycleScope as lifecycleScope
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

/** A small store for asks that happen once per install, and so are not settings or in backups. */
private const val ONCE_PREFS = "once"
private const val ASKED_NOTIFICATIONS = "asked_notifications"

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

/** The phone's screens as the bar and its transitions see them. */
private object TouchScreens {
    /** A screen's key: what its saved state and its say over the bar are kept under. */
    fun key(screen: Screen): String = when (screen) {
        is Screen.Chats -> SCREEN_CHATS
        is Screen.Media -> SCREEN_MEDIA
        is Screen.Settings -> SCREEN_SETTINGS
        is Screen.Downloads -> SCREEN_DOWNLOADS
        is Screen.About -> SCREEN_ABOUT
    }

    /**
     * Where a screen stands along the bar, left to right, for which way the next one slides in:
     * Home, Chats, Search, then the picture's pages. Null for a chat, Settings and About, which
     * are not places on the bar: a chat and About change without a slide, and Settings is pushed
     * over whatever opened it ([isPushed]).
     */
    fun order(screen: Screen, tab: BrowseSection?): Int? = when (screen) {
        is Screen.Chats -> when {
            tab == null || tab.isHome -> 0
            tab.onChatsPage -> 1
            else -> 3
        }
        is Screen.Downloads -> 3
        is Screen.Settings, is Screen.Media, is Screen.About -> null
    }

    /**
     * Settings, which the picture's popover opens as a screen of its own rather than a tab: it
     * comes in from the end and goes back out towards it, the way a pushed screen does, and no
     * swipe leads into it.
     */
    fun isPushed(screen: Screen): Boolean = screen is Screen.Settings

    /** How far a screen slides in as it fades: a twelfth of the width, as the browser's pages do. */
    const val SLIDE_PART = 12

    /** How far a pushed screen slides as it comes in, and the one under it as it goes: Material's shared axis. */
    const val PUSH_PART = 10
}

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
    /** Low storage mode is off and the video would stream, so the message names the mode. */
    val lowStorageOff: Boolean = false,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Asked for rather than inherited. API 35 and above draws behind the bars anyway, but the
        // stick and half the phones this runs on are older, so this makes the two the same
        // everywhere. Every screen already handles its own insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // `--ez lowend true` on a debug build: the low-end device verdict, short of memory and of
        // storage, so the setup step, the Settings card and the hint can be seen anywhere (CP68).
        if (BuildConfig.DEBUG && intent?.hasExtra("lowend") == true) {
            com.tmplayer.data.DeviceFitOverride.forced =
                if (intent.getBooleanExtra("lowend", false)) com.tmplayer.data.DeviceFit(true, true) else null
            if (intent.getBooleanExtra("devicehint", false)) com.tmplayer.data.DeviceFitHint.raise()
        }
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
        // Debug builds only; R8 drops these from a release, where BuildConfig.DEBUG is false.
        if (BuildConfig.DEBUG) {
            debugCrashAtStart = intent?.getBooleanExtra(EXTRA_CRASH_AT_START, false) == true
            debugHangSeconds = intent?.getIntExtra(EXTRA_DEBUG_ANR, 0) ?: 0
            if (intent?.getBooleanExtra(EXTRA_DEBUG_NATIVE_CRASH, false) == true) {
                // A moment after the first frame, so the crash is the running app's and not the
                // launch's. SIGSEGV through debuggerd, as a real native crash goes, which leaves
                // a tombstone and an exit of REASON_CRASH_NATIVE in Android's list.
                window.decorView.postDelayed({ android.os.Process.sendSignal(android.os.Process.myPid(), 11) }, 3_000)
            }
        }
        noteRequestedScreen(intent)
        setContent {
            TMPlayerTheme {
                // Shared screens (a video's copies) open their sheets as the phone's own sheet.
                androidx.compose.runtime.CompositionLocalProvider(
                    com.tmplayer.ui.components.LocalSheetHost provides com.tmplayer.ui.components.PhoneSheetHost,
                ) {
                    // Where each tap lands, for a page it opens to grow out of (see PageMotion).
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().noteTouchOrigin()) {
                        // The last runs crashed as they started: the question alone, then the shell.
                        val holding by CrashReports.holdShell.collectAsStateWithLifecycle()
                        // On a remote, a list keeps still while the focused item is in full view.
                        com.tmplayer.ui.components.StillWhenVisible(enabled = !com.tmplayer.ui.components.isTouch()) {
                            if (holding) StartupCrashQuestion() else Root()
                        }
                        // Messages at the top, clear of the floating bar and the buttons at the foot.
                        TopMessageHost()
                    }
                }
            }
        }
        if (savedInstanceState == null) playFromHomeScreen(intent)
    }

    /**
     * An entry picked in the home screen's "Play next" row (see [WatchNextPublisher]), or a video's
     * card on one of the app's own channels ([TvChannelsPublisher]), opens the
     * player straight away, over the app as it was. The intent's action is cleared once acted on,
     * so recreating the activity does not play it again.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun playFromHomeScreen(intent: Intent?) {
        // A folded show's card on one of the app's own home screen channels: its page on Home.
        TvChannelsPublisher.showFrom(intent)?.let { show ->
            intent?.action = Intent.ACTION_MAIN
            lifecycleScope.launch {
                if (!com.tmplayer.data.KidsLock.allows(com.tmplayer.data.ChatGate.awaitReady(), show.chatId)) return@launch
                HomeLaunch.show.value = show
            }
            return
        }
        val video = WatchNextPublisher.videoFrom(intent) ?: return
        intent?.action = Intent.ACTION_MAIN
        // An entry published before the kids lock went on (CP57) still names its chat; one the
        // lock leaves out opens the app instead of the video.
        lifecycleScope.launch {
            if (!com.tmplayer.data.KidsLock.allows(com.tmplayer.data.ChatGate.awaitReady(), video.chatId)) return@launch
            // Known gone from Telegram this session (HX21) and not downloaded: the app says so
            // rather than opening a player that can only fail. The row itself goes with the entry.
            com.tmplayer.data.GoneVideos.reasonFor(video.chatId, video.messageId)?.let { reason ->
                val downloaded = withContext(Dispatchers.IO) {
                    runCatching { LocalDownloads.fileFor(SettingsStore(this@MainActivity), video.chatId, video.messageId) }.getOrNull()
                } != null
                if (!downloaded) {
                    com.tmplayer.ui.components.showMessage(this@MainActivity, com.tmplayer.data.GoneVideos.openMessage(reason))
                    return@launch
                }
            }
            startActivity(PlayerActivity.intent(this@MainActivity, video.toMediaItem(), video.chatTitle))
        }
    }

    /**
     * A language picked on Android's own page for this app (Android 13 and later) while it was in
     * the background becomes the in-app choice. See [AppLocales].
     */
    /** The player has the screen now; its own loader takes over from the press's spinner. */
    override fun onStop() {
        super.onStop()
        com.tmplayer.ui.components.PlayPress.end()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInputAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // Back from the player (or never left): no press of Play is still on its way.
        com.tmplayer.ui.components.PlayPress.end()
        lifecycleScope.launch {
            val store = SettingsStore(this@MainActivity)
            runCatching { AppLocales.adopt(this@MainActivity, store.language.first())?.let { store.setLanguage(it) } }
        }
    }

    /**
     * The debug-only hang (CP102d): the first key or touch after a start with the debug_anr extra
     * puts the main thread to sleep for that many seconds while the input waits, which is what
     * Android counts as not responding. Choose "Close app" in its dialog so the exit is recorded
     * as an ANR. Never in a release build.
     */
    private fun debugHangOnInput(down: Boolean) {
        if (!BuildConfig.DEBUG || !down || debugHangSeconds <= 0) return
        val seconds = debugHangSeconds
        debugHangSeconds = 0
        Thread.sleep(seconds * 1_000L)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        debugHangOnInput(event.action == android.view.KeyEvent.ACTION_DOWN)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        debugHangOnInput(event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
        return super.dispatchTouchEvent(event)
    }

    /**
     * Esc on a keyboard plugged into a tablet or a phone is Back, as it is in every other app with
     * a keyboard: it closes a sheet, a search or a page the way the back gesture does. Only when
     * nothing on screen took the key first (these run after the views had it), and not on a
     * television, whose remote has a Back of its own.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && !FormFactor.isTv(this)) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && !FormFactor.isTv(this)) {
            if (event.isTracking && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
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
        // A t.me or tg:// link another app handed over, TMPlayer picked from the chooser (CP79):
        // the window's link flow opens it once signed in. Taken off the intent, so a rotation
        // does not open it again.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let(com.tmplayer.data.TelegramLinks::parse)?.let { link ->
                com.tmplayer.ui.browse.LinkRequests.ask(link)
                intent.data = null
                intent.action = Intent.ACTION_MAIN
            }
        }
        // A new videos notification: the chat it is about, or the list (0). Taken off the intent,
        // so a rotation does not open it again.
        if (intent?.hasExtra(com.tmplayer.data.BackgroundChecks.EXTRA_OPEN_CHAT) == true) {
            requestedChat.value = intent.getLongExtra(com.tmplayer.data.BackgroundChecks.EXTRA_OPEN_CHAT, 0L)
            intent.removeExtra(com.tmplayer.data.BackgroundChecks.EXTRA_OPEN_CHAT)
        }
        val asked = intent?.getStringExtra(EXTRA_OPEN) ?: return
        // The sponsor reminder: the Buy Me a Coffee window, once the shell is free for it.
        if (asked == com.tmplayer.data.BackgroundChecks.OPEN_SUPPORT) {
            intent.removeExtra(EXTRA_OPEN)
            SupportReminder.requestOpen()
            return
        }
        requestedScreen.value = asked
    }

    companion object {
        /** When the last key or touch reached the app, for Home's auto-refresh to stop when idle. */
        @Volatile
        var lastInputAt = 0L
            private set

        /** Which screen a launch is asking for, when it is asking for one. */
        const val EXTRA_OPEN = "com.tmplayer.extra.OPEN"

        /** Debug and promo builds only: show the support card now. See [SupportReminder.forced]. */
        const val EXTRA_SUPPORT_REMINDER = "support_reminder"

        /** Debug and promo builds only: `--ei support_rung N` shows rung N's card (1 to 3). */
        const val EXTRA_SUPPORT_RUNG = "support_rung"

        /**
         * Debug and promo builds only, for the crash questions (CP102c):
         * `adb shell am start -n com.tmplayer/.MainActivity --ez crash_at_start true` makes the
         * shell throw as it is first composed. Leave it off the next start to see the shell again.
         */
        const val EXTRA_CRASH_AT_START = "crash_at_start"

        /** Whether this start was asked to crash in the shell. See [EXTRA_CRASH_AT_START]. */
        @Volatile
        internal var debugCrashAtStart = false

        /**
         * Debug and promo builds only, for the ANR question (CP102d): `--ei debug_anr 10` hangs the
         * main thread for 10 s on the next key or touch. See [debugHangOnInput].
         */
        const val EXTRA_DEBUG_ANR = "debug_anr"

        /** Seconds the next key or touch hangs the main thread for, from [EXTRA_DEBUG_ANR]. */
        private var debugHangSeconds = 0

        /**
         * Debug and promo builds only, for the native crash question (CP102d):
         * `--ez debug_native_crash true` sends the app a SIGSEGV three seconds after it opens.
         */
        const val EXTRA_DEBUG_NATIVE_CRASH = "debug_native_crash"

        const val OPEN_DOWNLOADS = SCREEN_DOWNLOADS

        /** Downloads with Free up space already up: the player's "Not enough space" button. */
        const val OPEN_FREE_UP_SPACE = "free_up_space"

        /**
         * The ask, waiting to be read by the composition.
         *
         * A flow rather than the intent itself, because the composable that owns the current
         * screen cannot see the activity's intent. Must be cleared once acted on: left set, a
         * rotation would send the viewer back to Downloads every time they turned the phone.
         */
        internal val requestedScreen = MutableStateFlow<String?>(null)

        /** The chat a new videos notification asks for (0: the chat list), until it is opened. */
        internal val requestedChat = MutableStateFlow<Long?>(null)
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
    // A debug or promo build started with the crash_at_start extra: the shell throws as it is
    // first composed, as 1.27.0's Home did, to try the start-up crash question (CP102c). Twice in
    // a row and the third start asks before building anything.
    if (BuildConfig.DEBUG && MainActivity.debugCrashAtStart) {
        error("Debug crash at start, asked for with crash_at_start")
    }
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Td.auth.collectAsStateWithLifecycle()
    val networkStatus by NetworkMonitor.status.collectAsStateWithLifecycle()
    val telegramConnected by Td.connected.collectAsStateWithLifecycle()
    val settings = remember { SettingsStore(context) }
    // Resume across devices, when turned on: the other devices' places in, this one's out, once
    // Telegram is reachable (ResumeSync).
    LaunchedEffect(telegramConnected) { if (telegramConnected) com.tmplayer.data.ResumeSync.syncSoon(settings) }
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
    // The window's own ground, from the theme rather than the launch resource, so what shows
    // behind a screen's edge or during a transition is true black under Pure black too.
    val ground = Tone.background
    LaunchedEffect(ground, activity) {
        // None on a television: the shell's root paints the same ground over the whole window on
        // every frame (the box round the loader below), so the window's own was a second
        // full-screen fill under it, which a TV stick's GPU paid for on each frame of every press.
        if (activity != null && FormFactor.isTv(activity)) {
            activity.window?.setBackgroundDrawable(null)
            return@LaunchedEffect
        }
        activity?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground.toArgb()))
    }

    // The setup after sign in (CP67), armed by a sign in screen. False until the store answers,
    // which is right for everybody already signed in: they are never asked.
    val setupPending by settings.setupPending.collectAsStateWithLifecycle(initialValue = false)
    // The quick tour (CP69). Done until the store answers, so the offer never flashes up.
    val tourState by settings.tourState.collectAsStateWithLifecycle(initialValue = SettingsStore.TourState.Done)
    // The favourites the kids lock lets through, as the Favourites tab lists them, so every count
    // of them (the badge, Clear all) says the same number.
    val storedFavorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
    val favorites = remember(storedFavorites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavorites, allowedChats) }
    val watchProgress by settings.watchProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    val continueWatching by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchedVideos by watchedStore.watched.collectAsStateWithLifecycle(initialValue = emptyMap())
    val watchedHistory by watchedStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchLater by watchedStore.watchLater.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastChatId by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val openLastChat by settings.openLastChat.collectAsStateWithLifecycle(initialValue = false)
    val minSize by settings.minSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsStateWithLifecycle(initialValue = SizeFilter.DEFAULT_MAX)
    // When each video was last watched, for the "New" badge on a show's tile.
    val touchTimes = remember(continueWatching, watchedVideos) {
        com.tmplayer.data.NewEpisodes.touchTimes(continueWatching, watchedVideos.values)
    }
    val homeWatch = remember(watchProgress, watchedVideos, touchTimes) {
        SeriesWatch(
            point = { watchProgress[SettingsStore.progressKey(it.chatId, it.messageId)] },
            finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedVideos },
            touchedAt = { touchTimes[SettingsStore.progressKey(it.chatId, it.messageId)] },
        )
    }
    // The app lock (CP57). Null until the store has answered, which gates nothing: the pad must
    // never flash up over an app that has no PIN.
    val appLock by settings.appLock.collectAsStateWithLifecycle(initialValue = null)
    val appUnlocked by AppLockSession.unlocked.collectAsStateWithLifecycle()
    // Settings asks once per visit: back to the chats and it asks again.
    var settingsUnlocked by rememberSaveable { mutableStateOf(false) }
    // Leaving the app locks the session, and Settings with it: whoever opens the app next, or
    // the activity restored after the process died, must not find Settings already open.
    LaunchedEffect(appUnlocked) {
        if (!appUnlocked) settingsUnlocked = false
    }

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
    val inShell = auth is AuthState.Ready && !setupPending && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
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
    // "Updated to 1.29.0", once, on the first launch after TMPlayer updated itself (HX5). A pill
    // at the top that takes no focus, so a remote starts where it always does.
    LaunchedEffect(inShell) {
        if (!inShell) return@LaunchedEffect
        val updated = runCatching { com.tmplayer.data.AfterUpdate.onLaunch(context) }.getOrNull() ?: return@LaunchedEffect
        toast(com.tmplayer.i18n.L.updateUpdatedTo(version = updated))
    }
    var changelogQr by remember { mutableStateOf(false) }

    // The Buy Me a Coffee window: after real use, at most every two weeks, and only at a good
    // moment (a video watched to the end, a download finished) while this list or Downloads is up,
    // never in the player or over another window; or at once when its notification is pressed.
    // The rules and counters are SupportReminder's. [supportRung] is 1 while it is up, else 0.
    var supportRung by remember { mutableStateOf(0) }
    // "New videos in starred chats", offered once, on a phone, just after a chat was starred.
    var newVideoOffer by remember { mutableStateOf(false) }
    val offerNewVideos: suspend () -> Unit = {
        if (!FormFactor.isTv(context) && runCatching { !settings.newVideoOfferedNow() }.getOrDefault(false)) {
            newVideoOffer = true
        }
    }
    var supportCounters by remember { mutableStateOf(SupportReminder.Counters()) }
    var supporting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { settings.noteSupportFirstSeen(System.currentTimeMillis()) } }
    // Finished videos are counted here, in the process, so a watch counts even while a screen is
    // being rebuilt behind the player.
    // The notification permission, asked for once, after the first video watched to the end: the
    // notifications that are on from the start (new episodes, downloads, low storage, updates, the
    // support ask) can only reach someone who has allowed them, and launch is the wrong moment.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        SupportReminder.finished.collect { done ->
            runCatching { settings.noteSupportCompleted(done.key, done.at) }
            if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !FormFactor.isTv(context) &&
                !com.tmplayer.data.BackgroundChecks.canNotify(context)
            ) {
                val once = context.getSharedPreferences(ONCE_PREFS, android.content.Context.MODE_PRIVATE)
                if (!once.getBoolean(ASKED_NOTIFICATIONS, false)) {
                    once.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
                    runCatching { askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
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

    // A kept video Telegram says is gone for good (deleted, its chat closed to this account, the
    // post edited into something else) leaves Continue watching, Watched and Watch later, unless
    // a download of it still plays, and the viewer is told (HX21). The answers come from TDLib's
    // deletes and from the lookups Home and History make anyway: nothing is checked at start.
    LaunchedEffect(Unit) {
        com.tmplayer.data.GoneVideos.follow(
            settings = settings,
            watched = watchedStore,
            keptOnDevice = { chatId, messageId -> LocalDownloads.fileFor(settings, chatId, messageId) != null },
        ) { forgotten -> withContext(Dispatchers.Main) { toast(com.tmplayer.data.GoneVideos.message(forgotten)) } }
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
                ChatListViewModel(settings, com.tmplayer.data.PageQueries.cache) as T
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
                HomeViewModel(
                    sizeLimits = { settings.minSizeBytes.first() to settings.maxSizeBytes.first() },
                    cached = true,
                    // Home's refetches wait while a video plays (see HomeViewModel's busy).
                    busy = { com.tmplayer.data.WatchCache.anyPlaying() },
                ) as T
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
    // Home keeps itself up to date while the app is in front: on coming back, when its rows are
    // a minute old, and every few minutes after, in place (the tiles stay up and keep the focus).
    val resumedBefore = remember { booleanArrayOf(false) }
    LaunchedEffect(shellInFront) {
        if (!shellInFront) return@LaunchedEffect
        // Coming back (from the player, nearly always) the rows are older than a minute after any
        // video, and a refresh rebuilds them all just as the viewer reaches for the remote: on a
        // TV stick that made the first presses on Home lag. So it waits for the remote to rest a
        // few seconds, or [RETURN_REFRESH_MAX_WAIT_MS] at most. The first start asks at once.
        if (resumedBefore[0]) {
            val back = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val quiet = now - maxOf(back, MainActivity.lastInputAt)
                if (quiet >= RETURN_REFRESH_QUIET_MS || now - back >= RETURN_REFRESH_MAX_WAIT_MS) break
                delay(RETURN_REFRESH_QUIET_MS - quiet)
            }
        }
        resumedBefore[0] = true
        homeViewModel.refreshIfStale()
        while (true) {
            delay(HomeViewModel.AUTO_REFRESH_MS)
            // Only while someone is using it: a TV left on Home refreshed every few minutes for
            // hours, rebuilding every row each time. Untouched, Home waits for the next press or
            // for coming back to the front ([refreshIfStale]).
            val idle = android.os.SystemClock.elapsedRealtime() - MainActivity.lastInputAt
            if (idle < HomeViewModel.AUTO_REFRESH_MS) homeViewModel.refreshInPlace(background = true)
        }
    }
    val accountHeader by chatsViewModel.accountHeader.collectAsStateWithLifecycle()
    val chats = (chatsState as? UiState.Content)?.value?.chats.orEmpty()

    // A sign in screen arms the setup that follows it (CP67, see Setup).
    LaunchedEffect(auth) {
        if (Setup.isSignInStep(auth)) {
            runCatching { settings.armSetup() }
            // A sign in screen means the account is gone, however it went: signed out here, or
            // from Telegram's Devices list, which is the way back from a forgotten PIN. The lock
            // was that account's.
            runCatching { settings.clearAppLock() }
        }
    }

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
                // Nor on the TV home screen, where the app's channels show them. The cards go and
                // the channels stay: an empty channel is not shown, and a new one would need the
                // viewer to add it again in the launcher's own settings.
                App.backgroundScope.launch { TvChannelsPublisher.clearPrograms(context.applicationContext) }
            }
        }
    }

    var connectionNotice by remember { mutableStateOf(ConnectionNotice.Hidden) }
    var wasOffline by remember { mutableStateOf(false) }
    var wasDisconnected by remember { mutableStateOf(false) }
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
                wasDisconnected = false
                // Nothing said: the offline pill going is the news, and the lists refresh in place.
                if (auth is AuthState.Ready) {
                    chatsViewModel.load()
                    homeViewModel.refreshInPlace()
                    updates.checkIfDue()
                }
            }
            // Online, signed in, and the line to Telegram is down: back from the background, most
            // often. What is on screen stays; after a short settle a small "Connecting" pill says
            // why nothing new arrives, as Telegram's own title does. Retry and the proxy wait for
            // a real failure (Td.unreachable).
            auth is AuthState.Ready && !telegramConnected -> {
                delay(CONNECTING_SETTLE_MS)
                wasDisconnected = true
                connectionNotice = ConnectionNotice.Connecting
            }
            wasDisconnected -> {
                // The line is back: the lists refresh quietly in place, no toast.
                connectionNotice = ConnectionNotice.Hidden
                wasDisconnected = false
                if (auth is AuthState.Ready) {
                    chatsViewModel.load()
                    // In place: a refresh() wiped Home and its cached copy each time the line came back.
                    homeViewModel.refreshInPlace()
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
    // "Switch to Low storage mode?", for a video that only fits that way, and the refusal "Not now"
    // falls back to. A refused download leaves its question in [LowStorageOffers].
    var lowStorageAsk by remember { mutableStateOf<Pair<LowStorageOffers.Ask, RoomPrompt?>?>(null) }
    val offeredLowStorage by LowStorageOffers.pending.collectAsStateWithLifecycle()
    LaunchedEffect(offeredLowStorage) {
        if (offeredLowStorage != null) LowStorageOffers.take()?.let { lowStorageAsk = it to null }
    }
    // The machine the space message is about, since "this phone is 2 GB short" on a television
    // reads as the app talking about something else entirely.
    val device = remember { if (FormFactor.isTv(context)) "tv" else "phone" }
    // Where leaving the Downloads screen goes back to.
    var downloadsCameFrom by remember { mutableStateOf<Screen>(Screen.Chats) }
    // Where leaving Settings goes back to: the page the profile popover's Settings was picked on.
    var settingsCameFrom by remember { mutableStateOf<Screen>(Screen.Chats) }
    // On a television, the rail item to put the remote back on when Back from Settings or
    // Downloads returns to the browser: the place it was pressed, not the first tile of the page.
    var railReturn by remember { mutableStateOf<com.tmplayer.ui.browse.RailReturn?>(null) }
    // Whether the Downloads screen opens on its cached videos, which is how Settings' Cached
    // videos row reaches the list of them.
    var downloadsOnCached by remember { mutableStateOf(false) }
    // How many times the Downloads screen has been asked to open with Free up space up (0: not),
    // which is where "Not enough space" sends the viewer: straight to what can go, not to a tab
    // to work it out from. A count, so a second refusal while the page is up opens it again.
    var downloadsFreeUp by remember { mutableStateOf(0) }
    // Whether that ask came from a video refused for room, which makes the review lead with Low
    // storage mode, or from Settings' Storage, whose Free up space is the same review.
    var downloadsAskedForRoom by remember { mutableStateOf(true) }
    // The Settings page open, kept here because Settings itself is gone while About or the cached
    // videos are showing, and coming back from either should land on the page that led there.
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // A launch that asked for a particular screen, which is how the download notification opens
    // the list it is about. Cleared as it is acted on, so it happens once per press.
    val requestedScreen by MainActivity.requestedScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requestedScreen) {
        if (requestedScreen != SCREEN_DOWNLOADS && requestedScreen != MainActivity.OPEN_FREE_UP_SPACE) return@LaunchedEffect
        // Back from the Downloads screen goes wherever the viewer already was, not to the chat
        // list: they were reading a chat, pressed a notification, and Back should return them to it.
        if (screen !is Screen.Downloads) downloadsCameFrom = screen
        downloadsOnCached = false
        // The player's "Not enough space" asks for Free up space itself.
        downloadsFreeUp = if (requestedScreen == MainActivity.OPEN_FREE_UP_SPACE) downloadsFreeUp + 1 else 0
        downloadsAskedForRoom = true
        screen = Screen.Downloads
        MainActivity.requestedScreen.value = null
    }
    val supportMoment by SupportReminder.moment.collectAsStateWithLifecycle()
    val supportScreen = screen is Screen.Chats || screen is Screen.Downloads
    var supportForcedShown by remember { mutableStateOf(false) }
    val supportOpenRequested by SupportReminder.openRequested.collectAsStateWithLifecycle()
    val crashAsking = CrashReports.pending.collectAsStateWithLifecycle().value != null
    // Anything else up: the update, the language notice, the crash question, What's new, the
    // support page's own codes. The window waits for all of them.
    val otherWindowUp = showUpdate || languageNotice.language != null || crashAsking || whatsNew.showing || supporting != null
    LaunchedEffect(supportMoment, supportOpenRequested, supportScreen, inShell, otherWindowUp, supportRung) {
        if (!inShell || !supportScreen || otherWindowUp || supportRung != 0) {
            return@LaunchedEffect
        }
        if (supportOpenRequested) {
            // The notification was pressed: the window the viewer asked for, counted as an ask.
            delay(SUPPORT_CARD_DELAY_MS / 4)
            if (!SupportReminder.takeOpenRequest()) return@LaunchedEffect
            val now = System.currentTimeMillis()
            supportCounters = runCatching { settings.supportCountersNow() }.getOrNull() ?: SupportReminder.Counters()
            runCatching { settings.recordSupportAsk(now) }
            SupportReminder.sessionShown = true
            supportRung = 1
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
        if (runCatching { SupportReminder.claim(settings, now) }.getOrDefault(false)) {
            supportCounters = counters
            supportRung = 1
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
    // The Chats page's chip (a folder or one of Telegram's filters) last open, for the bar's Chats.
    var lastChatsKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pickedTab) { if (pickedTab?.onChatsPage == true) lastChatsKey = pickedTabKey }
    // Home's own background work (a post in one of its chats, a retry, the posters looked up
    // ahead) waits while Home is not on screen (HX8).
    val homeOnScreen = shellInFront && screen is Screen.Chats && (pickedTab == null || pickedTab.isHome)
    androidx.compose.runtime.SideEffect { homeViewModel.shown = homeOnScreen }
    // The phone's one floating bar, shared by every page it is on.
    val touchChrome = remember { TouchChrome() }

    // The account's Telegram folders, which become destinations of their own. Collected here
    // rather than inside the chat list's own state because they arrive on TDLib's schedule, not
    // with the chat list, and the rail has to be able to draw before either has turned up.
    val folders by Td.folders.collectAsStateWithLifecycle()

    // Whether a video is currently being got ready to play. Not saveable: a process death in the
    // middle of it means nothing is being prepared any more, and restoring true would leave every
    // video on the screen refusing to open.
    var preparing by remember { mutableStateOf(false) }
    // Whether a video from a stored list is being looked up again before it plays (see openStored).
    var openingStored by remember { mutableStateOf(false) }

    // Everything unfinished: coming down, waiting its turn, held or failed. That is the number
    // the drawer badges, because every one of them is a video the viewer is still owed. Held here
    // because the chat list is where the drawer lives, and a download is started from a different
    // screen entirely and outlives it.
    // Only the count: the map itself changes with every download's progress, several times a
    // second, and reading it here recomposed the whole shell for each one.
    val activeDownloadsState = OfflineDownloads.active.collectAsStateWithLifecycle()
    val activeDownloadCount by remember(activeDownloadsState) { derivedStateOf { activeDownloadsState.value.size } }

    /**
     * The preparation itself, split out only so [play] can wrap the whole of it in one pending
     * state without every early return having to remember to clear it.
     */
    suspend fun playNow(requested: MediaItem, confirmed: Boolean, requestedChat: String) {
        // A film posted more than once plays the copy chosen for the viewer (VideoCopies.pick):
        // the one they picked, one on the device, the one they started, their usual quality.
        // Off the main thread: judging a copy asks the codec list (HX2), slow the first time on a stick.
        val chosen = withContext(Dispatchers.Default) { runCatching { settings.copyToPlay(requested) }.getOrDefault(requested) }
        // A smoother copy played in place of one too heavy for this device: the player says so,
        // with Change one press away ([com.tmplayer.player.SmootherPick]).
        withContext(Dispatchers.Default) { runCatching { settings.copyPassedOver(requested) }.getOrNull() }
            ?.takeIf { it.id != chosen.id }
            ?.let { heavy -> com.tmplayer.player.SmootherPick.offer(chosen, com.tmplayer.data.VideoCopies.qualityLabel(heavy) ?: heavy.title) }
        // A stored row's file id may name another file in this session ([Td.fileIdFits]): the
        // message is asked for again, which gives the current id. A video in the Downloads
        // folder plays from its own file and never needs the id.
        // A video this session already knows is gone from Telegram ([com.tmplayer.data.GoneVideos],
        // HX21) and not downloaded says so instead of opening a player that can only fail.
        var gone: com.tmplayer.data.GoneReason? = null
        val item = withContext(Dispatchers.Default) {
            val downloaded = LocalDownloads.fileFor(settings, chosen.chatId, chosen.messageId) != null
            if (!downloaded) gone = com.tmplayer.data.GoneVideos.reasonFor(chosen.chatId, chosen.messageId)
            if (!downloaded && gone == null &&
                !runCatching { Td.fileIdFits(chosen.fileId, chosen.sizeBytes) }.getOrDefault(true)
            ) {
                val fresh = runCatching { Td.refreshMedia(chosen.chatId, chosen.messageId) }.getOrNull()
                if (fresh == null) gone = com.tmplayer.data.GoneVideos.reasonFor(chosen.chatId, chosen.messageId)
                fresh?.let { chosen.copy(fileId = it.fileId, sizeBytes = it.sizeBytes) } ?: chosen
            } else {
                chosen
            }
        }
        gone?.let { reason ->
            toast(com.tmplayer.data.GoneVideos.openMessage(reason))
            return
        }
        val chatTitle = if (item.chatId == requested.chatId) requestedChat else CopyHandoff.chatTitleOf(item) ?: requestedChat
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
                    com.tmplayer.ui.components.PlayPress.open()
                    context.startActivity(PlayerActivity.intent(context, item, chatTitle))
                }
                return@withContext
            }
            val local = runCatching { Td.localFileAvailability(item.fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            // A stream: its head and index start coming now, while the room is measured and the
            // player is built ([com.tmplayer.player.StreamWarmup]).
            if (local != LocalFileAvailability.Complete) com.tmplayer.player.StreamWarmup.warm(item) { settings.resumeStartFor(item) }
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
            val lowStorageOn = runCatching { settings.lowStorageModeNow() }.getOrDefault(true)

            // Back on Main from here down. Everything below writes Compose state or starts an
            // activity, and neither is a thing to do from a background thread.
            withContext(Dispatchers.Main) {
                fun start() {
                // The player claims the cache for itself the moment it opens, which is what keeps
                // a series honest. This only has to leave the record alone for a kept download.
                if (!kept) scope.launch { settings.rememberCachedVideo(item, chatTitle) }
                com.tmplayer.ui.components.PlayPress.open()
                // The room was decided just above, so the player need not measure it again.
                context.startActivity(
                    PlayerActivity.intent(context, item, chatTitle)
                        .putExtra(PlayerActivity.EXTRA_ROOM_DECIDED, item.fileId),
                )
            }

            when (plan) {
                is CacheShelf.Plan.Proceed -> start()

                is CacheShelf.Plan.NotEnoughSpace -> {
                    val refusal = RoomPrompt(
                        item = item,
                        reclaimBytes = plan.reclaimBytes,
                        shortfallBytes = plan.shortfallBytes,
                        chatTitle = chatTitle,
                        lowStorageOff = !kept && !lowStorageOn,
                    )
                    // Asked every time a video would fit in Low storage mode, before the refusal.
                    if (plan.fitsInLowStorage) {
                        lowStorageAsk = LowStorageOffers.Ask(item, chatTitle, keep = false) to refusal
                    } else {
                        roomPrompt = refusal
                    }
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
        // and press again, so a second press while one is in flight is ignored. So is one after the
        // player was asked to open and before it covers the screen: a video's page stays open
        // under the player now, and its Play pressed twice opened two (PlayPress.opened lasts
        // until this activity stops or comes back).
        if ((preparing || com.tmplayer.ui.components.PlayPress.opened) && !confirmed) return
        preparing = true
        // Answered at once: the page's Play pill spins and, past a beat, a "Starting" pill says
        // which video is on its way (PlayPress, StartingStatus). It stays until the shell leaves
        // the screen for the player, or goes at once when nothing is going to open.
        val press = com.tmplayer.ui.components.PlayPress.begin(item.title)
        scope.launch {
            try {
                playNow(item, confirmed, chatTitle)
            } finally {
                preparing = false
                if (!com.tmplayer.ui.components.PlayPress.opened) com.tmplayer.ui.components.PlayPress.end(press)
            }
        }
    }

    /** Opening a chat is what makes it the one to reopen on the next launch. */
    fun openChat(chat: ChatSummary) {
        screen = Screen.Media(chat)
        scope.launch { settings.rememberChatOpened(chat.id) }
    }

    /**
     * Saved Messages is one chat, so asking for it opens that chat's videos at once rather than
     * a list holding it alone, which took a second press. Back returns to where it was asked
     * from. [orList] is the old way, for when the chat cannot be found yet.
     */
    fun openSavedMessages(orList: () -> Unit) {
        chats.firstOrNull { it.kind == com.tmplayer.data.ChatKind.Saved }?.let { openChat(it); return }
        scope.launch { com.tmplayer.data.savedMessagesNow()?.let { openChat(it) } ?: orList() }
    }
    val savedSection = BrowseSection.of(BrowseTab.Saved)

    /**
     * Marks a video watched by hand, or takes the mark off. Marking also forgets the saved
     * position, which is what takes it out of Continue watching.
     */
    fun setWatched(item: MediaItem, chatTitle: String, watched: Boolean) {
        App.backgroundScope.launch {
            runCatching {
                if (watched) {
                    // Every copy leaves Watch later with it.
                    watchedStore.markWatched(
                        WatchedRecord.of(item, chatTitle, System.currentTimeMillis(), manual = true),
                        copies = item.versions,
                    )
                    // Every copy of it in Continue watching, as Remove from Continue watching
                    // does: the card stands for all of them (HomeRows.foldCopies), and clearing
                    // only this one put the copy behind it straight back in its place.
                    val inContinue = settings.continueWatching.first()
                        .firstOrNull { it.chatId == item.chatId && it.messageId == item.messageId }
                    if (inContinue != null) {
                        settings.clearResumeCopies(inContinue)
                    } else {
                        settings.clearResumePosition(item.chatId, item.messageId)
                    }
                } else {
                    // Every copy's mark, or one still marked keeps the folded card ticked.
                    watchedStore.markUnwatched(item.chatId, item.messageId, copies = item.versions)
                }
            }
        }
        // Not said: the tick on the card, or the card leaving its row, shows it.
    }

    /** [openStored] once its guard is up. */
    suspend fun openStoredNow(stored: MediaItem, chatTitle: String) {
        settings.rememberChatOpened(stored.chatId)
        // In the Downloads folder: it plays from there, and asking Telegram first would only
        // be a wait, and offline a long one.
        if (LocalDownloads.fileFor(settings, stored.chatId, stored.messageId) != null) {
            play(stored, chatTitle = chatTitle)
            return
        }
        // The stored row carries a file id from whichever TDLib instance wrote it, which is
        // not a number this one can necessarily use. Asking Telegram for the message again
        // returns a current id and tells TDLib where the file came from, without which it
        // will not download it. The stored row is the fallback, and it is enough whenever the
        // video is already on the device. Gone for good (HX21): said here, and the lists let it
        // go ([com.tmplayer.data.GoneVideos]); not answered in time: the stored row plays, and
        // the player asks again itself, rather than the press spinning until Telegram answers.
        when (val lookup = Td.lookupMedia(stored.chatId, stored.messageId)) {
            is com.tmplayer.data.MessageLookup.Found -> play(lookup.item, chatTitle = chatTitle)
            is com.tmplayer.data.MessageLookup.Gone -> toast(com.tmplayer.data.GoneVideos.openMessage(lookup.reason))
            com.tmplayer.data.MessageLookup.Unreachable -> play(stored, chatTitle = chatTitle)
        }
    }

    /**
     * Plays a video from a list kept on this device (Continue watching, Previously watched), which
     * counts as a visit to its chat for the next launch.
     */
    fun openStored(stored: MediaItem, chatTitle: String) {
        // The same guard [play] keeps, over the wait for Telegram in [openStoredNow]: [play] only starts
        // counting once that answer is back, so two quick presses on a Continue watching card
        // both got through and opened the player twice.
        if (openingStored || preparing || com.tmplayer.ui.components.PlayPress.opened) return
        openingStored = true
        // The wait for Telegram's fresh copy is part of the press too.
        val press = com.tmplayer.ui.components.PlayPress.begin(stored.title)
        scope.launch {
            try {
                openStoredNow(stored, chatTitle)
            } finally {
                openingStored = false
                // play() took the press over with its own; this one only ends a lookup that failed.
                com.tmplayer.ui.components.PlayPress.end(press)
            }
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
        // The loader after the system splash, over everything while Telegram is being reached,
        // then fading out over the sign in pane or the home screen, whichever is drawn under it.
        // Once Telegram is reached, the loader waits on for Home's first whole screen (rows and the
        // carousel's first picture), at most HOLD_MAX_MS, so Home appears finished.
        var homeHoldOver by remember { mutableStateOf(false) }
        // The QR code stays up for a moment after it is scanned, marked "Scanned" (rememberScanHold).
        val shownAuth = com.tmplayer.ui.auth.rememberScanHold(auth)
        val reached = shownAuth is AuthState.Ready
        LaunchedEffect(reached) {
            if (!reached) return@LaunchedEffect
            kotlinx.coroutines.delay(com.tmplayer.ui.browse.HomeFirstPaint.HOLD_MAX_MS)
            homeHoldOver = true
        }
        val holdForHome = reached && !setupPending && !(appLock?.onOpen == true && !appUnlocked) && !homeHoldOver && !com.tmplayer.ui.browse.HomeFirstPaint.ready
        com.tmplayer.ui.components.BrandLoaderHandOff(
            label = com.tmplayer.ui.auth.loaderLabel(shownAuth, LocalStrings.current)
                ?: if (holdForHome) LocalStrings.current.loginGettingReady else null,
            modifier = Modifier.zIndex(1f),
        ) {
            // What it is doing now, and past the long wait, why it may be stuck with Retry, a
            // proxy and the logs: the desktop's help, on the phone and the TV too.
            com.tmplayer.ui.connect.ConnectingHelp(
                onOpenProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                sendLogs = { com.tmplayer.ui.settings.SendLogsButton(Modifier.padding(top = 8.dp)) },
            )
        }
        if (shownAuth !is AuthState.Ready) {
            // Signing out drops straight back to the login screen, so forget where we were.
            LaunchedEffect(Unit) {
                screen = Screen.Chats
                autoOpened = false
                autoOpenDecided = false
            }
            // The sign in screens, with the line Telegram's API terms ask a third party client to
            // show before sign in under them (the tour that carried it before sign in is gone,
            // CP67). Put away while the keyboard is up, where the room is the field's.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                val keyboard = WindowInsets.isImeVisible
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(if (keyboard) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars)),
                ) {
                    LoginScreen(
                        state = shownAuth,
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
                        onRefreshQr = {
                            signInError = null
                            scope.launch { Td.refreshQrCode() }
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
                        onSubmitEmailAddress = { address ->
                            signInError = null
                            scope.launch { signInError = Td.submitEmailAddress(address) }
                        },
                        onSubmitEmailCode = { code ->
                            signInError = null
                            scope.launch { signInError = Td.submitEmailCode(code) }
                        },
                        onRun = { block ->
                            signInError = null
                            scope.launch { signInError = block() }
                        },
                    )
                }
                if (!keyboard) {
                    // Under every sign in screen: the line Telegram's terms ask for, and the proxy
                    // settings, for a network where Telegram is only reached through one. Before
                    // this the proxy was offered only once the line was already known to be down.
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 24.dp)
                            .padding(top = 6.dp, bottom = if (FormFactor.isTv(context)) Tv.SafeV else 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val openProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true }
                        if (FormFactor.isTv(context)) {
                            // A real button on a remote, one press down from the form's own.
                            com.tmplayer.ui.components.TmSecondaryButton(onClick = openProxies) {
                                androidx.tv.material3.Text(LocalStrings.current.loginProxySettings)
                            }
                        } else {
                            androidx.compose.material3.TextButton(onClick = openProxies) {
                                androidx.compose.material3.Text(LocalStrings.current.loginProxySettings)
                            }
                        }
                        androidx.compose.material3.Text(
                            L.authUnofficial,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = Tone.muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            ConnectionStatus(
                notice = connectionNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Above the loader, which is where being offline matters most.
                    .zIndex(2f)
                    .padding(bottom = 20.dp),
            )
            // A sign in pane with no line to Telegram yet: say so before a number goes nowhere.
            if (com.tmplayer.ui.connect.signInBannerShown()) {
                com.tmplayer.ui.connect.ConnectBanner(
                    onOpenProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
            com.tmplayer.ui.connect.ProxyScreenHost(Modifier.zIndex(3f))
            // The crash question is asked on the sign in screens too: the shell's copy below is
            // never reached from here, and an ANR or native crash before sign in would otherwise
            // wait for a sign in that may never come.
            CrashReports.pending.collectAsStateWithLifecycle().value?.let { CrashQuestion(it, settings) }
            return@Box
        }

        // The setup after sign in (CP67), in place of the shell until it is done or skipped. It
        // waits for the chat list, so the shell is ready under it when it ends; a list slow to
        // come is not waited on for ever.
        if (setupPending) {
            var waitedOut by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(Setup.WAIT_FOR_CHATS_MS)
                waitedOut = true
            }
            val chatsLoaded = chatsState is UiState.Content
            // What this device is short of, and which settings would help (CP68): the Device step.
            val fit = com.tmplayer.ui.onboarding.rememberDeviceFit()
            val light by settings.lightSettings.collectAsStateWithLifecycle(initialValue = null)
            val seekThumbnails = com.tmplayer.ui.onboarding.rememberSeekThumbnailsShown()
            if (light == null ||
                Setup.decide(pending = true, chatsLoaded = chatsLoaded, waitedOut = waitedOut) == Setup.Decision.Wait
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = Tone.accent) }
                return@Box
            }
            val finish: () -> Unit = { scope.launch { runCatching { settings.markSetupDone() } } }
            // Read once, as the setup opens: applying them must not take the step away under the viewer.
            val recommendations = remember {
                com.tmplayer.data.DeviceFitRules.recommendations(fit, light!!, seekThumbnails)
            }
            // The same steps Settings' Setup choices lists (Setup.stepsFor).
            val steps = remember {
                Setup.stepsFor(
                    FormFactor.form(context),
                    posters = com.tmplayer.online.OnlineMetadata.current != null,
                    recommendations = recommendations,
                )
            }
            // The choices, then the quick tour (CP69); with no choices to make, the tour alone.
            SetupAndTour(
                settings,
                steps,
                onDone = finish,
                fit = fit,
                recommendations = recommendations,
                // CP102f: the crash report question, only in a build with somewhere to send one.
                crashReporting = remember {
                    com.tmplayer.ui.onboarding.CrashReporting(CrashReports.available) { on ->
                        if (on) CrashReports.start(context, true) else CrashReports.stop()
                    }
                },
            )
            return@Box
        }

        // The quick tour on its own (CP69): taken from the offer below, or opened from Settings.
        if (tourState == SettingsStore.TourState.Pending) {
            TourOnly(onDone = { scope.launch { runCatching { settings.markTourDone() } } })
            return@Box
        }

        // "Ask when the app opens" (CP57): the pad instead of the app, not over it, so a remote
        // cannot walk out of it into the screen behind. Back leaves the app.
        if (appLock?.onOpen == true && !appUnlocked) {
            val activity = LocalActivity.current
            PinPad(
                settings = settings,
                purpose = PinPurpose.Check,
                title = s.lockEnterForApp,
                brand = true,
                onDone = {
                    AppLockSession.unlock()
                    // The PIN has just been given; Settings need not ask for it again this visit.
                    settingsUnlocked = true
                },
                onCancel = { activity?.finish() },
            )
            return@Box
        }

        // Back to browsing locks Settings again.
        LaunchedEffect(screen) {
            if (screen is Screen.Chats || screen is Screen.Media) settingsUnlocked = false
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

        // A new videos notification pressed: the chat it named, once the list has it, in place of
        // whatever the launch would have opened. A chat the kids lock hides is not in the list.
        val requestedChat by MainActivity.requestedChat.collectAsStateWithLifecycle()
        LaunchedEffect(requestedChat, chatsState) {
            val wanted = requestedChat ?: return@LaunchedEffect
            if (wanted == 0L) {
                screen = Screen.Chats
            } else {
                if (chatsState is UiState.Loading) return@LaunchedEffect
                chats.firstOrNull { it.id == wanted }?.let { screen = Screen.Media(it) }
            }
            autoOpened = true
            autoOpenDecided = true
            MainActivity.requestedChat.value = null
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

        // Home's rows, built here rather than inside Home so the TV home screen channels follow
        // them whichever screen is open. Recently added is the featured carousel at the top of
        // Home, on a phone and a television, so it is worth having with one starred chat: it does
        // not repeat that chat's own row as a row of its own would.
        // Null until the setting is read, so no row that is off is fetched for the first frame.
        val homeLayout by remember { settings.homeLayout }.collectAsStateWithLifecycle(initialValue = null)
        val homeRows = rememberHomeRows(
            homeViewModel, chats, favorites, continueWatching, watchLater, watchedVideos.values,
            recentMinStarred = 1,
            layout = homeLayout,
        )
        val tvChannelsOn by settings.tvChannels.collectAsStateWithLifecycle(initialValue = null)
        TvHomeChannels(
            model = homeViewModel,
            rows = homeRows,
            chats = chats,
            watchProgress = watchProgress,
            signedIn = auth is AuthState.Ready,
            enabled = tvChannelsOn,
        )
        // A show's card on the home screen: Home, where its page opens over the rows (HomePane
        // reads the ask). Given up after a minute, so a show that never turns up in the rows does
        // not open by surprise later.
        val showAsk by HomeLaunch.show.collectAsStateWithLifecycle()
        LaunchedEffect(showAsk) {
            val ask = showAsk ?: return@LaunchedEffect
            autoOpened = true
            screen = Screen.Chats
            pickedTabKey = BrowseSection.encode(BrowseSection.of(BrowseTab.Home))
            delay(HomeLaunch.WAIT_MS)
            HomeLaunch.show.compareAndSet(ask, null)
        }

        // Settings and Downloads, from the picture's popover (the television's account menu), the
        // chat list's menus and the notification. Back from either goes to the page they were opened
        // from; moving between the two by the popover is a step sideways, so Back skips the other
        // one rather than bouncing between them (TouchRoutes).
        val openSettings: () -> Unit = {
            if (screen !is Screen.Settings) {
                settingsCameFrom = TouchRoutes.backTarget(
                    to = Screen.Settings,
                    from = screen,
                    fromBack = if (screen is Screen.Downloads) downloadsCameFrom else null,
                    toBack = settingsCameFrom,
                    lateral = { it is Screen.Settings || it is Screen.Downloads },
                    home = Screen.Chats,
                )
                settingsPage = null
                screen = Screen.Settings
            }
        }
        val openDownloads: () -> Unit = {
            if (screen !is Screen.Downloads) {
                downloadsCameFrom = TouchRoutes.backTarget(
                    to = Screen.Downloads,
                    from = screen,
                    fromBack = if (screen is Screen.Settings) settingsCameFrom else null,
                    toBack = downloadsCameFrom,
                    lateral = { it is Screen.Settings || it is Screen.Downloads },
                    home = Screen.Chats,
                )
            }
            downloadsOnCached = false
            downloadsFreeUp = 0
            screen = Screen.Downloads
        }
        val reducedMotion = rememberReducedMotion() || Motion.reduced
        // A page of the browser, from Settings or Downloads: the browser comes back on it.
        val browseTo: (BrowseSection) -> Unit = { section ->
            val show = {
                pickedTabKey = BrowseSection.encode(section)
                screen = Screen.Chats
            }
            if (section == savedSection) openSavedMessages(show) else show()
        }
        val touchNav = TouchNav(
            account = accountHeader,
            favoriteCount = favorites.size,
            watchingCount = continueWatching.size,
            watchedCount = watchedVideos.size,
            downloadCount = activeDownloadCount,
            updateVersion = offeredUpdate,
            onHome = { browseTo(BrowseSection.of(BrowseTab.Home)) },
            onChats = {
                val back = BrowseSection.decode(lastChatsKey)
                browseTo(back ?: BrowseSection.of(BrowseTab.Chats))
            },
            onSearch = {
                screen = Screen.Chats
                touchChrome.searchAsked = true
            },
            onSettings = openSettings,
            onFavourites = { browseTo(BrowseSection.of(BrowseTab.Favorites)) },
            onDownloads = openDownloads,
            onHistory = { tab ->
                scope.launch { settings.setHistoryTab(tab.name) }
                browseTo(BrowseSection.of(BrowseTab.History))
            },
            onUpdate = { showUpdate = true },
        )

        // The television's top bar over Downloads, as it is over the browser's pages. Settings has
        // none: it opens from the account's menu as a screen of its own, as About and the PIN pad do.
        val tvBar = FormFactor.isTv(context)
        val tvBarShown = tvBar && screen is Screen.Downloads
        val tvBarFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // Back from Settings to Downloads puts the remote back on the bar's picture, which opened it.
        var tvBarHold by remember { mutableStateOf(0) }
        var tvBarFocused by remember { mutableStateOf(false) }
        val screens: @Composable (Screen) -> Unit = screens@{ current ->
        when (current) {
            is Screen.Chats -> {
                BrowseScreen(
                    // Held on its own loading state until the jump has been decided, so the
                    // launch looks like one screen loading rather than two screens fighting.
                    state = if (autoOpenDecided) chatsState else UiState.Loading(),
                    account = accountHeader,
                    favorites = favorites,
                    continueWatching = continueWatching,
                    onRetry = chatsViewModel::retry,
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
                    onOpenSettings = openSettings,
                    onOpenDownloads = openDownloads,
                    returnToRail = railReturn,
                    tvNav = if (tvBar) touchNav else null,
                    onReturnedToRail = { railReturn = null },
                    downloadCount = activeDownloadCount,
                    updateVersion = offeredUpdate,
                    onUpdate = { showUpdate = true },
                    // Each of these is said only when Telegram refuses it. Done, the row says so
                    // itself: the pin, the bell, the badge clearing, or the row leaving the list.
                    onTogglePinned = { chat -> chatsViewModel.setPinned(chat, !chat.isPinned) { toast(it) } },
                    onToggleArchived = { chat -> chatsViewModel.setArchived(chat, !chat.isArchived) { toast(it) } },
                    onToggleMuted = { chat -> chatsViewModel.setMuted(chat, !chat.isMuted) { toast(it) } },
                    onMarkRead = { chat -> chatsViewModel.markRead(chat) { toast(it) } },
                    onToggleFavorite = { chat ->
                        scope.launch {
                            // Not said either way: the star fills or empties, and in the
                            // Favourites tab the row leaves.
                            val nowFavorite = settings.toggleFavorite(chat.id)
                            if (nowFavorite) offerNewVideos()
                        }
                    },
                    onRestartMedia = { record ->
                        scope.launch {
                            settings.clearResumePosition(record.chatId, record.messageId)
                            resumeMedia(record)
                        }
                    },
                    // Remove from history, Clear history and select mode are BrowseScreen's own
                    // (HistoryRemoval): Undo on a touch screen, asked first under a remote.
                    // The clears below were asked first, and the emptied row or tab is their answer.
                    onClearFavorites = { scope.launch { settings.clearFavorites(allowedChats) } },
                    onClearHistory = { scope.launch { settings.clearWatchHistory() } },
                    onMarkMediaWatched = { record ->
                        setWatched(record.toMediaItem(), record.chatTitle, watched = true)
                    },
                    // One card for the copies of one video, as Continue watching has; unmarking
                    // it unmarks them all.
                    watchedHistory = remember(watchedHistory) { HomeRows.foldWatched(watchedHistory) },
                    onOpenWatched = { record -> openStored(record.toMediaItem(), record.chatTitle) },
                    onMarkUnwatched = { record ->
                        setWatched(HomeRows.watchedItem(record, watchedHistory), record.chatTitle, watched = false)
                    },
                    onClearWatched = { scope.launch { runCatching { watchedStore.clear() } } },
                    watchLater = watchLater,
                    onOpenWatchLater = { record -> openStored(record.toMediaItem(), record.chatTitle) },
                    // The card leaving the row is the answer.
                    onRemoveWatchLater = { record ->
                        App.backgroundScope.launch {
                            runCatching { watchedStore.watchLater.remove(record) }
                        }
                    },
                    // Marked "Opens on launch" only while Carry on from the last chat is on.
                    launchChatId = if (openLastChat) lastChatId else 0L,
                    picked = pickedTab,
                    onPickTab = { section ->
                        if (section == savedSection) openSavedMessages { pickedTabKey = BrowseSection.encode(section) }
                        else pickedTabKey = BrowseSection.encode(section)
                    },
                    folders = folders,
                    homeRows = homeRows,
                    homeRowsReady = homeViewModel.rowsSettled,
                    homeLayout = homeLayout ?: com.tmplayer.data.HomeLayout(),
                    onOpenHomeSettings = {
                        openSettings()
                        settingsPage = SettingsPage.Home
                    },
                    homeArt = homeViewModel.art.collectAsStateWithLifecycle().value,
                    homeWatch = homeWatch,
                    onHomeRowShown = { row ->
                        when (row) {
                            is HomeRow.Chat -> homeViewModel.request(row.chatId)
                            is HomeRow.Recent -> homeViewModel.requestRecent()
                            is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.NewEpisodes -> Unit
                        }
                    },
                    onHomeArtWanted = homeViewModel::requestArt,
                    homeFeedOf = homeViewModel::seeAll,
                    chatPaging = chatsViewModel.paging.collectAsStateWithLifecycle().value,
                    onMoreChats = chatsViewModel::loadMoreChats,
                    onHomeLaterArtWanted = homeViewModel::requestArt,
                    onPlayMedia = { item, chatTitle -> play(item, chatTitle = chatTitle) },
                    homeRefreshing = homeViewModel.refreshing.collectAsStateWithLifecycle().value,
                    onRefreshHome = {
                        homeViewModel.refreshInPlace()
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
                    touchTimes = touchTimes,
                    onSetWatched = { item, watched -> setWatched(item, current.chat.title, watched) },
                    onToggleFavorite = {
                        scope.launch {
                            // The star on the chat's own bar fills in or empties: that is the answer.
                            if (settings.toggleFavorite(current.chat.id)) offerNewVideos()
                        }
                    },
                    onPlay = { play(it, chatTitle = current.chat.title) },
                    telegramConnected = telegramConnected,
                    offline = networkStatus == NetworkStatus.Offline && !telegramConnected,
                    onOfflineAction = toast,
                    connectionNotice = connectionNotice,
                )
            }

            is Screen.Downloads -> {
                // Back to whatever raised it. Reached from the drawer that is the chat list, and
                // reached from "Not enough space" it is the chat the video was in, which is where
                // somebody who has just freed space wants to be.
                val leaveDownloads = {
                    if (downloadsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.Downloads
                    screen = downloadsCameFrom
                }
                // On a television one Back leaves too, the remote landing on the account's picture
                // whose menu opened Downloads, as Back from Settings does: going by way of the bar
                // took two presses (2026-10-21).
                BackHandler(
                    onBack = {
                        if (tvBarShown) {
                            if (downloadsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.Profile
                            screen = downloadsCameFrom
                        } else {
                            leaveDownloads()
                        }
                    },
                )
                // Keyed on the ask, so a second "Not enough space" while the page is up opens the
                // sheet again rather than finding it already spent.
                key(downloadsFreeUp) {
                    DownloadsScreen(
                        onPlay = { record -> resumeMedia(record) },
                        onBack = leaveDownloads,
                        openOnCached = downloadsOnCached,
                        openFreeUp = downloadsFreeUp > 0,
                        askedForRoom = downloadsFreeUp > 0 && downloadsAskedForRoom,
                        // An empty Downloads page: to the chats, where Download is.
                        onBrowse = {
                            pickedTabKey = BrowseSection.encode(BrowseSection.of(BrowseTab.Chats))
                            screen = Screen.Chats
                        },
                    )
                }
            }

            is Screen.Settings -> {
                val leaveSettings = {
                    // Only if it has had time to go stale. Settings cannot change the chat list,
                    // so a full re-sync on the way out would be work nothing asked for.
                    chatsViewModel.refreshIfStale()
                    settingsPage = null
                    // On a television the remote goes back to the account's picture, whose menu
                    // opened Settings.
                    if (settingsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.Profile
                    if (tvBar && settingsCameFrom is Screen.Downloads) tvBarHold++
                    screen = settingsCameFrom
                }
                BackHandler(onBack = leaveSettings)
                val lock = appLock
                // With no PIN there is nothing to ask, and a PIN set from inside Settings must not
                // lock the viewer out of the page they set it on.
                LaunchedEffect(lock?.pinSet) { if (lock?.pinSet == false) settingsUnlocked = true }
                // No floating bar: Settings is a screen pushed from the picture's popover, with
                // its own back arrow, as a chat is.
                TouchPage(null)
                if (lock == null) return@screens
                if (lock.pinSet && !settingsUnlocked) {
                    PinPad(
                        settings = settings,
                        purpose = PinPurpose.Check,
                        title = s.lockEnterForSettings,
                        onDone = { settingsUnlocked = true },
                        onCancel = leaveSettings,
                    )
                    return@screens
                }
                SettingsScreen(
                    chats = chats,
                    account = accountHeader,
                    onLoggedOut = {
                        settingsPage = null
                        screen = Screen.Chats
                    },
                    onBack = leaveSettings,
                    // Storage's Free up space: the Downloads page with the review up, Back to Settings.
                    onFreeUpSpace = {
                        downloadsCameFrom = Screen.Settings
                        downloadsOnCached = false
                        downloadsFreeUp += 1
                        downloadsAskedForRoom = false
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
        }

        if (FormFactor.isTv(context)) {
            val tvPageFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            com.tmplayer.ui.browse.TvBarFrame(
                shown = tvBarShown,
                holdBar = tvBarHold,
                barFocus = tvBarFocus,
                contentFocus = tvPageFocus,
                bar = {
                    // A tab picked here lands in the browser with the remote still on the bar.
                    val stayOnBar = { railReturn = com.tmplayer.ui.browse.RailReturn.Settings }
                    com.tmplayer.ui.browse.TvTopBar(
                        nav = touchNav,
                        current = null,
                        inProfile = screen is Screen.Downloads,
                        searchOpen = false,
                        onHome = { stayOnBar(); touchNav.onHome() },
                        onChats = { stayOnBar(); touchNav.onChats() },
                        onSearch = {
                            com.tmplayer.ui.browse.TvBarSignals.searchAsked.value = true
                            screen = Screen.Chats
                        },
                        beforeProfile = stayOnBar,
                        onRefresh = null,
                        currentFocus = tvBarFocus,
                        contentFocus = tvPageFocus,
                        overArt = false,
                        modifier = Modifier.onFocusChanged { tvBarFocused = it.hasFocus },
                    )
                },
            ) { screens(screen) }
        } else {
            // The phone: one floating bar over every page it belongs to, and the pages changing
            // under it the way Home and Chats do (see PhoneBrowse), each keeping its scroll.
            val holder = rememberSaveableStateHolder()
            // The end a pushed screen comes in from: the right, or the left in a right to left language.
            val endSign = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
            TouchNavHost(nav = touchNav, chrome = touchChrome, pageKey = TouchScreens.key(screen)) {
                AnimatedContent(
                    targetState = screen,
                    contentKey = { TouchScreens.key(it) },
                    transitionSpec = {
                        val from = TouchScreens.order(initialState, pickedTab)
                        val to = TouchScreens.order(targetState, pickedTab)
                        val push = TouchScreens.isPushed(targetState) && initialState !is Screen.About
                        val pop = TouchScreens.isPushed(initialState) && targetState !is Screen.About
                        when {
                            // Settings, pushed over the page that opened it and popped back off:
                            // in from the end and out towards it, the way a pushed screen moves.
                            (push || pop) && reducedMotion -> fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast))
                            push || pop -> {
                                val sign = if (push) endSign else -endSign
                                (
                                    fadeIn(Motion.standard(Motion.Normal)) +
                                        slideInHorizontally(Motion.standard(Motion.Normal)) { width -> sign * width / TouchScreens.PUSH_PART }
                                    ) togetherWith (
                                    fadeOut(Motion.standard(Motion.Fast)) +
                                        slideOutHorizontally(Motion.standard(Motion.Normal)) { width -> -sign * width / TouchScreens.PUSH_PART }
                                    )
                            }
                            // A chat and About open as they always have, at once.
                            from == null || to == null -> EnterTransition.None togetherWith ExitTransition.None
                            reducedMotion -> fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast))
                            else -> (
                                fadeIn(Motion.standard(Motion.Normal)) +
                                    slideInHorizontally(Motion.standard(Motion.Normal)) { width -> (if (to >= from) width else -width) / TouchScreens.SLIDE_PART }
                                ) togetherWith fadeOut(Motion.standard(Motion.Fast))
                        }
                    },
                    label = "phoneScreen",
                ) { shown ->
                    val key = TouchScreens.key(shown)
                    holder.SaveableStateProvider(key) {
                        CompositionLocalProvider(LocalTouchPageKey provides key) { screens(shown) }
                    }
                }
            }
        }

        // Telegram links (CP79): a post, an invite or a public chat, from Search, a pinned post or
        // another app; and videos found off a chat's grid (comments, round videos, CP81).
        com.tmplayer.ui.browse.TelegramLinkHost(
            onPlay = { item, title -> play(item, chatTitle = title) },
            onOpenChat = { chat -> openChat(chat) },
        )

        lowStorageAsk?.let { (ask, refusal) ->
            // An invitation, so focus starts on "Switch and play": it does what the viewer pressed.
            TvConfirm(
                title = L.settingsLowStorage,
                message = if (ask.keep) {
                    L.mainLowStorageAskKeep(mode = L.settingsLowStorage)
                } else {
                    L.mainLowStorageAskPlay(mode = L.settingsLowStorage)
                },
                detail = L.settingsLowStorageDetail,
                confirmLabel = L.mainLowStorageSwitchPlay,
                cancelLabel = L.mainLowStorageNotNow,
                destructive = false,
                focusConfirm = true,
                onConfirm = {
                    lowStorageAsk = null
                    scope.launch {
                        runCatching { settings.setLowStorageMode(true) }
                        play(ask.item, chatTitle = ask.chatTitle)
                    }
                },
                // "Not now" is today's answer: the refusal for a video, the message already shown
                // for a download.
                onDismiss = {
                    lowStorageAsk = null
                    roomPrompt = refusal
                },
            )
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
                    (
                        if (pending.reclaimBytes > 0) {
                            L.mainNoRoomReclaim(size = Translator.messages.formatter.bytes(pending.reclaimBytes))
                        } else {
                            L.mainNoRoomFree(device = device)
                        }
                        ) +
                    // A stream, with the mode off: the one answer that deletes nothing is named.
                    if (pending.lowStorageOff) " " + L.mainNoRoomLowStorage(mode = L.settingsLowStorage, device = device) else "",
                detail = L.mainNoRoomDetail,
                // Straight to what can go, with the sheet that says what each line frees already up
                // (user, 2026-10-18), rather than to a tab to work it out from.
                confirmLabel = L.downloadsFreeUpSpace,
                destructive = false,
                focusConfirm = true,
                onConfirm = {
                    roomPrompt = null
                    if (screen !is Screen.Downloads) downloadsCameFrom = screen
                    downloadsOnCached = false
                    downloadsFreeUp += 1
                    downloadsAskedForRoom = true
                    screen = Screen.Downloads
                },
                onDismiss = { roomPrompt = null },
            )
        }

        if (showUpdate) {
            UpdateDialog(onDismiss = { showUpdate = false; Updates.dismiss() })
        }

        val crashQuestion by CrashReports.pending.collectAsStateWithLifecycle()
        val crashPending = crashQuestion != null
        crashQuestion?.let { question -> if (!showUpdate) CrashQuestion(question, settings) }

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
        // The one-time hint (CP68): the player dropped frames or the system ran short of memory.
        // Once per install, in the shell, and only with something to offer.
        DeviceStruggleHint(settings, inShell = inShell && !showUpdate && noticeLanguage == null)
        // Only once the setup is done: inShell waits for it.
        if (FormFactor.isTv(context) && inShell && screen is Screen.Chats) {
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
        // The quick tour, offered once to an install that was signed in before it existed (CP69): a
        // fresh sign in has it after the setup. Behind every other card, and on the chat list only.
        if (tourState == SettingsStore.TourState.Never && inShell && screen is Screen.Chats && !showUpdate &&
            noticeLanguage == null && !whatsNew.showing && supportRung == 0 && !crashPending
        ) {
            TvConfirm(
                title = s.tourOfferTitle,
                message = s.tourOfferBody,
                confirmLabel = s.tourOfferTake,
                cancelLabel = s.deviceNotNow,
                destructive = false,
                focusConfirm = true,
                onConfirm = { scope.launch { runCatching { settings.openTour() } } },
                onDismiss = { scope.launch { runCatching { settings.markTourDone() } } },
            )
        }
        if (changelogQr) LinkQrDialog(WhatsNew.CHANGELOG, s.settingsQrChangelogPage, onClose = { changelogQr = false })

        if (newVideoOffer) {
            TvConfirm(
                title = s.settingsNewVideos,
                message = s.notifyNewVideosOffer,
                detail = s.settingsNewVideosDetail,
                confirmLabel = s.notifyNewVideosTurnOn,
                cancelLabel = s.deviceNotNow,
                destructive = false,
                icon = com.tmplayer.ui.icons.TmIcons.Bell,
                onConfirm = {
                    newVideoOffer = false
                    // Android 13 asks for the permission here, at the switch, and nowhere else.
                    com.tmplayer.ui.browse.askForNotifications(context)
                    App.backgroundScope.launch {
                        runCatching {
                            settings.setNewVideoAlerts(true)
                            com.tmplayer.data.BackgroundChecks.schedule(context.applicationContext)
                        }
                    }
                },
                onDismiss = {
                    newVideoOffer = false
                    scope.launch { runCatching { settings.markNewVideoOffered() } }
                },
            )
        }
        if (supportRung > 0 && noticeLanguage == null && (screen is Screen.Chats || screen is Screen.Downloads) && !showUpdate) {
            CoffeeDialog(
                counters = supportCounters,
                asking = true,
                onLater = { supportRung = 0 },
                onNever = {
                    supportRung = 0
                    if (SupportReminder.forcedRung == 0) scope.launch { runCatching { settings.markSupportNever() } }
                },
            )
        }
        supporting?.let { from -> SupportDialog(from = from, onClose = { supporting = null }) }

        ConnectionStatus(
            notice = connectionNotice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
        // Over the connection notice's place, a little higher when both are up.
        com.tmplayer.ui.components.StartingStatus(
            Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (connectionNotice == ConnectionNotice.Hidden) 20.dp else 76.dp, start = 16.dp, end = 16.dp),
        )
        // The network is up and Telegram has not answered for a while: blocked, most likely,
        // which the offline pill above never says, since the device is not offline.
        val unreachable by Td.unreachable.collectAsStateWithLifecycle()
        if (unreachable) {
            com.tmplayer.ui.connect.ConnectBanner(
                onOpenProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
        com.tmplayer.ui.connect.ProxyScreenHost()
    }
}

private const val OFFLINE_SETTLE_MS = 750L

/** How long a lost line may take to come back before the "Connecting" pill shows. */
private const val CONNECTING_SETTLE_MS = 2_500L

/** How long the chat list is up before the support card may slide in. */
private const val SUPPORT_CARD_DELAY_MS = 4_000L

/** How long the remote rests after coming back to Home before its stale rows are refreshed. */
private const val RETURN_REFRESH_QUIET_MS = 3_000L

/** The longest that refresh waits for the remote to rest: a viewer browsing on gets it anyway. */
private const val RETURN_REFRESH_MAX_WAIT_MS = 20_000L

/**
 * The hint after the device struggled (CP68, [com.tmplayer.data.DeviceFitHint]): a bar at the foot
 * of a phone's screen, a dialog on a television with Apply focused. Offered once per install, and
 * only while something recommended is still off; a stutter counts as short of memory whatever the
 * device's figures said.
 */
/**
 * The question about a crash left from an earlier run, or the app having stopped responding.
 *
 * Asked once per crash, and only while the switch in Settings is off: with it on, the report went
 * without asking. Back or a tap outside counts as Don't send, so a viewer who waves it away is not
 * asked again about the same crash. Drawn in the shell for an ordinary crash, and on its own
 * before the shell for a start-up loop ([StartupCrashQuestion]). A native crash is a crash to the
 * viewer, and is asked about in the same words.
 */
@Composable
private fun CrashQuestion(question: CrashReports.Question, settings: SettingsStore) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var always by remember { mutableStateOf(false) }
    val stalled = question == CrashReports.Question.NotResponding
    TvConfirm(
        title = if (stalled) s.mainCrashAnrTitle else s.mainCrashTitle,
        message = if (stalled) s.mainCrashAnrMessage else s.mainCrashMessage,
        detail = s.mainCrashDetail,
        confirmLabel = s.mainCrashSend,
        cancelLabel = s.mainCrashDontSend,
        destructive = false,
        focusConfirm = true,
        extra = {
            TickRow(s.mainCrashAlways, always) { always = !always }
            // The logs as well, sent the viewer's own way: the crash report carries the
            // stack, these carry what led up to it.
            com.tmplayer.ui.settings.SendLogsButton()
        },
        onConfirm = {
            val keep = always
            com.tmplayer.platform.Background.scope.launch(Dispatchers.IO) {
                if (keep) {
                    runCatching { settings.setCrashReports(true) }
                    runCatching { CrashReports.start(context, true) }
                }
                runCatching { CrashReports.sendPending(context) }
            }
        },
        onDismiss = { CrashReports.discardPending() },
    )
}

/**
 * What MainActivity draws instead of its shell while [CrashReports.holdShell] holds it: the
 * window's ground and, once the review has read the switch, the crash question on its own. Nothing
 * of the shell is built behind it, since building the shell is what crashed (CP102c). After the
 * answer, or once a report sent with the switch on has been flushed, the hold lets go and the
 * shell is built as on any other start.
 */
@Composable
private fun StartupCrashQuestion() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val asking by CrashReports.pending.collectAsStateWithLifecycle()
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(Tone.background),
    ) {
        asking?.let { CrashQuestion(it, settings) }
    }
}

@Composable
private fun DeviceStruggleHint(settings: SettingsStore, inShell: Boolean) {
    val raised by com.tmplayer.data.DeviceFitHint.raised.collectAsStateWithLifecycle()
    val shown by settings.deviceHintShown.collectAsStateWithLifecycle(initialValue = true)
    val light by settings.lightSettings.collectAsStateWithLifecycle(initialValue = null)
    if (!raised || shown || !inShell) return
    val now = light ?: return
    val fit = com.tmplayer.ui.onboarding.rememberDeviceFit()
    val seekThumbnails = com.tmplayer.ui.onboarding.rememberSeekThumbnailsShown()
    val recommendations = remember(fit, now, seekThumbnails) {
        com.tmplayer.ui.onboarding.recommendationsAfterStruggle(fit, now, seekThumbnails)
    }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    if (recommendations.isEmpty()) {
        LaunchedEffect(Unit) { com.tmplayer.data.DeviceFitHint.clear() }
        return
    }
    fun answer(apply: Boolean) {
        com.tmplayer.data.DeviceFitHint.clear()
        scope.launch {
            runCatching {
                if (apply) settings.applyRecommendations(recommendations)
                settings.markDeviceHintShown()
            }
        }
        if (apply) toast(L.deviceApplied)
    }
    val context = LocalContext.current
    if (FormFactor.isTv(context)) {
        TvConfirm(
            title = L.deviceHintTitle,
            message = L.deviceHintBody,
            confirmLabel = L.deviceApply,
            cancelLabel = L.deviceNotNow,
            destructive = false,
            focusConfirm = true,
            extra = { com.tmplayer.ui.onboarding.RecommendationList(recommendations) },
            onConfirm = { answer(apply = true) },
            onDismiss = { answer(apply = false) },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            com.tmplayer.ui.onboarding.DeviceHintBar(
                onApply = { answer(apply = true) },
                onNotNow = { answer(apply = false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            )
        }
    }
}
