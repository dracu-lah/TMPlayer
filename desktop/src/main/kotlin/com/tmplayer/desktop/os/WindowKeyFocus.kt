package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * Keeps the keyboard on Compose's own component inside the window.
 *
 * Compose hears keys through a listener on one component deep inside the frame (its content
 * component, under the root pane and Compose's panel). AWT sends a key to whichever component
 * holds its focus, and a window system that hands the window back after fullscreen, a restore
 * from the taskbar or Alt+Tab can leave that focus on the frame itself or one of the panes around
 * the content: Windows does it most, after the caption comes off the frame (NativeFullscreen), and
 * `Window.requestFocus()` asks for exactly that. Keys then reached no listener at all, the
 * player's included, until a click put the focus back on the content.
 *
 * [install] does two things for the window: when it gains focus with AWT's focus on the frame or
 * a pane around the content, the content takes it; and a key that still lands there meanwhile is
 * handed to the content's listeners rather than lost. A component of its own inside the window
 * (none today, a SwingPanel some day) keeps its keys: only the frame and the content's own
 * ancestors are redirected. [focus] is what to call instead of `window.requestFocus()`.
 */
object WindowKeyFocus {

    private const val TAG = "WindowKeyFocus"

    /** Compose's key listeners are its scene mediator's, by class name (the classes are internal). */
    private const val COMPOSE_LISTENER = "androidx.compose.ui.scene.ComposeSceneMediator"

    /**
     * Compose's content component in [window]: the largest component carrying Compose's key
     * listener (a popup or dialog layer drawn as a component of its own is smaller). Null before
     * the window is built.
     */
    fun contentOf(window: Window): Component? {
        var best: Component? = null
        var bestArea = -1L
        fun walk(c: Component) {
            if (c.keyListeners.any { it.javaClass.name.startsWith(COMPOSE_LISTENER) }) {
                val area = c.width.toLong() * c.height.toLong()
                if (area > bestArea) {
                    best = c
                    bestArea = area
                }
            }
            if (c is Container) c.components.forEach(::walk)
        }
        runCatching { walk(window) }.onFailure { Logger.w(TAG, "content lookup failed: ${it.message}") }
        return best
    }

    /** Whether Compose has a text field taking typing in [window] (an input session is open). */
    fun textInputActive(window: Window): Boolean =
        runCatching { contentOf(window)?.inputMethodRequests != null }.getOrDefault(false)

    /** The window to the keyboard, onto Compose's content rather than the frame. */
    fun focus(window: Window) {
        val content = contentOf(window)
        if (content == null || !content.isShowing) {
            window.requestFocus()
            return
        }
        content.requestFocus()
    }

    /**
     * Whether a key or a focus on [target] belongs to Compose's [content] instead: [target] is
     * the window or one of the containers that hold the content, none of which listens for keys.
     */
    internal fun isAroundContent(target: Component?, content: Component, window: Component): Boolean {
        if (target == null || target === content) return false
        if (target === window) return true
        return target is Container && SwingUtilities.isDescendingFrom(content, target)
    }

    /** Installs both for [window]; the returned function takes them away again. */
    fun install(window: Window): () -> Unit {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val onFocus = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                // After AWT has settled who holds the focus in the window.
                EventQueue.invokeLater { runCatching { takeBack(window, focusManager) } }
            }
        }
        val dispatcher = KeyEventDispatcher { e -> runCatching { redirect(e, window) }.getOrDefault(false) }
        window.addWindowFocusListener(onFocus)
        focusManager.addKeyEventDispatcher(dispatcher)
        return {
            window.removeWindowFocusListener(onFocus)
            focusManager.removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun takeBack(window: Window, focusManager: KeyboardFocusManager) {
        if (!window.isFocused) return
        val content = contentOf(window) ?: return
        val owner = focusManager.focusOwner
        if (owner == null || isAroundContent(owner, content, window)) content.requestFocusInWindow()
    }

    /** A key that landed on the frame or a pane in [window]: given to Compose's listeners. */
    private fun redirect(e: KeyEvent, window: Window): Boolean {
        val source = e.component ?: return false
        if (source !== window && SwingUtilities.getWindowAncestor(source) !== window) return false
        val content = contentOf(window) ?: return false
        if (!isAroundContent(source, content, window)) return false
        val copy = KeyEvent(content, e.id, e.`when`, e.modifiersEx, e.keyCode, e.keyChar, e.keyLocation)
        content.keyListeners.forEach { listener ->
            when (e.id) {
                KeyEvent.KEY_PRESSED -> listener.keyPressed(copy)
                KeyEvent.KEY_RELEASED -> listener.keyReleased(copy)
                KeyEvent.KEY_TYPED -> listener.keyTyped(copy)
            }
        }
        // The next key takes the usual way.
        if (e.id == KeyEvent.KEY_PRESSED) content.requestFocusInWindow()
        e.consume()
        return true
    }
}
