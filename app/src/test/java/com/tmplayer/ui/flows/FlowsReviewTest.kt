package com.tmplayer.ui.flows

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Release
import com.tmplayer.data.UpdateState
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.auth.PaneError
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.ui.connect.ProxyScreen
import com.tmplayer.ui.downloads.PickingActions
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.update.TouchUpdateDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The flows review (R6): what a screen reader hears when a step fails, and the ways out of a
 * dialog and a selection that held the viewer in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlowsReviewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun liveRegionOf(text: String): LiveRegionMode? =
        rule.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a sign in error is read out when it appears`() {
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { PaneError("Wrong code") } }
        rule.onNodeWithText("Wrong code").assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, liveRegionOf("Wrong code"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a countdown line can opt out of being read every second`() {
        rule.setContent {
            TMPlayerTheme {
                Text("Try again in 30 s", Modifier.liveMessage(on = false))
                Text("Wrong PIN", Modifier.liveMessage())
            }
        }
        assertEquals(null, liveRegionOf("Try again in 30 s"))
        assertEquals(LiveRegionMode.Polite, liveRegionOf("Wrong PIN"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, a download in the update dialog has a Close that hides it`() {
        Motion.reduced = true
        var dismissed = 0
        val release = Release(version = "9.9.9", pageUrl = "https://example.invalid/r")
        rule.setContent {
            TMPlayerTheme {
                TouchUpdateDialog(
                    state = UpdateState.Downloading(release, 0.4f),
                    offer = null,
                    onPrimary = {},
                    onLater = {},
                    onSkip = {},
                    onDismiss = { dismissed++ },
                    onReleasePage = null,
                )
            }
        }
        rule.onNodeWithText(L.commonClose).assertIsDisplayed()
        rule.onNodeWithText(L.commonClose).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi", fontScale = 2.0f)
    fun `phone at twice the font, the selection bar keeps every action reachable`() {
        Motion.reduced = true
        var shared = 0
        var deleted = 0
        rule.setContent {
            TMPlayerTheme {
                PickingActions(
                    onShare = { shared++ },
                    onSaveToFolder = {},
                    onSaveToGallery = {},
                    onDelete = { deleted++ },
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        rule.onNodeWithText(L.commonShare).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.downloadsSaveToFolder).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.downloadsSaveToGallery).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.commonDelete).assertIsDisplayed()
        rule.onNodeWithContentDescription(L.commonDelete).performClick()
        rule.onNodeWithText(L.commonShare).performClick()
        assertEquals(1, deleted)
        assertEquals(1, shared)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, a proxy link Telegram cannot add says why and the button comes back`() {
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { ProxyScreen(onClose = {}) } }
        rule.onNode(hasSetTextAction()).performTextInput("https://t.me/socks?server=1.2.3.4&port=1080")
        rule.onNodeWithText(L.proxyAdd).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(L.errorsNotConnected)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(L.errorsNotConnected).assertIsDisplayed()
        // Busy ended: the button says Add proxy again, not Testing.
        rule.onNodeWithText(L.proxyAdd).assertIsDisplayed()
    }
}
