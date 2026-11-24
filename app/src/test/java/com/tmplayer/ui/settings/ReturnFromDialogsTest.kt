package com.tmplayer.ui.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.runtime.remember
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.onboarding.Onboarding
import com.tmplayer.ui.onboarding.TvLanguages
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * WP-15: a dialog or a picker closes and the remote is on the row that opened it (About's Buy Me a
 * Coffee), and the language list opens on the chosen language with two rows above it showing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ReturnFromDialogsTest {

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

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    @Test
    fun `About, Buy Me a Coffee opens its window and Back leaves the remote on that row`() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { AboutScreen(onBack = {}) } } }
        windowTakesKeys()
        val coffee = "Buy me a coffee"
        // The row is below the first screen: walk Down to it as the remote does.
        var presses = 0
        while (presses++ < 40) {
            val on = rule.onNode(androidx.compose.ui.test.isFocused())
            val reached = runCatching { rule.onNode(androidx.compose.ui.test.hasText(coffee, substring = true) and androidx.compose.ui.test.isFocused()).assertExists() }.isSuccess
            if (reached) break
            on.performKeyInput { pressKey(Key.DirectionDown) }
            idle()
        }
        val row = rule.onNode(androidx.compose.ui.test.hasText(coffee, substring = true) and androidx.compose.ui.test.isFocused())
        row.assertIsFocused()
        row.performKeyInput { pressKey(Key.DirectionCenter) }
        idle()
        windowTakesKeys()
        assertTrue("the support window is up", ShadowDialog.getLatestDialog() != null)
        ShadowDialog.getLatestDialog().onBackPressed()
        idle()
        windowTakesKeys()
        rule.onNode(androidx.compose.ui.test.hasText(coffee, substring = true) and androidx.compose.ui.test.isFocused()).assertIsFocused()
    }

    @Test
    fun `the language list opens on the chosen language with two rows above it showing`() {
        FormFactor.override(true)
        Motion.reduced = true
        val choices = Onboarding.languages(pseudo = false)
        val chosenAt = 15
        val chosen = choices[chosenAt]
        val focus = FocusRequester()
        rule.setContent {
            TMPlayerTheme {
                val context = LocalContext.current
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TvLanguages(
                        settings = remember { SettingsStore(context) },
                        modifier = Modifier.fillMaxSize(),
                        focusSelected = focus,
                        current = chosen.tag,
                    )
                }
            }
        }
        windowTakesKeys()
        // The selected row is the one the focus requester sits on: the dialog's own request lands there.
        rule.runOnUiThread { focus.requestFocus() }
        idle()
        rule.onNodeWithText(chosen.name!!).assertIsFocused()
        rule.onNodeWithText(choices[chosenAt - 2].name!!).assertIsDisplayed()
        rule.onNodeWithText(choices[chosenAt - 1].name!!).assertIsDisplayed()
    }
}
