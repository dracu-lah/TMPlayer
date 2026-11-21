package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchSort
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ClearButton
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's search page: Back clears the words first (with the filters and the sort) and closes
 * only on an empty field, there is no separate close button, and the clear button is a cross in a
 * soft round disc, not a bare Close glyph.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class PhoneSearchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var closed = 0
    private var filtersSet: SearchFilters? = null
    private var sortSet: SearchSort? = null
    private var words = ""

    @After
    fun tearDown() {
        Motion.reduced = false
    }

    /** The soft keyboard as the page asks for it: how often it was raised and how often put away. */
    private class FakeKeyboard : androidx.compose.ui.platform.SoftwareKeyboardController {
        var shown = 0
        var hidden = 0
        override fun show() { shown++ }
        override fun hide() { hidden++ }
    }

    private val keyboard = FakeKeyboard()

    private fun open(start: String, continuing: List<com.tmplayer.data.ResumeRecord> = emptyList()) {
        Motion.reduced = true
        words = start
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalSoftwareKeyboardController provides keyboard) {
            TMPlayerTheme {
                var text by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
                // The words as the page reads them, so the test can see what Back did to the field.
                androidx.compose.runtime.SideEffect { words = text.text }
                PhoneSearchScreen(
                    state = GlobalSearchState(query = GlobalSearch.parse(text.text)),
                    text = text,
                    onText = { text = it },
                    onSubmit = {},
                    onClose = { closed++ },
                    fieldModifier = Modifier,
                    chats = emptyList(),
                    favourites = emptySet(),
                    recentSearches = listOf("harbour lights"),
                    continueWatching = continuing,
                    continueArt = { it.toMediaItem() },
                    progress = { null },
                    onOpenChat = {},
                    onOpenGroup = {},
                    onResume = {},
                    onFilters = { filtersSet = it },
                    onSort = { sortSet = it },
                    onLoadMore = {},
                    onRetry = {},
                    onRemoveRecent = {},
                    onClearRecent = { emptyList() },
                    onRestoreRecent = {},
                    onVoice = {},
                    listState = rememberLazyListState(),
                )
            }
            }
        }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun `Back clears the words first, with the filters and sort, and closes on an empty field`() {
        open("harbour")
        rule.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        back()
        // The words are gone, the filters and sort are back to default, and the page is still open.
        assertEquals("", words)
        assertEquals(SearchFilters(), filtersSet)
        assertEquals(SearchSort.BestMatch, sortSet)
        assertEquals(0, closed)
        // Back with the recent searches showing: the clear button is gone, and a second Back closes.
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        rule.onNodeWithText("harbour lights").assertIsDisplayed()
        back()
        assertEquals(1, closed)
    }

    @Test
    fun `Back that clears the words does not raise the keyboard again, the cross does`() {
        open("harbour")
        val before = keyboard.shown
        back()
        assertEquals("", words)
        assertEquals("Back leaves the keyboard down: it was already closed when Back reached the page", before, keyboard.shown)
        // Two Backs in all: one clears, one closes. No third, no keyboard to put away in between.
        back()
        assertEquals(1, closed)
    }

    @Test
    fun `the cross keeps the keyboard up`() {
        open("night")
        val shownBefore = keyboard.shown
        rule.onNodeWithContentDescription("Clear search").performClickOn()
        assertEquals("the cross is a tap in the field, which keeps the keyboard up", shownBefore + 1, keyboard.shown)
    }

    @Test
    fun `Continue watching on the idle search names a video as Home does, not by its file name`() {
        val record = com.tmplayer.data.ResumeRecord(
            chatId = 1, messageId = 2, fileId = 3,
            title = "The.Mongoose.@ArcheMovie.2026.mp4", chatTitle = "Archive",
            sizeBytes = 100_000_000, durationSec = 5_000, positionMs = 1_000_000, durationMs = 5_000_000, updatedAt = 1,
        )
        open("", continuing = listOf(record))
        // The tile's name, with its year as Home's tiles read it, and not the file's.
        val name = com.tmplayer.data.SeriesShelf.tileTitle(record.toMediaItem())
        assertTrue("no dots, handle or extension in $name", '@' !in name && !name.endsWith(".mp4") && "The Mongoose" in name)
        rule.onNodeWithText(name).assertIsDisplayed()
        rule.onNodeWithText("The.Mongoose.@ArcheMovie.2026.mp4").assertDoesNotExist()
    }

    @Test
    fun `the clear button empties the field at once and the page has no Close button of its own`() {
        open("night")
        // The page's own close button is gone: Back, or the gesture, is the way out.
        rule.onNode(hasContentDescription("Close search")).assertDoesNotExist()
        rule.onNodeWithContentDescription("Clear search").performClickOn()
        assertEquals("", words)
        assertEquals(0, closed)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickOn() {
        performClick()
        rule.waitForIdle()
    }
}

/** The clear button's look, read off the pixels: a soft disc about 28 dp wide round the cross, nothing in the corners of its 48 dp target. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class ClearButtonLookTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Composable
    private fun Stage() {
        TMPlayerTheme {
            Box(Modifier.size(48.dp).background(Color.Black).testTag("stage"), contentAlignment = Alignment.Center) {
                ClearButton("Clear search", onClick = {})
            }
        }
    }

    @Test
    fun `the clear button is a cross in a soft round disc`() {
        rule.setContent { Stage() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        val image = rule.onNodeWithTag("stage").captureToImage()
        val density = rule.activity.resources.displayMetrics.density
        fun at(dx: Float, dy: Float): Int {
            val x = (image.width / 2f + dx * density).toInt().coerceIn(0, image.width - 1)
            val y = (image.height / 2f + dy * density).toInt().coerceIn(0, image.height - 1)
            val pixels = IntArray(1)
            image.readPixels(pixels, x, y, 1, 1)
            return pixels[0]
        }
        fun lift(argb: Int) = (argb shr 16) and 0xFF
        val background = lift(at(-23f, -23f))
        // Inside the disc, off the cross (the cross is within 4 dp of the centre's lines): lighter than the stage.
        val inDisc = lift(at(10f, 6f))
        // Outside the 28 dp disc but inside the 48 dp target: the stage again.
        val outside = lift(at(19f, 6f))
        assertTrue("the disc is lighter than the stage ($inDisc vs $background)", inDisc > background + 8)
        assertEquals("nothing is drawn outside the disc", background, outside)
        // The disc is centred: the same on the other side.
        assertEquals(inDisc, lift(at(-10f, -6f)))
    }
}
