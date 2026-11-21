package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Home carousel's lead page for a video being watched that has no picture at all (no Telegram
 * thumbnail, no online match: "Kuruthi 2021" on the Mi TV, PL-1 follow up). It is a hero on its
 * generated cover with the name, the time left, Resume and Details, on the television and on a
 * phone upright and on its side, and on the television the start focus lands on its Resume.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResumeHeroNoArtTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var played: MediaItem? = null
    private var opened = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    /** A 2 h 02 min file with nothing to draw: no thumbnail file, no inline preview. */
    private val kuruthi = MediaItem(
        chatId = 7, messageId = 41, fileId = 41, title = "Kuruthi.2021.1080p.mkv", sizeBytes = 2_400_000_000,
        durationSec = 7_320, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 900, fileName = "Kuruthi.2021.1080p.mkv",
    )

    private val watch = SeriesWatch(
        // Six minutes in of two hours two: 1 h 56 min left.
        point = { if (it.id == kuruthi.id) WatchPoint(positionMs = 360_000, durationMs = 7_320_000) else null },
        finished = { false },
    )

    private fun lead(): List<FeaturedPick> = listOf(FeaturedPick(ShelfEntry.File(kuruthi)))

    private fun showTv(startOnPlay: Boolean = false) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val requester = remember { FocusRequester() }
                // What HomePane does once the banner is up: the remote starts on Play.
                if (startOnPlay) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
                Column(
                    Modifier.fillMaxSize().background(Tone.background)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                else -> false
                            }
                        },
                ) {
                    TvFeaturedCarousel(
                        picks = lead(), height = TV_FEATURED_HEIGHT, start = 48.dp, end = 48.dp, watch = watch,
                        chatTitle = { "Weekend Films" }, active = false,
                        onPlay = { item, _ -> played = item }, onDetails = { opened++ },
                        playFocus = requester,
                    )
                    Row(Modifier.padding(start = 48.dp, top = 8.dp).focusRestorer().focusGroup()) {
                        for (n in 0 until 3) Box(Modifier.padding(end = 12.dp).size(200.dp, 60.dp).clickable {}) { Text("Tile $n") }
                    }
                }
            }
        }
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
    }

    /**
     * The hero's name and time left are in the tree a moment after its first frame: wait for them
     * (up to 5 s) instead of asserting at once, which failed once on a slow CI runner (the release
     * run of 2026-11-18) and passes everywhere else.
     */
    private fun awaitText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun press(key: Key) {
        rule.onNode(androidx.compose.ui.test.isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedNow(): List<String> =
        rule.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasClickAction())
            .fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty() }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on the television the lead page shows its name, the time left, Resume and Details`() {
        showTv()
        rule.onNodeWithText("Resume").assertExists()
        rule.onNodeWithText("Details").assertExists()
        awaitText("Kuruthi")
        // The time left stands in the facts line, in place of the length.
        awaitText("1h 56m left")
        assertTrue(rule.onAllNodes(hasText("Play")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the start focus lands on the lead page's Resume and OK plays it`() {
        showTv(startOnPlay = true)
        rule.onNodeWithText("Resume").assertIsFocused()
        assertEquals(listOf("Resume"), focusedNow())
        press(Key.DirectionCenter)
        assertEquals(kuruthi.id, played?.id)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `from the start focus Right reaches Details and Left comes back`() {
        showTv(startOnPlay = true)
        press(Key.DirectionRight)
        assertEquals(listOf("Details"), focusedNow())
        press(Key.DirectionLeft)
        assertEquals(listOf("Resume"), focusedNow())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `from the start focus Down reaches the row and Up comes back to Resume`() {
        showTv(startOnPlay = true)
        press(Key.DirectionDown)
        assertEquals(listOf("Tile 0"), focusedNow())
        press(Key.DirectionUp)
        assertEquals(listOf("Resume"), focusedNow())
    }

    private fun showPhone() {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
                    val under = 24.dp
                    FeaturedCarousel(
                        picks = lead(), height = featuredHeight(maxWidth, maxHeight - under) + under, under = under,
                        start = 16.dp, end = 16.dp, watch = watch, chatTitle = { "Weekend Films" }, active = false,
                        onPlay = { item, _ -> played = item }, onDetails = { opened++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `on a phone upright the lead page shows its name, the time left and Resume`() {
        showPhone()
        rule.onNodeWithText("Resume").assertExists()
        awaitText("Kuruthi")
        awaitText("1h 56m left")
        rule.onNodeWithText("Resume").performClick()
        rule.waitForIdle()
        assertEquals(kuruthi.id, played?.id)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `on a phone on its side the lead page shows its name, the time left and Resume`() {
        showPhone()
        rule.onNodeWithText("Resume").assertExists()
        awaitText("Kuruthi")
        awaitText("1h 56m left")
    }

    private fun record(item: MediaItem, positionMs: Long = 360_000, durationMs: Long = 7_320_000) = ResumeRecord(
        chatId = item.chatId, messageId = item.messageId, fileId = item.fileId, title = item.title, chatTitle = "Weekend Films",
        sizeBytes = item.sizeBytes, durationSec = item.durationSec, positionMs = positionMs, durationMs = durationMs, updatedAt = 1_000L,
    )

    @Test
    fun `the remote starts on the carousel when its lead is the video being watched, art or not`() {
        val row = HomeRow.Continue(listOf(record(kuruthi)))
        // The lead page is Kuruthi: its Resume takes the remote, not the first Continue card.
        assertTrue(leadsWithResume(lead(), row))
        assertFalse(continueStartsTheRemote(row, lead()))
        // A new title leading with Continue watching below it: the remote stays on the Continue card.
        val other = kuruthi.copy(messageId = 99, fileId = 99, title = "Other.Film.2020.mkv", fileName = "Other.Film.2020.mkv")
        val new = listOf(FeaturedPick(ShelfEntry.File(other)))
        assertFalse(leadsWithResume(new, row))
        assertTrue(continueStartsTheRemote(row, new))
        // Nothing in Continue watching, or not the first row: the carousel's Play.
        assertFalse(continueStartsTheRemote(HomeRow.Continue(emptyList()), new))
        assertFalse(continueStartsTheRemote(null, new))
    }

    @Test
    fun `a video with no art is eligible for the lead`() {
        assertFalse(HomeFeatured.hasArt(kuruthi))
        assertTrue(HomeFeatured.eligible(lead().single()))
    }
}
