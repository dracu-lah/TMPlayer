package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The lead page for a video being watched that has no picture at all (no Telegram thumbnail, no
 * online match; PL-1 follow up): a hero on its generated cover with the name, the time left, Resume
 * and Details, drawn at a television's, a phone's and a desktop's size. With TMPLAYER_SHOTS=<dir>
 * the frames are written there.
 */
@OptIn(ExperimentalTestApi::class)
class HomeCarouselResumeNoArtTest {

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

    private val kuruthi = MediaItem(
        chatId = 7, messageId = 41, fileId = 41, title = "Kuruthi.2021.1080p.mkv", sizeBytes = 2_400_000_000,
        durationSec = 7_320, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 900, fileName = "Kuruthi.2021.1080p.mkv",
    )

    private val watch = SeriesWatch(
        // Six minutes in of two hours two: 1 h 56 min left.
        point = { if (it.id == kuruthi.id) WatchPoint(positionMs = 360_000, durationMs = 7_320_000) else null },
        finished = { false },
    )

    private val picks = listOf(FeaturedPick(ShelfEntry.File(kuruthi)))

    private fun page(w: Int, h: Int, onPlay: (MediaItem) -> Unit = {}): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.size(w.dp, h.dp), color = Tone.background) {
                    Box(Modifier.size(w.dp, h.dp)) {
                        FeaturedCarousel(
                            picks = picks, height = featuredHeight(w.dp, h.dp), side = 24.dp, watch = watch,
                            chatTitle = { "Weekend Films" }, onPlay = onPlay, onDetails = {},
                        )
                    }
                }
            }
        }
    }

    private fun shows(w: Int, h: Int) = runComposeUiTest {
        var played: MediaItem? = null
        setContent(page(w, h) { played = it })
        waitForIdle()
        onNodeWithText("Resume").assertExists()
        onNode(hasText("Kuruthi", substring = true), useUnmergedTree = true).assertExists()
        onNode(hasText("1h 56m left", substring = true), useUnmergedTree = true).assertExists()
        assertTrue("no Play beside Resume", onAllNodes(hasText("Play")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("Resume").performClick()
        waitForIdle()
        assertEquals(kuruthi.id, played?.id)
    }

    @Test fun `a resume video with no art leads at a television's size`() = shows(960, 540).also { shot(960, 540) }
    @Test fun `a resume video with no art leads at a phone's size`() = shows(411, 891).also { shot(411, 891) }
    @Test fun `a resume video with no art leads at a desktop's size`() = shows(1280, 800).also { shot(1280, 800) }

    /** The frame as a PNG, when asked for; always drawn, so a crash in the cover shows here. */
    private fun shot(w: Int, h: Int) {
        val png = ImageComposeScene(w, h, Density(1f)) { page(w, h)() }.use { scene ->
            var t = 0L
            repeat(8) {
                scene.render(t)
                Thread.sleep(100)
                t += 100_000_000
            }
            scene.render(t + 1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("resume-no-art-$w.png").writeBytes(png)
    }
}
