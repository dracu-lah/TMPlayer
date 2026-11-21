package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.i18n.L
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.WatchOffers
import com.tmplayer.online.WhereToWatch
import com.tmplayer.ui.DrainMainLooperRule
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
import java.io.File

/**
 * HX41 on the phone: titles over the videos, a title with copies saying how many and opening the
 * best card, one "Not in your chats" with Where to watch, and the sheet listing the services with
 * the one-off search of the other chats. Pictures into build/device-test/review for a look.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class SearchTitlesPhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        Motion.reduced = false
    }

    private fun video(id: Long, name: String, chat: Long) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    private val nightTrain = PosterChoice(7, false, "Night Train", 2019, "en", "", null)
    private val harbour = PosterChoice(8, false, "Night Train Home", 2001, "en", "", null)

    private fun state(watching: Pair<PosterChoice, WhereToWatch?>? = null): GlobalSearchState {
        val groups = listOf(
            ResultGroup("g1", listOf(video(1, "Night.Train.2019.1080p.mkv", 1), video(2, "Night.Train.2019.720p.mkv", 2))),
        )
        return GlobalSearchState(
            query = GlobalSearch.parse("night train"), groups = groups, titles = listOf(nightTrain, harbour),
            titleLookup = TitleLookup.Found, addedOnly = true, watching = watching,
        )
    }

    private var current by mutableStateOf(state())
    private val opened = mutableListOf<ResultGroup>()
    private val watched = mutableListOf<PosterChoice>()
    private var others = 0

    private fun open() {
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                PhoneSearchScreen(
                    state = current,
                    text = TextFieldValue("night train", TextRange(11)),
                    onText = {},
                    onSubmit = {},
                    onClose = {},
                    fieldModifier = Modifier,
                    chats = emptyList(),
                    favourites = emptySet(),
                    recentSearches = emptyList(),
                    continueWatching = emptyList(),
                    continueArt = { it.toMediaItem() },
                    progress = { null },
                    onOpenChat = {},
                    onOpenGroup = { opened += it },
                    onResume = {},
                    onFilters = {},
                    onSort = {},
                    onLoadMore = {},
                    onRetry = {},
                    onSearchOthers = { others++ },
                    onWhereToWatch = { watched += it },
                    onCloseWhereToWatch = { current = current.copy(watching = null) },
                    onRemoveRecent = {},
                    onClearRecent = { emptyList() },
                    onRestoreRecent = {},
                    onVoice = null,
                    listState = rememberLazyListState(),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    fun `a title with copies says how many and opens its best card, one not in the chats offers Where to watch`() {
        open()
        rule.onNodeWithText(L.searchTitlesHeading).assertIsDisplayed()
        rule.onNodeWithText(L.searchTitleCopies(2, 2)).assertIsDisplayed()
        rule.onNodeWithText(L.searchNotInChats).assertIsDisplayed()
        shot("phone-search-titles.png")
        rule.onNodeWithTag("title-tmdb:film:7").performClick()
        rule.waitForIdle()
        assertEquals(listOf("g1"), opened.map { it.key })
        rule.onAllNodesWithText(L.searchWhereToWatch)[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf(harbour), watched)
    }

    @Test
    fun `the sheet lists the services by kind, names JustWatch, and searches the other chats`() {
        current = state(watching = harbour to WhereToWatch.Found(WatchOffers("GB", stream = listOf("Stream One", "Stream Two"), rent = listOf("Rent Shop"))))
        open()
        rule.onNode(isDialog()).assertIsDisplayed()
        rule.onNodeWithText("Stream One, Stream Two").assertIsDisplayed()
        rule.onNodeWithText(L.searchWatchSource).assertIsDisplayed()
        shot("phone-where-to-watch.png")
        rule.onNodeWithTag("where-to-watch-search-others").performClick()
        rule.waitForIdle()
        assertEquals(1, others)
        assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a region with nothing listed and an unreachable TMDB say so`() {
        current = state(watching = harbour to WhereToWatch.NoneHere("GB"))
        open()
        rule.onNodeWithText(L.searchWatchNoneHere(regionName("GB"))).assertIsDisplayed()
        current = current.copy(watching = harbour to WhereToWatch.Unavailable)
        rule.waitForIdle()
        rule.onNodeWithText(L.searchWatchUnavailable).assertIsDisplayed()
        rule.onNodeWithText(L.commonTryAgain).performClick()
        rule.waitForIdle()
        assertEquals(listOf(harbour), watched)
    }

    @Test
    fun `a huge answer of titles and copies draws`() {
        val many = (1..10).map { PosterChoice(it, it % 2 == 0, "Night Train ${"x".repeat(it * 30)}", 1990 + it, "en", "y".repeat(140), null) }
        val groups = (1L..300L).map { ResultGroup("g$it", listOf(video(it, "Night.Train.$it.2019.mkv", it))) }
        current = GlobalSearchState(query = GlobalSearch.parse("night train"), groups = groups, titles = many, titleLookup = TitleLookup.Found)
        open()
        rule.onNodeWithText(L.searchTitlesHeading).assertIsDisplayed()
        assertTrue(current.titleHits.size <= GlobalSearch.TITLES_SHOWN)
    }
}
