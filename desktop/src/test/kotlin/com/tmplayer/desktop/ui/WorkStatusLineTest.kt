package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.LINE_MIN_HEIGHT
import com.tmplayer.ui.components.ShownWork
import com.tmplayer.ui.components.WorkStatusLine
import com.tmplayer.ui.components.WorkStatusPill
import com.tmplayer.ui.components.rememberWorkStatusTimer
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared work status line and the television's pill (WP-91), drawn by Compose on the desktop:
 * Idle takes nothing, a failure offers Try again, the words are a polite live region except a
 * ticking wait's, one height for Updating and the failure, the timing ladder on a virtual clock,
 * and a large font or a right to left language wraps rather than spills.
 */
@OptIn(ExperimentalTestApi::class)
class WorkStatusLineTest {

    private val now = 1_800_000_000_000L
    private val failed = WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000)

    private val live = SemanticsMatcher("is a polite live region") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }

    private fun described(text: String) = SemanticsMatcher("described as \"$text\"") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    @Test
    fun `Idle draws no node and takes no space`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.testTag("host")) { WorkStatusLine(ShownWork.None) }
            }
        }
        assertEquals(0f, onNodeWithTag("host").getBoundsInRoot().let { (it.bottom - it.top).value })
        onAllNodes(live).assertCountEqualsTo(0)
    }

    @Test
    fun `a failure with content says what is kept and Try again answers the click`() = runComposeUiTest {
        var retries = 0
        setContent {
            TmMaterialTheme(dark = true) {
                WorkStatusLine(ShownWork(failed), onRetry = { retries++ }, now = { now })
            }
        }
        val words = WorkStatusWords.line(failed, now)
        onNode(described(words) and live).assertExists()
        onNodeWithText(L.commonTryAgain).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a ticking wait is not a live region that changes every second`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var clock by mutableStateOf(now)
        val limited = WorkStatus.RateLimited(now + 42_000)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.testTag("line")) { WorkStatusLine(ShownWork(limited), now = { clock }) }
            }
        }
        mainClock.advanceTimeBy(100)
        val start = WorkStatusWords.line(limited, now)
        onNode(described(start) and live).assertExists()
        val before = onNodeWithTag("line").captureToImage().toPixelMap()
        repeat(3) {
            clock += 1_000
            mainClock.advanceTimeBy(1_000)
        }
        // The seconds on screen moved; what a screen reader is told did not.
        val after = onNodeWithTag("line").captureToImage().toPixelMap()
        var differ = false
        for (x in 0 until minOf(before.width, after.width)) for (y in 0 until minOf(before.height, after.height)) {
            if (before[x, y] != after[x, y]) differ = true
        }
        assertTrue("the seconds on screen tick", differ)
        onNode(described(start) and live).assertExists()
        onAllNodes(described(WorkStatusWords.line(limited, clock))).assertCountEqualsTo(0)
    }

    @Test
    fun `a wait that runs out ends the line and its spinner by itself`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var clock by mutableStateOf(now)
        val limited = WorkStatus.RateLimited(now + 2_500)
        setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.testTag("host")) {
                    WorkStatusLine(ShownWork(limited), now = { clock })
                    WorkStatusPill(ShownWork(limited), now = { clock })
                }
            }
        }
        mainClock.advanceTimeBy(100)
        onAllNodes(described(WorkStatusWords.line(limited, now))).assertCountEqualsTo(2)
        repeat(3) {
            clock += 1_000
            mainClock.advanceTimeBy(1_000)
        }
        mainClock.advanceTimeBy(500)
        // Not left at "1 second" with a spinner turning: both are gone and take no space.
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEqualsTo(0)
        assertEquals(0f, onNodeWithTag("host").getBoundsInRoot().let { (it.bottom - it.top).value })
        // And nothing is left ticking: a further minute of the clock changes nothing and draws nothing.
        clock += 60_000
        mainClock.advanceTimeBy(60_000)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEqualsTo(0)
    }

    @Test
    fun `work that runs past 15 s says Telegram is slow and offers Retry`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var retries = 0
        setContent {
            TmMaterialTheme(dark = true) {
                WorkStatusLine(WorkStatus.Syncing(), asked = true, onRetry = { retries++ }, now = { now })
            }
        }
        mainClock.advanceTimeBy(14_000)
        onAllNodesWithText(L.commonRetry).assertCountEqualsTo(0)
        mainClock.advanceTimeBy(1_100)
        onNode(described(L.commonSlowAnswer)).assertExists()
        onNodeWithText(L.commonRetry).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the line keeps one height from Updating to the failure`() = runComposeUiTest {
        var status by mutableStateOf<ShownWork>(ShownWork(WorkStatus.Syncing()))
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.width(600.dp).testTag("line")) { WorkStatusLine(status, onRetry = {}, now = { now }) }
            }
        }
        val syncing = onNodeWithTag("line").getBoundsInRoot().let { it.bottom - it.top }
        status = ShownWork(failed)
        waitForIdle()
        val failing = onNodeWithTag("line").getBoundsInRoot().let { it.bottom - it.top }
        assertEquals(syncing, failing)
        assertEquals(LINE_MIN_HEIGHT, syncing)
    }

    @Test
    fun `nothing at 300 ms, the words at 1 s, Up to date gone at 2 s`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var status by mutableStateOf<WorkStatus>(WorkStatus.Syncing())
        setContent {
            TmMaterialTheme(dark = true) {
                WorkStatusLine(rememberWorkStatusTimer(status, asked = true), asked = true, now = { now })
            }
        }
        mainClock.advanceTimeBy(300)
        onAllNodes(described(L.stateUpdating)).assertCountEqualsTo(0)
        mainClock.advanceTimeBy(800)
        onNode(described(L.stateUpdating) and live).assertExists()

        status = WorkStatus.Updated(now)
        mainClock.advanceTimeBy(100)
        onNode(described(L.stateUpdated) and live).assertExists()
        mainClock.advanceTimeBy(2_000)
        onAllNodes(described(L.stateUpdated)).assertCountEqualsTo(0)
    }

    @Test
    fun `automatic work says nothing for 3 s and is not spoken`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            TmMaterialTheme(dark = true) {
                WorkStatusLine(rememberWorkStatusTimer(WorkStatus.Syncing(), asked = false), asked = false, now = { now })
            }
        }
        mainClock.advanceTimeBy(2_900)
        onAllNodes(described(L.stateUpdating)).assertCountEqualsTo(0)
        mainClock.advanceTimeBy(200)
        onNode(described(L.stateUpdating)).assertExists()
        onAllNodes(live).assertCountEqualsTo(0)
    }

    @Test
    fun `a large font in a narrow column puts Try again under the words, also right to left`() {
        for (direction in LayoutDirection.entries) runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale = 2f),
                    LocalLayoutDirection provides direction,
                ) {
                    TmMaterialTheme(dark = true) {
                        Box(Modifier.width(320.dp).testTag("line")) { WorkStatusLine(ShownWork(failed), onRetry = {}, now = { now }) }
                    }
                }
            }
            val words = onNode(described(WorkStatusWords.line(failed, now))).getBoundsInRoot()
            val retry = onNodeWithText(L.commonTryAgain).getBoundsInRoot()
            val line = onNodeWithTag("line").getBoundsInRoot()
            assertTrue("$direction: Try again under the words", retry.top >= words.bottom)
            assertTrue("$direction: nothing spills sideways", words.right <= line.right && retry.right <= line.right && retry.left >= line.left)
        }
    }

    @Test
    fun `the pill holds no button and draws no failure`() = runComposeUiTest {
        var status by mutableStateOf<ShownWork>(ShownWork(WorkStatus.Syncing()))
        setContent {
            TmMaterialTheme(dark = true) { WorkStatusPill(status, asked = true, now = { now }) }
        }
        onNode(described(L.stateUpdating) and live).assertExists()
        onAllNodesWithText(L.commonTryAgain).assertCountEqualsTo(0)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).assertCountEqualsTo(0)
        status = ShownWork(failed)
        waitForIdle()
        onAllNodes(described(WorkStatusWords.line(failed, now))).assertCountEqualsTo(0)
        assertNotEquals(0, L.stateUpdating.length)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsTo(count: Int) {
    assertEquals(count, fetchSemanticsNodes().size)
}
