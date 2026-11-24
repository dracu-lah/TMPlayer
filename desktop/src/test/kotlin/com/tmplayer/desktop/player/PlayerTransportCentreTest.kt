package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop player's centre transport: Play and Pause stand on the middle of the picture for the
 * first, a middle and the last episode and for a single video (a missing Previous or Next keeps its
 * slot), the two skip buttons sit evenly either side of it, and they seek by the 10 s step.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerTransportCentreTest {

    private val skipped = mutableListOf<Long>()
    private var previous = 0
    private var next = 0

    private fun ComposeUiTest.open(hasPrevious: Boolean, hasNext: Boolean) {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("bar")) {
                    PlayerTransport(
                        status = PlaybackStatus(playing = true),
                        previousHint = if (hasPrevious) "Previous episode" else null,
                        nextHint = if (hasNext) "Next episode" else null,
                        onPrevious = { previous++ },
                        onNext = { next++ },
                        onSkip = { skipped += it },
                        onTogglePlay = {},
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.centreOf(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x

    private fun ComposeUiTest.assertCentred() {
        val bar = centreOf("bar")
        assertEquals("Play and Pause on the middle", bar, centreOf("play-pause"), 1f)
        assertEquals("the skips either side of it", centreOf("play-pause") - centreOf("skip-back"), centreOf("skip-forward") - centreOf("play-pause"), 1f)
    }

    @Test
    fun `centred for the first episode, no Previous`() = runComposeUiTest {
        open(hasPrevious = false, hasNext = true)
        assertCentred()
        onNodeWithTag("previous-episode").assertDoesNotExistCompat()
        onNodeWithTag("next-episode").assertExists()
    }

    @Test
    fun `centred for a middle episode`() = runComposeUiTest {
        open(hasPrevious = true, hasNext = true)
        assertCentred()
        onNodeWithTag("previous-episode").assertExists()
        onNodeWithTag("next-episode").assertExists()
    }

    @Test
    fun `centred for the last episode, no Next`() = runComposeUiTest {
        open(hasPrevious = true, hasNext = false)
        assertCentred()
        onNodeWithTag("next-episode").assertDoesNotExistCompat()
    }

    @Test
    fun `centred for a single video, neither`() = runComposeUiTest {
        open(hasPrevious = false, hasNext = false)
        assertCentred()
        onNodeWithTag("previous-episode").assertDoesNotExistCompat()
        onNodeWithTag("next-episode").assertDoesNotExistCompat()
    }

    private fun widthOf(hasPrevious: Boolean, hasNext: Boolean): Float {
        var width = 0f
        runComposeUiTest {
            open(hasPrevious, hasNext)
            width = onNodeWithTag("transport").fetchSemanticsNode().boundsInRoot.width
        }
        return width
    }

    @Test
    fun `the whole transport is the same width in all four cases`() {
        val widths = listOf(false to false, false to true, true to false, true to true).map { (p, n) -> widthOf(p, n) }
        assertTrue("one width for every case: $widths", widths.all { kotlin.math.abs(it - widths[0]) <= 1f })
    }

    @Test
    fun `the skip buttons seek back and forward by 10 s and the episode buttons step`() = runComposeUiTest {
        open(hasPrevious = true, hasNext = true)
        onNodeWithTag("skip-back").performClick()
        onNodeWithTag("skip-forward").performClick()
        onNodeWithTag("previous-episode").performClick()
        onNodeWithTag("next-episode").performClick()
        assertEquals(listOf(-10_000L, 10_000L), skipped)
        assertEquals(1, previous)
        assertEquals(1, next)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    /** The four cases stacked on one frame, a guide line down the middle of the picture. With TMPLAYER_SHOTS=<dir> it is written there. */
    @Test
    fun `a picture of the four cases`() {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val png = androidx.compose.ui.ImageComposeScene(900, 560, androidx.compose.ui.unit.Density(1f)) {
            TmMaterialTheme(dark = true) {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxSize().background(Color(0xFF14161A)),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for ((p, n) in listOf(false to true, true to true, true to false, false to false)) {
                        PlayerTransport(PlaybackStatus(playing = true), if (p) "Previous episode" else null, if (n) "Next episode" else null, {}, {}, {}, {})
                    }
                }
                // The middle of the picture.
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxSize().background(Color(0x33FF5050)))
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(200)
            scene.render(400_000_000).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        }
        java.io.File(out).apply { mkdirs() }.resolve("player-transport-four-cases.png").writeBytes(png)
        assertTrue(png.size > 1000)
    }
}
