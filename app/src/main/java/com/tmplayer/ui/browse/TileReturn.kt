package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tmplayer.ui.components.isTouch

/**
 * The tile a video's page was opened from, for putting the remote back on it once the page
 * closes. The page is a window of its own over the list, and while it was up the list under it
 * could take its focus elsewhere (a chat's grid asking for its first tile when a page of messages
 * arrived, History's list when its first card changed), so Back landed on the first tile or the
 * top bar's picture rather than where the viewer had been (Mi TV, 2026-10-23). Home keeps its
 * place the same way after the player ([HomeFocusMemory]).
 *
 * The player is such a page too (WP-15): the shell stops behind it, and where the system destroyed
 * the shell meanwhile ("Don't keep activities", a stick short of memory) the tile's key and index
 * are saved with the screen ([rememberTileReturn]) and put back on the rebuilt list.
 *
 * Plain state, read only from effects: a tile's focus moving is no reason to recompose the list.
 */
@Stable
internal class TileReturn(restoredKey: String? = null, restoredIndex: Int = -1) {
    private val requesters = HashMap<Any, FocusRequester>()

    /** The tile the page showing was opened from, by its list key. */
    private var opened: Any? = restoredKey

    /** Where the tile stood in its list when it was opened, or -1 when the opener did not say. */
    internal var openedIndex: Int = restoredIndex
        private set

    /** The index of the tile [takeReturn] last handed out, for the tile that is no longer there. */
    internal var takenIndex: Int = -1
        private set

    /** The tile the remote is on, by key, or null when it is outside the list. */
    private var focused: Any? = null

    /** Set while a page is up, so its closing is told from a list that never had one. */
    private var waiting = restoredKey != null

    /**
     * The page about to open was opened from the tile [key], which stood at [index] in its list
     * (-1 when the caller does not know), for when the list is built again without the tile.
     */
    fun open(key: Any, index: Int = -1) {
        opened = key
        openedIndex = index
    }

    /** The key [rememberTileReturn] keeps across a destroyed activity: only a string can be saved. */
    internal fun savedKey(): String? = opened as? String

    /** The shell stopped (the player or another app covers it) with a tile opened from: it comes back to it. */
    internal fun stopped() {
        if (opened != null) waiting = true
    }

    internal fun attach(key: Any, requester: FocusRequester) {
        requesters[key] = requester
    }

    internal fun detach(key: Any, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
        if (focused == key) focused = null
    }

    internal fun focusChanged(key: Any, has: Boolean) {
        if (has) {
            focused = key
            // The remote moved on before any page took over: the opened tile was a tick or a menu
            // that closed, not a page the remote has to come back from.
            if (!waiting && opened != null && opened != key) opened = null
        } else if (focused == key) {
            focused = null
        }
    }

    internal fun pageShown() {
        if (opened != null) waiting = true
    }

    /** The tile to go back to, once, when a page was up and has closed. */
    internal fun takeReturn(): Any? {
        if (!waiting) return null
        waiting = false
        takenIndex = openedIndex
        openedIndex = -1
        return opened.also { opened = null }
    }

    internal fun isOn(key: Any) = focused == key

    internal fun request(key: Any): Boolean {
        val requester = requesters[key] ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }
}

/**
 * The screen's [TileReturn], saved with it: a killed activity builds the screen again and the tile
 * the player was opened from is still known.
 */
@Composable
internal fun rememberTileReturn(): TileReturn = rememberSaveable(
    saver = listSaver<TileReturn, Any>(
        save = { listOf(it.savedKey() ?: "", it.openedIndex) },
        restore = { saved -> TileReturn((saved[0] as? String)?.takeIf { it.isNotEmpty() }, saved[1] as? Int ?: -1) },
    ),
) { TileReturn() }

/**
 * Marks this tile as one [ret] can put the remote back on, by its list [key]. Nothing on a touch
 * screen, where nothing holds a focus to put back.
 */
@Composable
internal fun Modifier.returnTile(ret: TileReturn, key: Any): Modifier {
    if (isTouch()) return this
    val requester = remember { FocusRequester() }
    DisposableEffect(ret, key) {
        ret.attach(key, requester)
        onDispose { ret.detach(key, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { ret.focusChanged(key, it.hasFocus) }
}

/**
 * Puts the remote back on the tile the page was opened from once [pageOpen] goes false and the
 * list's window has the focus again. Held for a few frames, as Home's return after the player is,
 * so a claim the list makes as it comes back (its own first focus) is put right. [scrollTo] brings
 * a tile that has scrolled out of the list back into it (false when the tile is gone, and the
 * remote is left where the list put it). [nearest] then names the tile now at the index the opened
 * one stood at (null when there is none), so a video deleted meanwhile leaves the remote on its
 * neighbour. [ready] is false while the list has nothing yet (a screen built again after the
 * activity was killed), so the return waits for the tiles.
 */
@Composable
internal fun ReturnToTile(
    ret: TileReturn,
    pageOpen: Boolean,
    ready: Boolean = true,
    nearest: (Int) -> Any? = { null },
    scrollTo: suspend (Any) -> Boolean,
) {
    if (isTouch()) return
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, ret) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) ret.stopped() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val nearestNow = rememberUpdatedState(nearest)
    val shellResumed = owner.lifecycle.currentStateFlow.collectAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(ret, pageOpen, windowFocused, ready, shellResumed) {
        if (pageOpen) {
            ret.pageShown()
            return@LaunchedEffect
        }
        if (!windowFocused || !ready || !shellResumed) return@LaunchedEffect
        var key = ret.takeReturn() ?: return@LaunchedEffect
        val index = ret.takenIndex
        var settled = 0
        for (frame in 0 until TILE_RETURN_FRAMES) {
            withFrameNanos { }
            if (ret.isOn(key)) {
                if (++settled >= TILE_RETURN_SETTLED_FRAMES) break
                continue
            }
            settled = 0
            if (!ret.request(key)) {
                // Scrolled out of the list (it went back to its top while the page was up).
                if (!scrollTo(key)) {
                    // Gone from it (deleted, filtered out): its neighbour at the same place.
                    val other = nearestNow.value(index)
                    if (other == null || other == key) break
                    key = other
                }
            }
        }
    }
}

/** How many frames after the page closes the tile is held against other claims. */
private const val TILE_RETURN_FRAMES = 12

/** Frames in a row the remote has to stay on the tile for it to count as back. */
private const val TILE_RETURN_SETTLED_FRAMES = 3
