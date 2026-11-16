package com.tmplayer.desktop.cast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.cast.CastPhase
import com.tmplayer.cast.TranscodeProgress
import com.tmplayer.i18n.L
import com.tmplayer.ui.player.CastRemote
import com.tmplayer.ui.player.CastRemoteActions
import com.tmplayer.ui.player.CastRemoteState
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast remote under a finger, a keyboard and a large font (R8): every control is at least
 * 48 dp both ways, Tab draws a ring round the control it lands on (the controls had no focus mark
 * at all, so a desktop keyboard could not see where it was), the way-on pills stay whole on the
 * screen at twice the font, and the subtitles button says whether it is on.
 */
@OptIn(ExperimentalTestApi::class)
class CastRemoteReachTest {

    private val actions = object : CastRemoteActions {
        override fun back() = Unit
        override fun devices() = Unit
        override fun togglePlay() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun previous() = Unit
        override fun next() = Unit
        override fun audio() = Unit
        override fun subtitles() = Unit
        override fun speed() = Unit
        override fun setVolume(level: Float) = Unit
        override fun toggleMute() = Unit
        override fun stop() = Unit
        override fun reconnect() = Unit
        override fun playHere() = Unit
        override fun retry() = Unit
    }

    private val playing = CastRemoteState(
        title = "Episode 4",
        subtitle = "Season 1",
        device = "Living Room TV",
        phase = CastPhase.Playing,
        positionMs = 754_000,
        durationMs = 1_442_000,
        showsPause = true,
        volume = 0.42f,
        hasAudioChoice = true,
        hasSubtitles = true,
        subtitlesOn = true,
        speed = 1f,
        hasPrevious = true,
        hasNext = true,
        converting = TranscodeProgress(0, 802_000, 1_442_000, 1.6f),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: CastRemoteState, widthDp: Int, heightDp: Int, fontScale: Float = 1f) {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { CastRemote(state, actions) }
                }
            }
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertTargetsAtLeast48() {
        val density = onRoot().fetchSemanticsNode().layoutInfo.density.density
        // The laid out size, not the bounds clipped by a scroll: a control below the fold is whole.
        val small = onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.height / density < 47.5f || it.size.width / density < 47.5f }
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription) ?: it.config.getOrNull(SemanticsProperties.Text) }
        assertTrue("controls under 48 dp: $small", small.isEmpty())
    }

    /** Every control inside the screen: for the sideways layout, which does not scroll. */
    private fun androidx.compose.ui.test.ComposeUiTest.assertAllOnScreen() {
        val root = onRoot().fetchSemanticsNode().size
        val off = onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.positionInRoot.y + it.size.height > root.height + 0.5f || it.positionInRoot.y < -0.5f }
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription) ?: it.config.getOrNull(SemanticsProperties.Text) }
        assertTrue("controls off the screen: $off", off.isEmpty())
    }

    @Test
    fun `every control is a 48 dp target, upright and sideways`() {
        runComposeUiTest {
            show(playing, 393, 760)
            assertTargetsAtLeast48()
        }
        runComposeUiTest {
            show(playing.copy(converting = null), 851, 393)
            assertTargetsAtLeast48()
        }
        runComposeUiTest {
            show(playing, 360, 740, fontScale = 2f)
            assertTargetsAtLeast48()
        }
        runComposeUiTest {
            show(playing.copy(converting = null), 740, 360, fontScale = 2f)
            assertTargetsAtLeast48()
            assertAllOnScreen()
        }
    }

    @Test
    fun `the way on stays whole on a phone at twice the font`() = runComposeUiTest {
        show(playing.copy(phase = CastPhase.Failed, error = "The TV couldn't play this video.", converting = null), 360, 740, fontScale = 2f)
        val width = onRoot().fetchSemanticsNode().boundsInRoot.width
        for (word in listOf(L.commonTryAgain, L.castPlayHere)) {
            val node = onNode(hasText(word) and hasClickAction()).fetchSemanticsNode()
            assertTrue("$word runs off the screen: ${node.boundsInRoot} in $width", node.boundsInRoot.right <= width + 0.5f)
        }
        assertTargetsAtLeast48()
    }

    @Test
    fun `a keyboard sees where it is`() = runComposeUiTest {
        show(playing, 393, 760)
        val back = onNode(hasContentDescription(L.commonBack) and hasClickAction())
        val before = back.captureToImage().toPixelMap()
        back.requestFocus()
        waitForIdle()
        val after = back.captureToImage().toPixelMap()
        var changed = 0
        for (x in 0 until before.width) for (y in 0 until before.height) if (before[x, y] != after[x, y]) changed++
        assertTrue("focus left no mark on Back ($changed pixels changed)", changed > before.width * 2)
    }

    @Test
    fun `the first Tab lands on a named control, not on the screen behind them`() = runComposeUiTest {
        show(playing, 393, 760)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        val focused = onNode(isFocused()).fetchSemanticsNode()
        assertTrue("Tab landed on ${focused.config}", focused.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true)
    }

    @Test
    fun `the subtitles button says whether it is on`() = runComposeUiTest {
        show(playing, 393, 760)
        val node = onNode(hasContentDescription(L.tracksSubtitles) and hasClickAction()).fetchSemanticsNode()
        assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
        onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
    }
}
