package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormatFacts
import com.tmplayer.ui.components.FormatBadgeRow
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format badges on the desktop (T8): the row of pills in a narrow column at the normal and at a
 * 200% font, every pill whole inside the column, none a control or a keyboard stop.
 */
@OptIn(ExperimentalTestApi::class)
class FormatBadgeRenderTest {

    private val tags = FormatFacts.tags(
        FormatFacts.Probe(
            videoMime = "video/hevc", width = 3840, height = 2160, frameRate = 59.94f, transfer = FormatFacts.Transfer.Pq,
            audio = listOf(FormatFacts.AudioTrack(mime = "audio/dts", profile = "DTS-HD MA", channels = 8)),
        ),
    )

    private fun check(fontScale: Float) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TmMaterialTheme(dark = true) { Box(Modifier.width(280.dp)) { FormatBadgeRow(tags) } }
            }
        }
        for (text in listOf("4K", "HDR10", "HEVC", "60 fps", "DTS-HD", "7.1")) {
            onNodeWithText(text).assertIsDisplayed()
            val bounds = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertTrue("$text inside the 280 dp column at $fontScale: $bounds", bounds.left >= 0f && bounds.right <= 280f)
        }
        onAllNodes(hasClickAction()).assertCountEquals(0)
        onAllNodes(isFocusable()).assertCountEquals(0)
        onAllNodes(hasText("4K")).assertCountEquals(1)
    }

    @Test
    fun `the row sits in a narrow column`() = check(1f)

    @Test
    fun `the row wraps at a 200 percent font`() = check(2f)
}
