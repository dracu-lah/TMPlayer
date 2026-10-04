package com.tmplayer.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.DesktopUpdates
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.SelfUpdate
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.isBackKey

/** The desktop only services the pages reach through the shell. */
class DesktopExtras(
    val prefs: DesktopPrefs,
    val updates: DesktopUpdates?,
    val watchCache: DesktopWatchCache?,
    val selfUpdate: SelfUpdate? = null,
) {
    companion object {
        fun live() = DesktopExtras(DesktopServices.prefs, DesktopServices.updates, DesktopServices.watchCache, DesktopServices.selfUpdate)
    }
}

/** Poster widths for the grids, smallest first. */
val POSTER_STEPS = listOf(160.dp, 208.dp, 264.dp, 320.dp)

/** The places the sidebar (or the rail, in a narrow window) goes to. */
enum class Destination(val label: String) {
    Chats("Chats"),
    Favourites("Favourites"),
    Continue("Continue"),
    Downloads("Downloads"),
    Settings("Settings"),
}

/**
 * A video the viewer asked to watch, as the player receives it.
 *
 * @param chatTitle the chat it came from, for the resume record and the player's title line; blank
 *   when the shell does not know it.
 */
data class PlayRequest(
    val item: MediaItem,
    val startFromBeginning: Boolean,
    val chatTitle: String,
)

/**
 * Everything the desktop window's navigation knows: where it is, which chat is open, what is
 * playing, and the keys that move between them.
 *
 * One per window. Pages read it and call into it; nothing below it holds navigation of its own.
 */
@Stable
class ShellState(
    val settings: SettingsStore,
    val downloads: DownloadRunner,
    services: () -> DesktopExtras = { DesktopExtras.live() },
) {
    /** The desktop's own settings, cache and update check; opened on first use, so tests that never touch them pay nothing. */
    val extras: DesktopExtras by lazy(services)

    var destination by mutableStateOf(Destination.Chats)
        private set

    /** The chat whose videos are on screen, over whichever destination opened it. */
    var openChat by mutableStateOf<ChatSummary?>(null)
        private set

    /** What the player is showing, or null while browsing. */
    var nowPlaying by mutableStateOf<PlayRequest?>(null)
        private set

    /** Whether the window is fullscreen: the first rung of the Esc ladder. The player sets it. */
    var fullscreen by mutableStateOf(false)

    /** The window's back dispatcher, fed by Esc, Backspace, Alt+Left, Cmd+[ and the mouse. */
    val backStack = BackStack()

    /** Whichever search field the page on screen has; "/" and Ctrl+F focus it. */
    val searchFocus = FocusRequester()

    /** The width a poster asks for in the grids; the toolbar steps it through [POSTER_STEPS]. */
    var posterWidth by mutableStateOf(POSTER_STEPS[1])

    fun stepPoster(direction: Int) {
        val at = POSTER_STEPS.indexOf(posterWidth).coerceAtLeast(0)
        posterWidth = POSTER_STEPS[(at + direction).coerceIn(0, POSTER_STEPS.lastIndex)]
    }

    /** Kept here so leaving a chat comes back to the same place in the list. */
    val chatListState = LazyListState()

    /** Chat titles by id, so a video opened from anywhere can say where it came from. */
    private val chatTitles = mutableMapOf<Long, String>()

    fun go(to: Destination) {
        openChat = null
        destination = to
    }

    fun openChat(chat: ChatSummary) {
        chatTitles[chat.id] = chat.title
        openChat = chat
    }

    fun closeChat() {
        openChat = null
    }

    fun noteChatTitles(chats: List<ChatSummary>) {
        chats.forEach { chatTitles[it.id] = it.title }
    }

    fun noteChatTitle(chatId: Long, title: String) {
        if (title.isNotBlank()) chatTitles[chatId] = title
    }

    fun chatTitleOf(chatId: Long): String = chatTitles[chatId].orEmpty()

    /**
     * The one way anything in the window starts a video. The browse pages stay composed under
     * the player, so closing it returns to the same page and scroll position.
     *
     * @param startFromBeginning true for "Play from start"; false resumes where the viewer left
     *   off, if anywhere.
     */
    fun openPlayer(item: MediaItem, startFromBeginning: Boolean) {
        nowPlaying = PlayRequest(item, startFromBeginning, chatTitleOf(item.chatId))
    }

    fun closePlayer() {
        nowPlaying = null
        fullscreen = false
    }

    /** Puts the cursor in the page's search field. Quietly nothing when the page has none. */
    fun focusSearch() {
        runCatching { searchFocus.requestFocus() }
    }

    /**
     * Shortcuts that work wherever focus is, even inside a text field: Ctrl+F (Cmd+F) to search,
     * Ctrl+, (Cmd+,) for Settings.
     */
    fun onPreviewKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || nowPlaying != null) return false
        val command = (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed
        return when {
            command && event.key == Key.F -> {
                focusSearch()
                true
            }
            command && event.key == Key.Comma -> {
                go(Destination.Settings)
                true
            }
            else -> false
        }
    }

    /**
     * Keys nothing focused wanted: "/" to search (a text field keeps its own slash), and the back
     * keys, which unwind the [backStack] one rung at a time.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (isBackKey(event)) {
            if (fullscreen) {
                fullscreen = false
                return true
            }
            return backStack.handleBack()
        }
        if (event.type == KeyEventType.KeyDown && event.key == Key.Slash && nowPlaying == null &&
            !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed
        ) {
            focusSearch()
            return true
        }
        return false
    }
}
