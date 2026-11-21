package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * GitHub issue 16 on a television, the way it was reported: from the top bar's Home tab ("Start"),
 * Down could not be seen to land anywhere in the banner, Left or Right took two presses to change
 * the title, and Details could not be reached. Here the real [TvTopBar] stands over the real
 * [TvFeaturedCarousel], wired as BrowseScreen wires them (Down from the bar asks the banner's Play):
 * Down lands on Play and Play is then drawn in the focus fill (read back from its pixels), the only
 * thing focused; one Left changes the title; Right reaches Details in one step and one more Right
 * changes the title from there; OK on Details opens it.
 *
 * [TvBannerFocusTest] covers the banner and the row under it on their own. Left to the device:
 * the Mi TV's own remote and how bright the fill reads from a sofa.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvBannerFromBarTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var nextId = 1L
    private var opened = 0
    private var focusFill = Color.Unspecified
    private lateinit var titles: List<String>

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun video(name: String) = nextId++.let { id ->
        MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000 + id * 90_000_000,
            durationSec = 2_400, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
        )
    }

    private fun picks(): List<FeaturedPick> = HomeFeatured.pick(
        SeriesShelf.arrange(
            listOf(
                video("Night.Train.2019.1080p.WEB-DL.mkv"),
                video("Paper.Moons.2018.1080p.BluRay.mkv"),
                video("Low.Tide.2022.720p.WEB.mkv"),
                video("Mountains.2021.2160p.HDR.mkv"),
                video("Quiet.Orchard.2020.1080p.mkv"),
            ),
        ),
    )

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        val all = picks()
        titles = all.map { SeriesShelf.tileTitle(it.item) }
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        rule.setContent {
            TMPlayerTheme {
                focusFill = Tone.focusFill
                val play = remember { FocusRequester() }
                val spot = remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = remember { FocusRequester() }, contentFocus = remember { FocusRequester() }, overArt = true,
                        // BrowseScreen's own: Down from the bar lands on the banner's Play.
                        onDown = { runCatching { play.requestFocus(FocusDirection.Enter) }.getOrDefault(false) },
                    )
                    TvFeaturedCarousel(
                        picks = all,
                        height = TV_FEATURED_HEIGHT,
                        start = 48.dp,
                        end = 48.dp,
                        watch = SeriesWatch(point = { null }, finished = { false }),
                        chatTitle = { "Weekend Films" },
                        active = false,
                        onPlay = { _, _ -> },
                        onDetails = { opened++ },
                        playFocus = play,
                        spot = spot,
                    )
                    Row(Modifier.padding(start = 48.dp, top = 8.dp).focusRestorer()) {
                        for (n in 0 until 4) {
                            Box(Modifier.padding(end = 12.dp).size(200.dp, 60.dp).clickable {}) { Text("Tile $n") }
                        }
                    }
                }
            }
        }
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
        // The remote on the bar's Home tab, where the report starts.
        rule.onNodeWithText(L.browseTabHome).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText(L.browseTabHome).assertIsFocused()
    }

    private fun press(key: Key) {
        rule.onNode(isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedNow(): List<String> =
        rule.onAllNodes(isFocused() and hasClickAction())
            .fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty() }

    /** The page's title, from the page's own description ("Night Train. Film  ·  2019 ..."). */
    private fun shownTitle(): String {
        val described = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        return titles.single { title -> described.any { it.startsWith("$title.") || it == title } }
    }

    /** The colour inside Play's pill at its end cap, clear of its icon and words. */
    private fun playFill(): Color {
        val bitmap = rule.onNodeWithText("Play").captureToImage().asAndroidBitmap()
        val inset = (14 * rule.activity.resources.displayMetrics.density).toInt()
        return Color(bitmap.getPixel(bitmap.width - inset, bitmap.height / 2))
    }

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < 0.04f && abs(a.green - b.green) < 0.04f && abs(a.blue - b.blue) < 0.04f

    @Test
    fun `Down from the Home tab lands on Play, drawn in the focus fill, the only thing focused`() {
        open()
        val before = playFill()
        assertTrue("unfocused Play is calm, not the focus fill: $before", !near(before, focusFill))
        press(Key.DirectionDown)
        rule.onNodeWithText("Play").assertIsFocused()
        assertEquals(listOf("Play"), focusedNow())
        val after = playFill()
        assertTrue("focused Play wears the focus fill $focusFill, was $after", near(after, focusFill))
    }

    @Test
    fun `from there one Left changes the title, and the remote stays on Play`() {
        open()
        press(Key.DirectionDown)
        val first = shownTitle()
        press(Key.DirectionLeft)
        assertNotEquals(first, shownTitle())
        rule.onNodeWithContentDescription("Page 5 of 5").assertExists()
        assertEquals(listOf("Play"), focusedNow())
    }

    @Test
    fun `Right reaches Details in one step, one more Right changes the title, and OK opens Details`() {
        open()
        press(Key.DirectionDown)
        val first = shownTitle()
        press(Key.DirectionRight)
        assertEquals(listOf(L.detailOpenDetails), focusedNow())
        assertEquals(first, shownTitle())
        press(Key.DirectionRight)
        assertNotEquals(first, shownTitle())
        rule.onNodeWithContentDescription("Page 2 of 5").assertExists()
        assertEquals(listOf(L.detailOpenDetails), focusedNow())
        press(Key.DirectionCenter)
        assertEquals(1, opened)
    }
}
