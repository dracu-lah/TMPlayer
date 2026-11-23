package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.online.PosterChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * HX41 on the television: the titles are one row of pills the remote walks. OK on a title with
 * copies opens its best card, OK on one not in the chats opens Where to watch, and closing that
 * puts the remote back on the pill it came from. Down from the titles reaches the posters.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SearchTitlesTvTest {

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

    private fun video(id: Long, name: String, chat: Long) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    private val inChats = PosterChoice(7, false, "Night Train", 2019, "en", "", null)
    private val notIn = PosterChoice(8, false, "Night Train Home", 2001, "en", "", null)
    private val groups = (1L..4L).map { id -> ResultGroup("g$id", listOf(video(id, "Night.Train.2019.Part.$id.1080p.mkv", id))) }

    private var current by mutableStateOf(
        GlobalSearchState(
            query = GlobalSearch.parse("night train"),
            groups = listOf(ResultGroup("g0", listOf(video(10, "Night.Train.2019.1080p.mkv", 1)))) + groups,
            titles = listOf(inChats, notIn),
            titleLookup = TitleLookup.Found,
        ),
    )
    private val opened = mutableListOf<ResultGroup>()
    private var retries = 0

    private fun show() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background).padding(end = Tv.SafeH)) {
                    TvSearchScreen(
                        state = current,
                        text = "night train",
                        onText = {},
                        onSubmit = {},
                        chats = emptyList(),
                        favourites = emptySet(),
                        recentSearches = emptyList(),
                        suggestions = emptyList(),
                        progress = { null },
                        onOpenChat = {},
                        onOpenGroup = { opened += it },
                        onFilters = {},
                        onSort = {},
                        onLoadMore = {},
                        onRetry = {},
                        onWhereToWatch = { current = current.copy(watching = it to null) },
                        onCloseWhereToWatch = { current = current.copy(watching = null) },
                        onRetryTitles = { retries++; current = current.copy(titleLookup = TitleLookup.Looking) },
                    )
                }
            }
        }
        rule.waitForIdle()
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).requestFocus()
        rule.waitForIdle()
    }

    @Test
    fun `OK on a title with copies opens its best card, Right reaches the one not in the chats, Down the posters`() {
        show()
        val first = rule.onNodeWithTag("title-${inChats.onlineKey}")
        first.assertIsDisplayed()
        first.requestFocus()
        rule.waitForIdle()
        first.assertIsFocused()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/tv-search-titles.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        first.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("g0", opened.single().key)
        first.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("title-${notIn.onlineKey}").assertIsFocused()
        // Down from the titles is a poster under them.
        rule.onNodeWithTag("title-${notIn.onlineKey}").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        // The count row (with Add more chats) sits between the titles and the posters, and in the
        // narrower pane beside the keyboard the first Down can land on it; Down again is a poster,
        // whichever column it is in (the grid is three across).
        val names = current.shown.map { GlobalSearch.titleOf(it) }
        fun focusedNames() = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().flatMap { node ->
            if (androidx.compose.ui.semantics.SemanticsProperties.ContentDescription in node.config) {
                node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
            } else {
                emptyList()
            }
        }
        repeat(2) {
            if (focusedNames().none { it in names }) {
                rule.onAllNodes(androidx.compose.ui.test.isFocused())[0].performKeyInput { pressKey(Key.DirectionDown) }
                rule.waitForIdle()
                rule.mainClock.advanceTimeBy(500)
            }
        }
        assertTrue("a poster is focused, was ${focusedNames()}", focusedNames().any { it in names })
    }

    @Test
    fun `Where to watch opens from a title not in the chats and closing it puts the remote back on that title`() {
        show()
        val pill = rule.onNodeWithTag("title-${notIn.onlineKey}")
        pill.requestFocus()
        rule.waitForIdle()
        pill.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(notIn, current.watching?.first)
        // Closed (Close or Back on the panel): the remote is on the title again, not on the field.
        rule.runOnUiThread { current = current.copy(watching = null) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("title-${notIn.onlineKey}").assertIsFocused()
    }

    @Test
    fun `Look up titles again keeps the remote on its pill while asking, then lands on the field or the first title`() {
        current = current.copy(titles = emptyList(), titleLookup = TitleLookup.Unavailable)
        show()
        val pill = rule.onNodeWithTag("titles-retry")
        pill.requestFocus()
        rule.waitForIdle()
        pill.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, retries)
        // Asking: the row and the pill stay, with the remote on it.
        rule.onNodeWithTag("titles-retry").assertIsFocused()
        // TMDB answers with nothing: the row goes and the remote is on the field, not lost.
        rule.runOnUiThread { current = current.copy(titleLookup = TitleLookup.Found) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("night train").assertIsFocused()
    }

    @Test
    fun `a retry that finds titles puts the remote on the first one, one that fails again on the pill`() {
        current = current.copy(titles = emptyList(), titleLookup = TitleLookup.Unavailable)
        show()
        rule.onNodeWithTag("titles-retry").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("titles-retry").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.runOnUiThread { current = current.copy(titleLookup = TitleLookup.Unavailable) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("titles-retry").assertIsFocused()
        rule.onNodeWithTag("titles-retry").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.runOnUiThread { current = current.copy(titles = listOf(inChats, notIn), titleLookup = TitleLookup.Found) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("title-${inChats.onlineKey}").assertIsFocused()
        assertEquals(2, retries)
    }
}
