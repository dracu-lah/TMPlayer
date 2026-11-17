package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Bookmark
import com.tmplayer.desktop.player.BookmarkLine
import com.tmplayer.desktop.player.OverlayButton
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.i18n.L
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The review wave's fixes in the desktop's first chunk (R1): what each control says to a screen
 * reader, what the keyboard does to it and whether a keyboard-focused control can be seen.
 */
@OptIn(ExperimentalTestApi::class)
class ControlReviewR1Test {

    @Before
    fun shown() {
        FocusVisibility.visible = true
    }

    @After
    fun restore() {
        FocusVisibility.visible = true
    }

    private fun hasRole(role: Role) = SemanticsMatcher("role is $role") { it.config.getOrNull(SemanticsProperties.Role) == role }

    // ---- the player's round buttons ---------------------------------------------------------

    @Test
    fun `an overlay button is a button, named, and Enter presses it`() = runComposeUiTest {
        var pressed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(200.dp).background(Color.Black)) {
                    OverlayButton(TmIcons.Close, "Close it", { pressed++ }, modifier = Modifier.testTag("b"))
                }
            }
        }
        onNodeWithTag("b").assert(hasRole(Role.Button)).assert(hasClickAction())
        onNodeWithTag("b").requestFocus()
        onNodeWithTag("b").assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, pressed)
    }

    @Test
    fun `a keyboard-focused overlay button wears a ring, a clicked one does not`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(200.dp).background(Color.Black)) {
                    OverlayButton(TmIcons.Close, "Close it", {}, size = 80, modifier = Modifier.testTag("b"))
                }
            }
        }
        fun whitePixels(): Int {
            val image = onNodeWithTag("b").captureToImage().toAwtImage()
            var count = 0
            // The ring is a stroke on the circle's rim: the pixels on the middle row, near each end.
            val y = image.height / 2
            for (x in 0 until 6) if (brightness(image.getRGB(x, y)) > 200) count++
            for (x in image.width - 6 until image.width) if (brightness(image.getRGB(x, y)) > 200) count++
            return count
        }
        val before = whitePixels()
        onNodeWithTag("b").requestFocus()
        waitForIdle()
        val ringed = whitePixels()
        assertTrue("focus draws white on the rim ($before -> $ringed)", ringed > before)
        // A mouse press hides focus, as the window's tracker does; the ring goes with it.
        FocusVisibility.visible = false
        waitForIdle()
        assertEquals("no ring once focus is hidden", before, whitePixels())
    }

    private fun brightness(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return minOf(r, g, b)
    }

    // ---- a bookmark's line --------------------------------------------------------------------

    @Test
    fun `a bookmark line jumps as a button, and its delete is named as one`() = runComposeUiTest {
        var jumped = 0
        var deleted = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.size(400.dp, 200.dp).background(Color.Black)) {
                    BookmarkLine(
                        Bookmark(chatId = 1, messageId = 2, id = 3, positionMs = 65_000, note = "the door"),
                        onJump = { jumped++ },
                        onEdit = {},
                        onDelete = { deleted++ },
                    )
                }
            }
        }
        onNode(hasRole(Role.Button) and hasClickAction() and androidx.compose.ui.test.hasText("the door", substring = true)).performClick()
        assertEquals(1, jumped)
        onNode(hasContentDescription(L.playerBookmarkDelete)).performClick()
        assertEquals(1, deleted)
    }

    // ---- the crash question's tick box and History's ------------------------------------------

    @Test
    fun `the crash question's Always box is a checkbox the keyboard and the click both flip`() = runComposeUiTest {
        var sent: Boolean? = null
        setContent {
            TmMaterialTheme(dark = true) {
                CrashQuestion(onSend = { sent = it }, onDismiss = {})
            }
        }
        val box = hasRole(Role.Checkbox)
        onNode(box).assert(SemanticsMatcher("off") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off })
        onNode(box).performClick()
        onNode(box).assert(SemanticsMatcher("on") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On })
        onNode(box).requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        onNode(box).assert(SemanticsMatcher("off again") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off })
        assertEquals(null, sent)
    }

    @Test
    fun `History's tick box is a checkbox with a state a reader can hear`() = runComposeUiTest {
        var ticked by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(100.dp).padding(8.dp)) { TickBox(ticked, Modifier.testTag("tick")) { ticked = !ticked } }
            }
        }
        onNodeWithTag("tick").assert(hasRole(Role.Checkbox))
        onNodeWithTag("tick").assert(SemanticsMatcher("off") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off })
        onNodeWithTag("tick").performClick()
        assertTrue(ticked)
        onNodeWithTag("tick").assert(SemanticsMatcher("on") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On })
    }

    // ---- Ctrl+A on Downloads --------------------------------------------------------------------

    @Test
    fun `Ctrl+A picks everything with no selection yet, and stands down on an empty page`() = runComposeUiTest {
        var picks = 0
        var enabled by mutableStateOf(true)
        setContent {
            Box(Modifier.fillMaxSize().selectAllChord(enabled) { picks++ }) {
                Box(Modifier.size(50.dp).testTag("cell").focusable())
            }
        }
        onNodeWithTag("cell").requestFocus()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        assertEquals(1, picks)
        onNode(isFocused()).performKeyInput { pressKey(Key.A) }
        assertEquals("A alone is not the chord", 1, picks)
        enabled = false
        waitForIdle()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        assertEquals("nothing to pick", 1, picks)
    }

    // ---- the chat search's recent searches --------------------------------------------------------

    @Test
    fun `a recent search has its own remove, and picking it is not removing it`() = runComposeUiTest {
        val picked = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var cleared = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(800.dp, 200.dp)) {
                    RecentPanel(listOf("harbour", "night"), onPick = { picked += it }, onClear = { cleared++ }, onRemove = { removed += it })
                }
            }
        }
        val remove = hasTestTag("recent-remove")
        assertEquals(2, onAllNodes(remove).fetchSemanticsNodes().size)
        onAllNodes(remove)[1].assert(hasContentDescription("${L.commonRemove}: night"))
        onAllNodes(remove)[1].performClick()
        assertEquals(listOf("night"), removed)
        assertEquals(emptyList<String>(), picked)
        assertEquals(0, cleared)
        onNode(hasClickAction() and androidx.compose.ui.test.hasText("harbour")).performClick()
        assertEquals(listOf("harbour"), picked)
    }

    @Test
    fun `the dim behind a panel closes on a click and is no Tab stop`() = runComposeUiTest {
        var closed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(300.dp)) {
                    Box(Modifier.fillMaxSize().testTag("dim").scrimDismiss { closed++ })
                    Column {
                        Box(Modifier.size(40.dp).testTag("a").focusable())
                        Box(Modifier.size(40.dp).testTag("b").focusable())
                    }
                }
            }
        }
        onNodeWithTag("a").requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("b").assertIsFocused()
        // Past the last: Tab wraps to the first control, never to the dim that comes before it.
        onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("a").assertIsFocused()
        onNodeWithTag("dim").performClick()
        assertEquals(1, closed)
    }
}
