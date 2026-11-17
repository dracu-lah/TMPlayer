package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.LocalSeriesWatch
import com.tmplayer.ui.browse.MediaDetailScreen
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.WideDetailPanel
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalTrailerOpener
import com.tmplayer.ui.online.TrailerOpener
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The trailer button after the in-app trailer seam (private/TRAILERS-PLAN.md, T1): on a phone's
 * page upright and on its side, the desktop's large detail and a show's page, the button is still
 * there once the extras arrive, takes the focus, keeps its name, and a press goes to the
 * platform's player behind [LocalTrailerOpener] with the title's trailer. The television's is
 * held to the same by the app's TvTrailerFocusTest.
 */
@OptIn(ExperimentalTestApi::class)
class TrailerButtonTest {

    private val bunny = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", sizeBytes = 885L * 1024 * 1024,
        durationSec = 596, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", width = 1920, height = 1080,
    )

    private val episodes = (1..4).map { n ->
        bunny.copy(chatId = 4, messageId = 600L + n, title = "Harbour.Lights.S01E%02d.1080p.WEB-DL.mkv".format(n), fileName = "Harbour.Lights.S01E%02d.1080p.WEB-DL.mkv".format(n))
    }

    private val rows = listOf(
        DetailRow(DetailAction.Play, "Play", TmIcons.Play, onSelect = {}),
        DetailRow(DetailAction.MarkWatched, "Mark as watched", TmIcons.Check, onSelect = {}),
    )

    private var reduced = false

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

    private class Recording : TrailerOpener {
        val opened = mutableListOf<String>()
        override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
            opened += trailer.key
        }
    }

    @Test
    fun `a film's page upright keeps its trailer button and sends it to the platform's player`() = trailer(412, 900, "YE7VzlLtp-4") {
        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `a film's page on its side keeps its trailer button`() = trailer(900, 412, "YE7VzlLtp-4") {
        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `the desktop's large detail keeps its trailer button`() = trailer(1000, 740, "YE7VzlLtp-4") {
        WideDetailPanel(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `a show's page keeps its trailer button`() = trailer(412, 1000, "promoHarbr1") {
        val show = (SeriesShelf.arrange(episodes).first() as ShelfEntry.Show).series
        SeriesPanel(show, SeriesWatch.None, onPlay = {}, onDownload = {}, chatTitle = "Weekend series")
    }

    private fun trailer(width: Int, height: Int, key: String, page: @Composable () -> Unit) = runComposeUiTest {
        MetaFixture.install("on")
        val player = Recording()
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalTrailerOpener provides player) {
                TmMaterialTheme(dark = true) {
                    CompositionLocalProvider(LocalSeriesWatch provides SeriesWatch.None) {
                        Box(Modifier.size(width.dp, height.dp)) {
                            Surface(Modifier.size(width.dp, height.dp), color = Tone.background) { page() }
                        }
                    }
                }
            }
        }
        advance { buttons().fetchSemanticsNodes().isNotEmpty() }
        repeat(10) { step() }
        val button = buttons().onFirst()
        button.requestFocus()
        step()
        button.assertIsFocused()
        button.performClick()
        step()
        assertEquals("the press reached the platform's player", listOf(key), player.opened)
    }

    /** The trailer button by its name, as a screen reader reads it: "Watch trailer", or the round button's "Trailer". */
    private fun ComposeUiTest.buttons() = onAllNodes(
        hasClickAction() and (hasText(L.metadataTrailer) or hasContentDescription(L.metadataTrailer) or hasText(L.detailRoundTrailer)),
    )

    private fun ComposeUiTest.step() {
        Thread.sleep(20)
        mainClock.advanceTimeBy(50)
    }

    private fun ComposeUiTest.advance(until: () -> Boolean) {
        repeat(200) {
            step()
            if (until()) return
        }
        throw AssertionError("timed out")
    }
}
