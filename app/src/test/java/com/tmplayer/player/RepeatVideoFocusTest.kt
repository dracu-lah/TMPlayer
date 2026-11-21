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
import com.tmplayer.i18n.L
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
 * Repeat this video in the television's More menu (EG-F52), by remote alone: the remote reaches the
 * line from the A-B repeat above it, OK switches it (and the line keeps focus, as the switches of
 * the panel do), and Down goes on to the line below. The panel's own lines are these same
 * [ChoiceLine]s, in the order [PlayerMenu.tvEntries] gives; the list is drawn in [PlayerMoreRenderTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class RepeatVideoFocusTest {

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
    fun `the remote reaches Repeat this video from the A-B repeat and OK switches it`() {
        FormFactor.override(true)
        val entries = PlayerMenu.tvEntries(pictureInPicture = false, repeatVideo = true)
        val at = entries.indexOf(PlayerMenuEntry.RepeatVideo)
        assertEquals(PlayerMenuEntry.AbLoop, entries[at - 1])
        var switched = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    ChoiceList {
                        ChoiceLine(title = PlayerMenu.loopLabel(null), onClick = {})
                        ChoiceLine(title = L.playerRepeatVideo, detail = L.commonOff, onClick = { switched++ })
                        ChoiceLine(title = L.playerRemoteKeys, onClick = {})
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

        rule.onNodeWithText(PlayerMenu.loopLabel(null)).requestFocus()
        rule.onNodeWithText(PlayerMenu.loopLabel(null)).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerRepeatVideo).assertIsFocused()

        rule.onNodeWithText(L.playerRepeatVideo).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, switched)
        rule.onNodeWithText(L.playerRepeatVideo).assertIsFocused()

        rule.onNodeWithText(L.playerRepeatVideo).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerRemoteKeys).assertIsFocused()
    }
}
