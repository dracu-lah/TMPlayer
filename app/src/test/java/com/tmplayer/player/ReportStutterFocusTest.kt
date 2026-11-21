package com.tmplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
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
 * The More menu's new lines on a television, by remote alone: the arrow keys reach the line from
 * its neighbour and OK chooses it, with a long translation under it (it wraps, it does not clip the
 * focus). The panel's own lines are these same [ChoiceLine]s; its list and first focus are drawn
 * in [PlayerMoreRenderTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ReportStutterFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun `the remote reaches Report stutter from the line above and OK chooses it`() {
        FormFactor.override(true)
        var chosen = 0
        val long = "Marks this moment for Send logs. You can also hold Info on the remote, and a translation can be a good deal longer than that"
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    ChoiceList {
                        ChoiceLine(title = "Open in another app", onClick = {})
                        ChoiceLine(title = "Report stutter", detail = long, onClick = { chosen++ })
                        ChoiceLine(title = "Remote keys", onClick = {})
                    }
                }
            }
        }
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

        rule.onNodeWithText("Open in another app").requestFocus()
        rule.onNodeWithText("Open in another app").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("Report stutter").assertIsFocused()

        rule.onNodeWithText("Report stutter").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, chosen)

        rule.onNodeWithText("Report stutter").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("Remote keys").assertIsFocused()
    }
}
