package com.tmplayer.tvux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.tmplayer.data.AuthState
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.UiScale
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.GlobalSearchState
import com.tmplayer.ui.browse.ListPage
import com.tmplayer.ui.browse.MediaDetailOpened
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.TouchNav
import com.tmplayer.ui.browse.TvSearchScreen
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.lock.ProfilesGate
import com.tmplayer.ui.profiles.ProfileKit
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.railTag
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.UiScaled
import com.tmplayer.ui.theme.focusRingColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/**
 * The tv-ux-review checklist on every TV screen (WP-16): entry focus, labels and roles, focus rect
 * height, the safe area, the arrows' graph (no traps, reach), key ownership from
 * `docs/agents/tv-keys.md`, Back to the opener, focus size and drawn rect, the drawn rect inside
 * its clip, focus contrast, selected against focused, a held Down's stale repeats on the long
 * lists, and the 14 sp text floor at three interface sizes and two font scales. A finding no
 * `tvux-allow/WP-xx.txt` line covers fails the screen's test.
 *
 * Behind `-Ptvux=true` (app/build.gradle.kts): every batch gate runs it once; the pre-push hook does
 * not. The player's row is [TvUxPlayerRowTest] (Views); first content within 3 s is `HomeScaleTest`
 * in `ui` jvmTest on `SyntheticAccount`, not here.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvUxChecklistTest(private val screen: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = SCREENS.map { arrayOf<Any>(it.name) }

        /** The interface sizes and font scales the text floor is read at (the skill's 10-foot rules). */
        val PASSES = listOf(UiScale.Smaller, UiScale.Default, UiScale.Largest).flatMap { size ->
            listOf(1f, 1.3f).map { font -> SizePass("${size.name},${"%.1f".format(font)}", size.factor, font) to size }
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val fixtures = Fixtures()

    @After
    fun tearDown() {
        fixtures.close()
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun checklist() {
        val spec = SCREENS.first { it.name == screen }
        val ux = TvUxChecklist(rule, screen)
        val content = spec.content
        if (content == null) {
            ux.add("render", "screen", spec.missing ?: "no composable for this screen")
            assertNoFindings(screen, ux.findings)
            return
        }
        FormFactor.override(true)
        Motion.reduced = false
        rule.mainClock.autoAdvance = false
        var scale by mutableStateOf(UiScale.Default)
        var font by mutableStateOf(1f)
        var ring = Color.Unspecified
        var dark = true
        val body = content(fixtures)
        rule.setContent {
            TMPlayerTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, font)) {
                    UiScaled(scale, keepTextSize = true) {
                        Box(Modifier.fillMaxSize().background(Tone.background)) {
                            body()
                            val r = focusRingColor()
                            val d = LocalDarkTheme.current
                            SideEffect { ring = r; dark = d }
                        }
                    }
                }
            }
        }
        // A screen whose entry focus is asked once, in its first frames, needs the remote's input mode before them.
        if (spec.remoteFirst) ux.windowForRemote()
        // Disk reads (settings, the lists' store) answer on their own threads.
        repeat(6) { Thread.sleep(100); ux.settle(300) }
        // The window takes the remote once the screen is up (earlier, Home's first focus never
        // landed under Robolectric); a screen then has 3 s to put the remote somewhere.
        ux.windowForRemote()
        repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
        spec.prepare(ux)

        ux.entryFocus()
        ux.labelled()
        ux.targets()
        ux.safeArea()
        if (ring != Color.Unspecified) ux.ringAgainstArt(ring, dark)

        val entry = ux.focused().firstOrNull()
        val graph = ux.arrowGraph()
        println("tvux $screen arrows from '${entry?.let { ux.nameOf(it) }}': $graph")
        spec.actions.forEach { ux.reach(graph, it) }
        // The arrows may have turned a page (the Settings rail's Up and Down): put the screen back first.
        val reenter = spec.reenter
        if (reenter != null) reenter(ux)
        else entry?.let { e -> ux.nodes().firstOrNull { ux.nameOf(it) == ux.nameOf(e) && it.config.contains(SemanticsProperties.Focused) }?.let { ux.focus(it) } }
        spec.column?.let { ux.keyOwnership(it) }
        spec.openers.forEach { ux.backReturns(it) }
        ux.focusWalk()
        if (spec.longList) ux.heldDown()

        for ((pass, size) in PASSES) {
            scale = size
            font = pass.fontScale
            ux.settle(500)
            ux.textFloor(pass)
        }
        if (ux.skipped.isNotEmpty()) println("tvux $screen skipped: " + ux.skipped.joinToString("; "))
        assertNoFindings(screen, ux.findings)
    }
}

/** What some screens need made per test and closed after it. */
internal class Fixtures : AutoCloseable {
    private val closers = mutableListOf<() -> Unit>()

    fun profiles(): ProfileKit = ProfileKit("Asha", "Ben").also { kit -> closers += { kit.close() } }

    fun lists(): Pair<com.tmplayer.data.ListsStore, String> {
        val dir = Files.createTempDirectory("tvux-lists").toFile()
        closers += { dir.deleteRecursively() }
        val store = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists
        val id = runBlocking {
            val info = store.create("Weekend")!!
            for (n in 1L..6L) store.add(info.id, later(n))
            info.id
        }
        return store to id
    }

    override fun close() = closers.forEach { runCatching { it() } }
}

/**
 * One TV screen: [content] draws it from what [Fixtures] makes (null when it is not built yet,
 * which is a `render` finding the package that builds it allows), [prepare] walks to it from
 * there, [column] is its context in `docs/agents/tv-keys.md`, [actions] the words of the actions
 * that must be at most 3 presses away, [openers] the controls whose OK and Back must bring the
 * remote back to them.
 */
internal class Screen(
    val name: String,
    val column: String? = null,
    val longList: Boolean = false,
    val actions: List<String> = emptyList(),
    val openers: List<String> = emptyList(),
    val missing: String? = null,
    val prepare: (TvUxChecklist) -> Unit = {},
    /** Puts the screen back where the checks that follow the arrows' walk expect it, when that walk moved it. */
    val reenter: ((TvUxChecklist) -> Unit)? = null,
    /** Take the remote's window mode before the screen's first frames (its entry focus is requested once, early). */
    val remoteFirst: Boolean = false,
    val content: ((Fixtures) -> @Composable () -> Unit)?,
)

private const val TILE = "Tile, chat row, picker tile"
private const val RAIL = "Settings rail and pane"

/** A row on each Settings page that opens a picker, for backReturns: OK opens it, Back lands on the row. */
private val SETTINGS_OPENERS: Map<SettingsPage, List<String>> = mapOf(
    SettingsPage.Playback to listOf("Decoder"),
    SettingsPage.Subtitles to listOf("Subtitle style"),
    SettingsPage.Storage to listOf("Storage location"),
)

private fun media(id: Long, title: String, chat: Long = -5L) = MediaItem(
    chatId = chat, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1_000_000_000, durationSec = 5_000,
    mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "$title.mp4",
)

private fun record(id: Long, title: String) = ResumeRecord(
    chatId = -5L, messageId = id, fileId = id.toInt(), title = title, chatTitle = "Clips",
    sizeBytes = 900_000_000, durationSec = 6_000, positionMs = 600_000, durationMs = 6_000_000, updatedAt = id,
)

private fun later(id: Long) = WatchLaterRecord(
    chatId = -100, messageId = id, fileId = id.toInt(), title = "Clip $id", fileName = "Clip $id.mp4", chatTitle = "Chat",
    sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = id,
)

private val CHATS = (1L..30L).map { ChatSummary(id = -it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) }
private val RECORDS = (1L..12L).map { record(it, "Video $it") }
private val ROWS = listOf(
    HomeRow.Continue(RECORDS),
    HomeRow.Chat(-5L, "Clips", (20L..32L).map { ShelfEntry.File(media(it, "Clip $it")) }, loaded = true),
    HomeRow.Chat(-6L, "Talks", (40L..52L).map { ShelfEntry.File(media(it, "Talk $it", -6L)) }, loaded = true),
    HomeRow.Chat(-7L, "Lessons", (60L..72L).map { ShelfEntry.File(media(it, "Lesson $it", -7L)) }, loaded = true),
)

private val NAV = TouchNav(
    account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
    onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
    onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
)

/** The bar with the account menu's two buttons, as the app shell builds it (WP-02). */
private val ACCOUNT_NAV = TouchNav(
    account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
    onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
    onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
    onSwitchOrAdd = {}, onSignOut = {},
)

private fun browse(tab: BrowseTab): (Fixtures) -> @Composable () -> Unit = {
    {
        BrowseScreen(
            state = UiState.Content(BrowseData(CHATS)),
            favorites = emptySet(),
            continueWatching = RECORDS,
            onRetry = {},
            onOpenChat = {},
            onResumeMedia = {},
            onOpenSettings = {},
            tvNav = NAV,
            picked = BrowseSection.of(tab),
            homeRows = ROWS,
            homeWatch = SeriesWatch.None,
        )
    }
}

private fun settings(page: SettingsPage): (Fixtures) -> @Composable () -> Unit = {
    { SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = page, accountFolders = emptyList()) }
}

internal val SCREENS: List<Screen> = listOf(
    Screen("signIn", content = {
        {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Tone.text) {
                LoginScreen(state = AuthState.Qr("tg://login?token=AAAA"), onSubmitPassword = {}, submitError = null)
            }
        }
    }),
    Screen("profilePicker", column = TILE, actions = listOf(L.profilesAdd), openers = listOf(L.profilesManage), content = { f ->
        val kit = f.profiles()
        val flow = kit.flow();
        { ProfilesGate(flow, signInStep = false) { androidx.compose.material3.Text("Home") } }
    }),
    Screen("home", column = TILE, longList = true, actions = listOf(L.browseTabChats), content = browse(BrowseTab.Home)),
    Screen("chatList", column = TILE, longList = true, actions = listOf(L.browseTabChats), content = browse(BrowseTab.Chats)),
    Screen("history", column = TILE, longList = true, content = browse(BrowseTab.History)),
    Screen("chatGrid", column = TILE, longList = true, content = {
        {
            com.tmplayer.ui.browse.MediaGridScreen(
                chatId = -5L, chatTitle = "Clips", chatPhotoFileId = 0, chatMiniThumbnail = null, isFavorite = false,
                minSizeBytes = 0L, maxSizeBytes = Long.MAX_VALUE, watchProgress = emptyMap(), onToggleFavorite = {}, onPlay = {},
                telegramConnected = true, offline = false, onOfflineAction = {},
                connectionNotice = com.tmplayer.ui.components.ConnectionNotice.Hidden,
            )
        }
    }),
    Screen("details", remoteFirst = true, content = {
        {
            MediaDetailOpened(
                item = media(20, "Clip 20"), chatTitle = "Clips", watched = null, finished = false,
                onPlay = {}, onSetWatched = {}, onDownload = {}, onDismiss = {},
            )
        }
    }),
    Screen("lists", column = TILE, longList = true, openers = listOf("Delete list"), remoteFirst = true, content = { f ->
        val (store, id) = f.lists()
        val shown = runBlocking { store.visible.first() };
        {
            ListPage(
                initial = id, lists = shown, store = store, art = emptyMap(), onArtWanted = {}, onOpen = {},
                onDetails = {}, onMessage = {}, onDismiss = {},
            )
        }
    }),
    Screen("downloads", column = TILE, longList = true, content = { { DownloadsScreen(onPlay = {}, onBack = {}) } }),
    Screen("tvSearch", column = TILE, longList = true, content = {
        val groups = (1L..15L).map { ResultGroup(key = "g$it", copies = listOf(media(it, "Night Harbour Part $it"))) }
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), groups = groups);
        {
            Column(Modifier.fillMaxSize().padding(top = com.tmplayer.ui.browse.TV_BAR_HEIGHT, end = com.tmplayer.ui.theme.Tv.SafeH)) {
                TvSearchScreen(
                    state = state, text = "night harbour", onText = {}, onSubmit = {}, chats = emptyList(), favourites = emptySet(),
                    recentSearches = emptyList(), suggestions = emptyList(), progress = { null }, onOpenChat = {}, onOpenGroup = {},
                    onFilters = {}, onSort = {}, onLoadMore = {}, onRetry = {},
                )
            }
        }
    }),
) + SettingsPage.entries.map { page ->
    Screen(
        "settings:${page.name}", column = RAIL, longList = true, openers = SETTINGS_OPENERS[page].orEmpty(), content = settings(page),
        // The rail's Up and Down turn the page: back on this page's own rail item, then Right into its first row.
        reenter = { ux ->
            val rail = ux.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == railTag(page) }
            if (rail != null && ux.focus(rail)) ux.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 500)
        },
    )
} + listOf(
    Screen("about", content = { { AboutScreen(onBack = {}) } }),
    Screen(
        "signOutPrompt",
        column = null,
        prepare = { ux ->
            fun find() = ux.nodes().firstOrNull { ux.nameOf(it) == L.settingsSignOut && it.config.contains(SemanticsProperties.Focused) }
            // The button is at the page's foot: the remote walks down to it, as a viewer's does.
            var steps = 0
            while (find() == null && steps++ < 25) ux.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 200)
            val button = find()
            if (button == null) {
                ux.add("render", "screen", "no Sign out button on the Account page to open the prompt from")
            } else {
                ux.focus(button)
                ux.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER, 800)
                if (ux.nodes().none { ux.nameOf(it).contains(L.settingsSignOutTitle) }) {
                    ux.add("render", "screen", "OK on Sign out opened no prompt")
                } else {
                    ux.topWindowOnly = true
                }
            }
        },
        content = settings(SettingsPage.Accounts),
    ),
    Screen(
        "accountMenu",
        column = null,
        // The sheet's rows come in the order Favourites, History, Watch later, Settings, Add account: the first two
        // are within 3 presses; Add account is the fifth row by design (TvAccountMenuTest walks every row and the version).
        actions = listOf(L.browseTabFavourites, L.navSettings),
        prepare = { ux ->
            // The way a remote with a Menu key gets there: Up from Home's rows to the bar, then Menu, which the bar
            // takes on its release (the picture itself refuses a programmatic focus in this harness; OK on it is
            // TvAccountMenuTest's).
            fun onBar() = ux.focused().any { ux.nameOf(it).let { n -> n == L.browseTabHome || n == L.browseTabChats || n == L.commonSearch } }
            var ups = 0
            while (!onBar() && ups++ < 8) ux.key(android.view.KeyEvent.KEYCODE_DPAD_UP, 300)
            ux.key(android.view.KeyEvent.KEYCODE_MENU, 800)
            // The account sheet is a window of its own: it takes a few frames to come up on a slow machine. Sign out is
            // in Settings, Account now (WP-02), so the sheet is known by its Add account row.
            var waits = 0
            while (ux.nodes().none { ux.nameOf(it) == L.profilesAdd } && waits++ < 10) {
                Thread.sleep(100)
                ux.settle(300)
            }
            if (ux.nodes().none { ux.nameOf(it) == L.profilesAdd }) {
                ux.add("render", "screen", "Menu on the bar opened no account sheet")
            } else {
                ux.topWindowOnly = true
                // The sheet is a window of its own: out of touch mode, holding focus, and a moment for its first row to take the remote.
                ux.windowForRemote()
                repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
            }
        },
        content = {
            {
                BrowseScreen(
                    state = UiState.Content(BrowseData(CHATS)),
                    favorites = emptySet(),
                    continueWatching = RECORDS,
                    onRetry = {},
                    onOpenChat = {},
                    onResumeMedia = {},
                    onOpenSettings = {},
                    tvNav = ACCOUNT_NAV,
                    picked = BrowseSection.of(BrowseTab.Home),
                    homeRows = ROWS,
                    homeWatch = SeriesWatch.None,
                )
            }
        },
    ),
)
