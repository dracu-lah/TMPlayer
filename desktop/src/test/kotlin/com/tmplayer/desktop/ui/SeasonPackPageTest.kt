package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TS: a season posted as one file is a row of the show's page, under its episodes. It plays the
 * file, Down from the last episode reaches it with the focus on it, and a season the chats hold
 * only as such a file is a season of the picker. The keyboard here is the TV remote's: the page is
 * one shared panel on the phone, the television and the desktop.
 */
@OptIn(ExperimentalTestApi::class)
class SeasonPackPageTest {

    private var nextId = 1L

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 2_400_000_000,
            durationSec = 2600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    @After
    fun reset() {
        OnlineMetadata.current = null
    }

    private fun show(vararg names: String): Series =
        SeriesShelf.arrange(names.map(::video)).filterIsInstance<ShelfEntry.Show>().single().series

    private fun androidx.compose.ui.test.ComposeUiTest.panel(series: Series, played: MutableList<MediaItem>) {
        val watch = SeriesWatch(point = { null }, finished = { false })
        setContent {
            TmMaterialTheme(dark = true) {
                // The window's arrows, as the desktop shell moves the focus for the remote.
                val focus = LocalFocusManager.current
                Box(Modifier.size(1280.dp, 900.dp).onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }) {
                    SeriesPanel(
                        series, watch, onPlay = { played += it }, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 32.dp),
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.untilText(text: String, timeoutMs: Long = 10_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The hero and the buttons fill the first screen; the episodes and the pack are further down. */
    private fun androidx.compose.ui.test.ComposeUiTest.scrollDown() {
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        waitForIdle()
    }

    private val names = arrayOf(
        "Harbour.Lights.S01E01.720p.mkv", "Harbour.Lights.S01E02.720p.mkv", "Harbour.Lights.S01E03.720p.mkv",
        "Harbour Lights Season 1 Complete 1080p.mkv", "Harbour Lights Season 2 Complete 1080p.mkv",
    )

    @Test
    fun `the pack is a row under its season and plays the file`() = runComposeUiTest {
        OnlineMetadata.current = null
        val played = mutableListOf<MediaItem>()
        val series = show(*names)
        panel(series, played)
        untilText("Season 1")
        scrollDown()
        untilText("Whole season")
        onNodeWithText("Whole season").performClick()
        waitForIdle()
        assertEquals(listOf("Harbour Lights Season 1 Complete 1080p.mkv"), played.map { it.fileName })
        // The pack is no episode: three of them, and the season that only has a pack is in the picker.
        onNodeWithText("Season 2").assertExists()
    }

    @Test
    fun `Down from the last episode reaches the pack row and the focus is on it`() = runComposeUiTest {
        OnlineMetadata.current = null
        panel(show(*names), mutableListOf())
        untilText("Season 1")
        scrollDown()
        untilText("Whole season")
        onNodeWithText("S1 E3").requestFocus()
        waitForIdle()
        onNodeWithText("S1 E3").assertIsFocused()
        onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("Whole season").assertIsFocused()
        // Up goes back to the last episode, and the pack row does not trap the focus.
        onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithText("S1 E3").assertIsFocused()
    }

    @Test
    fun `a season held only as a pack lists the pack and nothing missing`() = runComposeUiTest {
        OnlineMetadata.current = null
        val played = mutableListOf<MediaItem>()
        panel(show(*names), played)
        untilText("Season 2")
        onNodeWithText("Season 2").performClick()
        waitForIdle()
        onNodeWithText("Whole season").assertExists()
        onAllNodesWithText("Not in your chats yet").assertCountEquals(0)
        onNodeWithText("Whole season").performClick()
        waitForIdle()
        assertEquals(listOf("Harbour Lights Season 2 Complete 1080p.mkv"), played.map { it.fileName })
    }

    @Test
    fun `render shots of the page with a pack at a phone width and a desktop width`() {
        for ((name, width, height) in listOf(Triple("phone", 411, 890), Triple("desktop", 1280, 900))) {
            runComposeUiTest {
                OnlineMetadata.current = null
                val watch = SeriesWatch(point = { null }, finished = { false })
                setContent {
                    TmMaterialTheme(dark = true) {
                        Box(Modifier.size(width.dp, height.dp)) {
                            SeriesPanel(show(*names), watch, onPlay = {}, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (width < 600) 16.dp else 32.dp))
                        }
                    }
                }
                untilText("Season 1")
                scrollDown()
                untilText("Whole season")
                val image = onAllNodes(isRoot())[0].captureToImage()
                val out = File(System.getProperty("tm.shots") ?: "build/shots").also { it.mkdirs() }
                javax.imageio.ImageIO.write(image.toAwtImage(), "png", File(out, "season-pack-$name.png"))
            }
        }
    }
}
