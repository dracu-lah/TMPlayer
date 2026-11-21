package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A group of many topics on the television (U14): the "Find a topic" field sits after All topics
 * and the remote reaches it, and the tabs past it, with Right; what is typed narrows the tabs and
 * keeps the chosen one; a name nothing answers says so.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TopicTabsFindTest {

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

    private val topics = (1..25).map { ForumTopicSummary(id = it, name = if (it % 2 == 0) "Season $it" else "Lesson $it") }

    private var picked = -1

    private fun open(count: Int = topics.size) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var selected by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TopicTabs(TopicBar(topics.take(count), selected), onSelect = { selected = it; picked = it })
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

    @Test
    fun `the remote walks from All topics to the field and on to the first tab`() {
        open()
        rule.onNodeWithText("All topics").requestFocus()
        rule.onNodeWithText("All topics").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithContentDescription("Find a topic").assertIsFocused()
        rule.onNodeWithContentDescription("Find a topic").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Lesson 1").assertIsFocused()
        rule.onNodeWithText("Lesson 1").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, picked)
    }

    @Test
    fun `OK opens the field, typing narrows the tabs, and a name nothing answers says so`() {
        open()
        rule.onNodeWithContentDescription("Find a topic").requestFocus()
        rule.onNodeWithContentDescription("Find a topic").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("season 4")
        rule.waitForIdle()
        rule.onNodeWithText("Season 4", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Lesson 1").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("zz")
        rule.waitForIdle()
        rule.onNodeWithText("No topic is called “season 4zz”").assertExists()
        // An arrow while typing leaves the field for the tab beside it, with the words kept.
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithText("All topics").assertIsFocused()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `twenty topics or fewer have no field`() {
        open(count = 20)
        rule.onNodeWithContentDescription("Find a topic").assertDoesNotExist()
    }
}
