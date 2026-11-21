package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.TvBrowseHintCard
import com.tmplayer.ui.theme.TmMaterialTheme
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
 * D-1: the tour offer sits in a row of its own under the page, so the last row's title is never
 * under it, and it waits for the television's browse hint to go so the two never show together.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TourOfferFrameTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var offer by mutableStateOf(true)
    private var hintUp by mutableStateOf(true)
    private val declines = mutableListOf<Unit>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                TourOfferFrame(
                    offer = offer,
                    hintUp = hintUp,
                    card = { m -> com.tmplayer.ui.onboarding.TipsCard(onShow = {}, onDismiss = { declines += Unit }, modifier = m) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // A page whose last row title hugs the bottom edge, as Home's "Recently added".
                    Box(Modifier.fillMaxSize()) {
                        Text("Recently added", Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp))
                        // The browse hint, as MainActivity shows it while it is up.
                        if (hintUp) TvBrowseHintCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
                    }
                }
            }
        }
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }

    private fun count(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    private fun cardUp() = count("Tips") > 0
    private fun hintUpNow() = count("OK opens a chat or plays a video.", substring = true) > 0

    @Test
    fun `the card waits for the hint and they never show together`() {
        show()
        assertTrue(hintUpNow())
        assertTrue("the card must hold back while the hint is up", !cardUp())

        rule.runOnUiThread { hintUp = false }
        rule.waitForIdle()
        assertTrue(!hintUpNow())
        assertTrue("the card comes once the hint is gone", cardUp())
    }

    @Test
    fun `the card does not cover the last row title`() {
        hintUp = false
        show()
        val title = rule.onNodeWithText("Recently added").getUnclippedBoundsInRoot()
        val card = rule.onNodeWithText("Tips").getUnclippedBoundsInRoot()
        assertTrue("title bottom ${title.bottom} must be above the card top ${card.top}", title.bottom <= card.top)
    }

    @Test
    fun `no offer leaves the page its whole height`() {
        hintUp = false
        offer = false
        show()
        assertTrue(!cardUp())
        val title = rule.onNodeWithText("Recently added").getUnclippedBoundsInRoot()
        assertTrue("title bottom ${title.bottom}", title.bottom > 500.dp)
    }

    @Test
    fun `the remote lands on Show me and Back is Not now`() {
        hintUp = false
        show()
        rule.onNode(hasText("Show me") and hasClickAction()).assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, declines.size)
    }
}

/** The same on a phone: in the page's flow, under the last row, never over it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-xhdpi")
class TourOfferFramePhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    fun `the card sits under the last row on a phone`() {
        rule.setContent {
            TmMaterialTheme(dark = true) {
                TourOfferFrame(
                    offer = true,
                    hintUp = false,
                    card = { m -> com.tmplayer.ui.onboarding.TipsCard(onShow = {}, onDismiss = {}, modifier = m) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Text("Recently added", Modifier.align(Alignment.BottomStart).fillMaxWidth().height(24.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
        val title = rule.onNodeWithText("Recently added").getUnclippedBoundsInRoot()
        val card = rule.onNodeWithText("Tips").getUnclippedBoundsInRoot()
        assertTrue("title bottom ${title.bottom} must be above the card top ${card.top}", title.bottom <= card.top)
        rule.onNode(hasText("Not now") and hasClickAction()).assertExists()
    }
}
