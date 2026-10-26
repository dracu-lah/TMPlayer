package com.tmplayer.desktop.ui

import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.ChatSort
import com.tmplayer.ui.browse.ChatFilter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CopyHandoff
import com.tmplayer.platform.Background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.BookmarkStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.SelfUpdate
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.NavGroup
import com.tmplayer.ui.browse.navGroupOf
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.isBackKey

/** The desktop only services the pages reach through the shell. */
class DesktopExtras(
    val prefs: DesktopPrefs,
    val updates: UpdateScheduler?,
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
enum class Destination(private val labelText: () -> String) {
    Home({ L.browseTabHome }),
    /** Continue watching and Previously watched, as the two tabs of one page. */
    History({ L.browseTabHistory }),
    Favourites({ L.navFavourites }),
    Chats({ L.navChats }),
    Downloads({ L.navDownloads }),
    Settings({ L.navSettings }),
    ;

    /** Settings and Downloads open as a panel over the page they were reached from (ModalPageHost). */
    val isModal: Boolean get() = this == Downloads || this == Settings

    /** The sidebar label, in the UI language. */
    val label: String get() = labelText()
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
 * A video's detail pane, asked for by a right click, the context menu key or I on its poster.
 *
 * @param outsideChat opened from Home or the search across chats, where "Go to" the chat is a step.
 * @param selectThis starts a selection with this poster; null where the page has no selection.
 * @param onClosed hands focus back to the poster the pane was opened from.
 */
class DetailRequest(
    val item: MediaItem,
    val chatTitle: String,
    val outsideChat: Boolean = false,
    val selectThis: (() -> Unit)? = null,
    val onClosed: () -> Unit = {},
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
    /** The Watched list: the ticks on the posters and the page of finished videos. */
    val watched: WatchedStore,
    services: () -> DesktopExtras = { DesktopExtras.live() },
    /** The bookmarks the detail pane lists; null (the UI tests) leaves the list out. */
    val bookmarks: BookmarkStore? = null,
) {
    /** The desktop's own settings, cache and update check; opened on first use, so tests that never touch them pay nothing. */
    val extras: DesktopExtras by lazy(services)

    var destination by mutableStateOf(Destination.Home)
        private set

    /** The page drawn under Settings or Downloads: where they were reached from, Home if nowhere. */
    var underlay by mutableStateOf(Destination.Home)
        private set

    /** Whether Settings or Downloads is showing as a panel (not in the big-screen mode, which keeps them as pages). */
    val modalOpen: Boolean get() = destination.isModal && !BigScreen.on

    /** The page that fills the window: [destination], or the one under the panel while one is open. */
    val pageDestination: Destination get() = if (modalOpen) underlay else destination

    /** Closes the panel, back to the page it was opened over. */
    fun closeModal() {
        if (destination.isModal) go(underlay)
    }

    /**
     * Which slice of the chat list the Chats page shows: Chats (with its chips), Saved Messages or
     * a Telegram folder. Kept here rather than on the page so the side bar can pick it.
     */
    var chatSection by mutableStateOf<BrowseSection>(BrowseSection.of(BrowseTab.Chats))
        private set

    /**
     * The chips over the Chats list and the open tab of History. The shell reads them back from
     * [SettingsStore] at start and writes each change there, as the phone and the TV do.
     */
    /**
     * Whether the app lock's PIN (CP57) has been given for this visit to Settings. Leaving for any
     * other page forgets it, so Settings asks again next time.
     */
    var settingsUnlocked by mutableStateOf(false)

    var chatFilter by mutableStateOf(ChatFilter.All)
    var chatSort by mutableStateOf(ChatSort.Recent)
    var historyTab by mutableStateOf(HistoryTab.Continue)

    /** History's order, one for its three lists: most recent first, or by title. */
    var historySort by mutableStateOf(ChatSort.Recent)

    /**
     * Set by Settings' Recently played row: Downloads opens with that section, folded away at the
     * page's foot otherwise, unfolded and in view. The page clears it once it has done so.
     */
    var downloadsOpenRecent by mutableStateOf(false)

    /** Set by the low storage notification (CP76): Downloads opens with Free up space up. */
    var downloadsOpenFreeUp by mutableStateOf(false)
    /**
     * Set by the player's "Not enough space": Downloads opens with Free up space already up, the
     * device counted as short so Low storage mode is offered. The page clears it once shown.
     */
    var downloadsFreeUp by mutableStateOf(false)

    /** The player refused a video for room: closed, and Downloads open on Free up space. */
    fun freeUpSpace() {
        closePlayer()
        downloadsFreeUp = true
        go(Destination.Downloads)
    }

    /** History, on [tab]. */
    fun showHistory(tab: HistoryTab) {
        historyTab = tab
        go(Destination.History)
    }

    /** The side bar group holding where the window is, which is always open; null on Settings. */
    val currentGroup: NavGroup?
        get() = when (destination) {
            Destination.Chats -> navGroupOf(chatSection)
            Destination.Settings -> null
            else -> NavGroup.Watch
        }

    /** Which slice the Chats page shows, without going there: for a section the side bar no longer offers. */
    fun pickChatSection(section: BrowseSection) {
        if (section == SAVED) openSavedMessages { chatSection = section } else chatSection = section
    }

    /** The chat list, showing [section]. Saved Messages opens as the chat itself. */
    fun showChats(section: BrowseSection) {
        val show = {
            chatSection = section
            go(Destination.Chats)
        }
        if (section == SAVED) openSavedMessages(show) else show()
    }

    /**
     * Saved Messages is one chat, so it opens over the page it was asked from, rather than as a
     * list holding it alone, which took a second click. [orList] is the old way, for when the
     * chat cannot be found yet.
     */
    private fun openSavedMessages(orList: () -> Unit) {
        knownChats.values.firstOrNull { it.kind == com.tmplayer.data.ChatKind.Saved }?.let { openChat(it); return }
        Background.scope.launch {
            val saved = com.tmplayer.data.savedMessagesNow()
            withContext(Dispatchers.Main) { if (saved != null) openChat(saved) else orList() }
        }
    }

    /** The chat whose videos are on screen, over whichever destination opened it. */
    var openChat by mutableStateOf<ChatSummary?>(null)
        private set

    /** What the player is showing, or null while browsing. */
    var nowPlaying by mutableStateOf<PlayRequest?>(null)
        private set

    /**
     * Whether the window is fullscreen: the first rung of the Esc ladder, and the last in the
     * big-screen mode, where the whole app is full screen. The player and F11 set it.
     */
    var fullscreen by mutableStateOf(false)

    /** Whether the update popup is open: the side bar item, Settings or the once per version rule. */
    var updatePopup by mutableStateOf(false)

    /** The window's back dispatcher, fed by Esc, Backspace, Alt+Left, Cmd+[ and the mouse. */
    val backStack = BackStack()

    /**
     * Whether the search palette is over the window (Ctrl+K, Cmd+K on a Mac). Its query outlives
     * it, so Esc and Ctrl+K again bring back what was typed, selected.
     */
    var paletteOpen by mutableStateOf(false)

    var paletteQuery by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))

    /**
     * Set with the palette's text when something other than typing opened it ([searchEverywhere]),
     * so the palette runs the search at once rather than waiting for a key.
     */
    var paletteSubmit by mutableStateOf(false)

    /** "Search all chats" from a listing that matched nothing: the palette, open on [text]. */
    fun searchEverywhere(text: String) {
        if (text.isBlank()) return
        paletteQuery = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
        paletteSubmit = true
        paletteOpen = true
    }

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

    /** The Chats page's scroll in a wide window, where its rows are a grid of cards. */
    val chatGridState = androidx.compose.foundation.lazy.grid.LazyGridState()

    /** Chat titles by id, so a video opened from anywhere can say where it came from. */
    private val chatTitles = mutableMapOf<Long, String>()

    /** The chats the list has shown, by id, so a pane opened from Home or search can go to one. */
    private val knownChats = mutableMapOf<Long, ChatSummary>()

    /**
     * "Open a video's page first" from Settings, mirrored here for every poster to read: a click
     * (or Enter) on a video opens its detail pane and a double click plays it; off, a click plays.
     */
    var detailFirst by mutableStateOf(true)

    /**
     * The detail panes open, the one showing on top: a video opened from a pane's "More like this"
     * lies over the pane it came from, so Esc or Back goes back to that one before closing.
     */
    private val details = com.tmplayer.ui.browse.DetailBackStack<DetailRequest>({ it.item })

    /**
     * The detail pane a video was started from (its Play, a bookmark, a comment's video), with the
     * panes under it, put away while the player is up and shown again when it closes, so leaving
     * the video, or a video that would not play, lands on the pane Play was pressed on rather than
     * on the page behind it. Kept out of the window while the player is up: the pane holds the
     * keyboard's focus and draws over a picture shown under the window. Going anywhere else lets
     * them go.
     */
    private var detailUnderPlayer by mutableStateOf(false)

    /** The detail pane beside or over the page, or null while none is open. */
    val detail: DetailRequest?
        get() = details.top?.page?.takeUnless { detailUnderPlayer }

    /** The poster the showing pane gives the focus back to, after Back from a video opened by it. */
    val detailReturn: com.tmplayer.ui.browse.DetailReturn?
        get() = details.top?.returnTo

    /** Opens a pane afresh, from a poster on a page: any panes open before are let go. */
    fun openDetail(request: DetailRequest) {
        details.open(request)
        detailUnderPlayer = false
    }

    /** Opens another video's pane over the one showing, for "More like this". */
    fun pushDetail(request: DetailRequest) {
        details.push(request)
    }

    /** Turns the showing pane to another copy of the same film, in place. */
    fun turnDetail(request: DetailRequest) {
        details.turn(request)
    }

    /**
     * Back to the pane under this one, or, on the last, closes it and gives focus back to the
     * poster it came from.
     */
    fun closeDetail() {
        val open = detail ?: return
        if (!details.back()) open.onClosed()
    }

    /** Closes every pane at once (a click on the scrim), focus back to the first one's poster. */
    fun closeAllDetails() {
        if (detail == null) return
        val first = details.entries.firstOrNull()?.page
        details.clear()
        first?.onClosed?.invoke()
    }

    fun chatOf(chatId: Long): ChatSummary? = knownChats[chatId]

    fun go(to: Destination) {
        openChat = null
        chatToRefocus = null
        details.clear()
        detailUnderPlayer = false
        if (to != Destination.Settings) settingsUnlocked = false
        // A panel opened from another panel (Settings to Downloads) keeps the page under both.
        if (to.isModal && !destination.isModal) underlay = destination
        destination = to
    }

    fun openChat(chat: ChatSummary) {
        chatTitles[chat.id] = chat.title
        knownChats[chat.id] = chat
        details.clear()
        detailUnderPlayer = false
        openChat = chat
    }

    fun closeChat() {
        chatToRefocus = openChat?.id
        openChat = null
    }

    /** The chat just closed, whose row takes the keyboard focus back on the list it came from. */
    private var chatToRefocus: Long? = null

    /** [chatToRefocus], once: the list that asks first gets it. */
    fun takeChatToRefocus(): Long? = chatToRefocus.also { chatToRefocus = null }

    fun noteChatTitles(chats: List<ChatSummary>) {
        chats.forEach {
            chatTitles[it.id] = it.title
            knownChats[it.id] = it
        }
    }

    fun noteChatTitle(chatId: Long, title: String) {
        if (title.isNotBlank()) chatTitles[chatId] = title
    }

    fun chatTitleOf(chatId: Long): String = chatTitles[chatId].orEmpty()

    /**
     * The one way anything in the window starts a video. The browse pages stay composed under
     * the player, so closing it returns to the same page and scroll position, and a detail pane
     * open at the press comes back with it ([detailUnderPlayer]).
     *
     * @param startFromBeginning true for "Play from start"; false resumes where the viewer left
     *   off, if anywhere.
     */
    fun openPlayer(item: MediaItem, startFromBeginning: Boolean) {
        if (detail != null) detailUnderPlayer = true
        warmStream(item, fromStart = startFromBeginning)
        if (item.versions.size <= 1) {
            nowPlaying = PlayRequest(item, startFromBeginning, chatTitleOf(item.chatId))
            return
        }
        // A film posted more than once plays the copy chosen for the viewer (VideoCopies.pick),
        // and the player is handed the others for its Copies menu.
        CopyHandoff.offer(item.versions, item.versions.associate { it.chatId to chatTitleOf(it.chatId) }.filterValues { it.isNotBlank() })
        Background.scope.launch {
            val picked = runCatching { settings.copyToPlay(item) }.getOrDefault(item)
            withContext(Dispatchers.Main) { nowPlaying = PlayRequest(picked, startFromBeginning, chatTitleOf(picked.chatId)) }
        }
    }

    /**
     * Starts the head and the index of [item]'s stream coming the moment it is asked for
     * ([com.tmplayer.player.StreamWarmup]), unless it plays from the Downloads folder. Also the
     * call for a card under the pointer for a moment, or the details pane's Play. A video with a
     * saved place is warmed there too, unless it is to start over ([fromStart]).
     */
    fun warmStream(item: MediaItem, fromStart: Boolean = false) {
        if (item.isJoined || item.fileId <= 0) return
        Background.scope.launch {
            if (com.tmplayer.desktop.DownloadIndex.fileFor(settings, item.chatId, item.messageId) == null) {
                com.tmplayer.player.StreamWarmup.warm(item, if (fromStart) null else ({ settings.resumeStartFor(item) }))
            }
        }
    }

    fun closePlayer() {
        nowPlaying = null
        // The big-screen mode is full screen for the whole app, not for the video alone.
        if (!BigScreen.on) fullscreen = false
        detailUnderPlayer = false
    }

    // ---- the big-screen mode (plan 8.7) --------------------------------------------------------

    /** The TV bar's items, while the big-screen mode draws it. */
    val tvBar = TvBarFocus()

    /**
     * The page under the bar, as one focus group that remembers the item the remote was last on
     * there ([NavFrame]): Down from the bar and the end of a video go back to it.
     */
    val pageFocus = FocusRequester()

    /** The window's focus, for the arrows nothing on the page took; set by [DesktopShell]. */
    var focusManager: androidx.compose.ui.focus.FocusManager? = null

    /** The remote onto the bar's item for the page on screen (the picture for the pages behind it). */
    fun focusBar(): Boolean {
        if (!tvBar.shown) return false
        return runCatching { tvBar.requesterFor(navPlaceOf(this)).requestFocus(FocusDirection.Enter) }.getOrDefault(false)
    }

    /** The remote back onto the page, on the item it was last on there, else the page's first. */
    fun focusPage(): Boolean = runCatching { pageFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)

    /**
     * Back from a page other than Home, the TV's order (focus.ts): from the Chats page's items up
     * to its place on the bar first, then Home, with the remote on Home's place. A page behind
     * the picture goes straight to Home. On the desktop, Home at once.
     */
    fun backFromPage() {
        if (modalOpen) {
            closeModal()
            return
        }
        if (BigScreen.on && destination == Destination.Chats && tvBar.shown && !tvBar.focused) {
            focusBar()
            return
        }
        go(Destination.Home)
        if (BigScreen.on) runCatching { tvBar.home.requestFocus() }
    }

    /**
     * The end of the big-screen mode's Back: with nothing left to close and Home on screen, the
     * remote goes up to the bar, then the window leaves full screen, which is the remote's only
     * way out of it (F11 is the keyboard's).
     */
    private fun lastBack(): Boolean {
        if (tvBar.shown && !tvBar.focused && focusBar()) return true
        if (fullscreen) {
            fullscreen = false
            return true
        }
        return false
    }

    /**
     * An arrow nothing on the page took: Compose's own search for the nearest item that way, so
     * the remote reaches every control, a toolbar's and a dialog's included. Nothing that way:
     * Up goes to the bar, and with nothing focused at all, the page's first item.
     */
    private fun arrow(direction: FocusDirection, key: Key): Boolean {
        // The picture's menu keeps the remote: Up and Down walk it, Left and Right go nowhere.
        if (profileOpen && (key == Key.DirectionLeft || key == Key.DirectionRight)) return true
        val moved = focusManager?.moveFocus(direction) ?: false
        if (moved) return true
        if (direction == FocusDirection.Up && !tvBar.focused && focusBar()) return true
        if (!tvBar.focused) focusPage()
        return true
    }

    /** Puts the cursor in the page's search field. Quietly nothing when the page has none. */
    fun focusSearch() {
        // A chat keeps its search folded into the bar at its foot; opening it is the page's own.
        searchOpener?.let {
            it()
            return
        }
        runCatching { searchFocus.requestFocus() }
    }

    /** Set by a page whose search field is not on screen until asked for (a chat's bar). */
    var searchOpener: (() -> Unit)? = null

    /**
     * The page's own refresh, for F5 and Ctrl+R (Cmd+R): the phone pulls a list down to refresh it,
     * which a mouse and a keyboard have no word for. Each page that can refresh sets it while it
     * is on screen ([RegisterRefresh]); null where there is nothing to refresh.
     */
    var refreshAction: (() -> Unit)? = null

    /** A chat's videos as rows of a small poster and the name, rather than tiles. */
    var chatRows by mutableStateOf(false)

    /** Whether the profile popover over the floating bar is open. */
    var profileOpen by mutableStateOf(false)

    /**
     * Ends the app, set by the window. Ctrl+Q (Cmd+Q) reaches it from any page: a tiling window
     * manager such as sway draws no title bar, so there is no close button to find, and the player
     * already answered Ctrl+Q while the rest of the app did not.
     */
    var quit: (() -> Unit)? = null

    /** Minimises the window, set by the window where there is somewhere to minimise to (Cmd+M on a Mac). */
    var minimize: (() -> Unit)? = null

    /**
     * Shortcuts that work wherever focus is, even inside a text field: Ctrl+F (Cmd+F) to search
     * the page, Ctrl+K (Cmd+K) to open or close the search across every chat, Ctrl+, (Cmd+,) for
     * Settings, Ctrl+Q (Cmd+Q) to quit. The command key is the OS's own, as
     * the player reads it: Ctrl on a Mac is a text field's caret key, and Super+Q on Linux belongs
     * to the window manager.
     */
    fun onPreviewKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || nowPlaying != null) return false
        val command = (if (OsInfo.isMac) event.isMetaPressed else event.isCtrlPressed) && !event.isAltPressed
        return when {
            command && event.key == Key.Q && quit != null -> {
                quit?.invoke()
                true
            }
            // A Mac's own window keys: Cmd+W closes the window, which is the app's only one, and
            // Cmd+M minimises it. Elsewhere the window manager has its own (Alt+F4, Super+H).
            OsInfo.isMac && command && event.key == Key.W && quit != null -> {
                quit?.invoke()
                true
            }
            OsInfo.isMac && command && event.key == Key.M && minimize != null -> {
                minimize?.invoke()
                true
            }
            command && event.key == Key.K -> {
                paletteOpen = !paletteOpen
                true
            }
            command && event.key == Key.F -> {
                focusSearch()
                true
            }
            command && event.key == Key.Comma -> {
                go(Destination.Settings)
                true
            }
            // The floating bar's first two places, by the number of their place on it.
            command && event.key == Key.One -> {
                go(Destination.Home)
                true
            }
            command && event.key == Key.Two -> {
                if (destination != Destination.Chats || openChat != null) {
                    showChats(if (chatSection.onChatsPage) chatSection else BrowseSection.of(BrowseTab.Chats))
                }
                true
            }
            // Full screen from any page, as the player has it: F11, and Ctrl+Cmd+F on a Mac. The
            // big-screen mode's way back into it after Back has left it.
            (event.key == Key.F11 && !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed) ||
                (OsInfo.isMac && event.isCtrlPressed && event.isMetaPressed && event.key == Key.F) -> {
                fullscreen = !fullscreen
                true
            }
            (command && event.key == Key.R) || (event.key == Key.F5 && !event.isAltPressed) -> {
                refreshAction?.invoke()
                refreshAction != null
            }
            else -> false
        }
    }

    /**
     * Keys nothing focused wanted: "/" to search (a text field keeps its own slash), and the back
     * keys, which unwind the [backStack] one rung at a time.
     */
    fun onKey(event: KeyEvent): Boolean {
        val remote = remoteKeyOf(event)
        if (isBackKey(event) || remote == RemoteKey.Back) {
            // On a television full screen is the last rung rather than the first: Back unwinds
            // the pages, and only then leaves it.
            if (BigScreen.on) return backStack.handleBack() || lastBack()
            if (fullscreen) {
                fullscreen = false
                return true
            }
            return backStack.handleBack()
        }
        if (BigScreen.on && nowPlaying == null && event.type == KeyEventType.KeyDown) {
            val plain = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            if (direction != null && plain) return arrow(direction, event.key)
            if (remote == RemoteKey.Home) {
                go(Destination.Home)
                runCatching { tvBar.home.requestFocus() }
                return true
            }
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

private val SAVED = BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Saved)
