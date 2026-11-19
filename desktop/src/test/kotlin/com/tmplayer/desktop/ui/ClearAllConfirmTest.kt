package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Clear history and the other clear-alls ask first on the desktop (HX59, [rememberClearAllConfirm]):
 * the prompt says what goes, focus starts on Cancel (so Enter pressed out of habit keeps
 * everything), Esc cancels, Clear clears once, a second press asks once, an empty list asks
 * nothing, and a list emptied meanwhile closes the question and clears nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ClearAllConfirmTest {

    private val cards = mutableIntStateOf(3)
    private val cleared = mutableListOf<Int>()

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        setContent {
            TmMaterialTheme(dark = true) {
                val ask = rememberClearAllConfirm(
                    count = cards.intValue,
                    title = "Clear history?",
                    message = "All ${cards.intValue} videos leave Continue watching and Watched.",
                    detail = "The videos stay in their chats.",
                ) { cleared += cards.intValue }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Column {
                    Button(onClick = ask, modifier = Modifier.focusRequester(first)) { Text("Clear history") }
                    Button(onClick = { ask(); ask() }) { Text("Twice") }
                }
            }
        }
        waitForIdle()
    }

    private fun asking(test: androidx.compose.ui.test.ComposeUiTest) =
        test.onAllNodes(hasText("Clear history?")).fetchSemanticsNodes().size

    @Test
    fun `Clear history asks and focus starts on Cancel`() = runComposeUiTest {
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        onNodeWithText("Cancel").assertIsFocused()
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `Enter on Cancel clears nothing`() = runComposeUiTest {
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `Esc cancels and clears nothing`() = runComposeUiTest {
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `Clear clears once with the list as it is then`() = runComposeUiTest {
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        cards.intValue = 2
        waitForIdle()
        onNodeWithText("Clear").performClick()
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(listOf(2), cleared)
    }

    @Test
    fun `a second press while the question is open asks once`() = runComposeUiTest {
        show()
        onNodeWithText("Twice").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `an empty list asks nothing`() = runComposeUiTest {
        cards.intValue = 0
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `a list emptied while the question was open closes it and clears nothing`() = runComposeUiTest {
        show()
        onNodeWithText("Clear history").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        cards.intValue = 0
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), cleared)
    }
}
