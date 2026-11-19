package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HX65: the desktop Home carousel has no arrow buttons at its sides. The pages still turn by the
 * arrow keys and by the dots, and the keyboard's Enter opens the page that is up.
 */
@OptIn(ExperimentalTestApi::class)
class HomeCarouselPagesTest {

    private var reduced = false

    @Before
    fun still() {
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
    }

    private fun clip(id: Long, name: String) = MediaItem(
        chatId = 2, messageId = id, fileId = 0, title = name, sizeBytes = 100L * 1024 * 1024,
        durationSec = 5400, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
        date = 1_780_000_000 - id.toInt(), fileName = name,
    )

    private val picks = listOf("First.Clip.2019.mp4", "Second.Clip.2020.mp4", "Third.Clip.2021.mp4")
        .mapIndexed { at, n -> FeaturedPick(ShelfEntry.File(clip(10L + at, n))) }

    private fun androidx.compose.ui.test.ComposeUiTest.show(opened: MutableList<FeaturedPick>) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Surface(Modifier.size(1280.dp, 800.dp), color = Tone.background) {
                        Box(Modifier.size(1280.dp, 800.dp)) {
                            FeaturedCarousel(
                                picks = picks, height = featuredHeight(1280.dp, 800.dp), side = 24.dp, watch = SeriesWatch.None,
                                chatTitle = { "Film Club" }, onPlay = {}, onDetails = { opened += it },
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `no scroll arrows over the hero carousel`() = runComposeUiTest {
        show(mutableListOf())
        waitForIdle()
        assertEquals(0, onAllNodesWithContentDescription("Scroll back").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription("Scroll on").fetchSemanticsNodes().size)
        // The dots stay: one clickable mark per page.
        val dots = onAllNodesWithContentDescription("Page", substring = true).fetchSemanticsNodes()
        assertTrue("dots missing: ${dots.size}", dots.size >= 3)
    }

    @Test
    fun `the arrow keys turn the pages`() = runComposeUiTest {
        val opened = mutableListOf<FeaturedPick>()
        show(opened)
        waitForIdle()
        val self = onAllNodes(hasContentDescription("Page 1 of 3") and !hasClickAction())[0]
        self.requestFocus()
        self.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onAllNodes(hasContentDescription("Page 2 of 3") and !hasClickAction())[0]
            .performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(picks[1].key), opened.map { it.key })
        onAllNodes(hasContentDescription("Page 2 of 3") and !hasClickAction())[0]
            .performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) }
        waitForIdle()
        // Left from the first page wraps to the last.
        assertEquals(1, onAllNodes(hasContentDescription("Page 3 of 3") and !hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `a dot turns to its page`() = runComposeUiTest {
        show(mutableListOf())
        waitForIdle()
        onAllNodes(hasContentDescription("Page 3 of 3") and hasClickAction())[0].performClick()
        waitForIdle()
        assertEquals(1, onAllNodes(hasContentDescription("Page 3 of 3") and !hasClickAction()).fetchSemanticsNodes().size)
    }

    /** HX63: a touchpad's two finger sideways swipe (a sideways scroll) turns one page, however long it runs on. */
    @Test
    fun `a sideways swipe turns one page and its momentum does not turn more`() = runComposeUiTest {
        show(mutableListOf())
        waitForIdle()
        onAllNodes(hasContentDescription("Page 1 of 3") and !hasClickAction())[0].performMouseInput {
            // A swipe: a burst of small sideways steps, 20 ms apart, as momentum delivers them.
            repeat(12) {
                scroll(1f, ScrollWheel.Horizontal)
                advanceEventTime(20)
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodes(hasContentDescription("Page 2 of 3") and !hasClickAction()).fetchSemanticsNodes().size)
        // The plain wheel is the page's: it turns nothing.
        onAllNodes(hasContentDescription("Page 2 of 3") and !hasClickAction())[0].performMouseInput {
            scroll(3f, ScrollWheel.Vertical)
        }
        waitForIdle()
        assertEquals(1, onAllNodes(hasContentDescription("Page 2 of 3") and !hasClickAction()).fetchSemanticsNodes().size)
    }
}
