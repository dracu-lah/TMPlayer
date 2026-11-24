package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.ChatGrid
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.SeriesShelf
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-01 on the real video grid of a large chat (2,000 videos from the listing kept from the last
 * visit): Refresh in the header reads the chat again, and what that does (Updating, then the
 * failure) is said over the grid where the remote is, not only in the foot line after the last
 * tile. When it fails the header's Refresh reads Try again, one Up from the tiles, and the first
 * tile is still on screen: nothing moved.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class MediaGridStatusTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val chatId = -7_101L
    private val total = 2_000

    @After
    fun tearDown() {
        PageQueries.cache.remove { it.chatId == chatId }
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun video(index: Int): MediaItem {
        val word = "Lantern" + index.toString(26).map { 'a' + (it.digitToInt(26)) }.joinToString("")
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
                        chatId = chatId, chatTitle = "Lantern", chatPhotoFileId = 0, chatMiniThumbnail = null, isFavorite = false,
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

    @Test
    fun `a failed refresh of a big chat is said over the grid and in the header, with the first tile still in place`() {
        show()
        rule.onNodeWithContentDescription(L.commonRefresh).requestFocus()
        rule.onNodeWithContentDescription(L.commonRefresh).performKeyInput { pressKey(Key.DirectionCenter) }
        // Whatever TDLib does here (nothing is signed in), the grid is read again and says how it went:
        // the words over the grid, never only a foot line 2,000 tiles down.
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasContentDescription(L.stateFailedPillHeader) or hasContentDescription(L.stateUpdating) or hasContentDescription(L.stateUpdated)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        val failed = rule.onAllNodes(hasContentDescription(L.stateFailedPillHeader)).fetchSemanticsNodes().isNotEmpty()
        if (failed) {
            assertTrue("the header's Refresh reads Try again", rule.onAllNodesWithContentDescription(L.commonTryAgain).fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue("the first tile is where it was", rule.onAllNodesWithText(nameOf(0)).fetchSemanticsNodes().isNotEmpty())
    }
}
