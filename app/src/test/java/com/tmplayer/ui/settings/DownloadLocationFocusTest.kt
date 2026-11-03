package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsFocused
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Storage location row on a television (HX57), by remote alone: the row takes the remote, OK
 * opens the list with the volume in use chosen, and "Other folder" is not in the television's list
 * (its system picker is a stub). The list's own focus and Back live in a dialog window that
 * Robolectric does not give focus to, so they are a device check (see PLAN.md, HX57 Phase 3).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class DownloadLocationFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun `the row takes the remote and OK opens the list without Other folder`() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) { Column(androidx.compose.ui.Modifier.fillMaxSize()) { DownloadLocationRow(onMoved = {}) } }
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

        rule.onNodeWithText("Storage location").requestFocus()
        rule.onNodeWithText("Storage location").assertIsFocused()

        rule.onNodeWithText("Storage location").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()

        // The list is up: its title, and the volume in use as a line beside the row's own mention
        // of it. "Other folder" is not offered to a television.
        rule.onAllNodesWithText("Storage location").assertCountEquals(2)
        rule.onAllNodesWithText("Internal storage").assertCountEquals(2)
        rule.onAllNodesWithText("Other folder").assertCountEquals(0)

        // The row kept the remote under the list, so the press that closes it lands back on the row.
        rule.onNode(hasText("Storage location") and hasClickAction()).assertIsFocused()
    }
}
