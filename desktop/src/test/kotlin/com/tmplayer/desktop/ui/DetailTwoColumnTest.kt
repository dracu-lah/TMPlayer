package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaItem
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.PLAY_PILL_TAG
import com.tmplayer.ui.browse.WideDetailPanel
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A video's large page in the desktop's window widths (900, 1280, 1920 and 2560 dp): the words keep to a
 * centred column with equal gutters (at most 1280 dp), from 1100 dp the body is two columns with the
 * About block beside the video's words, whose file name has room (no wrap every few letters), and Play
 * and the round actions stay reachable from the keyboard in both layouts.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTwoColumnTest {

    private val bunny = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", sizeBytes = 885L * 1024 * 1024,
        durationSec = 596, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", width = 1920, height = 1080,
    )

    private val rows = listOf(
        DetailRow(DetailAction.Play, "Play", TmIcons.Play, onSelect = {}),
        DetailRow(DetailAction.MarkWatched, "Mark as watched", TmIcons.Check, onSelect = {}),
        DetailRow(DetailAction.AddWatchLater, "Add to Watch later", TmIcons.Clock, onSelect = {}),
        DetailRow(DetailAction.Download, "Download", TmIcons.Download, onSelect = {}),
    )

    private var reduced = false
    private lateinit var focusManager: FocusManager

    @Before
    fun still() {
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
        OnlineMetadata.current = null
    }

    @Test
    fun `the words sit in a centred column with equal gutters and the About block beside them`() {
        for (width in listOf(1280, 1920, 2560)) {
            page(width, 900) {
                val gutter = onNodeWithTag(PLAY_PILL_TAG).getBoundsInRoot().left
                val expected = maxOf(56.dp, (width.dp - 1280.dp) / 2)
                assertEquals("left gutter at $width", expected.value, gutter.value, 1f)
                val about = onAllNodesWithText("Details")[0].getBoundsInRoot()
                // The right edge of the About block is the mirror of the left gutter.
                val name = onAllNodesWithText(bunny.fileName)[0].getBoundsInRoot()
                assertTrue("About is beside Play at $width: ${about.left} vs ${gutter}", about.left > gutter + 400.dp)
                assertTrue("the file name has room at $width: ${name.right - name.left}", (name.right - name.left) > 200.dp)
                assertTrue("the About block ends inside the right gutter at $width", name.right <= width.dp - gutter + 1.dp)
                // Top aligned with the facts: About's heading starts within a facts line of the first facts row.
                assertTrue("About is top aligned at $width", about.top < onNodeWithTag(PLAY_PILL_TAG).getBoundsInRoot().top)
            }
        }
    }

    @Test
    fun `one column below 1100 dp keeps the About block under the words`() {
        page(900, 900) {
            val gutter = onNodeWithTag(PLAY_PILL_TAG).getBoundsInRoot().left
            assertEquals(56f, gutter.value, 1f)
            val about = onAllNodesWithText("Details")[0].getBoundsInRoot()
            assertEquals("About starts at the same gutter in one column", gutter.value, about.left.value, 1f)
        }
    }

    @Test
    fun `Play and the round actions are reachable by keyboard in one and two columns`() {
        val labels = listOf("Mark as watched", "Add to Watch later", "Download")
        for (width in listOf(900, 1920)) {
            page(width, 900) {
                onNodeWithTag(PLAY_PILL_TAG).requestFocus()
                val seen = mutableSetOf<String>()
                repeat(16) {
                    onRoot().performKeyInput { pressKey(Key.Tab) }
                    waitForIdle()
                    for (label in labels) {
                        if (onAllNodes(hasContentDescription(label) and isFocused()).fetchSemanticsNodes().isNotEmpty()) seen += label
                    }
                }
                assertEquals("Tab reaches every action at $width", labels.toSet(), seen)
                // The shell turns an arrow into a move of focus in that direction (ShellState.onKey):
                // Right from Play walks the round actions in order, as it does in the hero.
                onNodeWithTag(PLAY_PILL_TAG).requestFocus()
                val along = mutableListOf<String>()
                repeat(4) {
                    runOnUiThread { focusManager.moveFocus(FocusDirection.Right) }
                    waitForIdle()
                    labels.firstOrNull { onAllNodes(hasContentDescription(it) and isFocused()).fetchSemanticsNodes().isNotEmpty() }?.let { along += it }
                }
                assertEquals("Right from Play walks the actions at $width", labels, along.distinct())
            }
        }
    }

    private fun page(width: Int, height: Int, check: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width, height) {
        MetaFixture.install("on")
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    focusManager = LocalFocusManager.current
                    Box(Modifier.size(width.dp, height.dp)) {
                        Surface(Modifier.size(width.dp, height.dp), color = Tone.background) {
                            WideDetailPanel(bunny, "Film Club", null, false, rows, onClose = {}, margin = 56.dp)
                        }
                    }
                }
            }
        }
        // Past the first frame's hold and the canned lookups.
        for (frame in 0 until 200) {
            Thread.sleep(20)
            mainClock.advanceTimeBy(50)
            if (onAllNodesWithText("Directed by", substring = true).fetchSemanticsNodes().isNotEmpty()) break
        }
        repeat(10) {
            Thread.sleep(20)
            mainClock.advanceTimeBy(50)
        }
        check()
    }
}
