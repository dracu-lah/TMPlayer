package com.tmplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tmplayer.i18n.L
import kotlinx.coroutines.runBlocking
import com.tmplayer.data.Account
import com.tmplayer.data.AuthState
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.data.CardLayout
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Translator
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesCard
import com.tmplayer.ui.browse.SeriesOpened
import com.tmplayer.ui.browse.SeriesViewToggle
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.Header
import com.tmplayer.ui.browse.MediaActionsSheet
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
import com.tmplayer.ui.browse.HiddenVideosNote
import com.tmplayer.ui.browse.FIRST_LOAD_TIP
import com.tmplayer.data.SizeFilter
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.padding
import com.tmplayer.ui.onboarding.OnboardingPage
import com.tmplayer.ui.onboarding.OnboardingScreen
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.SupportCard
import com.tmplayer.ui.settings.SupportDialog
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
 * the slow-answer line), and `media` with `--es variant hidden|empty-hidden|empty-more|slow|recent`.
 * `--es variant menu` opens the first video's menu over the grid, with its "x GB free" line, and
 * `--es screen downloads` is the Downloads screen, `--es screen support` the support codes over
 * Settings, and `--ez support_reminder true` the support card over the chat list. Add
 * `--ez tv true` to capture the television layout on a phone panel resized to 1920x1080, which is
 * how the TV shots on the site are taken now that the stick is not the only device this app has to look right on.
 *
 * Every picture in here is a demo drawable shipped with this build. Nothing on screen belongs to
 * anybody, which is the point: the shots on the README and the site can be published as they are.
 */
class PromoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val tv = intent.getBooleanExtra("tv", false)
        // Before setContent, because the whole tree branches on it during the first composition.
        FormFactor.override(tv)
        requestedOrientation = if (tv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        if (tv) {
            // A television has no status bar and no gesture pill, and a shot of the TV layout with
            // a phone's clock and battery in the corner would be a picture of something that does
            // not exist.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).hide(
                WindowInsetsCompat.Type.systemBars(),
            )
        }
        // `--es theme light|dark` pins the appearance, so the site can carry a light picture for
        // its light theme and a dark one for its dark theme rather than showing a black phone on
        // a white page. Written to this build's own preferences, which are its own: the promo
        // application id is separate, so nothing here can touch a real install's setting.
        intent.getStringExtra("theme")?.let { wanted ->
            val choice = when (wanted.lowercase()) {
                "light" -> ThemeChoice.Light
                "dark" -> ThemeChoice.Dark
                else -> ThemeChoice.System
            }
            runBlocking { SettingsStore(applicationContext).setThemeChoice(choice) }
        }

        // `--es lang en-XA` (or any shipped tag) pins the UI language for the shot, the same way
        // the Settings choice would; "" goes back to following the system.
        promoLanguage(intent.getStringExtra("lang"))

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
            var supportCard by remember { mutableStateOf(intent.getBooleanExtra("support_reminder", false)) }
            // `--ez confirm true` puts the sign out prompt over whatever screen is open, so the
            // confirm dialog can be captured beside the other modals.
            var confirming by remember { mutableStateOf(intent.getBooleanExtra("confirm", false)) }
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
                        // The tour, from `--es page language|about|signin|chats|videos`.
                        "overview" -> OnboardingScreen(
                            firstRun = true,
                            onDone = { screen = "chats" },
                            start = OnboardingPage.entries.firstOrNull {
                                it.name.equals(intent.getStringExtra("page"), ignoreCase = true)
                            } ?: OnboardingPage.Language,
                        )
                        "settings" -> SettingsScreen(
                            chats = promoChats(),
                            onLoggedOut = {},
                            onBack = { screen = "chats" },
                            onOpenAbout = { screen = "about" },
                        )
                        "about" -> AboutScreen(onBack = { screen = "settings" })
                        // The Downloads screen as it is, over this build's own empty index: the
                        // storage panel and the "Remove after watching" row.
                        "downloads" -> DownloadsScreen(onPlay = {}, onBack = { screen = "chats" })
                        else -> PromoChatsScreen(
                            onOpenChat = { screen = "media" },
                            onOpenSettings = { screen = "settings" },
                            onOpenDownloads = { screen = "downloads" },
                            // `--ez folders true` adds two Telegram folders, so the sidebar shows
                            // its Folders group; `--es update 9.9.9` adds the amber Update row.
                            folders = if (intent.getBooleanExtra("folders", false)) PROMO_FOLDERS else emptyList(),
                            updateVersion = intent.getStringExtra("update"),
                            // `--es layout grid` for the chat tiles rather than the list.
                            layout = if (intent.getStringExtra("layout") == "grid") CardLayout.Grid else CardLayout.List,
                        )
                    }
                    if (supportCard && screen == "chats") {
                        SupportCard(
                            onSupport = { supportCard = false; supporting = true },
                            onNotNow = { supportCard = false },
                            onNever = { supportCard = false },
                            modifier = Modifier
                                .align(if (tv) Alignment.BottomEnd else Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(if (tv) 40.dp else 16.dp),
                        )
                    }
                    if (supporting) SupportDialog(onClose = { supporting = false })
                    if (confirming) {
                        TvConfirm(
                            title = "Sign out of Telegram?",
                            message = "You'll be signed out and taken back to the sign-in screen. The cache, your " +
                                "favourites, your watched list and everything you were part-way through go with it.",
                            confirmLabel = "Sign out",
                            onConfirm = { confirming = false },
                            onDismiss = { confirming = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun imageBytes(@DrawableRes drawable: Int): ByteArray {
    val resources = LocalContext.current.resources
    return remember(drawable) { resources.openRawResource(drawable).use { it.readBytes() } }
}

private val PROMO_FOLDERS = listOf(ChatFolderSummary(1, "Films"), ChatFolderSummary(2, "Family"))

@Composable
private fun promoChats(): List<ChatSummary> = listOf(
    ChatSummary(101, "Weekend Clips", imageBytes(R.drawable.demo_coast), 0, ChatKind.Group),
    ChatSummary(102, "Home Projects", imageBytes(R.drawable.demo_workshop), 0, ChatKind.Channel),
    ChatSummary(103, "Recipe Notes", imageBytes(R.drawable.demo_kitchen), 0, ChatKind.Group),
    ChatSummary(104, "Travel Diary", imageBytes(R.drawable.demo_forest), 0, ChatKind.Channel),
    ChatSummary(105, "Design Study", imageBytes(R.drawable.demo_tutorial), 0, ChatKind.Direct),
    ChatSummary(106, "Family Archive", imageBytes(R.drawable.demo_birthday), 0, ChatKind.Group),
)

@Composable
private fun PromoChatsScreen(
    onOpenChat: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    state: UiState<BrowseData>? = null,
    layout: CardLayout = CardLayout.List,
    folders: List<ChatFolderSummary> = emptyList(),
    updateVersion: String? = null,
) {
    val chats = promoChats()
    // Picking a tab moves the highlight, so a walk down the sidebar shows its groups following.
    var picked by remember { mutableStateOf<BrowseSection>(BrowseSection.of(BrowseTab.Recent)) }
    val account = Account("Demo", "demo", null, 0)
    BrowseScreen(
        state = state ?: UiState.Content(BrowseData(chats, account)),
        favorites = setOf(102, 104),
        continueWatching = emptyList(),
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
        layout = layout,
        updateVersion = updateVersion,
    )
}

/**
 * The phone's chat listing: an app bar, then Telegram's dense captionless grid.
 *
 * This is the real scaffold and the real card, not a drawing of them, so a shot taken here cannot
 * quietly disagree with what the app does.
 */
@Composable
private fun PhoneMediaScreen(variant: String, onBack: () -> Unit = {}) {
    val media = promoMedia()
    val series = promoSeries(variant)
    TouchMediaScaffold(
        chatTitle = "Weekend Clips",
        chatPhotoFileId = 0,
        chatMiniThumbnail = imageBytes(R.drawable.demo_coast),
        isFavorite = true,
        query = "",
        onQuery = {},
        onSubmit = {},
        onBack = onBack,
        onToggleFavorite = {},
        layout = CardLayout.Grid,
        onToggleLayout = {},
        onRefresh = {},
        recentSearches = PROMO_RECENT,
        startSearching = variant == "recent",
    ) {
        val state = promoState(variant)
        if (state != null) {
            StateScaffold(
                state,
                onRetry = {},
                loading = { MediaGridSkeleton(layout = CardLayout.Grid) },
                onAction = {},
            ) {}
            return@TouchMediaScaffold
        }
        LazyVerticalGrid(
            // Three, which is what the real grid computes for a 393dp phone from its minimum
            // tile width. The fixture has to draw what the app draws or the screenshots are of a
            // layout nobody has.
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = DENSE_GAP, end = DENSE_GAP),
            horizontalArrangement = Arrangement.spacedBy(DENSE_GAP),
            verticalArrangement = Arrangement.spacedBy(DENSE_GAP),
        ) {
            // Three times through the set, so the grid runs past the bottom of the panel the way a
            // real chat's does. A shot that ends in empty background reads as an empty chat, and
            // twice was enough only while a tile was a bare picture: the caption under each one
            // costs two lines and a meta row, which is a whole row of tiles fewer per screen.
            val tiles = (0 until 3).flatMap { pass ->
                media.map { it.copy(messageId = it.messageId + pass * media.size) }
            }
            if (variant == "hidden") {
                item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(horizontal = 12.dp)) { HiddenVideosNote(12, 0) {} }
                }
            }
            if (series != null) {
                item(key = "series-toggle", span = { GridItemSpan(maxLineSpan) }) {
                    SeriesViewToggle(series.on, series.onChange, Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
                items(series.entries, key = { it.key }) { entry ->
                    when (entry) {
                        is ShelfEntry.Show -> SeriesCard(
                            series = entry.series,
                            progress = PROMO_WATCH.progress(entry.series),
                            onClick = { series.open(entry.series.key) },
                            dense = true,
                        )
                        is ShelfEntry.File -> PromoCard(entry.item, dense = true)
                    }
                }
            } else {
                items(tiles, key = { it.messageId }) { item ->
                    MediaCard(item = item, watched = null, onClick = {}, onFocused = {}, dense = true)
                }
            }
        }
        series?.Opened()
        if (variant == "menu") PromoMenu(media.first())
    }
}

/** The menu a long press or a held OK opens, for the first video, in the state a fresh one is in. */
@Composable
private fun PromoMenu(item: MediaItem) {
    var open by remember { mutableStateOf(true) }
    if (!open) return
    MediaActionsSheet(
        item = item,
        chatTitle = "Weekend Clips",
        watched = null,
        finished = false,
        onSetWatched = {},
        onPlay = {},
        onSelectVideos = {},
        onDownloadForLater = {},
        onRemoved = {},
        onDismiss = { open = false },
    )
}

@Composable
private fun TvMediaScreen(variant: String) {
    val media = promoMedia()
    val series = promoSeries(variant)
    val first = remember { FocusRequester() }
    val state = promoState(variant)
    LaunchedEffect(Unit) { if (state == null) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Header(
            chatTitle = "Weekend Clips",
            chatPhotoFileId = 0,
            chatMiniThumbnail = imageBytes(R.drawable.demo_coast),
            isFavorite = true,
            query = "",
            onQuery = {},
            onSubmit = {},
            onToggleFavorite = {},
            layout = CardLayout.Grid,
            // Screenshots are taken on a TV, so the heading starts inside the overscan.
            edge = Tv.SafeH,
            onToggleLayout = {},
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
                loading = { MediaGridSkeleton(layout = CardLayout.Grid) },
                onAction = {},
            ) {}
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(
                start = Tv.SafeH,
                end = Tv.SafeH,
                top = Tv.FocusClearance,
                bottom = Tv.SafeV + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (variant == "hidden") {
                item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) { HiddenVideosNote(12, 0) {} }
            }
            if (series != null) {
                item(key = "series-toggle", span = { GridItemSpan(maxLineSpan) }) {
                    SeriesViewToggle(series.on, series.onChange)
                }
                items(series.entries, key = { it.key }) { entry ->
                    val focus = if (entry === series.entries.first()) Modifier.focusRequester(first) else Modifier
                    when (entry) {
                        is ShelfEntry.Show -> SeriesCard(
                            series = entry.series,
                            progress = PROMO_WATCH.progress(entry.series),
                            onClick = { series.open(entry.series.key) },
                            modifier = focus,
                        )
                        is ShelfEntry.File -> PromoCard(entry.item, modifier = focus)
                    }
                }
            } else {
                items(media, key = { it.id }) { item ->
                    MediaCard(
                        item = item,
                        watched = null,
                        onClick = {},
                        onFocused = {},
                        modifier = if (item === media.first()) {
                            Modifier.focusRequester(first)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
    series?.Opened()
    if (variant == "menu") PromoMenu(media.first())
}

@Composable
private fun promoMedia(): List<MediaItem> {
    @Composable
    fun item(
        id: Long,
        title: String,
        drawable: Int,
        sizeMb: Long,
        duration: Int,
        fileName: String,
    ) = MediaItem(
        chatId = 101,
        messageId = id,
        fileId = 0,
        title = title,
        sizeBytes = sizeMb * 1024 * 1024,
        durationSec = duration,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = imageBytes(drawable),
        date = 0,
        fileName = fileName,
    )
    return listOf(
        item(1, "Coast walk, day 2", R.drawable.demo_coast, 428, 1_482, "coast-walk-day-2-1080p.mp4"),
        item(2, "Chickpea salad recipe", R.drawable.demo_kitchen, 186, 724, "chickpea-salad-1080p.mp4"),
        item(3, "Build a small shelf, part 1", R.drawable.demo_workshop, 612, 2_115, "small-shelf-part-1-1080p.mkv"),
        item(4, "Birthday highlights", R.drawable.demo_birthday, 344, 1_104, "birthday-highlights-1080p.mp4"),
        item(5, "Shape basics tutorial", R.drawable.demo_tutorial, 238, 968, "shape-basics-1080p.webm"),
        item(6, "Forest trail morning", R.drawable.demo_forest, 391, 1_376, "forest-trail-1080p.mp4"),
    )
}

/**
 * The series variants: `--es variant series` folds the demo shows into tiles, `series-open` opens
 * the first show (a bottom sheet on the phone, a page on the TV), and `files` is the same chat
 * with "All files" chosen. The toggle works in all three, so one run can walk between them.
 */
private class PromoSeries(
    val on: Boolean,
    val onChange: (Boolean) -> Unit,
    val entries: List<ShelfEntry>,
    val open: (String) -> Unit,
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
    if (variant != "series" && variant != "series-open" && variant != "files") return null
    val media = promoSeriesMedia() + promoMedia()
    var on by remember { mutableStateOf(variant != "files") }
    val arranged = remember(media) { SeriesShelf.arrange(media) }
    var openKey by remember { mutableStateOf(if (variant == "series-open") "harbour notes" else null) }
    return PromoSeries(
        on = on,
        onChange = { on = it },
        entries = if (on) arranged else media.map { ShelfEntry.File(it) },
        open = { openKey = it },
        opened = arranged.firstNotNullOfOrNull { (it as? ShelfEntry.Show)?.series?.takeIf { s -> s.key == openKey } },
        close = { openKey = null },
    )
}

/** A video's tile in the fixture, with the demo watch state on it. */
@Composable
private fun PromoCard(item: MediaItem, modifier: Modifier = Modifier, dense: Boolean = false) {
    MediaCard(
        item = item,
        watched = PROMO_WATCH.point(item),
        finished = PROMO_WATCH.finished(item),
        onClick = {},
        onFocused = {},
        dense = dense,
        modifier = modifier,
    )
}

/**
 * Three made-up shows, named the ways real uploads are: a scene release, the fansub dash form, and
 * a file named after nothing with the episode in its caption.
 */
@Composable
private fun promoSeriesMedia(): List<MediaItem> {
    val pictures = listOf(
        R.drawable.demo_coast, R.drawable.demo_forest, R.drawable.demo_workshop,
        R.drawable.demo_kitchen, R.drawable.demo_tutorial, R.drawable.demo_birthday,
    ).map { imageBytes(it) }
    var id = 200L
    fun episode(fileName: String, sizeMb: Long, minutes: Int, caption: String = "") = MediaItem(
        chatId = 101,
        messageId = id++,
        fileId = 0,
        title = fileName,
        sizeBytes = sizeMb * 1024 * 1024,
        durationSec = minutes * 60,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = pictures[(id % pictures.size).toInt()],
        date = id.toInt(),
        fileName = fileName,
        caption = caption,
    )
    val harbour = (1..6).map { episode("Harbour.Notes.S01E%02d.1080p.WEB-DL.mkv".format(it), 820, 44) } +
        (1..4).map { episode("Harbour.Notes.S02E%02d.1080p.WEB-DL.mkv".format(it), 860, 47) }
    val garden = (1..5).map { episode("[Demo] Sky Garden - %02d (1080p).mkv".format(it), 340, 24) }
    val kitchen = (1..3).map { episode("kitchen_journal_720p_part$it.mp4", 210, 18, caption = "Kitchen Journal Ep $it\nNew every Friday") }
    return (harbour + garden + kitchen).reversed()
}

/** Season one of Harbour Notes watched up to E04, which is half way through; Sky Garden done. */
private val PROMO_WATCH = SeriesWatch(
    point = { item ->
        if (item.fileName.contains("S01E04")) WatchPoint(positionMs = 19 * 60_000L, durationMs = 44 * 60_000L) else null
    },
    finished = { item ->
        Regex("""S01E0[1-3]""").containsMatchIn(item.fileName) || item.fileName.contains("Sky Garden")
    },
)

/** Telegram's grid gap, matched to [com.tmplayer.ui.browse] so the shot is the real spacing. */
private val DENSE_GAP = 2.dp

/** What the fixture's recent-search chips say: the kind of thing somebody types into a chat. */
private val PROMO_RECENT = listOf("coast walk", "shelf part 2", "birthday", "recipe")

/**
 * The media screen's states, as the view model would publish them, for `--es variant`. Null for
 * the variants that draw the grid itself.
 */
private fun promoState(variant: String): UiState<Unit>? = when (variant) {
    "empty-hidden" -> UiState.Empty(
        "${noVideosWithin(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)}\n\n${L.browseHiddenBySize(12)}",
        StateAction.ShowHidden,
    )
    "empty-more" -> UiState.Empty(STILL_MORE_TO_SEARCH, StateAction.KeepLooking)
    "slow" -> UiState.Loading(L.browseFindingVideos)
    else -> null
}

/** Saves [tag] as the UI language and switches to it now, ahead of the first composition. */
internal fun ComponentActivity.promoLanguage(tag: String?) {
    if (tag == null) return
    runBlocking { SettingsStore(applicationContext).setLanguage(tag) }
    Translator.select(tag, LocaleList.getDefault().toLanguageTags().split(','))
}
