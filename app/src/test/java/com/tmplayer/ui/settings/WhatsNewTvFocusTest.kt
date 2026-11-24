package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.FormFactor
import com.tmplayer.data.WhatsNew
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
 * WP-71: the "What's new" sheet on a television, by remote alone. Close holds the remote from the
 * start, Left reaches "Full changelog" and Right comes back, OK on each does what its label says
 * and nothing else, and the lines are the television's (the remote's keys, not the phone's gear).
 * The window around the panel is the platform's dialog (Back closes it, as for every dialog), so
 * the panel itself is what is driven here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class WhatsNewTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private var closed = 0
    private var changelog = 0

    private fun showTv(form: DeviceForm = DeviceForm.Tv, touch: Boolean = false) {
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    WhatsNewPanel(
                        sections = WhatsNew.sections(form),
                        touch = touch,
                        onClose = { closed++ },
                        onChangelog = { changelog++ },
                        modifier = Modifier.width(720.dp),
                    )
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

    private val closeButton get() = rule.onNode(hasText(L.commonClose) and hasClickAction())
    private val changelogButton get() = rule.onNode(hasText(L.whatsnewFullChangelog) and hasClickAction())

    @Test
    fun `Close holds the remote, Left and Right walk the two buttons and OK does what each says`() {
        FormFactor.override(true)
        showTv()

        closeButton.assertIsFocused()
        changelogButton.assertIsNotFocused()

        closeButton.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        changelogButton.assertIsFocused()

        changelogButton.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        closeButton.assertIsFocused()

        // OK on Close closes and nothing else; OK on the changelog opens it and nothing else.
        closeButton.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("OK on Close closes", 1, closed)
        assertEquals("OK on Close opens nothing else", 0, changelog)
        closeButton.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        changelogButton.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("OK on Full changelog opens it", 1, changelog)
        assertEquals("OK on Full changelog closes nothing", 1, closed)
    }

    @Test
    fun `the television reads its own lines under headings and not the phone's`() {
        FormFactor.override(true)
        showTv()

        rule.onNodeWithText(L.whatsnewSectionPlayer).assertExists()
        rule.onNodeWithText(L.whatsnewSectionAccount).assertExists()
        rule.onNodeWithText(L.whatsnewLineTvSeek).assertExists()
        rule.onNodeWithText(L.whatsnewLineAccountTv).assertExists()
        rule.onAllNodesWithText(L.whatsnewLineAccount).assertCountEquals(0)
        rule.onAllNodesWithText(L.whatsnewLineSettingsTablet).assertCountEquals(0)
        rule.onAllNodesWithText(L.whatsnewLineDesktopKeys).assertCountEquals(0)
    }

    @Test
    fun `touch takes no focus and shows the phone's lines`() {
        showTv(form = DeviceForm.Phone, touch = true)

        // Nothing asks for the remote on touch (the test window is not in touch mode, so the
        // platform's own first focus may land on the first button, but never the asked-for Close).
        closeButton.assertIsNotFocused()
        rule.onNodeWithText(L.whatsnewLineAccount).assertExists()
        rule.onAllNodesWithText(L.whatsnewLineTvSeek).assertCountEquals(0)
    }
}
