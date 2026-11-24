package com.tmplayer.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quitting with a download running asks first (WP-46): Keep running is focused and returns to the
 * app, Quit and pause pauses and quits, "Do not ask again" is remembered and skips the question
 * from then on (still pausing), and nothing running quits at once.
 */
@OptIn(ExperimentalTestApi::class)
class QuitQuestionTest {

    private var running = 2
    private var skip = false
    private var remembered = 0
    private var paused = 0
    private var quits = 0

    private fun guard() = QuitGuard(
        running = { running },
        skip = { skip },
        rememberSkip = { remembered++; skip = true },
        pauseAll = { paused++ },
    )

    @Test
    fun `nothing running quits at once without asking`() {
        running = 0
        val g = guard()
        g.request { quits++ }
        assertEquals(1, quits)
        assertNull(g.pending)
        assertEquals(0, paused)
    }

    @Test
    fun `a running download holds the quit until answered`() {
        val g = guard()
        g.request { quits++ }
        assertNotNull(g.pending)
        assertEquals(2, g.asked)
        assertEquals(0, quits)
    }

    @Test
    fun `a second request while the question is up changes nothing`() {
        val g = guard()
        g.request { quits++ }
        g.request { quits += 10 }
        g.quit(neverAsk = false)
        assertEquals(1, quits)
        assertEquals(1, paused)
    }

    @Test
    fun `Keep running drops the quit and pauses nothing`() {
        val g = guard()
        g.request { quits++ }
        g.keep()
        assertNull(g.pending)
        assertEquals(0, quits)
        assertEquals(0, paused)
        assertFalse(skip)
    }

    @Test
    fun `Do not ask again is remembered and later quits skip the question but still pause`() {
        val g = guard()
        g.request { quits++ }
        g.quit(neverAsk = true)
        assertEquals(1, remembered)
        assertEquals(1, quits)
        assertEquals(1, paused)
        g.request { quits++ }
        assertNull(g.pending)
        assertEquals(2, quits)
        assertEquals(2, paused)
    }

    @Test
    fun `the question starts on Keep running and Enter returns to the app`() = runComposeUiTest {
        val g = guard()
        g.request { quits++ }
        setContent { TmMaterialTheme(dark = true) { QuitQuestionHost(g) } }
        waitForIdle()
        onNodeWithText("2 downloads are running").assertExists()
        onNodeWithText("Keep running").assertIsFocused()
        onNodeWithText("Keep running").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, onAllNodesWithTextCount("Keep running"))
        assertEquals(0, quits)
        assertEquals(0, paused)
    }

    @Test
    fun `Quit and pause pauses then quits, with the tick remembered`() = runComposeUiTest {
        val g = guard()
        g.request { quits++ }
        setContent { TmMaterialTheme(dark = true) { QuitQuestionHost(g) } }
        waitForIdle()
        onNodeWithText("Do not ask again").performClick()
        onNodeWithText("Quit and pause").performClick()
        waitForIdle()
        assertEquals(1, quits)
        assertEquals(1, paused)
        assertTrue(skip)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
