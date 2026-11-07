package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long show on the desktop (the Die Bergretter report, 2026-11-07): 18 seasons, the last with 31
 * episodes and a sixth posted in two halves (the same season and episode number). The mouse wheel
 * moves the page, the last episode is reachable, and the halves are two cards with their own
 * labels and keys, so neither the list nor the focus takes them for one.
 */
@OptIn(ExperimentalTestApi::class)
class SeriesLongShowTest {

    private var nextId = 1L

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 400_000_000,
            durationSec = 2600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    @Test
    fun `the wheel and the list reach the last episode and the halves are two cards`() = runComposeUiTest {
        val names = buildList {
            for (season in 1..17) for (ep in 1..3) add("Die Bergretter - S%02d E%02d.mp4".format(season, ep))
            for (ep in 1..30) {
                if (ep == 6) {
                    add("Die Bergretter - S18 E06b - Teil 2.mp4")
                    add("Die Bergretter - S18 E06a - Teil 1.mp4")
                } else {
                    add("Die Bergretter - S18 E%02d.mp4".format(ep))
                }
            }
        }
        val series = SeriesShelf.arrange(names.map(::video)).filterIsInstance<ShelfEntry.Show>().single().series
        val watch = SeriesWatch(point = { null }, finished = { false })
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    SeriesPanel(series, watch, onPlay = {}, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp))
                }
            }
        }
        waitForIdle()
        val list = onNode(hasScrollToIndexAction())
        // The wheel moves the page.
        list.performMouseInput { scroll(400f) }
        waitForIdle()
        val offset = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f
        assertTrue("the wheel scrolled the page: $offset", offset > 0f)

        list.performScrollToIndex(1)
        waitForIdle()
        onNodeWithText("Season 18").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitForIdle()
        list.performScrollToIndex(7)
        waitForIdle()
        onNodeWithText("S18 E6a").assertExists()
        onNodeWithText("S18 E6b").assertExists()
        list.performScrollToIndex(32)
        waitForIdle()
        onNodeWithText("S18 E30").assertExists()
    }
}
