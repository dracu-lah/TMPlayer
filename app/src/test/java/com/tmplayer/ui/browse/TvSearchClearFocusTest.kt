package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's search box with words in it: the remote reaches the clear control with Right
 * from the field, OK empties the field, and the remote lands back on the field.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvSearchClearFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var words = "night harbour"
    private var filtersReset = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var text by remember { mutableStateOf(words) }
                Column(Modifier.fillMaxSize().background(Tone.background).padding(top = TV_BAR_HEIGHT, end = Tv.SafeH)) {
                    TvSearchScreen(
                        state = GlobalSearchState(query = GlobalSearch.parse(text)),
                        text = text,
                        onText = { text = it; words = it },
                        onSubmit = {},
                        chats = emptyList(),
                        favourites = emptySet(),
                        recentSearches = emptyList(),
                        suggestions = emptyList(),
                        progress = { null },
                        onOpenChat = {},
                        onOpenGroup = {},
                        onFilters = { filtersReset++ },
                        onSort = {},
                        onLoadMore = {},
                        onRetry = {},
                    )
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
    }

    private fun press(key: Key) {
        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `Right from the field reaches the clear control, OK empties the field and the remote returns to it`() {
        open()
        rule.onNodeWithText("night harbour").requestFocus()
        rule.onNodeWithText("night harbour").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription("Clear search").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("", words)
        assertEquals(1, filtersReset)
        // The control left with the words; the remote is back on the (empty) field.
        rule.onAllNodesWithContentDescription("Clear search").assertCountEquals(0)
    }
}
