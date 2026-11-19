package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HX55, the desktop's machine checks: the Home carousel's words (overview, scores, Play and Details)
 * stay whole inside the frame from a small window to 4K, at 100%, 150% and 200% scale. The frame is
 * the page's width (window pixels over the scale) and [featuredHeight] tall, so a 1280 x 800 window
 * at 200% is a 640 x 400 dp page: the words column must still fit under its own top.
 */
@OptIn(ExperimentalTestApi::class)
class HomeCarouselScaleTest {

    private var reduced = false

    @Before
    fun still() {
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
        com.tmplayer.online.OnlineMetadata.current = null
    }

    private val bunny = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", sizeBytes = 885L * 1024 * 1024,
        durationSec = 5400, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = 1_790_000_000, fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", width = 1920, height = 1080,
    )

    @Test fun `words fit at 100 percent`() = fits(1f)
    @Test fun `words fit at 150 percent`() = fits(1.5f)
    @Test fun `words fit at 200 percent`() = fits(2f)

    private fun fits(scale: Float) {
        // Window pixels: a small laptop, full HD and 4K.
        for ((wPx, hPx) in listOf(1280 to 800, 1920 to 1080, 3840 to 2160)) fitsIn(wPx, hPx, scale)
    }

    private fun fitsIn(wPx: Int, hPx: Int, scale: Float) = runComposeUiTest {
        MetaFixture.install("on")
        run {
            val w = (wPx / scale).dp
            val h = (hPx / scale).dp
            val height = featuredHeight(w, h)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    TmMaterialTheme(dark = true) {
                        Surface(Modifier.size(w, h), color = Tone.background) {
                            Box(Modifier.testTag("carousel")) {
                                FeaturedCarousel(
                                    picks = listOf(FeaturedPick(ShelfEntry.File(bunny))), height = height, side = 24.dp,
                                    watch = SeriesWatch.None, chatTitle = { "Film Club" }, onPlay = {}, onDetails = {},
                                )
                            }
                        }
                    }
                }
            }
            // The canned lookups run off the test clock: wait for the overview to be in.
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasText("gentle giant", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            val frame = onNodeWithTag("carousel").getBoundsInRoot()
            val overview = onAllNodes(hasText("gentle giant", substring = true)).fetchSemanticsNodes().first().boundsInRoot
            val play = onAllNodes(hasText("Play")).fetchSemanticsNodes().first().boundsInRoot
            val details = onAllNodes(hasContentDescription("Details")).fetchSemanticsNodes().first().boundsInRoot
            val label = "${wPx}x$hPx at $scale (page ${w.value} x ${height.value} dp)"
            val top = frame.top.value * scale
            val bottom = frame.bottom.value * scale
            assertTrue("$label: overview above the frame ${overview.top} < $top", overview.top >= top - 1f)
            assertTrue("$label: Play below the frame ${play.bottom} > $bottom", play.bottom <= bottom + 1f)
            assertTrue("$label: Details below the frame ${details.bottom} > $bottom", details.bottom <= bottom + 1f)
            assertTrue("$label: the overview runs into Play (${overview.bottom} > ${play.top})", overview.bottom <= play.top + 1f)
        }
    }
}
