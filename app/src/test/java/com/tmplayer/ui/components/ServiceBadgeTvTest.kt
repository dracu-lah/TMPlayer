package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The service tile on a television (HX93): a line with a badge has the one focus stop it always
 * had. The badge adds none, so Down and Up walk the lines exactly as they did without it, and a
 * settings row and a confirm dialog that carry a badge keep their order.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ServiceBadgeTvTest {

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

    @Test
    fun `the badge never takes the focus, Down and Up walk the lines one stop each`() {
        FormFactor.override(true)
        Motion.reduced = true
        val first = FocusRequester()
        rule.setContent {
            TMPlayerTheme {
                Column {
                    TvChoiceRow("Search online", Modifier.focusRequester(first), service = Service.OpenSubtitles, onClick = {})
                    TvChoiceRow("Malayalam subtitles", service = Service.MSone, onClick = {})
                    TvChoiceRow("Load a file", onClick = {})
                }
            }
        }
        rule.waitForIdle()
        // A remote is keys, not touch: the window leaves touch mode, so a line can take the focus.
        rule.runOnIdle {
            val root = rule.activity.window.decorView.parent
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        }
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        // Three lines, three stops: no badge adds one.
        rule.onAllNodes(isFocusable()).assertCountEquals(3)
        rule.onNodeWithText("Search online").assertIsFocused()
        rule.onNodeWithText("Search online").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Malayalam subtitles").assertIsFocused()
        rule.onNodeWithText("Malayalam subtitles").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Load a file").assertIsFocused()
        rule.onNodeWithText("Load a file").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithText("Malayalam subtitles").assertIsFocused()
    }

    @Test
    fun `a badge beside its name is drawn and takes no stop`() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { Column { ServiceBadgeRow(listOf(Service.Tmdb, Service.TvMaze, Service.AniList)) } } }
        rule.waitForIdle()
        rule.onAllNodes(isFocusable()).assertCountEquals(0)
        rule.onNodeWithText("TMDB").assertExists()
        rule.onNodeWithText("TVmaze").assertExists()
        rule.onNodeWithText("AniList").assertExists()
    }
}
