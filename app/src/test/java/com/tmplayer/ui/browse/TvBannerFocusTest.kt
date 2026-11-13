package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's Home top by remote alone (GitHub issue 16: "can't see where my position is
 * inside the slider"): the banner's Play and Details, a row of tiles under it, and the pager.
 * Exactly one thing is focused at a time (what the focus fill and the tile's ring are drawn from),
 * Left and Right move one step across the two buttons and turn the page at the ends, Down goes to
 * the row and Up comes back to the button the remote was on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvBannerFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var nextId = 1L
    private var played = 0
    private var opened = 0

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
        rule.setContent {
            TMPlayerTheme {
                val spot = androidx.compose.runtime.remember { mutableIntStateOf(0) }
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Column(
                    Modifier.fillMaxSize().background(Tone.background)
                        // Home's own Up and Down (HomePane): one step through the focus manager.
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
                        picks = all,
                        height = TV_FEATURED_HEIGHT,
                        start = 48.dp,
                        end = 48.dp,
                        watch = SeriesWatch(point = { null }, finished = { false }),
                        chatTitle = { "Weekend Films" },
                        active = false,
                        onPlay = { _, _ -> played++ },
                        onDetails = { opened++ },
                        spot = spot,
                    )
                    // The row under the banner, as a row of tiles that remembers its tile.
                    Row(Modifier.padding(start = 48.dp, top = 8.dp).focusRestorer()) {
                        for (n in 0 until 6) {
                            Box(
                                Modifier.padding(end = 12.dp).size(200.dp, 60.dp)
                                    .clickable {},
                            ) { Text("Tile $n") }
                        }
                    }
                    Box(Modifier.height(1.dp))
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
    }

    private fun press(key: Key) {
        rule.onNode(androidx.compose.ui.test.isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()

    }

    /** The labels of everything focused: exactly one, which is what one focus indicator means. */
    private fun focusedNow(): List<String> =
        rule.onAllNodes(androidx.compose.ui.test.isFocused() and androidx.compose.ui.test.hasClickAction())
            .fetchSemanticsNodes()
            .map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty() }

    private fun onlyFocused(expected: String) = assertEquals(listOf(expected), focusedNow())

    @Test
    fun `Play takes the remote and is the only thing focused`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        rule.onNodeWithText("Play").assertIsFocused()
        onlyFocused("Play")
        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()
    }

    @Test
    fun `Right on Play moves to Details in one step and Left comes back`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionRight)
        onlyFocused("Details")
        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()
        press(Key.DirectionLeft)
        onlyFocused("Play")
    }

    @Test
    fun `Right on Details turns the page and keeps the remote on Details`() {
        open()
        rule.onNodeWithText("Details").requestFocus()
        press(Key.DirectionRight)
        onlyFocused("Details")
        rule.onNodeWithContentDescription("Page 2 of 5").assertExists()
    }

    @Test
    fun `Left on Play turns the page back, round to the last from the first`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionLeft)
        onlyFocused("Play")
        rule.onNodeWithContentDescription("Page 5 of 5").assertExists()
        press(Key.DirectionLeft)
        rule.onNodeWithContentDescription("Page 4 of 5").assertExists()
        onlyFocused("Play")
    }

    @Test
    fun `Down goes to the row and Up comes back to Play`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionDown)
        onlyFocused("Tile 0")
        press(Key.DirectionUp)
        onlyFocused("Play")
    }

    @Test
    fun `Up from the row comes back to Details when the remote left from Details`() {
        open()
        rule.onNodeWithText("Details").requestFocus()
        press(Key.DirectionDown)
        // Details stands over the second tile.
        onlyFocused("Tile 1")
        press(Key.DirectionUp)
        onlyFocused("Details")
    }

    @Test
    fun `Right in the row moves one tile and Up goes back to the banner`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        onlyFocused("Tile 1")
        press(Key.DirectionLeft)
        onlyFocused("Tile 0")
        press(Key.DirectionUp)
        onlyFocused("Play")
    }

    @Test
    fun `OK on Play plays and OK on Details opens`() {
        open()
        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(1, played)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(1, opened)
    }
}
