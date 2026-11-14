package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's empty states by remote alone (U18): the button an empty page offers has the
 * focus when the page arrives, the centre key presses it, and a page with nothing to offer (the
 * kids lock's) draws no button for the focus to land on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class EmptyStateFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun show(state: UiState<Unit>, onAction: (StateAction) -> Unit = {}, takeFocus: Boolean = true) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme { StateScaffold(state, onAction = onAction, takeFocus = takeFocus) {} }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the focus lands on Search all chats and the centre key presses it`() {
        val got = mutableListOf<StateAction>()
        show(UiState.Empty(L.browseNoMatch("zebra"), StateAction.SearchAllChats), { got += it })
        rule.onNodeWithText(StateAction.SearchAllChats.label).assertIsFocused()
        rule.onNodeWithText(StateAction.SearchAllChats.label).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(StateAction.SearchAllChats), got)
    }

    @Test
    fun `the focus lands on Refresh for No chats yet`() {
        show(UiState.Empty(L.browseNoChats, StateAction.Refresh))
        rule.onNodeWithText(StateAction.Refresh.label).assertIsFocused()
    }

    @Test
    fun `the focus lands on Keep looking when the walk back stopped early`() {
        show(UiState.Empty("More to search", StateAction.KeepLooking))
        rule.onNodeWithText(StateAction.KeepLooking.label).assertIsFocused()
    }

    @Test
    fun `the kids lock empty page draws no button`() {
        show(UiState.Empty(L.lockKidsEmpty))
        rule.onNodeWithText(L.lockKidsEmpty).assertExists()
        for (action in StateAction.entries) rule.onNodeWithText(action.label).assertDoesNotExist()
    }

    @Test
    fun `typing into a search does not take the focus from the field`() {
        show(UiState.Empty(L.browseNoMatch("zeb"), StateAction.SearchAllChats), takeFocus = false)
        rule.onNodeWithText(StateAction.SearchAllChats.label).assertExists()
        rule.onNodeWithText(StateAction.SearchAllChats.label).assertIsNotFocused()
    }
}
