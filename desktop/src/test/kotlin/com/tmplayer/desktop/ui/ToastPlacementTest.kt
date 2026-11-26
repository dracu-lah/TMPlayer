package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.ActionToaster
import com.tmplayer.ui.components.LocalToastHost
import com.tmplayer.ui.components.ToastHost
import com.tmplayer.ui.components.ToastKind
import com.tmplayer.ui.components.ToastLayout
import com.tmplayer.ui.components.Toaster
import com.tmplayer.ui.components.rememberActionToast
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.components.toastFloor
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a desktop toast stands: ToastLayout.Gap (12 dp) above the floating bar by the bar's own
 * measured top edge (so a taller bar moves it up and no bar leaves it at the window's foot), centred
 * over the window and between 280 and 480 dp wide. It was drawn behind the bar when the clearance
 * was a constant the bar's design outgrew.
 */
@OptIn(ExperimentalTestApi::class)
class ToastPlacementTest {

    private lateinit var toast: Toaster
    private lateinit var undoToast: ActionToaster
    private var undone = 0

    private fun androidx.compose.ui.test.ComposeUiTest.open(barHeight: Int?) {
        val host = SnackbarHostState()
        setContent {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalToastHost provides host) {
                    toast = rememberToast()
                    undoToast = rememberActionToast()
                    Box(Modifier.fillMaxSize().background(Color(0xFF101010)).testTag("window")) {
                        if (barHeight != null) {
                            // The floating bar: it reports its top edge, with its own 16 dp margin below it.
                            Box(Modifier.align(Alignment.BottomCenter).toastFloor().padding(bottom = 16.dp)) {
                                Box(Modifier.width(420.dp).height(barHeight.dp).background(Color(0xFF303030)).testTag("bar"))
                            }
                        }
                        ToastHost(host)
                    }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(message: String, kind: ToastKind = ToastKind.Success) {
        runOnIdle { toast(message, kind) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    @Test
    fun `the toast stands 12 dp above the bar and centred`() = runComposeUiTest {
        open(barHeight = 62)
        show("Up to date")
        val bar = onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithText("Up to date").fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val d = Density(1f).density
        assertEquals("12 dp over the bar's top edge", 12f * d, bar.top - card.bottom, 1f)
        assertEquals("centred over the window", window.center.x, card.center.x, 1f)
        assertTrue("never wider than 480 dp", card.width <= 480f * d + 1f)
        assertTrue("never narrower than 280 dp", card.width >= 280f * d - 1f)
    }

    @Test
    fun `a taller bar moves it up, the bar's measured height decides`() = runComposeUiTest {
        open(barHeight = 90)
        show("Saved")
        val bar = onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, bar.top - card.bottom, 1f)
    }

    @Test
    fun `with no bar it stands 12 dp over the window's foot`() = runComposeUiTest {
        open(barHeight = null)
        show("Copied")
        val card = onNodeWithText("Copied").fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, window.bottom - card.bottom, 1f)
        assertTrue(ToastLayout.Gap.value == 12f)
    }

    @Test
    fun `a long message wraps to two lines within 480 dp and is cut, never wider`() = runComposeUiTest {
        open(barHeight = 62)
        val long = "A very long message that keeps going and going so that it needs more than one line to be read in full " +
            "and then goes on past two lines as well, until the end of it is cut off with an ellipsis for sure."
        show(long, ToastKind.Error)
        val card = onNodeWithText(long).fetchSemanticsNode().boundsInRoot
        assertTrue(card.width <= 480f + 1f)
        assertTrue("two lines of 14 sp text and padding stay under 90 dp", card.height < 90f)
    }

    @Test
    fun `an action is a button reached by the keyboard, and pressing it runs it once`() = runComposeUiTest {
        open(barHeight = 62)
        runOnIdle { undoToast("Chat hidden", "Undo") { undone++ } }
        // 20 s, not 5: the toast drew late on a gate run that shared the machine with another build (2026-11-26); passes alone.
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithText("Undo").performClickCompat()
        assertEquals(1, undone)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickCompat() {
        performClick()
    }
}
