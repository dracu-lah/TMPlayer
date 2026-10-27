package com.tmplayer.desktop.player

import com.tmplayer.desktop.os.WindowKeyFocus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

/** Which keys the window hands the open player (PlayerKeyRoute), and where AWT's focus is redirected (WindowKeyFocus). */
class PlayerKeyRouteTest {

    @Test
    fun `the player's own focus hears its keys itself`() {
        assertFalse(PlayerKeyRoute.takesKey(focusInside = true, typing = false, textInput = false, pageHasFocus = true))
    }

    @Test
    fun `focus lost or on the page behind, the window hands the key over`() {
        assertTrue(PlayerKeyRoute.takesKey(focusInside = false, typing = false, textInput = false, pageHasFocus = false))
        assertTrue(PlayerKeyRoute.takesKey(focusInside = false, typing = false, textInput = false, pageHasFocus = true))
        // A search field behind the picture: the viewer cannot see it, so the key is the player's.
        assertTrue(PlayerKeyRoute.takesKey(focusInside = false, typing = false, textInput = true, pageHasFocus = true))
    }

    @Test
    fun `typing keeps its keys`() {
        // The player's own note, time and subtitle search prompts, and its menus.
        assertFalse(PlayerKeyRoute.takesKey(focusInside = false, typing = true, textInput = false, pageHasFocus = false))
        // A text field in a dialog or a popup over the window.
        assertFalse(PlayerKeyRoute.takesKey(focusInside = false, typing = false, textInput = true, pageHasFocus = false))
    }

    @Test
    fun `a closed route is not taken away by an older player`() {
        val older = PlayerKeyRoute.Route({ true }, { false }, { false }, {})
        val newer = PlayerKeyRoute.Route({ true }, { false }, { false }, {})
        PlayerKeyRoute.open(older)
        PlayerKeyRoute.open(newer)
        PlayerKeyRoute.close(older)
        assertTrue(PlayerKeyRoute.isOpen)
        PlayerKeyRoute.close(newer)
        assertFalse(PlayerKeyRoute.isOpen)
    }

    @Test
    fun `only the frame and the panes around the content are redirected`() {
        // The frame stands in as a plain container: a real Window needs a display.
        val window = JPanel()
        val outer = JPanel()
        val content = JPanel()
        val inside = JPanel()
        val beside = JPanel()
        content.add(inside)
        outer.add(content)
        outer.add(beside)
        window.add(outer)
        assertTrue(WindowKeyFocus.isAroundContent(window, content, window))
        assertTrue(WindowKeyFocus.isAroundContent(outer, content, window))
        assertFalse(WindowKeyFocus.isAroundContent(content, content, window))
        assertFalse(WindowKeyFocus.isAroundContent(inside, content, window))
        // A Swing component of its own beside the content keeps its keys.
        assertFalse(WindowKeyFocus.isAroundContent(beside, content, window))
        assertFalse(WindowKeyFocus.isAroundContent(null, content, window))
    }

    @Test
    fun `Compose's content component is found in a real window by its key listener`() {
        // Needs a display, though the window is never shown.
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        javax.swing.SwingUtilities.invokeAndWait {
            val window = androidx.compose.ui.awt.ComposeWindow()
            try {
                window.setContent { }
                window.pack()
                val content = WindowKeyFocus.contentOf(window)
                org.junit.Assert.assertNotNull(content)
                assertTrue(WindowKeyFocus.isAroundContent(window, content!!, window))
                assertTrue(WindowKeyFocus.isAroundContent(window.rootPane, content, window))
                assertFalse(WindowKeyFocus.textInputActive(window))
            } finally {
                window.dispose()
            }
        }
    }
}
