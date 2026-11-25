package com.tmplayer

import com.tmplayer.ui.components.ToastKind
import com.tmplayer.ui.components.TopMessageHost
import androidx.compose.ui.zIndex
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.tmplayer.ui.components.pageBackScale
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
import com.tmplayer.ui.components.tvReachRoot
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
import com.tmplayer.ui.components.tvPillSlot
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.onboarding.Setup
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
import com.tmplayer.ui.onboarding.TipsCard
import com.tmplayer.ui.settings.LayoutNudgeHost
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
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.data.guardedLifecycleScope as lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The mandatory chat choice's picker ([com.tmplayer.data.ChatsGate]) keeps its view model under
 * this run (counting down by one for each time the gate is answered), apart from every opening
 * from Settings, so a rotation mid-choice keeps the ticks and a later sign in starts clean.
 */
private const val GATE_RUN = -1

/**
 * How long getting a video ready may take before the app says so.
 *
 * Long enough that the usual case, where everything asked for is already in TDLib's database and
 * the player is up almost at once, passes in silence. Short enough that a slow one is explained
 * before the viewer has decided their press was missed.
 */


/** Where the user is. Deliberately three screens deep and no more. */
internal sealed interface Screen {
    data object Chats : Screen
    data class Media(val chat: ChatSummary) : Screen
    data object Settings : Screen
    data object Downloads : Screen
    data object About : Screen

    /** The Add chats picker (HX27), a full screen of its own over whatever opened it. */
    data object AddChats : Screen
}

/**
 * Saves which screen is open across a rotation or a process death.
 *
 * [Media] carries a whole [ChatSummary], which is not parcelable and holds a blurred preview nobody
 * needs restored, so only the fields the media screen actually reads are written down and the row
 * is rebuilt from them. The picture reappears the moment the chat list lands.
 */
internal val ScreenSaver = listSaver<Screen, Any>(
    save = { screen ->
        when (screen) {
            is Screen.Chats -> listOf(SCREEN_CHATS)
            is Screen.Settings -> listOf(SCREEN_SETTINGS)
            is Screen.Downloads -> listOf(SCREEN_DOWNLOADS)
            is Screen.About -> listOf(SCREEN_ABOUT)
            is Screen.AddChats -> listOf(SCREEN_ADD_CHATS)
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
            SCREEN_ADD_CHATS -> Screen.AddChats
            else -> Screen.Chats
        }
    },
)

/** The last window ground the shell drew, for the next launch to paint before its first frame (WP-44). */
private const val LAUNCH_PREFS = "launch"
private const val LAUNCH_GROUND = "ground"

private const val SCREEN_CHATS = "chats"
private const val SCREEN_MEDIA = "media"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_DOWNLOADS = "downloads"
private const val SCREEN_ABOUT = "about"
private const val SCREEN_ADD_CHATS = "add_chats"

/** The phone's screens as the bar and its transitions see them. */
private object TouchScreens {
    /** A screen's key: what its saved state and its say over the bar are kept under. */
    fun key(screen: Screen): String = when (screen) {
        is Screen.Chats -> SCREEN_CHATS
        is Screen.Media -> SCREEN_MEDIA
        is Screen.Settings -> SCREEN_SETTINGS
        is Screen.Downloads -> SCREEN_DOWNLOADS
        is Screen.About -> SCREEN_ABOUT
        is Screen.AddChats -> SCREEN_ADD_CHATS
    }

    /**
     * Where a screen stands along the bar, left to right, for which way the next one slides in:
     * Home, Chats, Search, then the picture's pages. Null for a chat, Settings, About and the Add chats picker, which
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
        is Screen.Settings, is Screen.Media, is Screen.About, is Screen.AddChats -> null
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
    /** While streaming is on Keep video and the video would stream, so the message names Save space. */
    val lowStorageOff: Boolean = false,
)

class MainActivity : FragmentActivity() {

    // The TV resources follow the app's layout, not only the system's UI mode (HX78).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        FormFactor.uiModeOverride(newBase)?.let { wanted ->
            runCatching { applyOverrideConfiguration(wanted) }
                .onFailure { com.tmplayer.platform.Logger.w("tm-form", "ui mode override failed", it) }
            forcedTv = FormFactor.isTv(newBase)
        }
    }

    /** The TV layout on a device the system does not call a TV: a box, or a tablet set to TV (HX78). */
    private var forcedTv = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.tmplayer.platform.Logger.i(
            "tm-form",
            "MainActivity ui mode type=${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK}",
        )
        // The TV layout is drawn for a landscape screen; a phone or tablet set to it turns to one.
        if (forcedTv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        // `--ez fakechats true` on a debug build: the Add chats picker over 3,000 fake chats (HX76).
        if (BuildConfig.DEBUG && intent?.hasExtra("fakechats") == true) {
            com.tmplayer.ui.browse.DebugFakeChats.on = intent.getBooleanExtra("fakechats", false)
        }
        Td.start(this)
        // A PIN profile stays out of the recents screen (HX88).
        com.tmplayer.ui.profiles.SecureWindow.follow(this)
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
        // The retired mini player switch (T12): an old stored value is dropped, never read.
        lifecycleScope.launch { runCatching { SettingsStore(this@MainActivity).dropRetiredMiniPlayer() } }
        // The window's ground from the theme the viewer chose, before the first frame (WP-44): the launch
        // resource follows the system's night mode only, so a dark choice under a light system (or the
        // reverse) flashed the wrong colour for a few frames. Mirrored from the shell's own ground below,
        // one synchronous read of a small file. A television paints its own ground and keeps none.
        if (!FormFactor.isTv(this)) {
            val ground = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).getInt(LAUNCH_GROUND, 0)
            if (ground != 0) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground))
        }
        setContent {
            TMPlayerTheme {
                // Shared screens (a video's copies) open their sheets as the phone's own sheet.
                androidx.compose.runtime.CompositionLocalProvider(
                    com.tmplayer.ui.components.LocalSheetHost provides com.tmplayer.ui.components.PhoneSheetHost,
                ) {
                    // Where each tap lands, for a page it opens to grow out of (see PageMotion).
                    // On a TV, banners and lines with buttons are reached from the screen's edge (HX22).
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().noteTouchOrigin().tvReachRoot()) {
                        // What sits over the gate shows only while the open profile's shell does (HX88).
                        val shellShown = com.tmplayer.ui.lock.profileShellShown(
                            androidx.compose.runtime.remember { com.tmplayer.ui.profiles.AndroidProfiles.flow(this@MainActivity) },
                        )
                        // The last runs crashed as they started: the question alone, then the shell.
                        val holding by CrashReports.holdShell.collectAsStateWithLifecycle()
                        // On a remote, a list keeps still while the focused item is in full view.
                        com.tmplayer.ui.components.StillWhenVisible(enabled = !com.tmplayer.ui.components.isTouch()) {
                            // Trailers play in the app's own full screen player (TRAILERS-PLAN T3), from every detail page.
                            // The profiles (HX88): Who's watching? at launch with two or more, the
                            // shell keyed on the open profile. One profile: Root as it always was.
                            // Trailers, offline downloads and title reports inside the profiles, keyed
                            // on the open profile like the shell: a switch or a lock closes them, and
                            // nothing of one profile is drawn over the PIN pad or the next profile.
                            if (holding) StartupCrashQuestion() else ProfilesRoot {
                                com.tmplayer.ui.trailer.TrailerPlayerRoot {
                                    com.tmplayer.ui.browse.OfflineDownloadsHost { Root() }
                                }
                            }
                            // Once per safe start, over the shell, after any crash question is answered.
                            if (!holding) SafeModeNotice()
                        }
                        // Messages at the top, clear of the floating bar and the buttons at the foot.
                        TopMessageHost(suppressed = !shellShown)
                        // The layout nudge (HX78), over every screen: a box shown the touch layout
                        // meets it on the sign in screen already.
                        LayoutNudgeHost()
                        // "Wrong title?" and the question after a rename (N1): one place, over every
                        // screen, so it outlives the page whose rename asked it.
                        // Over everything as before, but only while the open profile's shell is
                        // what shows (HX88): never over its PIN or the picker, and keyed on the open
                        // profile, so a report never carries over a switch. One profile: as before.
                        if (shellShown) {
                            val openFolder by com.tmplayer.data.Accounts.activeFolder.collectAsStateWithLifecycle()
                            androidx.compose.runtime.key(openFolder) {
                                com.tmplayer.ui.components.TitleReportHost(tvPanel = { st, actions -> com.tmplayer.ui.components.TitleReportTvPanel(st, actions) })
                            }
                        }
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
            // The app lock that asks when the app opens: a tap on the widget (or a Play next entry)
            // that was drawn before the lock went on must not open a video past it. The app opens on
            // its lock instead, and the next tap, from a widget now drawn locked, goes through it.
            val lock = runCatching { SettingsStore(this@MainActivity).appLock.first() }.getOrNull()
            if (lock?.onOpen == true && !AppLockSession.unlocked.value) return@launch
            if (!com.tmplayer.data.KidsLock.allows(com.tmplayer.data.ChatGate.awaitReady(), video.chatId)) return@launch
            // Known gone from Telegram this session (HX21) and not downloaded: the app says so
            // rather than opening a player that can only fail. The row itself goes with the entry.
            com.tmplayer.data.GoneVideos.reasonFor(video.chatId, video.messageId)?.let { reason ->
                val downloaded = withContext(Dispatchers.IO) {
                    runCatching { LocalDownloads.videoFor(SettingsStore(this@MainActivity), video.chatId, video.messageId) }.getOrNull()
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
        // A remote on the touch layout: offer the TV one (HX78). The key still goes through.
        runCatching { com.tmplayer.ui.settings.LayoutNudge.onKey(this, event) }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        debugHangOnInput(event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
        runCatching { com.tmplayer.ui.settings.LayoutNudge.onTouch(this, event) }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Esc on a keyboard plugged into a tablet or a phone is Back, as it is in every other app with
     * a keyboard: it closes a sheet, a search or a page the way the back gesture does. Only when
     * nothing on screen took the key first (these run after the views had it), and not on a
     * television, whose remote has a Back of its own.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (SearchKeyGuard.swallows(keyCode)) return true
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && !FormFactor.isTv(this)) {
            event.startTracking()
            return true
        }
        // Ctrl+A in a chat's grid ticks every video it has loaded (a tablet or Chromebook keyboard, as
        // on the desktop). Not in a text field: the field has focus and takes the key before it comes here.
        if (keyCode == android.view.KeyEvent.KEYCODE_A && event.isCtrlPressed && !FormFactor.isTv(this) &&
            com.tmplayer.ui.browse.GridKeys.askSelectAll()
        ) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (SearchKeyGuard.swallows(keyCode)) return true
        // The remote's Menu key, when no screen took it (a tile's actions do, so does the bar). On a
        // television with the bar on screen it opens the account's menu for every profile count (WP-02:
        // an empty Home, the carousel's background, a page where nothing claims Menu), the picker being
        // a button of it. Where no bar is shown (Settings, a phone) it is the profiles, with two or
        // more on this device (HX88); one profile: the key does what it always did.
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            val flow = com.tmplayer.ui.profiles.AndroidProfiles.flow(this)
            val free = flow == null || (flow.entered && !flow.asked)
            if (free && com.tmplayer.ui.browse.TvBarSignals.askAccountMenu()) return true
            if (flow != null && com.tmplayer.data.ProfilePicker.atLaunch(flow.switcher.profiles.value) && flow.entered && !flow.asked) {
                flow.ask()
                return true
            }
        }
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
        // A link shared to TMPlayer (WP-42): the first Telegram link in the text goes to the link flow; a
        // text with none is answered with what TMPlayer opens. Taken off the intent like a VIEW.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(MAX_SHARED_TEXT)
            val link = com.tmplayer.data.TelegramLinks.find(shared)
            if (link != null) {
                com.tmplayer.ui.browse.LinkRequests.ask(link)
            } else {
                com.tmplayer.ui.components.showMessage(this, L.linkShareNotALink)
            }
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.action = Intent.ACTION_MAIN
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

        /** A shared text longer than this is cut before it is searched for a link: nothing outside is trusted to be small. */
        private const val MAX_SHARED_TEXT = 8_000

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
    private var profile: String? = null
    private var store = ViewModelStore()

    /**
     * The open profile's (HX88): a switch clears the last profile's view models, so the chat list
     * and Home are built again for the profile the shell is now keyed on, never shown from the
     * last one's memory.
     */
    override val viewModelStore: ViewModelStore
        get() = synchronized(this) {
            val now = com.tmplayer.data.Accounts.active.id
            if (profile != null && profile != now) {
                store.clear()
                store = ViewModelStore()
            }
            profile = now
            store
        }
}

/**
 * [content] behind the profiles (HX88, [com.tmplayer.ui.lock.ProfilesGate]): the picker at launch
 * with two profiles or more, and the shell keyed on the open profile. With one, nothing changes.
 */
@Composable
private fun ProfilesRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val flow = remember { com.tmplayer.ui.profiles.AndroidProfiles.flow(context) }
    val auth by Td.auth.collectAsStateWithLifecycle()
    com.tmplayer.ui.lock.ProfilesGate(
        flow,
        signInStep = Setup.isSignInStep(auth),
        sendLogs = { com.tmplayer.ui.settings.SendLogsButton() },
        content = content,
    )
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
        // Kept for the next cold start (onCreate reads it before the first frame).
        activity?.getSharedPreferences(LAUNCH_PREFS, android.content.Context.MODE_PRIVATE)?.edit()?.putInt(LAUNCH_GROUND, ground.toArgb())?.apply()
    }

    // The setup after sign in (CP67), armed by a sign in screen. False until the store answers,
    // which is right for everybody already signed in: they are never asked.
    val setupPending by settings.setupPending.collectAsStateWithLifecycle(initialValue = false)
    // The quick tour (CP69). Done until the store answers, so the offer never flashes up.
    val tourState by settings.tourState.collectAsStateWithLifecycle(initialValue = SettingsStore.TourState.Done)
    // Settings' "Run the setup again": the one question asked again as the ordinary picker.
    val setupReplay by settings.setupReplay.collectAsStateWithLifecycle(initialValue = false)
    // The favourites the kids lock lets through, as the Favourites tab lists them, so every count
    // of them (the badge, Clear all) says the same number.
    val storedFavorites by settings.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    // The Favourites tab lists the starred chats the kids lock shows, whether or not the viewer
    // added the chat in the picker (HX86): a star is their own act, in "All chats" and in picks.
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
    val favorites = remember(storedFavorites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavorites, allowedChats) }
    val watchProgress by settings.watchProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    val continueWatching by settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchedVideos by watchedStore.watched.collectAsStateWithLifecycle(initialValue = emptyMap())
    val watchedHistory by watchedStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchLater by watchedStore.watchLater.items.collectAsStateWithLifecycle(initialValue = emptyList())
    // The named lists (HX43): Home's rows of them and the lists' page.
    val namedLists by watchedStore.lists.visible.collectAsStateWithLifecycle(initialValue = null)
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
    // Settings asks once per visit (SettingsUnlock, process wide: a rotation keeps it, a process
    // that died asks again). Leaving Settings for anything but its own pages locks it, and so does
    // the app leaving the screen whatever the lock's options (App.LockOnLeave, SEC-03).
    val settingsUnlocked by com.tmplayer.data.SettingsUnlock.unlocked.collectAsStateWithLifecycle()

    val toast = rememberToast()
    val historyRemoval = com.tmplayer.ui.browse.rememberHistoryRemoval()
    val touch = com.tmplayer.ui.components.isTouch()
    var markWatchedAsk by remember { mutableStateOf<Triple<MediaItem, String, () -> Unit>?>(null) }

    // The update check, on every launch and before sign in too: ten seconds after the first
    // frame, then every six hours (see UpdateScheduler). Nothing is said unless there is genuinely
    // a newer release; the drawer and the rail are where it turns up.
    val updateState = Updates.state.collectAsStateWithLifecycle()
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
    // Read through a derived value: the download's progress moves this state a whole percent at a
    // time, and read here in the body it recomposed the whole shell on every step (an ANR in
    // Root on a TV box during an update). The version only changes when the offer does.
    val offeredUpdate by remember {
        derivedStateOf {
            val now = updateState.value
            now.release?.version?.takeUnless { (now as? UpdateState.Available)?.skipped == true }
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
                ChatListViewModel(settings, com.tmplayer.data.PageQueries.cache) as T
        },
    )
    // The mandatory chat choice (ChatsGate): an account that never chose which chats to show is
    // asked before the app, once the setup is done. While it is up (or briefly waits for the chat
    // list), nothing of the shell's own is offered: no update popup, What's new, language card,
    // tour offer or support prompt over or before it.
    val gateMove = com.tmplayer.ui.browse.rememberChatsGate(chatsViewModel, setupPending, signedIn = auth is AuthState.Ready)
    // While the setup is armed it is the one question (SetupFlow), shown through the same gate
    // bookkeeping: the question is a shown gate, every wait a wait.
    val setupFlow = com.tmplayer.ui.browse.rememberSetupFlow(chatsViewModel, setupPending, setupReplay, signedIn = auth is AuthState.Ready)
    val chatsGate = if (setupPending) com.tmplayer.data.SetupFlow.toMove(setupFlow) else gateMove
    val gateUp = com.tmplayer.data.ChatsGate.up(chatsGate)
    // The gate's picker model lives in the activity under its run. Once the gate has been answered
    // the next one gets a new run, so a second sign in in this process (Sign out does not recreate
    // the activity) meets a fresh picker, not the saved one of the old account, whose Done
    // returned at once and did nothing.
    var gateRun by rememberSaveable { mutableStateOf(GATE_RUN) }
    var gateShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(chatsGate) {
        val next = com.tmplayer.data.ChatsGate.advance(com.tmplayer.data.ChatsGate.Run(gateRun, gateShown), chatsGate)
        gateRun = next.run
        gateShown = next.shown
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inShell = auth is AuthState.Ready && !setupPending && !gateUp && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // The popup follows the item, once per version, two seconds after it appears. Not over the
    // sign in screens and not over the player (another activity, which leaves this one stopped):
    // either way the wait starts again once the shell is in front.
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
        toast(com.tmplayer.i18n.L.updateUpdatedTo(version = updated), ToastKind.Success)
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
    // The answer is read (WP-38): the "asked" mark is written when it comes, not before the question,
    // and the downloads' and Settings' asks go through the same launcher ([NotificationAsk]). The
    // registry takes it at composition, ahead of anything that can ask.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> com.tmplayer.data.NotificationAsk.answered(context, granted) }
    androidx.compose.runtime.DisposableEffect(askNotifications) {
        com.tmplayer.data.NotificationAsk.launcher = {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        onDispose { com.tmplayer.data.NotificationAsk.launcher = null }
    }
    LaunchedEffect(Unit) {
        SupportReminder.finished.collect { done ->
            runCatching { settings.noteSupportCompleted(done.key, done.at) }
            if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !FormFactor.isTv(context) &&
                !com.tmplayer.data.BackgroundChecks.canNotify(context)
            ) {
                // Once, and only where the system will still show its question: a refusal that
                // Android treats as final is answered by the card in Settings, not by a silent launch.
                // A Skip on the first run's notifications step is a no: nothing asks by itself after it.
                val skippedInSetup = runCatching {
                    settings.onboardingSteps.first().outcomes[com.tmplayer.data.OnboardingSteps.Step.Notifications] ==
                        com.tmplayer.data.OnboardingSteps.Outcome.Skipped
                }.getOrDefault(false)
                if (!skippedInSetup && !com.tmplayer.data.NotificationAsk.asked(context)) com.tmplayer.data.NotificationAsk.request(context)
            }
        }
    }

    // The downloads an earlier run of the app was in the middle of, back on the Downloads screen
    // as paused rows. Waits for TDLib, because restoring asks it how much of each file is actually
    // on disk, and a queue restored against a closed database would claim every video was at zero.
    LaunchedEffect(auth) {
        if (auth is AuthState.Ready) {
            OfflineDownloads.restore(context)
            // A switch away left this profile's downloads paused, as a restart does (HX88): the
            // viewer resumes them. What earlier builds kept to resume by itself goes.
            com.tmplayer.ui.profiles.PausedForSwitch.clearOldCopy(context)
        }
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
                    downloadsDir = { com.tmplayer.data.AndroidStorage.profileDownloadsDir(context) },
                    notifier = CoalescingTransferNotifier(AndroidTransferNotifier(context)),
                ).migrateOnce()
            }.getOrNull()
        }
        LegacyDownloads.toast(result)?.let(toast)
    }

    // A download finishing, said in the window too. A television shows apps like this one no
    // notification shade, so without this the end of a download would pass unremarked there.
    LaunchedEffect(Unit) {
        AndroidTransferNotifier.completions.collect { title -> toast(s.mainDownloaded(title = title), ToastKind.Success) }
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
            keptOnDevice = { chatId, messageId -> LocalDownloads.videoFor(settings, chatId, messageId) != null },
        ) { forgotten -> withContext(Dispatchers.Main) { toast(com.tmplayer.data.GoneVideos.message(forgotten)) } }
    }

    // Held as a State and read only where it is used (the browser, the effects below, Home's rows),
    // never in this body: a busy account changes the chat list on every post (an unread count, a
    // reorder), and each read here recomposed the whole shell, two thousand lines of it, on the
    // main thread of a weak TV box until the input wait ran out (HX81, Sentry 161627449).
    val chatsState = chatsViewModel.state.collectAsStateWithLifecycle()
    val chatList = remember {
        derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) {
            // With the starred chats the viewer did not add (HX86): Home's starred rows and the
            // lookups by id read them too. Chats itself lists BrowseData.chats alone.
            (chatsState.value as? UiState.Content)?.value?.withStarred.orEmpty()
        }
    }
    // Whether the list is still arriving, the one thing the launch's jumps wait on.
    val chatsLoading by remember { derivedStateOf { chatsState.value is UiState.Loading } }
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
    // The profiles (HX88): Switch profile in the account's menu with two or more.
    val profileFlow = remember { com.tmplayer.ui.profiles.AndroidProfiles.flow(context) }
    val profileList by remember(profileFlow) {
        profileFlow?.switcher?.profiles ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsStateWithLifecycle()
    val severalProfiles = profileList?.let { com.tmplayer.data.ProfilePicker.atLaunch(it) } == true
    // Sign out (WP-02): one host at the shell, asked by Settings, the TV's account menu and the phone's
    // popover alike, so a backup started from a menu is not tied to the menu.
    val signOutState = com.tmplayer.ui.settings.rememberSignOutState()

    // An interface size that was being tried when the app died (WP-10) goes back to the size before it,
    // once per process: a rotation or a new activity in the same run must not undo a question still asked.
    LaunchedEffect(Unit) {
        if (!com.tmplayer.ui.settings.TentativeSizeGuard.checked) {
            com.tmplayer.ui.settings.TentativeSizeGuard.checked = true
            runCatching { settings.revertUiScale() }
        }
    }

    // A sign in screen arms the setup that follows it (CP67, see Setup).
    LaunchedEffect(auth) {
        // First a session Telegram ended (HX23): unlike Sign out, the viewer chose nothing, so
        // what they watched, Watch later, bookmarks and preferences stay; only the old session's
        // cache and cold start snapshot go ([forgetSession]).
        com.tmplayer.ui.connect.handleSessionEnd(auth, settings) {
            com.tmplayer.ui.settings.forgetSession(settings)
        }
        if (Setup.isSignInStep(auth)) {
            val others = com.tmplayer.data.AccountSwitcher.current()?.profiles?.value?.profiles?.size ?: 1
            // The setup is for a new start: after an ended session the preferences are still there.
            // Not for Add account's sign in either (HX88): the setup is the device's, already done,
            // and armed here it came up over the open profile once the new one was given up.
            if (!com.tmplayer.data.Td.sessionEnded.value && others <= 1) runCatching { settings.armSetup(tvDarkByDefault = FormFactor.isTv(context)) }
            // A sign in screen means the account is gone, however it went: signed out here, or
            // from Telegram's Devices list, which is the way back from a forgotten PIN. The lock
            // was that account's. Unless other profiles share the device (HX88): the lock is the
            // device's, and a new profile's sign in, or one profile's ended login, is not theirs.
            if (others <= 1) runCatching { settings.clearAppLock() }
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
    // Offline as the screens believe it: held for 2 s each way by the host (OfflineSettle), so the
    // banner and Home's cards never flash on a flaky link (R4).
    val settledOffline = com.tmplayer.ui.browse.LocalOfflineHome.current.offline
    LaunchedEffect(settledOffline, networkStatus, telegramConnected, auth) {
        when {
            settledOffline -> {
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

    // The corner's line for catching up, a wait Telegram asked for and a sync that needs the viewer (WP-100). A
    // connection state above wins; one effect, ticking only while a count down needs it; a locked profile sees none.
    val syncStatus by settings.resumeSyncStatus.collectAsStateWithLifecycle(initialValue = null)
    val hintsSeen by settings.hintsSeen.collectAsStateWithLifecycle(initialValue = emptySet())
    val statusDay = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) }
    // Filed as Failed once three runs in a row failed (WP-97, WP-100): that line has Try again and Send logs.
    val syncHintId = com.tmplayer.data.GlobalStatusRules.hintKind(syncStatus)?.let { com.tmplayer.data.GlobalStatusRules.syncHintId(it, statusDay) }
    val syncSendLogs = com.tmplayer.ui.settings.rememberSendLogs()
    val globalLine = com.tmplayer.ui.components.rememberGlobalLine(
        active = auth is AuthState.Ready && connectionNotice == ConnectionNotice.Hidden && !com.tmplayer.data.ProfileUnlock.hiddenNow(),
        syncKind = syncStatus?.kind,
        syncDismissed = syncHintId != null && syncHintId in hintsSeen,
        syncStopped = com.tmplayer.data.SyncStreak.stopped(syncStatus),
    )
    // On a television the line is drawn by the top bar, in its free space, so it never covers a page or a key (the bar reads these).
    androidx.compose.runtime.SideEffect {
        com.tmplayer.ui.browse.TvBarSignals.statusLine.value = globalLine
        com.tmplayer.ui.browse.TvBarSignals.statusSync.value = syncStatus
        com.tmplayer.ui.browse.TvBarSignals.statusConnection.value = connectionNotice
    }
    val tvBarsShown by com.tmplayer.ui.browse.TvBarSignals.barsShown.collectAsStateWithLifecycle()

    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Chats)
    }
    // One slot for whatever the current login pane got wrong: only one of them is ever on screen.
    var signInError by rememberSaveable { mutableStateOf<String?>(null) }
    var roomPrompt by remember { mutableStateOf<RoomPrompt?>(null) }
    // "Switch to Save space?", for a video that only fits that way, and the refusal "Not now"
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
    // Saved with the screen itself (WP-39): after a turn, a fold or a split screen resize Back goes
    // where it went before, not to the chat list.
    var downloadsCameFrom by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Chats) }
    // Where leaving Settings goes back to: the page the profile popover's Settings was picked on.
    var settingsCameFrom by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Chats) }
    // On a television, the rail item to put the remote back on when Back from Settings or
    // Downloads returns to the browser: the place it was pressed, not the first tile of the page.
    var railReturn by remember { mutableStateOf<com.tmplayer.ui.browse.RailReturn?>(null) }
    // Whether the Downloads screen opens on its cached videos, which is how Settings' Cached
    // videos row reaches the list of them.
    // How many times the Downloads screen has been asked to open with Free up space up (0: not),
    // which is where "Not enough space" sends the viewer: straight to what can go, not to a tab
    // to work it out from. A count, so a second refusal while the page is up opens it again.
    var downloadsFreeUp by remember { mutableStateOf(0) }
    // Whether that ask came from a video refused for room, which makes the review lead with Low
    // storage mode, or from Settings' Storage, whose Free up space is the same review.
    var downloadsAskedForRoom by remember { mutableStateOf(true) }
    // Where leaving the Add chats picker goes back to (Settings' row, the Chats page's empty state), and
    // how many times it has been opened: each opening has a view model of its own, so one closed does
    // not come back with its old ticks, while a rotation in the middle of one keeps them.
    var addChatsCameFrom by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Chats) }
    var addChatsRun by rememberSaveable { mutableStateOf(0) }
    // The Settings page open, kept here because Settings itself is gone while About or the cached
    // videos are showing, and coming back from either should land on the page that led there.
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // A sync status that stands (stopped, needs you) shows on the television for a few seconds when the app opens, when the
    // sync's state changes and when Settings is opened; then it is the avatar's dot and a row in the account sheet, and a
    // normal line in Settings' sync row. Never a pill for good.
    val tvForStanding = FormFactor.isTv(context)
    val inSettingsNow = screen is Screen.Settings
    var settingsVisits by remember { mutableStateOf(0) }
    LaunchedEffect(inSettingsNow) { if (inSettingsNow) settingsVisits++ }
    val standingKey = (globalLine as? com.tmplayer.data.GlobalLine.SyncNeedsYou)?.let { it.kind to syncStatus?.failures }
    com.tmplayer.ui.components.TvSyncStandingEffect(standingKey, settingsVisits, tvForStanding, com.tmplayer.ui.browse.TvBarSignals.syncStanding)

    // A launch that asked for a particular screen, which is how the download notification opens
    // the list it is about. Cleared as it is acted on, so it happens once per press.
    val requestedScreen by MainActivity.requestedScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requestedScreen) {
        if (requestedScreen != SCREEN_DOWNLOADS && requestedScreen != MainActivity.OPEN_FREE_UP_SPACE) return@LaunchedEffect
        // Back from the Downloads screen goes wherever the viewer already was, not to the chat
        // list: they were reading a chat, pressed a notification, and Back should return them to it.
        if (screen !is Screen.Downloads) downloadsCameFrom = screen
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
    // Hoisted out of BrowseScreen: opening a chat replaces that screen entirely, so a tab held
    // down there would be forgotten every time the viewer backed out of a chat.
    //
    // Saved as a string rather than as the value itself: a folder is not an enum constant, it is
    // whatever this account happens to have, so there is nothing for the default saver to write.
    // Safe mode (two crashes in a row soon after opening) opens on Chats, not Home.
    var pickedTabKey by rememberSaveable {
        mutableStateOf(if (com.tmplayer.data.SafeMode.active) BrowseSection.encode(BrowseSection.of(BrowseTab.Chats)) else "")
    }
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
    // The launcher's Search and Continue watching shortcuts (WP-42): the chat list, with the search up for the first.
    LaunchedEffect(requestedScreen) {
        val asked = requestedScreen
        if (asked != com.tmplayer.data.LauncherShortcuts.OPEN_SEARCH && asked != com.tmplayer.data.LauncherShortcuts.OPEN_CONTINUE) {
            return@LaunchedEffect
        }
        screen = Screen.Chats
        if (asked == com.tmplayer.data.LauncherShortcuts.OPEN_SEARCH) touchChrome.searchAsked = true
        MainActivity.requestedScreen.value = null
    }
    // The shortcuts themselves (WP-42): kept in step with the last chat played and with the profile
    // being hidden (a locked PIN profile takes the chat's name off the launcher).
    val shortcutHidden by remember { com.tmplayer.data.ProfileUnlock.hidden() }.collectAsStateWithLifecycle(initialValue = true)
    // A PIN set on the open profile takes the chat's name off at once (SEC2-11), not when the app leaves.
    val shortcutPin by remember { com.tmplayer.data.ProfileUnlock.activeHasPin() }.collectAsStateWithLifecycle(initialValue = true)
    val lastChatForShortcut by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val lastChatTitle = chatList.value.firstOrNull { it.id == lastChatForShortcut }?.title
    LaunchedEffect(lastChatForShortcut, lastChatTitle, shortcutHidden, shortcutPin, appLock) {
        // The chat's name goes out of the app only where OutsideWrites allows (a hidden PIN profile: never).
        // Hidden by the same rule the process-wide follower removes it by: the profile, a PIN on it, a
        // lock over the app, and a lock not read yet.
        com.tmplayer.data.LauncherShortcuts.publish(
            context, lastChatForShortcut, lastChatTitle,
            hidden = com.tmplayer.data.LauncherShortcuts.hiddenFor(
                appLock, shortcutHidden, shortcutPin || com.tmplayer.data.Accounts.active.pin != null, com.tmplayer.data.OutsideWrites.mayWriteNow(),
            ),
        )
    }

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
    val activeDownloadCount by remember(activeDownloadsState) { derivedStateOf { OfflineDownloads.runningCount(activeDownloadsState.value) } }

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
            val downloaded = LocalDownloads.videoFor(settings, chosen.chatId, chosen.messageId) != null
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
        // The default player (PR4): another app, our chooser, or on as always. A press that came
        // back from the room prompt ([confirmed]) was routed already.
        if (!confirmed && com.tmplayer.player.ExternalPlayRouter.take(context, settings, item, chatTitle)) return
        // Off the main thread for the whole of the decision. Everything between here and the plan
        // is blocking work: two stat() calls to see whether the file is on disk, a statvfs() for
        // the free space, a walk of every download record, and a TDLib round trip per record to
        // measure it. Launched from a composition, each of those would resume onto the thread
        // drawing the grid. Only the decision is moved: everything after it touches Compose state
        // or starts an activity and has to be back on Main to do it.
        withContext(Dispatchers.Default) {
            // A download plays from its file in the Downloads folder, and nothing below applies
            // to it: it needs no connection, no room, and it is not the cache's to claim.
            if (LocalDownloads.videoFor(settings, item.chatId, item.messageId) != null) {
                withContext(Dispatchers.Main) {
                    com.tmplayer.ui.components.PlayPress.open()
                    context.startActivity(PlayerActivity.intent(context, item, chatTitle))
                }
                return@withContext
            }
            val local = com.tmplayer.data.runCatchingCancellable { Td.localFileAvailability(item.fileId) }
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
            val lowStorageOn = com.tmplayer.data.runCatchingCancellable { settings.lowStorageModeNow() }.getOrDefault(true)

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

    // The default player's windows and plumbing (PR4); "Play in TMPlayer" and every fall back land in play().
    com.tmplayer.player.ExternalPlayHost(settings) { video, chat -> play(video, chatTitle = chat) }

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
        chatList.value.firstOrNull { it.kind == com.tmplayer.data.ChatKind.Saved }?.let { openChat(it); return }
        scope.launch { com.tmplayer.data.savedMessagesNow()?.let { openChat(it) } ?: orList() }
    }
    val savedSection = BrowseSection.of(BrowseTab.Saved)

    /**
     * Marks a video watched by hand, or takes the mark off. Marking also forgets the saved
     * position, which is what takes it out of Continue watching.
     */
    fun setWatched(item: MediaItem, chatTitle: String, watched: Boolean) {
        if (watched) {
            // A touch screen does it at once and says Undo for a few seconds ([HistoryRemoval]),
            // which restores the exact place. A remote cannot reach that line, so a television
            // asks first when there is a place to lose, and the card leaving is the answer.
            val mark = {
                historyRemoval.run({ L.mainMarkedWatched(com.tmplayer.ui.browse.continueCardTitle(item, L.messages).ifBlank { item.title }) }) { st, w ->
                    com.tmplayer.data.HistoryEdits.markWatched(st, w, item, chatTitle)
                }
            }
            if (touch) {
                mark()
            } else {
                scope.launch {
                    val hasPlace = runCatching { settings.resumePosition(item.chatId, item.messageId) > 0L }.getOrDefault(false)
                    if (hasPlace) markWatchedAsk = Triple(item, chatTitle, mark) else mark()
                }
            }
            return
        }
        App.backgroundScope.launch {
            runCatching {
                // Every copy's mark, or one still marked keeps the folded card ticked.
                watchedStore.markUnwatched(item.chatId, item.messageId, copies = item.versions)
            }
        }
        // Not said: the tick on the card, or the card leaving its row, shows it.
    }

    /** [openStored] once its guard is up. */
    suspend fun openStoredNow(stored: MediaItem, chatTitle: String) {
        settings.rememberChatOpened(stored.chatId)
        // In the Downloads folder: it plays from there, and asking Telegram first would only
        // be a wait, and offline a long one.
        if (LocalDownloads.videoFor(settings, stored.chatId, stored.messageId) != null) {
            play(stored, chatTitle = chatTitle)
            return
        }
        // Complete in the watch cache, or no way to reach Telegram: the lookup could only be a wait
        // (up to LOOKUP_TIMEOUT_MS, offline all of it) behind the Starting pill, and the stored row
        // already plays. The fresh id is fetched in the background for the next press, and
        // [play] says "connect to play" itself when a stream cannot start offline.
        val offline = networkStatus == NetworkStatus.Offline && !telegramConnected
        val cached = !offline && com.tmplayer.data.runCatchingCancellable { Td.localFileAvailability(stored.fileId) }
            .getOrDefault(LocalFileAvailability.Missing) == LocalFileAvailability.Complete
        if (offline || cached) {
            if (cached) App.backgroundScope.launch { runCatching { Td.lookupMedia(stored.chatId, stored.messageId) } }
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
    // A keyboard's shell keys (WP-28): Ctrl+F or "/" is search, Ctrl+comma Settings. Read after the
    // focused control had the key (a search field keeps its "/"), never on a television.
    val shellKeys = remember { ShellKeyHooks() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            .onPreviewKeyEvent { event ->
                remoteKeys.fire()
                // The remote's Search key on the browser's other screens (WP-22): a chat's videos and
                // Downloads have no search of the whole library, so the key takes the viewer to the TV
                // search with an empty query, as the bar's Search tab does from Downloads. The browse
                // shell (Home, the chat list) answers it itself (BrowseScreen), Settings does nothing.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Search && FormFactor.isTv(context) &&
                    (screen is Screen.Media || screen is Screen.Downloads)
                ) {
                    com.tmplayer.ui.browse.TvBarSignals.searchAsked.value = true
                    screen = Screen.Chats
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { pressed ->
                if (pressed.type != KeyEventType.KeyDown || FormFactor.isTv(context)) return@onKeyEvent false
                val (code, typed) = when (pressed.key) {
                    Key.F -> "KeyF" to "f"
                    Key.Slash -> "Slash" to "/"
                    Key.Comma -> "Comma" to ","
                    Key.F5 -> "F5" to ""
                    Key.R -> "KeyR" to "r"
                    else -> return@onKeyEvent false
                }
                val press = com.tmplayer.player.PlayerKeyMap.Press(
                    code, typed, pressed.isShiftPressed, pressed.isCtrlPressed, pressed.isAltPressed, pressed.isMetaPressed,
                )
                when (com.tmplayer.player.PlayerKeyMap.shellKeyFor(press)) {
                    com.tmplayer.player.PlayerKeyMap.ShellKey.Search -> {
                        val onBrowse = screen is Screen.Chats
                        if (onBrowse) touchChrome.searchAsked = true
                        onBrowse
                    }
                    com.tmplayer.player.PlayerKeyMap.ShellKey.Settings -> {
                        shellKeys.settings()
                        true
                    }
                    // F5 and Ctrl+R: the page's refresh, only where it has one (WP-40).
                    com.tmplayer.player.PlayerKeyMap.ShellKey.Refresh ->
                        screen is Screen.Chats && touchChrome.refreshNow()
                    null -> false
                }
            },
    ) {
        // Sign out (WP-02), composed first and before every gate below (the app lock, the setup, the
        // chat choice), whose early returns would otherwise take the host, its backup job and its
        // prompt with them. It asks the app lock's PIN when Settings' own gate is shut.
        com.tmplayer.ui.settings.SignOutHost(
            state = signOutState,
            name = accountHeader?.name ?: profileList?.active?.shownName,
            settings = settings,
            watchedStore = watchedStore,
            onLoggedOut = {
                settingsPage = null
                screen = Screen.Chats
            },
            settingsUnlocked = settingsUnlocked,
            severalProfiles = severalProfiles,
            // Nothing is drawn over the app lock's own pad, nor before the lock is read (SEC-05).
            appLocked = appLock?.let { it.onOpen && !appUnlocked } ?: true,
        )
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
        val holdForHome = reached && !setupPending && !gateUp && !(appLock?.onOpen == true && !appUnlocked) && !homeHoldOver && !com.tmplayer.ui.browse.HomeFirstPaint.ready
        com.tmplayer.ui.components.BrandLoaderHandOff(
            label = com.tmplayer.ui.auth.loaderLabel(shownAuth, LocalStrings.current)
                ?: if (holdForHome) LocalStrings.current.loginGettingReady else null,
            modifier = Modifier.zIndex(1f),
        ) {
            // What it is doing now, and past the long wait, why it may be stuck with Retry, a
            // proxy and the logs: the desktop's help, on the phone and the TV too. On a first open the
            // one line of what comes next sits above it (Raju pass 2, 1.1).
            com.tmplayer.ui.connect.FirstOpenPromise(settings)
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
                    // Under every sign in screen: the line Telegram's terms ask for, the proxy
                    // settings, for a network where Telegram is only reached through one, and Send
                    // logs, for a sign in that is stuck with no error state to Try again from (HX
                    // request, 2026-10-31: the QR pane had no way to get logs out at all). Before
                    // the proxy button, it was offered only once the line was already known to be down.
                    val loginFooterStrings = LocalStrings.current
                    val openProxies = { com.tmplayer.ui.connect.ProxyScreenState.open = true }
                    val sendLogs = com.tmplayer.ui.settings.rememberSendLogs()
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 24.dp)
                            .padding(top = 6.dp, bottom = if (FormFactor.isTv(context)) Tv.SafeV else 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (FormFactor.isTv(context)) {
                            // Two real buttons on a remote, not a dropdown: a remote's D-pad reads a
                            // button it can land on directly far better than a menu it has to open
                            // first, and two is still little enough to show both at once.
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                            ) {
                                com.tmplayer.ui.components.TmSecondaryButton(onClick = openProxies) {
                                    androidx.tv.material3.Text(loginFooterStrings.loginProxySettings)
                                }
                                com.tmplayer.ui.components.TmSecondaryButton(onClick = sendLogs) {
                                    androidx.tv.material3.Text(loginFooterStrings.logsSave)
                                }
                            }
                        } else {
                            // Words on a phone, not an icon (Raju pass 2, 2.4): "Can't connect? Get help" opens the
                            // proxy settings and Send logs, rare and diagnostic, so one link rather than two buttons
                            // competing with the form.
                            com.tmplayer.ui.connect.SignInHelpLink(
                                onProxy = openProxies,
                                onSendLogs = sendLogs,
                                sendLogsLabel = loginFooterStrings.logsSend,
                            )
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
            // Telegram ended the session (HX23): why the sign in is back, where a banner about the
            // line would otherwise go.
            if (!com.tmplayer.ui.connect.signInBannerShown()) {
                com.tmplayer.ui.connect.SessionEndedNote(
                    Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
            ConnectionStatus(
                notice = connectionNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Above the loader, which is where being offline matters most.
                    .zIndex(2f)
                    .padding(bottom = if (FormFactor.isTv(context)) com.tmplayer.ui.theme.Tv.SafeV else 20.dp),
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

        // The quick tour on its own (CP69): taken from the Tips card on Home, or opened from Settings.
        if (!setupPending && tourState == SettingsStore.TourState.Pending) {
            TourOnly(
                onDone = { scope.launch { runCatching { settings.markTourDone() } } },
                onSkip = { scope.launch { runCatching { settings.markTourSkipped() } } },
            )
            return@Box
        }

        // "Ask when the app opens" (CP57): the pad instead of the app, not over it, so a remote
        // cannot walk out of it into the screen behind. Back leaves the app.
        if (appLock?.onOpen == true && !appUnlocked) {
            val activity = LocalActivity.current
            // A fingerprint or face, for this pad only (WP-41): offered once the opt-in has been read as on
            // (null before: nothing is shown, nothing can open the pad but the PIN). Its success unlocks the
            // app and nothing else: Settings still asks for the PIN, as does signing out.
            val biometricOn by settings.appLockBiometric.collectAsStateWithLifecycle(initialValue = null)
            val prompter = remember(activity) { (activity as? FragmentActivity)?.let { com.tmplayer.ui.lock.BiometricPrompter(it) } }
            PinPad(
                settings = settings,
                purpose = PinPurpose.Check,
                title = s.lockEnterForApp,
                brand = true,
                onDone = {
                    AppLockSession.unlock()
                    // The PIN has just been given; Settings need not ask for it again this visit.
                    com.tmplayer.data.SettingsUnlock.unlock()
                },
                onCancel = { activity?.finish() },
                biometric = if (biometricOn == true) prompter else null,
                onBiometric = { AppLockSession.unlock() },
            )
            return@Box
        }

        // The mandatory chat choice (ChatsGate), after the app lock so a locked app is unlocked
        // before any chat name is shown. In place of the app, not over it, so a remote cannot walk
        // out of it; it goes by itself once Done writes the choice. Back leaves the app. The view
        // model is kept under the gate run, so a rotation keeps the ticks and a later gate starts clean.
        // The setup after sign in is this same picker (R3): "Which chats have your videos?", then
        // Home. Its waits are the spinner with "Loading your chats" under it, or the offline card.
        if (setupPending) {
            com.tmplayer.ui.onboarding.SetupFlowEffects(settings, setupFlow, replay = setupReplay)
            when (setupFlow) {
                com.tmplayer.data.SetupFlow.Next.Choose -> {
                    val activity = LocalActivity.current
                    com.tmplayer.ui.browse.AddChatsPage(
                        settings = settings,
                        chatsViewModel = chatsViewModel,
                        run = gateRun,
                        // Only a replay has a Back and a Done that closes it; the first question ends by itself.
                        onClose = { scope.launch { runCatching { settings.markSetupDone() } } },
                        required = com.tmplayer.data.SetupFlow.required(setupReplay),
                        onLeave = { activity?.finish() },
                    )
                }
                com.tmplayer.data.SetupFlow.Next.Offline -> com.tmplayer.ui.browse.SetupOffline()
                com.tmplayer.data.SetupFlow.Next.Wait -> com.tmplayer.ui.browse.ChatsGateWaiting()
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Tone.accent)
                }
            }
            return@Box
        }
        if (chatsGate == com.tmplayer.data.ChatsGate.Move.Wait) {
            com.tmplayer.ui.browse.ChatsGateWaiting()
            return@Box
        }
        if (chatsGate == com.tmplayer.data.ChatsGate.Move.Show) {
            val activity = LocalActivity.current
            com.tmplayer.ui.browse.AddChatsPage(
                settings = settings,
                chatsViewModel = chatsViewModel,
                run = gateRun,
                onClose = {},
                required = true,
                onLeave = { activity?.finish() },
            )
            return@Box
        }

        // The first run's optional steps, after the chats question and before Home: notifications
        // (Android 13 or later, a phone or tablet) and supporting TMPlayer. Each has a Skip, each is
        // shown once, and none is armed for an install that was only updated. After the app lock,
        // so nothing is drawn while the app is locked.
        if (com.tmplayer.ui.onboarding.OnboardingStepsHost(
                settings,
                lockRead = appLock != null,
                locked = appLock?.let { it.onOpen && !appUnlocked } ?: true,
            )
        ) {
            return@Box
        }

        // Anywhere but Settings and its own pages (About, Add chats) locks Settings again: Downloads
        // too, so its bar's Sign out asks the PIN, as a new visit does (SEC-03).
        LaunchedEffect(screen) {
            if (screen !is Screen.Settings && screen !is Screen.About && screen !is Screen.AddChats) {
                com.tmplayer.data.SettingsUnlock.lock()
            }
        }

        // A chat restored from the saved state carries only what could be written down, so its
        // blurred preview is missing until the real row turns up, when it is swapped back in.
        LaunchedEffect(screen) {
            val open = screen as? Screen.Media ?: return@LaunchedEffect
            if (open.chat.miniThumbnail != null) return@LaunchedEffect
            // Followed here rather than keyed on, so a new list wakes this effect and not the shell.
            snapshotFlow { chatList.value }.collect { chats ->
                chats.firstOrNull { it.id == open.chat.id }
                    ?.takeIf { it.miniThumbnail != null }
                    ?.let { screen = Screen.Media(it) }
            }
        }

        // Straight back into whatever was being watched last. Not routed through openChat: this
        // is not the viewer picking a chat, and rewriting the record it just read would be noise.
        LaunchedEffect(chatsLoading, autoOpened) {
            if (autoOpened) {
                autoOpenDecided = true
                return@LaunchedEffect
            }
            // The chat list is still arriving, and which chat is in it is the whole question.
            if (chatsLoading) return@LaunchedEffect

            autoOpened = true
            val target = settings.autoOpenTarget()
            chatList.value.firstOrNull { it.id == target }?.let { screen = Screen.Media(it) }
            autoOpenDecided = true
        }

        // A new videos notification pressed: the chat it named, once the list has it, in place of
        // whatever the launch would have opened. A chat the kids lock hides is not in the list.
        val requestedChat by MainActivity.requestedChat.collectAsStateWithLifecycle()
        LaunchedEffect(requestedChat, chatsLoading) {
            val wanted = requestedChat ?: return@LaunchedEffect
            if (wanted == 0L) {
                screen = Screen.Chats
            } else {
                if (chatsLoading) return@LaunchedEffect
                chatList.value.firstOrNull { it.id == wanted }?.let { screen = Screen.Media(it) }
            }
            autoOpened = true
            autoOpenDecided = true
            MainActivity.requestedChat.value = null
        }

        // The top level has no Back handler of its own: Back leaves, as in every other app, with no
        // "press again" (rule 13 of the UX interaction plan), and the system's back-to-home
        // animation plays because nothing here claims the press.

        // Home's rows, built here rather than inside Home so the TV home screen channels follow
        // them whichever screen is open. Recently added is the featured carousel at the top of
        // Home, on a phone and a television, so it is worth having with one starred chat: it does
        // not repeat that chat's own row as a row of its own would.
        // Null until the setting is read, so no row that is off is fetched for the first frame.
        val homeLayout by remember { settings.homeLayout }.collectAsStateWithLifecycle(initialValue = null)
        // Worked out in a scope of their own (ShellHomeRows): each answer of a chat Home reads, and
        // each change to the chat list, recomposes that and the browser's Home, not the shell.
        val homeRows = remember { mutableStateOf(homeViewModel.lastRows, androidx.compose.runtime.referentialEqualityPolicy()) }
        val tvChannelsOn by settings.tvChannels.collectAsStateWithLifecycle(initialValue = null)
        ShellHomeRows(
            model = homeViewModel,
            chats = chatList,
            favourites = favorites,
            continueWatching = continueWatching,
            watchLater = watchLater,
            watched = watchedVideos.values,
            layout = homeLayout,
            lists = namedLists.orEmpty(),
            watchProgress = watchProgress,
            signedIn = auth is AuthState.Ready,
            // Safe mode leaves the channels as they are: null is "not known yet", which does nothing.
            channelsOn = if (com.tmplayer.data.SafeMode.active) null else tvChannelsOn,
            into = homeRows,
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
        shellKeys.settings = openSettings
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
            downloadsFreeUp = 0
            screen = Screen.Downloads
        }
        // "Add chats" from Settings' Library, a chat list or Home with nothing added (AddChatsRequest):
        // the picker over whatever asked, which Back returns to.
        val openAddChats: () -> Unit = {
            if (screen !is Screen.AddChats) {
                addChatsCameFrom = screen
                addChatsRun++
                screen = Screen.AddChats
            }
        }
        val addChatsAsked by com.tmplayer.ui.browse.AddChatsRequest.pending.collectAsStateWithLifecycle()
        LaunchedEffect(addChatsAsked) {
            if (com.tmplayer.ui.browse.AddChatsRequest.take()) openAddChats()
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
            // The compact version row of the account sheet: About, with the build and the update source.
            onAbout = {
                openSettings()
                settingsPage = SettingsPage.About
            },
            onSwitchProfile = profileFlow?.takeIf { severalProfiles }?.let { flow -> { flow.ask() } },
            // Add account with one profile, the picker with more (WP-02, WP-103). Add asks the app
            // lock's PIN itself (ProfileFlow.add, closed while the lock is unread): one PIN, once.
            onSwitchOrAdd = profileFlow?.let { flow -> { com.tmplayer.ui.browse.switchOrAdd(flow, severalProfiles) } },
            onSignOut = { signOutState.request() },
            signOutPhase = signOutState.phase,
            profile = profileList?.active,
            // The television's standing sync status: the account sheet's row, which opens the sync switch.
            syncNote = if (tvForStanding) com.tmplayer.ui.components.TvStatusRules.standingSentence(LocalStrings.current, globalLine) else null,
            onSyncNote = if (tvForStanding) ({
                openSettings()
                settingsPage = SettingsPage.Accounts
            }) else null,
            // A profile panel or PIN pad, a notice, or the app lock over the pages: no hint over them.
            promptUp = (profileFlow != null && (profileFlow.stage != com.tmplayer.ui.lock.ProfileFlow.Stage.Picker || profileFlow.notice != null)) ||
                (appLock?.onOpen == true && !appUnlocked),
        )

        // The television's top bar over Downloads, as it is over the browser's pages. Settings has
        // none: it opens from the account's menu as a screen of its own, as About and the PIN pad do.
        val tvBar = FormFactor.isTv(context)
        val tvBarShown = tvBar && screen is Screen.Downloads
        val tvBarFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // Back from Settings to Downloads puts the remote back on the bar's picture, which opened it.
        var tvBarHold by remember { mutableStateOf(0) }
        var tvBarFocused by remember { mutableStateOf(false) }
        // The page shrinking under a back gesture (WP-44): chat, Settings and About leave with the system's preview.
        val pageBack = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val screens: @Composable (Screen) -> Unit = screens@{ current ->
        when (current) {
            is Screen.Chats -> {
                BrowseScreen(
                    // Held on its own loading state until the jump has been decided, so the
                    // launch looks like one screen loading rather than two screens fighting.
                    state = if (autoOpenDecided) chatsState.value else UiState.Loading(),
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
                    onRemoveChat = { chat ->
                        scope.launch {
                            // The whole list, before the kids lock: under "every chat" the rest of
                            // it becomes the choice, so a lock switched off later hides nothing more.
                            val everything = chatsViewModel.everything.value.map { com.tmplayer.data.AddedChat(it.id, it.title) }
                            runCatching { settings.removeChat(chat.id, everything) }
                                .onFailure { android.util.Log.w("TMPlayer", "removing a chat failed", it) }
                        }
                    },
                    onTurnOffAllChats = {
                        scope.launch {
                            // Whole only once the list says it has no more to read (a chat still on its way is never called gone).
                            val whole = !chatsViewModel.paging.value.more && !chatsViewModel.paging.value.partial
                            runCatching { settings.turnOffAllChats(chatsViewModel.everything.value, whole) }
                                .onFailure { android.util.Log.w("TMPlayer", "turning off All chats failed", it) }
                        }
                    },
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
                    homeRows = homeRows.value,
                    homeRowsReady = homeViewModel.rowsSettled,
                    homeLayout = homeLayout ?: com.tmplayer.data.HomeLayout(),
                    onOpenHomeSettings = {
                        openSettings()
                        settingsPage = SettingsPage.Library
                    },
                    homeArt = homeViewModel.art.collectAsStateWithLifecycle().value,
                    homeWatch = homeWatch,
                    onHomeRowShown = { row ->
                        when (row) {
                            is HomeRow.Chat -> homeViewModel.request(row.chatId)
                            is HomeRow.Recent -> homeViewModel.requestRecent()
                            is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.Listed, is HomeRow.NewEpisodes -> Unit
                        }
                    },
                    onHomeRowSeen = { row ->
                        when (row) {
                            is HomeRow.Chat -> homeViewModel.rowShown(row.chatId)
                            is HomeRow.Recent -> homeViewModel.requestRecent()
                            is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.Listed, is HomeRow.NewEpisodes -> Unit
                        }
                    },
                    onHomeRowLeft = { row -> if (row is HomeRow.Chat) homeViewModel.rowGone(row.chatId) },
                    onHomeArtWanted = homeViewModel::requestArt,
                    homeFeedOf = homeViewModel::seeAll,
                    chatPaging = chatsViewModel.paging.collectAsStateWithLifecycle().value,
                    onMoreChats = chatsViewModel::loadMoreChats,
                    onHomeLaterArtWanted = homeViewModel::requestArt,
                    onPlayMedia = { item, chatTitle -> play(item, chatTitle = chatTitle) },
                    homeRefreshing = homeViewModel.refreshing.collectAsStateWithLifecycle().value,
                    chatsRefreshing = chatsViewModel.refreshing.collectAsStateWithLifecycle().value,
                    // What failed, what is kept and what is slow, for the status line and Home's foot (WP-93).
                    chatsStatus = chatsViewModel.listStatus.collectAsStateWithLifecycle().value,
                    homeStatus = homeViewModel.status.collectAsStateWithLifecycle().value,
                    homeStalled = homeViewModel.stalled.collectAsStateWithLifecycle().value,
                    onRetryHomeRows = homeViewModel::retryFailedRows,
                    // Home's own rows alone (HX44): the chat list no longer needs a manual sync
                    // piggybacked on this, since TDLib's own updates already keep it live.
                    onRefreshHome = { homeViewModel.refreshInPlace() },
                    onSetMediaWatched = { item, chatTitle, watched -> setWatched(item, chatTitle, watched) },
                    onMessage = toast,
                    onSearched = { settings.addRecentSearch(it) },
                    namedLists = namedLists,
                    listsStore = watchedStore.lists,
                )
            }

            is Screen.Media -> {
                val leaveChat = {
                    // Backing out of a chat is the viewer asking for the chat list. Honour that
                    // for the rest of the session rather than jumping them back in.
                    autoOpened = true
                    screen = Screen.Chats
                }
                com.tmplayer.ui.components.PredictivePageBack(pageBack, onBack = leaveChat)
                // Another chat is another screen (WP-94): its own remembered state, shelf and scroll,
                // so nothing of the chat left shows under the new chat's header.
                androidx.compose.runtime.key(current.chat.id) {
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
            }

            is Screen.Downloads -> {
                // Back to whatever raised it. Reached from the drawer that is the chat list, and
                // reached from "Not enough space" it is the chat the video was in, which is where
                // somebody who has just freed space wants to be.
                val leaveDownloads = {
                    if (downloadsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.Downloads
                    screen = downloadsCameFrom
                }
                // On a television one Back leaves too, the remote landing on the bar, on the page it
                // comes back to: Downloads is a place on the bar now (R2), not behind the picture, so
                // there is no account item to go back to.
                BackHandler(
                    onBack = {
                        if (tvBarShown) {
                            if (downloadsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.Downloads
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
                        openFreeUp = downloadsFreeUp > 0,
                        askedForRoom = downloadsFreeUp > 0 && downloadsAskedForRoom,
                        // An empty Downloads page: to the chats, where Download is.
                        onBrowse = {
                            pickedTabKey = BrowseSection.encode(BrowseSection.of(BrowseTab.Chats))
                            screen = Screen.Chats
                        },
                        // Download season is on a show's page, which Home opens.
                        onDownloadSeason = {
                            pickedTabKey = BrowseSection.encode(BrowseSection.of(BrowseTab.Home))
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
                com.tmplayer.ui.components.PredictivePageBack(pageBack, onBack = leaveSettings)
                val lock = appLock
                // With no PIN there is nothing to ask, and a PIN set from inside Settings must not
                // lock the viewer out of the page they set it on.
                LaunchedEffect(lock?.pinSet) { if (lock?.pinSet == false) com.tmplayer.data.SettingsUnlock.unlock() }
                // No floating bar: Settings is a screen pushed from the picture's popover, with
                // its own back arrow, as a chat is.
                TouchPage(null)
                if (lock == null) return@screens
                if (lock.pinSet && !settingsUnlocked) {
                    PinPad(
                        settings = settings,
                        purpose = PinPurpose.Check,
                        title = s.lockEnterForSettings,
                        onDone = { com.tmplayer.data.SettingsUnlock.unlock() },
                        onCancel = leaveSettings,
                    )
                    return@screens
                }
                SettingsScreen(
                    chats = chatList.value,
                    account = accountHeader,
                    onLoggedOut = {
                        settingsPage = null
                        screen = Screen.Chats
                    },
                    onBack = leaveSettings,
                    // Storage's Free up space: the Downloads page with the review up, Back to Settings.
                    onFreeUpSpace = {
                        downloadsCameFrom = Screen.Settings
                        downloadsFreeUp += 1
                        downloadsAskedForRoom = false
                        screen = Screen.Downloads
                    },
                    onOpenAbout = { screen = Screen.About },
                    onOpenAddChats = openAddChats,
                    initialPage = settingsPage,
                    onPageChange = { settingsPage = it },
                    signOut = signOutState,
                )
            }

            is Screen.About -> {
                val leaveAbout = { screen = Screen.Settings }
                com.tmplayer.ui.components.PredictivePageBack(pageBack, onBack = leaveAbout)
                AboutScreen(onBack = leaveAbout)
            }

            is Screen.AddChats -> {
                // No floating bar: the picker is a screen of its own, and answers Back itself.
                com.tmplayer.ui.browse.AddChatsPage(
                    settings = settings,
                    chatsViewModel = chatsViewModel,
                    run = addChatsRun,
                    onClose = {
                        // On a television the remote goes back to the bar's Choose chats button
                        // when the picker was opened over Chats; from Settings, Back
                        // returns to Settings as before.
                        if (tvBar && addChatsCameFrom is Screen.Chats) railReturn = com.tmplayer.ui.browse.RailReturn.ChooseChats
                        screen = addChatsCameFrom
                    },
                )
            }
        }
        }

        // The quick tour's offer (CP69, ON-11, D-1): a quiet card in a row of its own under the
        // page, so it never covers a row or stacks with the television's browse hint. Offered once
        // to a fresh sign in after the setup, and to an install that was signed in before it
        // existed. Behind every other card, on the chat list only. Not now brings it back once
        // after a week; the second time it says No thanks.
        val crashQuestion by CrashReports.pending.collectAsStateWithLifecycle()
        val crashPending = crashQuestion != null
        val noticeLanguage = languageNotice.language
        var tvHintUp by remember { mutableStateOf(FormFactor.isTv(context)) }
        val tourOffer = SettingsStore.tipsDue(tourState) && inShell &&
            screen is Screen.Chats && !showUpdate && noticeLanguage == null && !pickingLanguage &&
            !whatsNew.showing && supportRung == 0 && !crashPending
        // On the phone and the tablet the card stands in the floating bar's banner slot, just
        // above the bar, rather than in a row under it; the connection notice goes first and the
        // card comes when it is gone. The television keeps its slim row at the foot.
        val tipAboveBar = tourOffer && !FormFactor.isTv(context)
        com.tmplayer.ui.settings.TourOfferFrame(
            offer = tourOffer && !tipAboveBar,
            hintUp = tvHintUp && FormFactor.isTv(context) && inShell && screen is Screen.Chats,
            card = { cardModifier ->
                TipsCard(
                    onShow = { scope.launch { runCatching { settings.openTour() } } },
                    // One tap, one answer: the card does not come back (the tour stays in Settings).
                    onDismiss = { scope.launch { runCatching { settings.markTourSkipped() } } },
                    modifier = cardModifier,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
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
                            downloadsShown = screen is Screen.Downloads,
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
                ) { com.tmplayer.ui.browse.ListPickerHost(watchedStore.lists, toast) { screens(screen) } }
            } else {
                // The phone: one floating bar over every page it belongs to, and the pages changing
                // under it the way Home and Chats do (see PhoneBrowse), each keeping its scroll.
                val holder = rememberSaveableStateHolder()
                // The end a pushed screen comes in from: the right, or the left in a right to left language.
                val endSign = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
                TouchNavHost(
                    nav = touchNav,
                    chrome = touchChrome,
                    pageKey = TouchScreens.key(screen),
                    // The connection notice stands above the bar, never over it, and the pages make
                    // room for it (R4, 9.1): Offline is a card with Try again and Downloads.
                    bannerShown = (connectionNotice != ConnectionNotice.Hidden && auth is AuthState.Ready) ||
                        globalLine != com.tmplayer.data.GlobalLine.Idle || tipAboveBar,
                    banner = {
                        // The notice first; the card only while it is really due, so the banner's fade-out
                        // after a notice clears never draws the card (or hands the remote to it).
                        if (auth is AuthState.Ready && connectionNotice != ConnectionNotice.Hidden) {
                            if (connectionNotice == ConnectionNotice.Offline) {
                                com.tmplayer.ui.connect.OfflineBanner(
                                    onOpenDownloads = openDownloads.takeIf { screen !is Screen.Downloads },
                                )
                            } else {
                                ConnectionStatus(notice = connectionNotice)
                            }
                        } else if (globalLine != com.tmplayer.data.GlobalLine.Idle) {
                            com.tmplayer.ui.components.GlobalStatusPill(
                                line = globalLine,
                                syncStatus = syncStatus,
                                onRetry = { scope.launch { Td.retryConnecting() } },
                                // The viewer's own run: the only thing that tries again once the sync has stopped.
                                onTryAgainSync = {
                                    syncHintId?.let { id -> scope.launch { runCatching { settings.markHintSeen(id) } } }
                                    com.tmplayer.platform.Background.scope.launch { runCatching { com.tmplayer.data.ResumeSync.sync(settings, manual = true) } }
                                },
                                onSendLogs = syncSendLogs,
                                onOpenSettings = {
                                    syncHintId?.let { id -> scope.launch { runCatching { settings.markHintSeen(id) } } }
                                    openSettings()
                                    settingsPage = SettingsPage.Accounts
                                },
                                onDismiss = { syncHintId?.let { id -> scope.launch { runCatching { settings.markHintSeen(id) } } } },
                            )
                        } else if (tipAboveBar) {
                            TipsCard(
                                onShow = { scope.launch { runCatching { settings.openTour() } } },
                                // One tap, one answer: the card does not come back (the tour stays in Settings).
                                onDismiss = { scope.launch { runCatching { settings.markTourSkipped() } } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                ) {
                    AnimatedContent(
                        targetState = screen,
                        contentKey = { TouchScreens.key(it) },
                        transitionSpec = {
                            val from = TouchScreens.order(initialState, pickedTab)
                            val to = TouchScreens.order(targetState, pickedTab)
                            val push = TouchScreens.isPushed(targetState) && initialState !is Screen.About && initialState !is Screen.AddChats
                            val pop = TouchScreens.isPushed(initialState) && targetState !is Screen.About && targetState !is Screen.AddChats
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
                            CompositionLocalProvider(LocalTouchPageKey provides key) {
                                com.tmplayer.ui.browse.ListPickerHost(watchedStore.lists, toast) {
                                    androidx.compose.foundation.layout.Box(
                                        Modifier.pageBackScale(pageBack.floatValue, com.tmplayer.ui.theme.rememberReducedMotion()),
                                    ) { screens(shown) }
                                }
                            }
                        }
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

        markWatchedAsk?.let { (item, _, mark) ->
            // Mark as unwatched is one press away, but the place is forgotten, so it is asked, and
            // focus starts on Cancel.
            TvConfirm(
                title = L.browseMarkWatchedTitle,
                message = L.browseMarkWatchedMessage(com.tmplayer.ui.browse.continueCardTitle(item, L.messages).ifBlank { item.title }),
                confirmLabel = L.gridMarkWatched,
                destructive = false,
                onConfirm = {
                    markWatchedAsk = null
                    mark()
                },
                onDismiss = { markWatchedAsk = null },
            )
        }

        lowStorageAsk?.let { (ask, refusal) ->
            // An invitation, so focus starts on "Switch and play": it does what the viewer pressed.
            TvConfirm(
                title = L.settingsStreamSwitchTitle,
                message = if (ask.keep) {
                    L.mainStreamAskKeep
                } else {
                    L.mainStreamAskPlay
                },
                detail = L.settingsStreamSaveDetail,
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
                    if (pending.lowStorageOff) " " + L.mainStreamNoRoom(device = device) else "",
                detail = L.mainNoRoomDetail,
                // Straight to what can go, with the sheet that says what each line frees already up
                // (user, 2026-10-18), rather than to a tab to work it out from.
                confirmLabel = L.downloadsFreeUpSpace,
                destructive = false,
                focusConfirm = true,
                onConfirm = {
                    roomPrompt = null
                    if (screen !is Screen.Downloads) downloadsCameFrom = screen
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

        crashQuestion?.let { question -> if (!showUpdate) CrashQuestion(question, settings) }

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
                // Above the pill slot, which the work pill or the connection pill may hold (F-03).
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = com.tmplayer.ui.theme.Tv.SafeV + 56.dp),
                onBusy = { tvHintUp = it },
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
        supporting?.let { SupportDialog(onClose = { supporting = null }) }

        // The phone's notice stands in the floating bar's host, above the bar (TouchNavHost's banner).
        // Elsewhere the pills keep the foot of the screen and Offline is the card at the top (R4).
        if (FormFactor.isTv(context)) {
            // One status at a time (TvStatusRules.pick): the top bar draws it in its free space, over no card or page.
            // Only where no bar is shown (a chat's grid) it keeps the foot, inside the safe area.
            // Settings and About carry it in their own header (SettingsHeader); a standing sync status never stands here.
            if (tvBarsShown == 0 && screen !is Screen.Settings && screen !is Screen.About) com.tmplayer.ui.components.TvStatusPill(
                input = com.tmplayer.ui.components.TvStatusInput(globalLine, syncStatus, connection = connectionNotice, standing = false),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .tvPillSlot(),
            )
        }
        if (connectionNotice == ConnectionNotice.Offline && auth is AuthState.Ready && FormFactor.isTv(context)) {
            com.tmplayer.ui.connect.OfflineBanner(
                modifier = if (FormFactor.isTv(context)) {
                    // On a TV, in the Connect banner's place: Up from the bar goes into it (HX22).
                    Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(2f)
                        .padding(top = com.tmplayer.ui.theme.Tv.SafeV, end = com.tmplayer.ui.theme.Tv.SafeH)
                        .widthIn(max = TV_BANNER_MAX)
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                },
                onOpenDownloads = openDownloads.takeIf { !FormFactor.isTv(context) && screen !is Screen.Downloads },
            )
        }
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
                modifier = if (FormFactor.isTv(context)) {
                    // On a TV, beside the bar's tabs at its end, never over them: Up from the bar
                    // (or Right from its last tab) goes into it (HX22).
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = com.tmplayer.ui.theme.Tv.SafeV, end = com.tmplayer.ui.theme.Tv.SafeH)
                        .widthIn(max = TV_BANNER_MAX)
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                },
            )
        }
        com.tmplayer.ui.connect.ProxyScreenHost()
    }
}

/**
 * Home's rows for the shell, and the TV home screen channels that follow them, in a restart scope
 * of their own. What they read changes often: every chat Home reads answers in turn, Recently
 * added, new episodes and the chat list itself, which a busy account changes on every post. Read
 * in [Root]'s body each of those recomposed the whole shell on the main thread (HX81). Here a
 * change recomposes this and, through [into], only what draws the rows. [into] takes the rows
 * after this composition is applied, so the browser's Home gets them a frame later; they are
 * worked out off the main thread and arrive whenever they are ready anyway.
 */
@Composable
internal fun ShellHomeRows(
    model: HomeViewModel,
    chats: androidx.compose.runtime.State<List<ChatSummary>>,
    favourites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    watchLater: List<com.tmplayer.data.WatchLaterRecord>,
    watched: Collection<WatchedRecord>,
    layout: com.tmplayer.data.HomeLayout?,
    lists: List<com.tmplayer.data.VisibleList>,
    watchProgress: Map<String, com.tmplayer.data.WatchPoint>,
    signedIn: Boolean,
    channelsOn: Boolean?,
    into: androidx.compose.runtime.MutableState<List<HomeRow>>,
) {
    val list = chats.value
    val rows = rememberHomeRows(model, list, favourites, continueWatching, watchLater, watched, recentMinStarred = 1, layout = layout, lists = lists)
    androidx.compose.runtime.SideEffect { into.value = rows }
    TvHomeChannels(model = model, rows = rows, chats = list, watchProgress = watchProgress, signedIn = signedIn, enabled = channelsOn)
}

/** The connection banner's width on a TV, clear of the bar's tabs at the start. */
private val TV_BANNER_MAX = 440.dp

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
 * Says once that this run is a safe one ([com.tmplayer.data.SafeMode]): the last two runs crashed
 * soon after opening, so it opened on Chats with Home and the background loading off. Continue
 * (or Back) keeps it; "Open normally next time" forgets the crashes now; Send logs shares the log
 * with the last crash in it. The same dialog on a phone and a television, as the crash question is.
 */
@Composable
private fun SafeModeNotice() {
    if (!com.tmplayer.data.SafeMode.active) return
    var open by rememberSaveable { mutableStateOf(true) }
    if (!open) return
    val s = LocalStrings.current
    TvConfirm(
        title = s.mainSafeTitle,
        message = s.mainSafeMessage,
        confirmLabel = s.mainSafeContinue,
        destructive = false,
        focusConfirm = true,
        extra = {
            com.tmplayer.ui.components.TmSecondaryButton(
                onClick = {
                    com.tmplayer.data.SafeMode.openNormallyNextTime()
                    open = false
                },
            ) { Text(s.mainSafeNormal) }
            com.tmplayer.ui.settings.SendLogsButton()
        },
        onConfirm = { open = false },
        onDismiss = { open = false },
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
        if (apply) toast(L.deviceApplied, ToastKind.Success)
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

/** What the shell's keys call, set once the screens that answer them exist (WP-28). */
private class ShellKeyHooks {
    var settings: () -> Unit = {}
}
