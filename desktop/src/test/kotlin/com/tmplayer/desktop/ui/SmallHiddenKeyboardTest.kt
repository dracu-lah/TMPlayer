package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.browse.VideoMenuPopover
import com.tmplayer.ui.browse.VideoMenuState
import com.tmplayer.ui.components.rememberPopoverAnchor
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R1 on the desktop: the keyboard reaches the "Hide small clips" line of the Sort menu and the
 * end row's Show them button, and Enter presses each.
 */
@OptIn(ExperimentalTestApi::class)
class SmallHiddenKeyboardTest {

    @Before
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun `Tab reaches Show them in the footer and Enter presses it`() = runComposeUiTest {
        var shown = 0
        setContent {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    Column {
                        Text("Last video")
                        SmallHiddenFooter(3, exact = true) { shown++ }
                    }
                }
            }
        }
        onNodeWithText("3 small videos hidden").assertExists()
        onNodeWithText("Show them").requestFocus()
        onNodeWithText("Show them").assertIsFocused()
        onNodeWithText("Show them").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, shown)
    }

    @Test
    fun `the footer promises no number before the whole chat is read`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { ProvideStrings { SmallHiddenFooter(3, exact = false) {} } } }
        onNodeWithText("Some small videos are hidden").assertExists()
        onNodeWithText("3 small videos hidden").assertDoesNotExist()
    }

    @Test
    fun `the Sort menu's switch takes the keyboard and Enter flips it`() = runComposeUiTest {
        var on = false
        setContent {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    val anchor = rememberPopoverAnchor()
                    VideoMenuPopover(
                        open = true,
                        anchor = anchor,
                        state = VideoMenuState(
                            VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {},
                            hideSmall = on, onHideSmall = { on = it },
                        ),
                        onDismiss = {},
                    )
                }
            }
        }
        val label = "Hide small clips (under 50 MB)"
        onNodeWithText("Small videos").assertExists()
        onNodeWithText(label).requestFocus()
        onNodeWithText(label).assertIsFocused()
        onNodeWithText(label).performKeyInput { pressKey(Key.Enter) }
        assertEquals(true, on)
    }
}
