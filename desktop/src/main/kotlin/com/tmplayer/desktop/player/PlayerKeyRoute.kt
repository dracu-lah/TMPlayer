package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.KeyEvent

/**
 * The open player's keys, heard by the window as well as by the picture.
 *
 * The player hears its keys through a focusable box around the picture (PlayerScreen), and
 * Compose only hands a key to a box when its focus is on it or inside it. That focus is lost
 * more often than it looks: a control the mouse pressed takes it and then hides with the
 * controls, the loading sheet's Try again takes it and goes when the video starts, a menu or a
 * sheet closes, the page behind the player asks for it (a detail pane's Play, a row's cell, a
 * search field), or a window system hands the window back after fullscreen, a restore or Alt+Tab
 * with no focus inside it. Each time Space, F and Esc reached nothing until a click on the
 * picture put the focus back (users, mostly on Windows, 2026-10-27).
 *
 * So the window asks here first ([onWindowKey], from its onPreviewKeyEvent): while a player is
 * open and Compose's focus is not inside it, the key is the player's, and the focus goes back to
 * the picture so the next key takes the usual way. A text field keeps its keys: the player's own
 * prompts and menus say so through [Route.typing], and a field in a dialog over the window
 * through [textInput], which is true while Compose has a text input session.
 */
object PlayerKeyRoute {

    /** What an open player gives the window. All are read on the UI thread, at the key press. */
    class Route(
        /** The player's key handling, the same the picture's own focus runs. */
        val handle: (KeyEvent) -> Boolean,
        /** Whether Compose's focus is on the picture or inside it, where [handle] already runs. */
        val focusInside: () -> Boolean,
        /** Whether a prompt or a menu of the player's holds the keyboard (typing, a list to walk). */
        val typing: () -> Boolean,
        /** Puts Compose's focus back on the picture. */
        val refocus: () -> Unit,
    )

    private var route: Route? = null

    /**
     * Whether a text field somewhere in the window is being typed into (Compose has an input
     * session open). Set by the window; a window that does not say keeps every key routed.
     */
    var textInput: () -> Boolean = { false }

    /**
     * Whether Compose's focus is somewhere in the window's main page (the shell under the
     * player), as opposed to a dialog or a popup over it. Set by the shell.
     */
    var pageHasFocus: () -> Boolean = { false }

    val isOpen: Boolean get() = route != null

    fun open(route: Route) {
        this.route = route
    }

    /** Takes [route] away, unless a newer player has already put its own in its place. */
    fun close(route: Route) {
        if (this.route === route) this.route = null
    }

    /** The window's preview of every key: true when the open player took it. */
    fun onWindowKey(event: KeyEvent): Boolean {
        val route = route ?: return false
        if (!takesKey(
                focusInside = guarded(false) { route.focusInside() },
                typing = guarded(true) { route.typing() },
                textInput = guarded(true) { textInput() },
                pageHasFocus = guarded(false) { pageHasFocus() },
            )
        ) {
            return false
        }
        // The focus back first, so the key's release and the next press take the usual way.
        runCatching { route.refocus() }
        return route.handle(event)
    }

    /**
     * Whether the window hands a key to the player rather than to where Compose's focus is.
     * A text field being typed into keeps it, unless it is one of the page's behind the player
     * (a search field left focused under the picture), which the viewer cannot see.
     */
    internal fun takesKey(focusInside: Boolean, typing: Boolean, textInput: Boolean, pageHasFocus: Boolean): Boolean = when {
        focusInside -> false
        typing -> false
        textInput && !pageHasFocus -> false
        else -> true
    }

    /** A check that fails counts as [fallback]; a key press never takes the window down. */
    private inline fun guarded(fallback: Boolean, check: () -> Boolean): Boolean = runCatching(check).getOrDefault(fallback)
}
