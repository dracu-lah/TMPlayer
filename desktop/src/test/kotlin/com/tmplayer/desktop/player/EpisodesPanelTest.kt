package com.tmplayer.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeOrder
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The episode list (CP40) driven with a mouse and keys on the desktop: the playing episode takes
 * focus first, a season tab switches the list, a right click marks a row, a click plays it, and
 * the switches and the order report back.
 */
@OptIn(ExperimentalTestApi::class)
class EpisodesPanelTest {

    private fun episode(season: Int, number: Int) = MediaItem(
        chatId = 1, messageId = (season * 100 + number).toLong(), fileId = season * 100 + number,
        title = "Harbour Notes S0${season}E0$number", sizeBytes = 1, durationSec = 2634,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = season * 100 + number,
        fileName = "Harbour.Notes.S0${season}E0$number.mkv",
    )

    private val items = (1..4).map { episode(1, it) } + (1..2).map { episode(2, it) }

    @Test
    fun `the list is walked and answered from the keyboard and the mouse`() = runComposeUiTest {
        val played = mutableListOf<String>()
        val marked = mutableListOf<String>()
        var order by mutableStateOf(EpisodeOrder.Number)
        var autoplay by mutableStateOf(true)
        val playing = items[2]
        setContent {
            val steps = EpisodeNeighbours.around(playing, items, order)
            PlayerTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    EpisodesSheet(
                        state = EpisodesState(
                            series = steps.series!!,
                            playing = playing,
                            previous = steps.previous,
                            previousLabel = "Previous",
                            next = steps.next,
                            nextLabel = "Next",
                            autoplay = autoplay,
                            order = order,
                        ),
                        watch = SeriesWatch.None,
                        actions = EpisodesActions(
                            onPlay = { played += it.fileName },
                            onToggleWatched = { marked += it.fileName },
                            onAutoplay = { autoplay = it },
                            onOrder = { order = it },
                            onSetIntro = {},
                            onClearIntro = {},
                        ),
                        position = { 61_000 },
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()
        // The playing episode is where the keyboard starts, and Enter on it is the player's to
        // answer (it closes the list).
        onNodeWithText("Episode 3", substring = true).assertIsFocused()

        onNodeWithText("Episode 4", substring = true).performMouseInput { rightClick() }
        assertEquals(listOf("Harbour.Notes.S01E04.mkv"), marked)

        onNodeWithText("Episode 4", substring = true).performClick()
        assertEquals("Harbour.Notes.S01E04.mkv", played.last())

        onNodeWithText("Season 2").performClick()
        waitForIdle()
        onNodeWithText("Harbour Notes S02E02").assertExists()

        onNodeWithText("Next").performClick()
        assertEquals("Harbour.Notes.S01E04.mkv", played.last())

        onNodeWithText("Autoplay next episode").performClick()
        assertEquals(false, autoplay)
        onNodeWithText("Upload order").performClick()
        assertEquals(EpisodeOrder.Upload, order)
    }
}
