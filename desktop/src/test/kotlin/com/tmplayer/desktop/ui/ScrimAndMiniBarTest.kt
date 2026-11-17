package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.MiniPlayerBar
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.ui.components.swallowClicks
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared scrim helper is no Tab stop, and the mini player's controls show where the keyboard is. */
@OptIn(ExperimentalTestApi::class)
class ScrimAndMiniBarTest {

    @Test
    fun `Tab goes past a scrim to the control on it and Enter never closes the dim`() = runComposeUiTest {
        var closed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(300.dp).background(Color.Black).testTag("dim").scrimDismiss { closed++ }) {
                    Box(Modifier.size(100.dp).swallowClicks().testTag("card")) {
                        BasicText("Go", Modifier.testTag("go").clickable {})
                    }
                }
            }
        }
        onNodeWithTag("dim").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("go").assertIsFocused()
        onNodeWithTag("go").performKeyInput { pressKey(Key.Tab) }
        // Nothing else to land on: the dim never takes the focus, so it is not what Enter would close.
        assertEquals(0, closed)
    }

    @Test
    fun `Tab walks the mini player's title, play and close, each taking a focus mark`() = runComposeUiTest {
        var expanded = 0
        setContent {
            TmMaterialTheme(dark = true) {
                MiniPlayerBar(
                    title = "Clip",
                    playing = false,
                    progress = 0.2f,
                    onExpand = { expanded++ },
                    onTogglePlay = {},
                    onDismiss = {},
                )
            }
        }
        val targets = onAllNodes(hasClickAction())
        assertEquals("title, play and close are three click targets", 3, targets.fetchSemanticsNodes().size)
        targets[0].requestFocus()
        targets[0].assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        targets[1].assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        targets[2].assertIsFocused()
        assertEquals(0, expanded)
    }
}
