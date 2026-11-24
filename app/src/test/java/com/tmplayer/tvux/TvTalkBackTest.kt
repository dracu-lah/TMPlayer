package com.tmplayer.tvux

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.GlobalSearchState
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.TouchNav
import com.tmplayer.ui.browse.TvSearchScreen
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack and voice on the television (WP-90): the bar is read before the page, each Home row's
 * title is a heading, a tile is named by what is on it, and TV search puts the remote on the
 * microphone first when a recogniser exists (the keyboard first when none does).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTalkBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val ux by lazy { TvUxChecklist(rule, "talkback") }

    private fun media(id: Long, title: String) = MediaItem(
        chatId = -5L, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1_000_000_000, durationSec = 5_000,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "$title.mp4",
    )

    private fun nodes(): List<SemanticsNode> = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().let { roots ->
        buildList {
            fun walk(n: SemanticsNode) { add(n); n.children.forEach(::walk) }
            roots.forEach(::walk)
        }
    }

    private fun home(font: Float = 1f, size: com.tmplayer.data.UiScale = com.tmplayer.data.UiScale.Default) {
        FormFactor.override(true)
        val rows = listOf(
            HomeRow.Chat(-5L, "Clips", (20L..26L).map { ShelfEntry.File(media(it, "Clip $it")) }, loaded = true),
            HomeRow.Chat(-6L, "Talks", (40L..46L).map { ShelfEntry.File(media(it, "Talk $it")) }, loaded = true),
        )
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        rule.setContent {
            TMPlayerTheme {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, font),
                ) {
                    com.tmplayer.ui.theme.UiScaled(size, keepTextSize = true) {
                BrowseScreen(
                    state = UiState.Content(BrowseData((1L..3L).map { ChatSummary(id = -it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) })),
                    favorites = emptySet(),
                    continueWatching = emptyList(),
                    onRetry = {},
                    onOpenChat = {},
                    onResumeMedia = {},
                    onOpenSettings = {},
                    tvNav = nav,
                    picked = BrowseSection.of(BrowseTab.Home),
                    homeRows = rows,
                    homeWatch = SeriesWatch.None,
                )
                    }
                }
            }
        }
        repeat(8) { Thread.sleep(50); rule.waitForIdle() }
        ux.windowForRemote()
    }

    @Test
    fun theBarIsReadBeforeThePageAndEachRowTitleIsAHeading() {
        home()
        val all = nodes()
        val bar = all.filter { it.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true }
        assertTrue("the bar is a traversal group read first", bar.any { (it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f) < 0f })
        assertTrue("the page is a traversal group", bar.any { (it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f) == 0f })
        val headings = all.filter { it.config.contains(SemanticsProperties.Heading) }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { t -> t.text } }
        assertTrue("Clips is a heading, got $headings", "Clips" in headings)
        assertTrue("Talks is a heading, got $headings", "Talks" in headings)
        assertTrue("Clips comes before Talks", headings.indexOf("Clips") < headings.indexOf("Talks"))
    }

    @Test
    fun aTileIsNamedByItsTitleAndRole() {
        home()
        val tiles = nodes().filter { n ->
            n.config.contains(SemanticsProperties.Role) && n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Clip 2") } == true
        }
        assertTrue("a Clip tile reads its title with its role", tiles.isNotEmpty())
    }

    @Test
    fun theBarKeepsEveryTabInTheFrameAtFont13AndLargest() {
        home(font = 1.3f, size = com.tmplayer.data.UiScale.Largest)
        val w = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().boundsInRoot.width
        for (name in listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads)) {
            val tab = nodes().firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab &&
                    (n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(name) } == true ||
                        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == name } == true)
            }
            assertTrue("the $name tab is in the bar", tab != null)
            val b = tab!!.boundsInRoot
            assertTrue("the $name tab is inside the frame: ${b.left}..${b.right} of $w", b.left >= 0f && b.right <= w)
        }
    }

    private fun search() {
        FormFactor.override(true)
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().padding(top = com.tmplayer.ui.browse.TV_BAR_HEIGHT)) {
                    TvSearchScreen(
                        state = GlobalSearchState(), text = "", onText = {}, onSubmit = {}, chats = emptyList(), favourites = emptySet(),
                        recentSearches = emptyList(), suggestions = emptyList(), progress = { null }, onOpenChat = {}, onOpenGroup = {},
                        onFilters = {}, onSort = {}, onLoadMore = {}, onRetry = {},
                    )
                }
            }
        }
        ux.windowForRemote()
        repeat(10) { Thread.sleep(50); ux.settle(300) }
    }

    private fun focusedName(): String? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    }

    @Test
    fun searchOpensOnTheMicrophoneWhenARecogniserExists() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.fake.recogniser"
                name = "com.fake.recogniser.Listen"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.fake.recogniser" }
            }
        }
        pm.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), info)
        search()
        assertEquals(L.browseSearchByVoice, focusedName())
    }

    @Test
    fun searchOpensOnTheKeyboardWhenNoRecogniserExists() {
        search()
        val name = focusedName()
        assertFalse("the microphone is not first without a recogniser, got $name", name == L.browseSearchByVoice)
    }
}
