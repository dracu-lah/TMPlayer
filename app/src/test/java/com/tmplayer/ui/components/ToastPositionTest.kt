package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where a toast stands on the phone (upright and on its side) and the television (960 x 540 and
 * 1920 x 1080): 12 dp over the floating bar by its measured top edge, centred, a phone's width the
 * window less 16 dp at each side up to 560 dp, a television's text 16 sp or more and inside the
 * 5 per cent margin. The "Up to date" confirmation is the repro: it stood under the bar.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToastPositionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(tv: Boolean) {
        FormFactor.override(tv)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101010)).testTag("window")) {
                    // The floating bar: it reports its top edge, with its own 16 dp margin below it.
                    Box(Modifier.align(Alignment.BottomCenter).toastFloor().padding(bottom = 16.dp)) {
                        Box(Modifier.width(360.dp).height(62.dp).background(Color(0xFF303030)).testTag("bar"))
                    }
                    TopMessageHost()
                }
            }
        }
        rule.waitForIdle()
    }

    private fun say(message: String, kind: ToastKind = ToastKind.Success) {
        rule.runOnUiThread { showMessage(rule.activity, message, kind) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun dp(px: Float) = px / rule.activity.resources.displayMetrics.density

    private fun checkAboveBarAndCentred(message: String) {
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        val window = rule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertEquals("12 dp over the bar", 12f, dp(bar.top - card.bottom), 1f)
        assertEquals("centred", dp(window.center.x), dp(card.center.x), 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `phone upright, the card fills the width less 16 dp each side, over the bar`() {
        open(tv = false)
        say("Up to date")
        checkAboveBarAndCentred("Up to date")
        val card = rule.onNodeWithText("Up to date").fetchSemanticsNode().boundsInRoot
        assertEquals(379f, dp(card.width), 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `phone on its side, the card stops at 560 dp, centred, over the bar`() {
        open(tv = false)
        say("Up to date")
        checkAboveBarAndCentred("Up to date")
        val card = rule.onNodeWithText("Up to date").fetchSemanticsNode().boundsInRoot
        assertEquals(560f, dp(card.width), 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `every kind is drawn, and an error message too long for two lines is cut`() {
        open(tv = false)
        for (kind in ToastKind.entries) {
            say("Message for ${kind.name}", kind)
            rule.onNodeWithText("Message for ${kind.name}").assertExists()
        }
        val long = "word ".repeat(300).trim()
        say(long, ToastKind.Error)
        val card = rule.onNodeWithText(long).fetchSemanticsNode().boundsInRoot
        assertTrue("two lines and padding", dp(card.height) < 90f)
    }

    private fun tvChecks(heightDp: Float) {
        open(tv = true)
        say("Up to date")
        checkAboveBarAndCentred("Up to date")
        val node = rule.onNodeWithText("Up to date").fetchSemanticsNode()
        val window = rule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val card = node.boundsInRoot
        assertTrue("inside the 5 per cent margin", dp(window.bottom - card.bottom) >= heightDp * 0.05f - 1f)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val size = results.firstOrNull()?.layoutInput?.style?.fontSize?.value
        assertTrue("the words are at least 16 sp on a television, got $size", size != null && size >= 16f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `television 960 by 540, 16 sp or more, over the bar and inside the margin`() = tvChecks(540f)

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-xhdpi")
    fun `television 1920 by 1080, 16 sp or more, over the bar and inside the margin`() = tvChecks(1080f)
}
