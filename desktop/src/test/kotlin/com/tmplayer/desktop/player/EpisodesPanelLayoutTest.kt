package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.MediaItem
import com.tmplayer.desktop.ui.LayoutChecks
import com.tmplayer.ui.browse.EPISODE_ART_TAG
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The desktop's episode panel at 1280 and 1920 (W15, 2026-11-25): a panel at the window's end,
 * a third of its width between 380 and 520 dp, the window's whole height; its pictures are 16:9,
 * one width and one left edge, its titles one size and one left edge; and a season of 500 episodes
 * opens on the episode playing, well inside three seconds, with only the rows near it composed.
 */
@OptIn(ExperimentalTestApi::class)
class EpisodesPanelLayoutTest {

    private fun episode(season: Int, number: Int) = MediaItem(
        chatId = 1, messageId = (season * 1000 + number).toLong(), fileId = season * 1000 + number,
        title = "Harbour Notes S%02dE%03d".format(season, number), sizeBytes = 1_400_000_000,
        durationSec = 2634, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = 5_000_000 - (season * 1000 + number), fileName = "Harbour.Notes.S%02dE%03d.1080p.mkv".format(season, number),
    )

    private val items = (1..500).map { episode(1, it) } + (1..6).map { episode(2, it) }

    private fun ComposeUiTest.open(width: Int, height: Int, playing: MediaItem) {
        val steps = EpisodeNeighbours.around(playing, items)
        setContent {
            PlayerTheme {
                Box(Modifier.requiredSize(width.dp, height.dp).background(Color(0xFF203040)).testTag("window")) {
                    EpisodesSheet(
                        state = EpisodesState(
                            series = steps.series!!, playing = playing, previous = steps.previous, previousLabel = "Previous",
                            next = steps.next, nextLabel = "Next", autoplay = true,
                        ),
                        watch = SeriesWatch.None,
                        actions = EpisodesActions(onPlay = {}, onToggleWatched = {}, onAutoplay = {}),
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun check(width: Int, height: Int) = runComposeUiTest {
        val started = System.nanoTime()
        open(width, height, items[299])
        val openMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("opened in $openMs ms, under 3000", openMs < 3_000)
        // 300 deep in a season of 500: scrolled to and focused.
        onNodeWithText("Episode 300", substring = true).assertIsFocused()

        // The panel and the window by their unmerged nodes (the scrim around the panel merges what is under it).
        val layout = LayoutChecks(this)
        val window = onAllNodesWithTag("window", useUnmergedTree = true).fetchSemanticsNodes().single()
        val panel = onAllNodesWithTag(EPISODES_PANEL_TAG, useUnmergedTree = true).fetchSemanticsNodes().single()
        layout.same("the panel's right edge is the window's", window.positionInRoot.x + window.size.width, panel.positionInRoot.x + panel.size.width)
        layout.same("the panel is the window's whole height", window.size.height.toFloat(), panel.size.height.toFloat())
        layout.same("the panel starts where its third of the window does", panel.size.width.toFloat(), (width * 0.32f).coerceIn(380f, 520f))

        val art = onAllNodesWithTag(EPISODE_ART_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("pictures drawn", art.size >= 3)
        assertTrue("composed ${art.size} pictures for 500 episodes", art.size <= 16)
        art.forEach { assertTrue("16:9", abs(it.size.width * 9f / 16f - it.size.height) <= 1.5f) }
        assertEquals("one picture width", 1, art.map { it.size.width }.toSet().size)
        assertEquals("one left edge", 1, art.map { Math.round(it.positionInRoot.x) }.toSet().size)
        // The pictures are about 40 percent of the list, none under 104 dp or over 220.
        val w = art.first().size.width.toFloat()
        assertTrue("picture $w dp", w in 104f..220f)
    }

    @Test
    fun `at 1280 the panel is a third wide, full height, the pictures 16 by 9 on one edge`() = check(1280, 720)

    @Test
    fun `at 1920 the panel stops at 520 dp`() = check(1920, 1080)
}
