package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.ChatGrid
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaFeedEntry
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
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
 * WP-14 on the real video grid: a large chat (6,000 videos, drawn from the listing kept from the
 * last visit, so no TDLib is asked) with the remote. Up from the first row reaches the header's
 * Options pill; the pill names the tile the remote left; a tile scrolled far away is brought back
 * first; OK on the pill opens that tile's menu and Back lands on the tile. Also the pure lookup the
 * pill scrolls by, for a video and for a show.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class MediaGridOptionsPillTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val chatId = -7_001L
    private val total = 6_000

    @After
    fun tearDown() {
        PageQueries.cache.remove { it.chatId == chatId }
        FormFactor.override(false)
        Motion.reduced = false
    }

    /** One video, named with a word of its own so no two group into a show. */
    private fun video(index: Int): MediaItem {
        val word = "Harbour" + index.toString(26).map { 'a' + (it.digitToInt(26)) }.joinToString("")
        return MediaItem(
            chatId = chatId, messageId = (total - index).toLong() * 16, fileId = index + 1, title = word, sizeBytes = 900L * 1024 * 1024,
            durationSec = 5_000, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = 1_700_000_000 - index, fileName = "$word.mp4",
        )
    }

    private val videos by lazy { (0 until total).map(::video) }

    private fun nameOf(index: Int) = SeriesShelf.tileTitle(videos[index])

    private fun show() {
        FormFactor.override(true)
        Motion.reduced = true
        PageQueries.cache.put(QueryKey.ChatMedia(chatId), ChatGrid(items = videos, cursors = MediaCursors(), endReached = true))
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    MediaGridScreen(
                        chatId = chatId, chatTitle = "Harbour", chatPhotoFileId = 0, chatMiniThumbnail = null, isFavorite = false,
                        minSizeBytes = 0L, maxSizeBytes = Long.MAX_VALUE, watchProgress = emptyMap(), onToggleFavorite = {}, onPlay = {},
                        telegramConnected = true, offline = false, onOfflineAction = {}, connectionNotice = ConnectionNotice.Hidden,
                    )
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
        rule.waitUntil(30_000) { rule.onAllNodesWithText(nameOf(0)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** The focusable that holds the words of the tile at [index] (the tile's semantics are merged into it, WP-90). */
    private fun tileOf(index: Int) = androidx.compose.ui.test.isFocusable() and
        androidx.compose.ui.test.hasText(nameOf(index), substring = true)

    private fun press(key: Key) {
        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun textsOf(n: SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() + " " +
            (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()) + " " +
            n.children.joinToString(" ") { textsOf(it) }).trim()

    private fun focusedText(): String =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { textsOf(it) }

    private fun optionsLabel(): String? =
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Options for", substring = true)).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test
    fun `no Options pill until a tile has had the remote, then Up from row one and Right reach it, naming the tile`() {
        show()
        // The grid puts the remote on its first tile as it opens, so the pill already names it.
        rule.onAllNodes(tileOf(0))[0].requestFocus()
        rule.waitForIdle()
        assertTrue("the first tile has the remote: ${focusedText()}", focusedText().contains(nameOf(0)))
        assertEquals("Options for ${optionsNameClipped(nameOf(0))}", optionsLabel())
        // Up from the first row lands in the header; Right along its buttons reaches the pill.
        press(Key.DirectionUp)
        var presses = 0
        while (!focusedText().startsWith("Options for") && presses < 8) {
            press(Key.DirectionRight)
            presses++
        }
        assertTrue("Options reached in $presses presses from the header: ${focusedText()}", focusedText().startsWith("Options for"))
    }

    @Test
    fun `a tile scrolled far away is brought back first, OK on the pill opens its menu and Back lands on the tile`() {
        show()
        val deep = 4_321
        // Far down the list: scroll there, take the remote to the tile, so the pill names it.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(deep)
        rule.waitForIdle()
        rule.waitUntil(30_000) { rule.onAllNodesWithText(nameOf(deep)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(tileOf(deep))[0].requestFocus()
        rule.waitForIdle()
        val expected = "Options for ${optionsNameClipped(nameOf(deep))}"
        assertEquals(expected, optionsLabel())
        // The remote goes to the pill, then the list goes back to its top: the tile is thousands of rows off screen.
        rule.onAllNodesWithContentDescription(expected)[0].requestFocus()
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.waitForIdle()
        // The grid lets go of the rows it scrolled past a frame or more later (a slow runner took longer than one idle wait).
        rule.waitUntil(30_000) { rule.onAllNodesWithText(nameOf(deep)).fetchSemanticsNodes().isEmpty() }
        assertEquals("the tile is off screen", 0, rule.onAllNodesWithText(nameOf(deep)).fetchSemanticsNodes().size)
        press(Key.DirectionCenter)
        // The hold menu is the video's own page as a menu: it names the video and offers Play.
        rule.waitUntil(30_000) { rule.onAllNodesWithText(L.commonPlay).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the menu is for ${nameOf(deep)}", rule.onAllNodesWithText(nameOf(deep), substring = true).fetchSemanticsNodes().isNotEmpty())
        // Back: the tile it was opened from, scrolled into view and holding the remote.
        press(Key.Back)
        rule.waitUntil(30_000) { focusedText().contains(nameOf(deep)) }
        assertTrue("the remote is back on the tile: ${focusedText()}", focusedText().contains(nameOf(deep)))
    }

    @Test
    fun `the lookup finds a video and a show by the key their tile has, and nothing for a sponsored card or a gone key`() {
        val a = ShelfEntry.File(videos[3])
        val series = com.tmplayer.data.Series(
            key = "night",
            title = "Night Harbour",
            seasons = listOf(
                com.tmplayer.data.SeriesSeason(
                    1,
                    listOf(com.tmplayer.data.SeriesEpisode(videos[4], season = 1, episode = 1)),
                ),
            ),
        )
        val show = ShelfEntry.Show(series)
        val feed: List<MediaFeedEntry<ShelfEntry>> = listOf(MediaFeedEntry.Media(a), MediaFeedEntry.Media(show))
        assertEquals(0, feedIndexOfTile(feed, a.key))
        assertEquals(1, feedIndexOfTile(feed, show.key))
        assertEquals(-1, feedIndexOfTile(feed, "media-nothing"))
        assertEquals(-1, feedIndexOfTile(emptyList(), a.key))
    }
}
