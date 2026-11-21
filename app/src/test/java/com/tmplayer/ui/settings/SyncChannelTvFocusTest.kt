package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.ChannelOption
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SyncChannelChoice
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sync channel page (HX87) on a television, by remote alone: it opens with the one button that
 * does the job focused ("Create it for me", no typing anywhere), Down walks the buttons, OK on
 * Create asks the question in place with Cancel focused, and Cancel returns to Create. The step
 * list is one line on a television.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SyncChannelTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun remoteWindow() {
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

    private fun down(text: String) {
        rule.onNodeWithText(text).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
    }

    @Test
    fun `Create it for me has the first focus, Down walks the buttons, OK asks with Cancel focused`() {
        FormFactor.override(true)
        val asking = mutableStateOf(false)
        val choice = SyncChannelChoice(-1001L, "TMPlayer sync", 42L)
        remoteWindow()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                SyncChannelContent(
                    SyncChannelUiState(choice = choice, asking = asking.value),
                    SyncChannelActions(onAskCreate = { asking.value = true }, onCancelCreate = { asking.value = false }),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(L.syncchannelButtonCreate).assertIsFocused()
        // The short list: one line, not three steps, and nothing to type.
        rule.onNodeWithText(L.syncchannelGuideTv).assertExists()
        rule.onNodeWithText(L.syncchannelGuideStep1).assertDoesNotExist()

        down(L.syncchannelButtonCreate)
        rule.onNodeWithText(L.syncchannelButtonChoose).assertIsFocused()
        down(L.syncchannelButtonChoose)
        rule.onNodeWithText(L.syncchannelButtonUseSaved).assertIsFocused()

        // Up walks back to Create, and OK there asks.
        rule.onNodeWithText(L.syncchannelButtonUseSaved).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithText(L.syncchannelButtonChoose).assertIsFocused()
        rule.onNodeWithText(L.syncchannelButtonChoose).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithText(L.syncchannelButtonCreate).assertIsFocused()
        rule.onNodeWithText(L.syncchannelButtonCreate).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        // Not before, at the first press: the question, and the safe answer under the remote.
        assertEquals(true, asking.value)
        rule.onNodeWithText(L.syncchannelCreateConfirmTitle).assertExists()
        rule.onNodeWithText(L.commonCancel).assertIsFocused()

        rule.onNodeWithText(L.commonCancel).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(false, asking.value)
        rule.onNodeWithText(L.syncchannelButtonCreate).assertIsFocused()
    }

    @Test
    fun `a list of channels takes the focus on its first entry`() {
        FormFactor.override(true)
        remoteWindow()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                SyncChannelContent(
                    SyncChannelUiState(options = listOf(ChannelOption(1, "TMPlayer sync"), ChannelOption(2, "Notes"))),
                    SyncChannelActions(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("TMPlayer sync").assertIsFocused()
        down("TMPlayer sync")
        rule.onNodeWithText("Notes").assertIsFocused()
    }
}
