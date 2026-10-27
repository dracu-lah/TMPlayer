package com.tmplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tmplayer.i18n.L
import com.tmplayer.promo.PromoCatalog
import com.tmplayer.ui.browse.shelfGrid
import kotlinx.coroutines.runBlocking
import com.tmplayer.data.Account
import com.tmplayer.data.AuthState
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Translator
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.HomeRows
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesOpened
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.Header
import com.tmplayer.ui.browse.MediaDetailOpened
import com.tmplayer.ui.browse.VideoSearchSource
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.Fuzzy
import kotlinx.coroutines.delay
import com.tmplayer.ui.browse.MediaCard
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.browse.TouchMediaScaffold
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.browse.noVideosWithin
import com.tmplayer.ui.browse.STILL_MORE_TO_SEARCH
import com.tmplayer.ui.browse.FIRST_LOAD_TIP
import com.tmplayer.data.SizeFilter
import androidx.compose.foundation.layout.padding
import com.tmplayer.ui.onboarding.SetupScreen
import com.tmplayer.ui.onboarding.SetupState
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.CoffeeDialog
import com.tmplayer.ui.settings.SupportDialog
import com.tmplayer.ui.settings.LanguageDialog
import com.tmplayer.ui.settings.LanguageNoticeCard
import com.tmplayer.ui.settings.WhatsNewDialog
import com.tmplayer.ui.settings.FeedbackDialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tv

/**
 * A promo-build-only fixture used to capture honest UI without exposing a real Telegram account.
 *
 * Start it with `--es screen chats`, `media` or `settings`. The empty, error and loading states have
 * screens of their own: `chats-first` (the first chat list, with its tip, and after fifteen seconds
 * the slow-answer line), and `media` with `--es variant empty-hidden|empty-more|slow|recent`.
 * `--es variant menu` opens the first video's menu over the grid, with its "x GB free" line, and
 * `--es screen downloads` is the Downloads screen, `--es screen support` the support codes over
 * Settings, and `--ei support_rung 1` to `3` (or `--ez support_reminder true`) the support card over the chat list,
 * and `--ez support_thanks true` the thank you state in Settings and About. Add
 * `--ez tv true` to capture the television layout on a phone panel resized to 1920x1080, which is
 * how the TV shots on the site are taken now that the stick is not the only device this app has to look right on.
 *
 * Every picture in here is a demo drawable shipped with this build. Nothing on screen belongs to
 * anybody, which is the point: the shots on the README and the site can be published as they are.
 */
class PromoCaptureActivity : ComponentActivity() {
    private val barsHidden get() = intent.getBooleanExtra("tv", false) || intent.getBooleanExtra("nobars", false)

    /**
     * Hidden again once the window has focus: a phone ignores a hide asked for before the window
     * is attached, so onCreate alone left the bars up there.
     */
    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && barsHidden) hideBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val tv = intent.getBooleanExtra("tv", false)
        // Before setContent, because the whole tree branches on it during the first composition.
        FormFactor.override(tv)
        // `--es fire tv|tablet` answers the Fire OS question as that device would, so the Fire
        // differences (no Watch Next switch, the keyboard's dictation hint) can be seen without one.
        promoFireOs(intent.getStringExtra("fire"))
        // `--ez watchnext true` stops two demo episodes the way the player does on the way out,
        // one part way through and one at the end, through the real rules and the real writer, so
        // the home screen's "Play next" row can be checked without a Telegram account.
        if (intent.getBooleanExtra("watchnext", false)) promoWatchNext()
        // `--ez meta true` turns posters and overviews on over a canned TMDB and AniList, and
        // `--es metakey none|refused|own` sets the TMDB key's state. See [PromoMeta].
        // `--ez poster_thumbs true` gives the films their posters as thumbnails, so Home's wide
        // tiles show letterboxed art over its blurred fill (the setup's Performance pictures).
        PromoMeta.posterThumbnails = intent.getBooleanExtra("poster_thumbs", false)
        PromoMeta.install(applicationContext, intent.getBooleanExtra("meta", false), intent.getStringExtra("metakey"))
        // `--ez channels true` writes Home's rows over the demo library into TMPlayer's channels on
        // the Android TV home screen, through the real planner and writer, so the launcher's
        // "Recently added" row can be checked and captured without a Telegram account. Add
        // `--ez meta true` first for the films' and shows' posters.
        if (intent.getBooleanExtra("channels", false)) promoChannels()
        // `--es orient land` turns a phone sideways, for the landscape check every phone screen gets.
        requestedOrientation = if (tv || intent.getStringExtra("orient") == "land") {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        // `--ez nobars true` does the same on a phone: the site's phone shots carry no status bar or
        // gesture pill, and the page fills the whole 1080x2400 rather than leaving a band where
        // the bars were. A swipe from the edge still brings them back for a moment.
        com.tmplayer.ui.browse.ScreenshotBarsOverride.hidden = barsHidden
        if (barsHidden) {
            // A television has no status bar and no gesture pill, and a shot of the TV layout with
            // a phone's clock and battery in the corner would be a picture of something that does
            // not exist.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            hideBars()
        }
        // `--es theme light|dark` pins the appearance, so the site can carry a light picture for
        // its light theme and a dark one for its dark theme rather than showing a black phone on
        // a white page. Written to this build's own preferences, which are its own: the promo
        // application id is separate, so nothing here can touch a real install's setting.
        intent.getStringExtra("theme")?.let { wanted ->
            val choice = when (wanted.lowercase()) {
                "light" -> ThemeChoice.Light
                "dark" -> ThemeChoice.Dark
                "black" -> ThemeChoice.Dark
                else -> ThemeChoice.System
            }
            runBlocking {
                SettingsStore(applicationContext).run {
                    setThemeChoice(choice)
                    // "black" is Dark with the Pure black switch on; every other value turns it off.
                    setPureBlack(wanted.lowercase() == "black")
                }
            }
        }

        // `--es lang en-XA` (or any shipped tag) pins the UI language for the shot, the same way
        // the Settings choice would; "" goes back to following the system.
        promoLanguage(intent.getStringExtra("lang"))
        // `--es online <state>` gives Settings an online subtitles section in that state.
        PromoOnline.install(applicationContext, intent.getStringExtra("online"))

        // `--ez support_thanks true` marks this build's viewer as a supporter, so Settings and About
        // show the thank you state; `--ei support_rung N` is the card of the Nth ask.
        if (intent.getBooleanExtra("support_thanks", false)) {
            runBlocking { SettingsStore(applicationContext).markSupporter() }
        }

        // `--ez firsttip true` shows the chat list's first-run tip (forward a video to Saved
        // Messages); without it the tip stays away, so the usual chat list shots are unchanged.
        com.tmplayer.ui.browse.FirstVideoTipOverride.show = intent.getBooleanExtra("firsttip", false)
        // `--ez metanotice true` shows the grid's one-time note on online metadata; without it the
        // note stays away, so the usual grid shots are unchanged.
        com.tmplayer.ui.browse.MetaNoticeOverride.show = intent.getBooleanExtra("metanotice", false)
        // `--ez metainvite true` shows the one-time invite to turn online metadata on.
        com.tmplayer.ui.browse.MetaNoticeOverride.invite = intent.getBooleanExtra("metainvite", false)

        // `--ez groups_demo true` puts four of the demo chats in the two demo folders and stars
        // three, two of them in no folder.
        val groupsDemo = intent.getBooleanExtra("groups_demo", false)
        runBlocking {
            val store = SettingsStore(applicationContext)
            if (groupsDemo) {
                store.clearFavorites()
                listOf(102L, 103L, 104L).forEach { store.toggleFavorite(it) }
            }
        }
        PromoGroups.demo = groupsDemo

        // `--ez lowend true`: this device counts as short of memory and of storage (CP68), for the
        // setup's Device step, the Settings card and the hint. `--ez lowend false` clears it, and
        // `--ez light_reset true` turns the four recommended settings back to their defaults.
        if (intent.hasExtra("lowend")) {
            com.tmplayer.data.DeviceFitOverride.forced =
                if (intent.getBooleanExtra("lowend", false)) com.tmplayer.data.DeviceFit(true, true) else null
        }
        if (intent.getBooleanExtra("light_reset", false)) {
            runBlocking {
                val store = SettingsStore(applicationContext)
                store.setLowStorageMode(false)
                store.setTrickplay(true)
                store.setLighterPictures(false)
                store.setReduceMotion(false)
            }
        }
        // `--ez lighter true` is the setup's "Lighter" answer (CP69): Lighter pictures and Reduce
        // motion on, thumbnails while seeking off; `--ez lighter false` is "Full quality". Set here
        // and on the picture cache at once, so the first frame is already drawn the chosen way.
        if (intent.hasExtra("lighter")) {
            val lighter = intent.getBooleanExtra("lighter", false)
            runBlocking {
                val store = SettingsStore(applicationContext)
                store.setLighterPictures(lighter)
                store.setReduceMotion(lighter)
                store.setTrickplay(!lighter)
            }
            com.tmplayer.data.Thumbnails.light = lighter
            com.tmplayer.ui.theme.Motion.reduced = lighter
        }
        // `--ez downloads_demo true` fills Downloads with a few demo videos, so the screen's list
        // can be captured with something in it. See [promoDownloads].
        if (intent.getBooleanExtra("downloads_demo", false)) promoDownloads()
        PromoShowOverride.key = intent.getStringExtra("show")?.lowercase() ?: "harbour lines"
        // `--es variant search` with `--es query "..."`: what the chat's search found for it.
        PromoSearch.query = intent.getStringExtra("query") ?: PromoSearch.query
        val start = intent.getStringExtra("screen") ?: "chats"
        if (start == "signin") {
            // The number field takes focus the moment it appears, which is right in the app and
            // wrong in a screenshot: half the picture would be somebody's keyboard, and the
            // autofill strip above it offers the number of whoever is holding the phone.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        setContent {
            // The fixture navigates, so one recording can walk from the chat list into a chat and
            // back out the way a viewer would, rather than being three unrelated clips cut together.
            // `--es screen support` is Settings with the "Support TMPlayer" codes open over it, and
            // `--ez support_reminder true` puts the support card over the chat list.
            var screen by remember { mutableStateOf(if (start == "support") "settings" else start) }
            var supporting by remember { mutableStateOf(start == "support") }
            var supportRung by remember {
                mutableStateOf(intent.getIntExtra("support_rung", if (intent.getBooleanExtra("support_reminder", false)) 1 else 0))
            }
            val promoCounters = remember { com.tmplayer.data.SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000) }
            // `--ez confirm true` puts the sign out prompt over whatever screen is open, so the
            // confirm dialog can be captured beside the other modals.
            var confirming by remember { mutableStateOf(intent.getBooleanExtra("confirm", false)) }
            // `--es overlay language|whatsnew|feedback|tvhint` opens the language picker, the "What's new"
            // sheet or "Report a problem" over the screen, and `--ez language_notice true` puts
            // the "Now in ..." card over the chat list, in whatever language is active.
            var overlay by remember { mutableStateOf(intent.getStringExtra("overlay")) }
            var languageNotice by remember { mutableStateOf(intent.getBooleanExtra("language_notice", false)) }
            var updateDialog by remember { mutableStateOf(intent.getBooleanExtra("update_dialog", false)) }
            TMPlayerTheme {
                // The real app does this from MainActivity, which the fixture does not run
                // through. Without it a light shot carries a white clock on a white status bar,
                // which is a picture of a bug the app does not have.
                val dark = LocalDarkTheme.current
                LaunchedEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // The theme's own background, not the television's literal. Painting the dark
                // constant here put a black page behind a light-mode screen, so every light shot
                // taken from this fixture was of something the app never draws.
                // As the app's own shell does: a list keeps still while the focused item is in view.
                com.tmplayer.ui.components.StillWhenVisible(enabled = tv || !com.tmplayer.ui.components.isTouch()) {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    when (screen) {
                        "media" -> {
                            val variant = intent.getStringExtra("variant") ?: "grid"
                            if (tv) {
                                TvMediaScreen(variant)
                            } else {
                                PhoneMediaScreen(variant, onBack = { screen = "chats" })
                            }
                        }
                        "chats-first" -> PromoChatsScreen(
                            state = UiState.Loading(L.browseLoadingChats, tip = FIRST_LOAD_TIP),
                        )
                        // The number pane, not the QR one. A shipped picture of a real QR is a
                        // working key to an account, which is why the old shot had to be blurred;
                        // an empty number field says the same thing and hides nothing.
                        "signin" -> LoginScreen(
                            state = AuthState.Phone(),
                            onSubmitPassword = {},
                            submitError = null,
                        )
                        // `--es screen signin-qr`: the QR pane, over a link that is not a login.
                        // Its token is the words "TMPlayer demo, not a login" in base64, so the
                        // code on the site opens nothing for anybody who scans it.
                        "signin-qr" -> LoginScreen(
                            state = AuthState.Qr(PROMO_QR_LINK),
                            onSubmitPassword = {},
                            submitError = null,
                        )
                        // The setup after sign in (CP67), which the fixture reaches without a sign in:
                        // every step, as an account with folders gets them, opening on `--es step
                        // library|posters|storage`. Finishing or Skip all goes to the chat list.
                        // With `--ez lowend true` the Device step (CP68) takes Storage's place, as on a
                        // low-end device: `--es step device` opens on it.
                        "setup" -> {
                            val store = remember { SettingsStore(applicationContext) }
                            val fit = com.tmplayer.ui.onboarding.rememberDeviceFit()
                            val recommendations = com.tmplayer.ui.onboarding.rememberRecommendations(store, fit)
                            val lowEnd = com.tmplayer.data.DeviceFitOverride.forced != null
                            val steps = remember(lowEnd) {
                                SetupStep.entries.filter { if (lowEnd) it != SetupStep.Storage else it != SetupStep.Device }
                            }
                            val setup = remember(steps) {
                                SetupState(
                                    steps,
                                    start = steps.indexOfFirst { it.name.equals(intent.getStringExtra("step"), ignoreCase = true) }.coerceAtLeast(0),
                                )
                            }
                            // Held once shown, as the app holds them, so applying does not empty the list.
                            val shown = remember(recommendations.isNotEmpty()) { recommendations }
                            SetupScreen(store, setup, onDone = { screen = "chats" }, fit = fit, recommendations = shown)
                        }
                        // A fresh sign in's whole walk (CP69): the setup's choices, then the tour, on one count.
                        "setuptour" -> {
                            val store = remember { SettingsStore(applicationContext) }
                            val phone = com.tmplayer.ui.components.deviceForm() == com.tmplayer.data.DeviceForm.Phone
                            val steps = remember(phone) {
                                SetupStep.entries.filter { it != SetupStep.Device && (phone || it != SetupStep.MobileData) }
                            }
                            com.tmplayer.ui.onboarding.SetupAndTour(store, steps, onDone = { screen = "chats" })
                        }
                        // The quick tour (CP69), from `--es page videos|home|player|controls|downloads|
                        // tvhome|settings`. Finishing or Skip all goes to the chat list.
                        "tour" -> {
                            val form = com.tmplayer.ui.components.deviceForm()
                            val tour = remember(form) {
                                val pages = com.tmplayer.ui.onboarding.Tour.pages(form)
                                com.tmplayer.ui.onboarding.TourProgress(
                                    pages,
                                    start = pages.indexOfFirst { it.name.equals(intent.getStringExtra("page"), ignoreCase = true) }.coerceAtLeast(0),
                                )
                            }
                            com.tmplayer.ui.onboarding.TourScreen(tour, onDone = { screen = "chats" })
                        }
                        // A page of it from `--es page appearance|playback|subtitles|...`.
                        "settings" -> {
                            val page = SettingsPage.entries.firstOrNull {
                                it.name.equals(intent.getStringExtra("page"), ignoreCase = true)
                            }
                            // A screen of its own, opened from the account's menu: no top bar over it,
                            // as in the app.
                            SettingsScreen(
                                chats = promoChats(),
                                // The demo account at the top, as the signed in one is in the app.
                                account = Account("Demo", "demo", null, 0),
                                onLoggedOut = {},
                                onBack = { screen = "chats" },
                                onOpenAbout = { screen = "about" },
                                initialPage = page,
                                accountFolders = if (intent.getBooleanExtra("folders", false) || groupsDemo) PROMO_FOLDERS else emptyList(),
                            )
                        }
                        "about" -> AboutScreen(onBack = { screen = "settings" })
                        // The Downloads screen as it is, over this build's own empty index: the
                        // storage panel and the "Remove after watching" row.
                        "downloads" -> PromoTvBar(shown = tv, current = null, inProfile = true, go = { screen = it }) {
                            DownloadsScreen(onPlay = {}, onBack = { screen = "chats" })
                        }
                        // Home's rows over demo chats: Continue, two starred chats (one of them
                        // a show), and the newest videos from the rest. `--es variant loading`
                        // leaves the starred rows and Recent waiting, `--es variant empty` is an
                        // account with nothing starred, played or posted.
                        "home" -> PromoChatsScreen(
                            onOpenChat = { screen = "media" },
                            onOpenSettings = { screen = "settings" },
                            onOpenDownloads = { screen = "downloads" },
                            folders = if (intent.getBooleanExtra("folders", false) || groupsDemo) PROMO_FOLDERS else emptyList(),
                            home = intent.getStringExtra("variant") ?: "rows",
                            section = intent.getStringExtra("section"),
                        )
                        else -> PromoChatsScreen(
                            onOpenChat = { screen = "media" },
                            onOpenSettings = { screen = "settings" },
                            onOpenDownloads = { screen = "downloads" },
                            // `--ez folders true` adds two Telegram folders, so the sidebar shows
                            // its Folders group; `--es update 9.9.9` adds the amber Update row.
                            folders = if (intent.getBooleanExtra("folders", false) || groupsDemo) PROMO_FOLDERS else emptyList(),
                            updateVersion = intent.getStringExtra("update"),
                            // `--es section saved|favorites|history` opens on that sidebar entry.
                            section = intent.getStringExtra("section"),
                        )
                    }
                    if (supportRung > 0 && screen == "chats") {
                        CoffeeDialog(
                            counters = promoCounters,
                            asking = true,
                            onLater = { supportRung = 0 },
                            onNever = { supportRung = 0 },
                        )
                    }
                    if (supporting) SupportDialog(from = "card1", onClose = { supporting = false })
                    // `--ez update_dialog true` opens the update popup over the screen, offering
                    // whatever the site's live feed names (this build calls itself x.y.z-promo).
                    if (updateDialog) {
                        LaunchedEffect(Unit) { com.tmplayer.data.Updates.check() }
                        com.tmplayer.ui.update.UpdateDialog(onDismiss = { updateDialog = false })
                    }
                    if (languageNotice && screen == "chats") {
                        val active by Translator.active.collectAsState()
                        LanguageNoticeCard(
                            language = active.tag,
                            onKeep = { languageNotice = false },
                            onChange = { languageNotice = false; overlay = "language" },
                            modifier = Modifier
                                .align(if (tv) Alignment.BottomEnd else Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(if (tv) 40.dp else 16.dp),
                        )
                    }
                    when (overlay) {
                        "language" -> LanguageDialog(SettingsStore(applicationContext), onClose = { overlay = null })
                        "whatsnew" -> WhatsNewDialog(onClose = { overlay = null }, onChangelog = { overlay = null })
                        "feedback" -> FeedbackDialog(onClose = { overlay = null })
                        // The CP41 sweep's prompts that the fixture cannot reach on its own: a
                        // Continue watching entry lives in MainActivity, and the online caches
                        // here are empty, which skips the question.
                        "forget" -> TvConfirm(
                            title = L.browseRemoveFromHistoryTitle,
                            message = L.browseRemoveFromHistoryMessage(title = "The Quiet Orbit"),
                            detail = L.browseRemoveSelectedDetail,
                            confirmLabel = L.commonRemove,
                            onConfirm = { overlay = null },
                            onDismiss = { overlay = null },
                        )
                        "online-cache" -> TvConfirm(
                            title = L.confirmOnlineCacheTitle,
                            message = L.confirmOnlineCacheMessage(size = "2.4 MB"),
                            confirmLabel = L.commonClear,
                            onConfirm = { overlay = null },
                            onDismiss = { overlay = null },
                        )
                        // The television's one-time browse hint, as it sits over the first chat list.
                        "tvhint" -> com.tmplayer.ui.browse.TvBrowseHintCard(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                        )
                        // The one-time hint after a stutter (CP68): the phone's bar, the TV's dialog.
                        "devicehint" -> {
                            val store = remember { SettingsStore(applicationContext) }
                            val light by store.lightSettings.collectAsState(initial = null)
                            val now = light
                            if (now != null) {
                                val list = com.tmplayer.ui.onboarding.recommendationsAfterStruggle(
                                    com.tmplayer.ui.onboarding.rememberDeviceFit(),
                                    now,
                                    com.tmplayer.ui.onboarding.rememberSeekThumbnailsShown(),
                                )
                                if (tv) {
                                    TvConfirm(
                                        title = L.deviceHintTitle,
                                        message = L.deviceHintBody,
                                        confirmLabel = L.deviceApply,
                                        cancelLabel = L.deviceNotNow,
                                        destructive = false,
                                        focusConfirm = true,
                                        extra = { com.tmplayer.ui.onboarding.RecommendationList(list) },
                                        onConfirm = { overlay = null },
                                        onDismiss = { overlay = null },
                                    )
                                } else {
                                    com.tmplayer.ui.onboarding.DeviceHintBar(
                                        onApply = { overlay = null },
                                        onNotNow = { overlay = null },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .windowInsetsPadding(WindowInsets.safeDrawing)
                                            .padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (confirming) {
                        val s = com.tmplayer.ui.i18n.LocalStrings.current
                        TvConfirm(
                            title = s.settingsSignOutTitle,
                            message = s.settingsSignOutMessage,
                            confirmLabel = s.settingsSignOutConfirm,
                            onConfirm = { confirming = false },
                            onDismiss = { confirming = false },
                        )
                    }
                }
                }
            }
        }
    }
}

private const val PROMO_QR_LINK = "tg://login?token=VE1QbGF5ZXIgZGVtbywgbm90IGEgbG9naW4"

/** The query `--es variant search` shows, and its results. */
private object PromoSearch {
    @Volatile
    var query = "harbor lines"

    /** Cinema Lounge's videos matched as the chat's search would, every file on its own. */
    fun results(): List<MediaItem> = Fuzzy.rank(promoMedia(), query) { it.searchText }
}

private val PROMO_FOLDERS = listOf(ChatFolderSummary(1, "Films"), ChatFolderSummary(2, "Family"))

/** `--ez groups_demo true`: the demo chats placed in the demo folders (see [promoChats]). */
private object PromoGroups {
    @Volatile
    var demo = false
}

@Composable
private fun promoChats(): List<ChatSummary> {
    // With the groups demo, Films holds Cinema Lounge and Anime Vault and Family the two personal
    // groups; Study Hub and Nature Docs are in no folder, so starring them gives the "Only my
    // folders" prompt two favourites to count.
    val inFolder: (Long) -> List<Int> = { id ->
        val folder = when (id) {
            PromoCatalog.CINEMA, PromoCatalog.ANIME -> 1
            PromoCatalog.CLIPS, PromoCatalog.FAMILY -> 2
            else -> null
        }
        if (PromoGroups.demo && folder != null) listOf(folder) else emptyList()
    }
    return remember {
        PromoCatalog.chats.map { chat ->
            ChatSummary(chat.id, chat.title, chat.avatar?.let(PromoMeta.pictures::bytes), 0, chat.kind, folderIds = inFolder(chat.id))
        }
    }
}

@Composable
private fun PromoChatsScreen(
    onOpenChat: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    state: UiState<BrowseData>? = null,
    folders: List<ChatFolderSummary> = emptyList(),
    updateVersion: String? = null,
    /** Null for the chat list; otherwise Home, in the named variant (see the "home" screen). */
    home: String? = null,
    /** A [BrowseTab] by name, any case, to open on instead of the default. */
    section: String? = null,
) {
    val chats = promoChats()
    // Picking a tab moves the highlight, so a walk down the sidebar shows its groups following.
    var picked by remember {
        val named = BrowseTab.entries.firstOrNull { it.name.equals(section, ignoreCase = true) }
        mutableStateOf<BrowseSection>(BrowseSection.of(named ?: if (home != null) BrowseTab.Home else BrowseTab.Chats))
    }
    val account = Account("Demo", "demo", null, 0)
    val homeData = promoHome(home)
    // The television's top bar, which BrowseScreen draws only when it is handed the bar's
    // places; without it the TV layout has nothing to draw.
    val tvNav = if (FormFactor.isTv(androidx.compose.ui.platform.LocalContext.current)) {
        promoTvNav(
            account = account,
            onHome = { picked = BrowseSection.of(BrowseTab.Home) },
            onChats = { picked = BrowseSection.of(BrowseTab.Chats) },
            onSettings = onOpenSettings,
            onDownloads = onOpenDownloads,
            onFavourites = { picked = BrowseSection.of(BrowseTab.Favorites) },
            onHistory = { picked = BrowseSection.of(BrowseTab.History) },
        )
    } else {
        null
    }
    BrowseScreen(
        tvNav = tvNav,
        state = state ?: UiState.Content(BrowseData(chats, account)),
        favorites = homeData?.favourites ?: PromoMeta.library.favourites,
        continueWatching = homeData?.continueWatching.orEmpty(),
        homeRows = homeData?.let {
            remember(it) {
                // Home as the app builds it: filled from the chats with videos, starred first.
                HomeRows.build(
                    it.continueWatching,
                    HomeRows.homeChats(chats, it.favourites, it.recent),
                    it.loaded,
                    it.recent,
                    watchLater = it.watchLater,
                    fresh = it.fresh,
                    recentMinStarred = 1,
                    starred = it.favourites,
                    autoFill = true,
                )
            }
        }.orEmpty(),
        homeArt = homeData?.art.orEmpty(),
        homeWatch = PROMO_WATCH,
        onRetry = {},
        onRefresh = {},
        onOpenChat = { onOpenChat() },
        onResumeMedia = {},
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
        onToggleFavorite = {},
        picked = picked,
        onPickTab = { picked = it },
        folders = folders,
        updateVersion = updateVersion,
        videoSearch = promoVideoSearch(),
    )
}

/**
 * Telegram's `searchMessages` across every chat, faked over the demo videos: the shows and the
 * clips spread over the demo chats, matched forgivingly against the file name, eight to a page
 * after a short wait, so "Searching every chat", the results and paging can all be captured.
 *
 * Two made-up reposts of Quiet Orbit (2024) in other demo chats, at 1080p and 720p, give the
 * search a card with copies across chats to show. The chat type filter is honoured, and each page
 * names its chats, as Telegram's does.
 */
@Composable
private fun promoVideoSearch(): VideoSearchSource {
    return remember {
        val orbit = PromoMeta.library.quietOrbit
        val reposts = listOf(
            orbit.copy(chatId = PromoCatalog.FAMILY, messageId = 7_001, fileId = 7_001, fileName = "Quiet.Orbit.2024.1080p.WEB-DL.mkv", sizeBytes = orbit.sizeBytes / 3, date = orbit.date - 400, onDevice = false),
            orbit.copy(chatId = PromoCatalog.CLIPS, messageId = 7_002, fileId = 7_002, fileName = "Quiet.Orbit.2024.720p.mkv", sizeBytes = orbit.sizeBytes / 6, date = orbit.date - 900, onDevice = false),
        ).map { it.copy(title = it.fileName) }
        val pool = PromoMeta.library.all + reposts
        val chats = PromoCatalog.chats.associateBy { it.id }
        VideoSearchSource { query, cursor, filters ->
            delay(PROMO_SEARCH_WAIT_MS)
            val kinds = when (filters.chatType) {
                com.tmplayer.data.SearchChatType.All -> null
                com.tmplayer.data.SearchChatType.Channels -> setOf(com.tmplayer.data.ChatKind.Channel)
                com.tmplayer.data.SearchChatType.Groups -> setOf(com.tmplayer.data.ChatKind.Group)
                com.tmplayer.data.SearchChatType.People -> setOf(com.tmplayer.data.ChatKind.Direct, com.tmplayer.data.ChatKind.Saved)
            }
            val hits = Fuzzy.rank(pool.filter { kinds == null || chats[it.chatId]?.kind in kinds }, query) { it.searchText }
            val start = cursor.videoOffset.toIntOrNull() ?: 0
            val page = hits.drop(start).take(PROMO_SEARCH_PAGE)
            val next = start + page.size
            AllChatsPage(
                items = page,
                cursor = AllChatsCursor(videoOffset = next.toString(), videoDone = next >= hits.size, documentDone = true),
                chats = page.map { it.chatId }.distinct().mapNotNull { id ->
                    chats[id]?.let { com.tmplayer.data.ChatBrief(it.id, it.title, it.kind, 0, it.avatar?.let(PromoMeta.pictures::bytes)) }
                },
            )
        }
    }
}

private const val PROMO_SEARCH_WAIT_MS = 600L
private const val PROMO_SEARCH_PAGE = 8

/** Everything Home is built from, as the view model would have it once every row has answered. */
private class PromoHome(
    val favourites: Set<Long>,
    val continueWatching: List<ResumeRecord>,
    val loaded: Map<Long, List<MediaItem>>,
    val recent: List<MediaItem>?,
    val art: Map<String, MediaItem>,
    val watchLater: List<com.tmplayer.data.WatchLaterRecord> = emptyList(),
    val fresh: List<com.tmplayer.data.NewEpisode> = emptyList(),
)

/**
 * Home over the demo library ([PromoCatalog.Library]): Continue watching with a film first, Watch later, New episodes, a row for each starred chat and
 * Recently added in them.
 */
private fun promoHome(variant: String?): PromoHome? {
    variant ?: return null
    if (variant == "empty") return PromoHome(emptySet(), emptyList(), emptyMap(), emptyList(), emptyMap())
    val library = PromoMeta.library
    val loading = variant == "loading"
    val starred = library.byChat.filterKeys { it in library.favourites }
    return PromoHome(
        favourites = library.favourites,
        continueWatching = library.continueWatching,
        // Every chat answered, as Home fills itself from the ones with videos, not only the starred.
        loaded = if (loading) starred.filterKeys { it == PromoCatalog.CINEMA } else library.byChat,
        recent = if (loading) null else library.all,
        art = library.art,
        watchLater = library.watchLater,
        fresh = library.newEpisodes,
    )
}

/**
 * The phone's chat listing: the app bar, then the chat's grid as [PromoShelfGrid] draws it.
 *
 * This is the real scaffold, arrangement and cards, not a drawing of them, so a shot taken here
 * cannot quietly disagree with what the app does.
 */
@Composable
private fun PhoneMediaScreen(variant: String, onBack: () -> Unit = {}) {
    val media = promoMedia()
    val series = promoSeries(variant)
    var held by remember { mutableStateOf(detailVariant(variant, media)) }
    TouchMediaScaffold(
        chatTitle = PromoCatalog.chatTitle(PromoCatalog.CINEMA),
        chatPhotoFileId = 0,
        chatMiniThumbnail = cinemaAvatar(),
        isFavorite = true,
        query = "",
        onQuery = {},
        onSubmit = {},
        onBack = onBack,
        onToggleFavorite = {},
        onRefresh = {},
        recentSearches = PROMO_RECENT,
        startSearching = variant == "recent",
    ) {
        val state = promoState(variant)
        if (state != null) {
            StateScaffold(
                state,
                onRetry = {},
                loading = { MediaGridSkeleton() },
                onAction = {},
            ) {}
            return@TouchMediaScaffold
        }
        PromoShelfGrid(series?.files ?: media, onHold = { held = it })
        series?.Opened()
        held?.let { PromoDetail(it) { held = null } }
    }
}

/**
 * The detail panel a long press, a held OK or the info key opens, in the grid's own context (it
 * offers Select videos). `--es variant menu` opens it on the first video as a fresh one is,
 * `resume` on one stopped at 34:10, and `protected` on one from a chat that restricts saving.
 */
@Composable
private fun PromoDetail(item: MediaItem, onDismiss: () -> Unit) {
    PromoDetail(item, PROMO_WATCH.point(item), PROMO_WATCH.finished(item), onDismiss)
}

@Composable
private fun PromoDetail(item: MediaItem, watched: WatchPoint?, finished: Boolean, onDismiss: () -> Unit) {
    MediaDetailOpened(
        item = item,
        chatTitle = PromoCatalog.chatTitle(item.chatId),
        watched = watched,
        finished = finished,
        onPlay = {},
        onSetWatched = {},
        onDownload = {},
        onSelectVideos = {},
        onDismiss = onDismiss,
    )
}

/** The variant's video for the panel: the first, stopped part way, or in a protected chat. */
private fun detailVariant(variant: String, media: List<MediaItem>): MediaItem? = when (variant) {
    "menu" -> media.first()
    // The film part way through, for its poster and overview with `--ez meta true`, or the line offering them without.
    "meta" -> PromoMeta.film
    "resume" -> PromoMeta.film
    "protected" -> media[1].copy(canBeSaved = false)
    else -> null
}

@Composable
private fun TvMediaScreen(variant: String) {
    val searching = variant == "search"
    val media = if (searching) remember { PromoSearch.results() } else promoMedia()
    val series = promoSeries(variant)
    var held by remember { mutableStateOf(detailVariant(variant, media)) }
    val first = remember { FocusRequester() }
    val state = promoState(variant)
    LaunchedEffect(Unit) { if (state == null) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Header(
            chatTitle = PromoCatalog.chatTitle(PromoCatalog.CINEMA),
            chatPhotoFileId = 0,
            chatMiniThumbnail = cinemaAvatar(),
            isFavorite = true,
            query = if (searching) PromoSearch.query else "",
            onQuery = {},
            onSubmit = {},
            onToggleFavorite = {},
            // Screenshots are taken on a TV, so the heading starts inside the overscan.
            edge = Tv.SafeH,
            onRefresh = {},
            // The fixture has nowhere to go back to. The pill is drawn, which is the point,
            // since the screenshots have to show the same header the app shows.
            onBack = {},
            recentSearches = PROMO_RECENT,
            alwaysShowRecent = variant == "recent",
        )
        if (state != null) {
            StateScaffold(
                state,
                onRetry = {},
                loading = { MediaGridSkeleton() },
                onAction = {},
            ) {}
            return@Column
        }
        PromoShelfGrid(series?.files ?: media, onHold = { held = it }, first = first, grouping = !searching)
    }
    series?.Opened()
    held?.let { PromoDetail(it) { held = null } }
}

/** The Cinema Lounge chat: the films, each copy on its own, then Harbour Lines' episodes. */
private fun promoMedia(): List<MediaItem> = PromoMeta.library.cinema

/**
 * `--es variant series-open`: the chat with the demo shows in it, every file on its own as the real
 * grid lists them, and the first show's page open over it (a bottom sheet on the phone, a page on
 * the TV). `--es show "skybound academy"` opens that show's page instead of Harbour Lines'.
 */
private class PromoSeries(
    val files: List<MediaItem>,
    private val opened: Series?,
    private val close: () -> Unit,
) {
    @Composable
    fun Opened() {
        val show = opened ?: return
        SeriesOpened(series = show, watch = PROMO_WATCH, onPlay = {}, onDismiss = close)
    }
}

@Composable
private fun promoSeries(variant: String): PromoSeries? {
    if (variant != "series-open") return null
    val media = promoSeriesMedia()
    val arranged = remember(media) { SeriesShelf.arrange(media) }
    var open by remember { mutableStateOf(true) }
    return PromoSeries(
        files = media,
        opened = arranged.firstNotNullOfOrNull { (it as? ShelfEntry.Show)?.series?.takeIf { s -> s.key == PromoShowOverride.key } }
            ?.takeIf { open },
        close = { open = false },
    )
}

/**
 * A chat's grid over [items], drawn the way [com.tmplayer.ui.browse.MediaGridScreen] draws it: the
 * same arrangement ([arrangeShelf]: a show as one tile, copies of a film as one poster, posters
 * first), the same column counts ([TileGrid]), the same sections and spans ([shelfGrid]) and the
 * same cards, with the demo viewer's watch state on them. Only the view model is left out.
 */
@Composable
private fun PromoShelfGrid(
    items: List<MediaItem>,
    onHold: (MediaItem) -> Unit,
    first: FocusRequester? = null,
    // Off while a search is showing, as the real grid lists every file a search finds on its own.
    grouping: Boolean = true,
) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    val touch = !FormFactor.isTv(androidx.compose.ui.platform.LocalContext.current)
    val edge = if (touch) com.tmplayer.ui.browse.TOUCH_EDGE else Tv.SafeH
    val watch = PROMO_WATCH
    val progress = remember(items) {
        items.mapNotNull { item -> watch.point(item)?.let { SettingsStore.progressKey(item.chatId, item.messageId) to it } }.toMap()
    }
    val shelved = remember(items) { com.tmplayer.ui.browse.arrangeShelf(items, progress, grouping = grouping) }
    val feed = remember(shelved) { shelved.shelf.map { com.tmplayer.data.MediaFeedEntry.Media(it) } }
    var openSeries by remember { mutableStateOf<Series?>(null) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val posterColumns = com.tmplayer.ui.components.TileGrid.posterColumns(maxWidth - edge * 2, touch, landscape)
        val wideColumns = com.tmplayer.ui.components.TileGrid.wideColumns(maxWidth - edge * 2, touch, landscape)
        val columns = com.tmplayer.ui.components.TileGrid.columns(posterColumns, wideColumns)
        val gap = if (touch) com.tmplayer.ui.components.TileGrid.TouchGap else com.tmplayer.ui.components.TileGrid.TvGap
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = edge,
                end = edge,
                top = if (touch) 0.dp else Tv.FocusClearance,
                bottom = if (touch) 32.dp else Tv.SafeV + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
                shelfGrid(feed, shelved.posterKeys, columns, posterColumns, wideColumns) { entry ->
                    val shelf = (entry as com.tmplayer.data.MediaFeedEntry.Media<ShelfEntry>).item
                    val focus = if (first != null && shelf.key == shelved.shelf.firstOrNull()?.key) Modifier.focusRequester(first) else Modifier
                    if (shelf is ShelfEntry.Show) {
                        com.tmplayer.ui.browse.SeriesCard(
                            series = shelf.series,
                            progress = remember(shelf.series) { watch.progress(shelf.series) },
                            fresh = remember(shelf.series) { watch.newEpisodes(shelf.series) > 0 || shelf.series.key in PROMO_FRESH_SHOWS },
                            onClick = { openSeries = shelf.series },
                            dense = touch,
                            poster = true,
                            modifier = focus,
                        )
                    } else {
                        val item = (shelf as ShelfEntry.File).item
                        MediaCard(
                            item = item,
                            watched = watch.point(item),
                            finished = watch.finished(item),
                            dense = touch,
                            poster = shelf.key in shelved.posterKeys,
                            onClick = { onHold(item) },
                            onLongClick = { onHold(item) },
                            onFocused = {},
                            modifier = focus,
                        )
                    }
                }
        }
    }
    openSeries?.let { SeriesOpened(series = it, watch = watch, onPlay = {}, onDismiss = { openSeries = null }) }
}

/** The shows with episodes the demo viewer has not seen yet, for the "New" badge (see [PromoCatalog.Library.newEpisodes]). */
private val PROMO_FRESH_SHOWS = setOf("harbour lines", "skybound academy")

/**
 * The demo's three shows together, named the ways real uploads are: a scene release (Harbour
 * Lines), the fansub dash form (Skybound Academy) and a documentary posted in 4K (Wild Coasts),
 * with the films after them.
 */
private fun promoSeriesMedia(): List<MediaItem> = PromoMeta.library.let { it.anime + it.nature + it.cinema }

/** The show `--es variant series-open` opens, by its key; `--es show` sets it. */
private object PromoShowOverride {
    var key = "harbour lines"
}

/** Where the demo viewer is: see [PromoCatalog.Library]. */
private val PROMO_WATCH: SeriesWatch
    get() = PromoMeta.library.let { library -> SeriesWatch(point = library::point, finished = library::isFinished) }

/** What the fixture's recent-search chips say: the kind of thing somebody types into a chat. */
private val PROMO_RECENT = listOf("quiet orbit", "harbour lines", "lesson 03", "skybound")

/**
 * The media screen's states, as the view model would publish them, for `--es variant`. Null for
 * the variants that draw the grid itself.
 */
private fun promoState(variant: String): UiState<Unit>? = when (variant) {
    "empty-hidden" -> UiState.Empty(
        "${noVideosWithin(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)}\n\n${L.browseHiddenBySize(12)} ${L.browseSizeLimitsInSettings}",
        icon = com.tmplayer.ui.icons.TmIcons.FilterHorizontal,
    )
    "empty-more" -> UiState.Empty(STILL_MORE_TO_SEARCH, StateAction.KeepLooking)
    "slow" -> UiState.Loading(L.browseFindingVideos)
    else -> null
}

/** See `--ez watchnext true` above. Honours the setting, so it writes nothing while that is off. */
private fun ComponentActivity.promoWatchNext() {
    val context = applicationContext
    val supported = com.tmplayer.data.DeviceQuirks.remote(context).watchNextSupported
    fun episode(n: Int) = com.tmplayer.data.WatchNext.Video(
        chatId = 1, messageId = n.toLong(), fileId = n, title = "The Coast S01E0$n",
        chatTitle = "Nature Channel", sizeBytes = 1_400_000_000, durationSec = 2_634,
    )
    Thread {
        val enabled = runBlocking { SettingsStore(context).watchNextNow() }
        val now = System.currentTimeMillis()
        val changes = com.tmplayer.data.WatchNext.changesFor(episode(2), 2_600_000, 2_634_000, now - 60_000, episode(3), enabled, supported) +
            com.tmplayer.data.WatchNext.changesFor(episode(4), 1_265_000, 2_634_000, now, episode(5), enabled, supported)
        android.util.Log.i("TMPlayerPromo", "Watch Next changes: $changes")
        runBlocking { com.tmplayer.data.WatchNextPublisher.apply(context, changes) }
    }.start()
}

/**
 * See `--ez downloads_demo true` above: four demo videos recorded as downloads, each over a sparse
 * file of its full size in this build's own cache, so the rows read as whole downloads without the
 * gigabytes ever being written. Anything recorded before is dropped first.
 */
private fun ComponentActivity.promoDownloads() {
    val dir = java.io.File(cacheDir, "promo-downloads").apply { deleteRecursively(); mkdirs() }
    runBlocking {
        val store = SettingsStore(applicationContext)
        store.downloadsNow().forEach { store.forgetDownload(it.chatId, it.messageId) }
        PROMO_DOWNLOADS.mapNotNull { part -> PromoMeta.library.all.firstOrNull { part in it.fileName } }
            .forEachIndexed { index, item ->
                val file = java.io.File(dir, item.fileName)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(item.sizeBytes) }
                // A record needs a file id; the demo videos have none, never having been in TDLib.
                store.noteDownload(item.copy(fileId = PROMO_DOWNLOAD_IDS + index), PromoCatalog.chatTitle(item.chatId), file.path)
            }
    }
}

/**
 * The demo videos [promoDownloads] keeps, by a part of each file name: two anime episodes and two
 * lessons, small enough together to sit inside the emulator's free space as the panel draws it.
 */
private val PROMO_DOWNLOADS = listOf("Skybound Academy - 04", "Skybound Academy - 03", "Lesson 04", "Lesson 03")
private const val PROMO_DOWNLOAD_IDS = 900_000

/** See `--ez channels true` above. Honours the setting, so it writes nothing while that is off. */
private fun ComponentActivity.promoChannels() {
    val context = applicationContext
    val library = PromoMeta.library
    // The demo videos' pictures are this build's drawables, not Telegram thumbnails: each card's
    // picture is written out from the video's own frame, found by its name. The videos the library
    // keeps without a picture (the in-app tiles show the generated cover there) wear a demo frame
    // that suits them, as a real upload's thumbnail would be what the launcher gets.
    val frames = java.io.File(context.cacheDir, "promo-channel-art").apply { mkdirs() }
    com.tmplayer.data.TvChannelsPublisher.fixtureArt = { art ->
        val item = library.all.firstOrNull { it.nameForParsing == art.name && it.caption == art.caption }
        val bytes = item?.miniThumbnail ?: item?.let { PromoMeta.pictures.bytes(promoChannelFrame(it)) }
        bytes?.let {
            java.io.File(frames, "${art.key.hashCode().toUInt()}.jpg").apply { if (!isFile) writeBytes(it) }
        }
    }
    Thread {
        if (!runBlocking { SettingsStore(context).tvChannelsNow() }) return@Thread
        val chats = PromoCatalog.chats.map { ChatSummary(it.id, it.title, null, 0, it.kind) }
        // As Home builds them: its own chats, starred or not.
        val rows = HomeRows.build(
            library.continueWatching,
            HomeRows.homeChats(chats, library.favourites, library.all),
            library.byChat,
            library.all,
            watchLater = library.watchLater,
            fresh = library.newEpisodes,
            recentMinStarred = 1,
            autoFill = true,
        )
        val plan = com.tmplayer.data.TvChannels.plan(
            rows = rows,
            allowed = null,
            chatTitle = PromoCatalog::chatTitle,
            progress = { chat, message -> library.all.firstOrNull { it.chatId == chat && it.messageId == message }?.let(library::point) },
            art = library.art,
        ).orEmpty()
        android.util.Log.i("TMPlayerPromo", "channel cards: ${plan.size}")
        runBlocking {
            com.tmplayer.data.TvChannelsPublisher.publish(context, plan) { channel ->
                when (channel) {
                    com.tmplayer.data.TvChannels.Channel.Recent -> L.homeChannelRecent
                    com.tmplayer.data.TvChannels.Channel.WatchLater -> L.homeRowWatchLater
                    com.tmplayer.data.TvChannels.Channel.NewEpisodes -> L.homeRowNewEpisodes
                }
            }
        }
    }.start()
}

/** A demo frame for a video the library keeps without a picture, for its home screen card. */
private fun promoChannelFrame(item: MediaItem): String = when {
    item.chatId == PromoCatalog.STUDY -> "demo_tutorial"
    "Kestrel" in item.fileName -> "demo_coast"
    "Tiffin" in item.fileName -> "demo_kitchen"
    "ladakh" in item.fileName -> "demo_forest"
    else -> "demo_workshop"
}

/** `tv` or `tablet` answers the Fire OS question as that device would; anything else leaves it. */
internal fun promoFireOs(asked: String?) {
    when (asked) {
        "tv" -> com.tmplayer.data.DeviceQuirks.override(com.tmplayer.data.FireOs.Kind.Tv)
        "tablet" -> com.tmplayer.data.DeviceQuirks.override(com.tmplayer.data.FireOs.Kind.Tablet)
    }
}

/** Saves [tag] as the UI language and switches to it now, ahead of the first composition. */
internal fun ComponentActivity.promoLanguage(asked: String?) {
    // `--es lang system` follows the system again: adb cannot pass an empty string.
    val tag = if (asked == "system") "" else asked ?: return
    runBlocking { SettingsStore(applicationContext).setLanguage(tag) }
    Translator.select(tag, com.tmplayer.data.AppLocales.system(applicationContext))
}

/** The Cinema Lounge chat's picture, for the listing's header as the chat list shows it. */
private fun cinemaAvatar(): ByteArray? =
    PromoCatalog.chats.first { it.id == PromoCatalog.CINEMA }.avatar?.let(PromoMeta.pictures::bytes)

/** The top bar's places over the demo library, for the television layout. */
internal fun promoTvNav(
    account: Account = Account("Demo", "demo", null, 0),
    onHome: () -> Unit = {},
    onChats: () -> Unit = {},
    onSettings: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onFavourites: () -> Unit = {},
    onHistory: () -> Unit = {},
) = com.tmplayer.ui.browse.TouchNav(
    account = account,
    favoriteCount = PromoMeta.library.favourites.size,
    downloadCount = 0,
    updateVersion = null,
    onHome = onHome,
    onChats = onChats,
    onSearch = {},
    onSettings = onSettings,
    onFavourites = onFavourites,
    onDownloads = onDownloads,
    onHistory = { onHistory() },
    onUpdate = {},
)

/**
 * Downloads under the television's top bar, as MainActivity puts it; a tab picked on it, or a
 * row of the account's menu, goes to that fixture screen.
 */
@Composable
private fun PromoTvBar(
    shown: Boolean,
    current: com.tmplayer.ui.browse.TvBarTab?,
    inProfile: Boolean,
    go: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!FormFactor.isTv(androidx.compose.ui.platform.LocalContext.current)) {
        content()
        return
    }
    val barFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    val nav = promoTvNav(
        onHome = { go("home") },
        onChats = { go("chats") },
        onSettings = { go("settings") },
        onDownloads = { go("downloads") },
    )
    com.tmplayer.ui.browse.TvBarFrame(
        shown = shown,
        barFocus = barFocus,
        contentFocus = pageFocus,
        bar = {
            com.tmplayer.ui.browse.TvTopBar(
                nav = nav,
                current = current,
                inProfile = inProfile,
                searchOpen = false,
                onHome = nav.onHome,
                onChats = nav.onChats,
                onSearch = { go("chats") },
                onRefresh = null,
                currentFocus = barFocus,
                contentFocus = pageFocus,
                overArt = false,
            )
        },
        content = content,
    )
}
