package com.tmplayer.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tmplayer.desktop.os.OsInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A key the grid understands, free of Compose's event type so the arithmetic can be tested. */
enum class NavKey { Left, Right, Up, Down, RowStart, RowEnd, First, Last, PageUp, PageDown }

/**
 * Where focus goes in a grid of [count] cells laid out [columns] wide, after [key] (WAI-ARIA grid
 * pattern, B2.1): arrows move one cell and stop at the edges rather than wrapping, Home and End go
 * to the ends of the row, Ctrl+Home and Ctrl+End to the first and last cell, Page Up and Page Down
 * move [pageRows] rows and keep the column. A list is a grid one column wide.
 *
 * @return the new index, or the same one at an edge (the key is still the grid's).
 */
object GridNav {
    fun move(index: Int, key: NavKey, columns: Int, count: Int, pageRows: Int = 1): Int {
        if (count <= 0) return -1
        val cols = columns.coerceAtLeast(1)
        val at = index.coerceIn(0, count - 1)
        val row = at / cols
        val lastRow = (count - 1) / cols
        val rowStart = row * cols
        val rowEnd = minOf(rowStart + cols - 1, count - 1)
        val rows = pageRows.coerceAtLeast(1)
        return when (key) {
            NavKey.Left -> if (at > rowStart) at - 1 else at
            NavKey.Right -> if (at < rowEnd) at + 1 else at
            NavKey.Up -> if (row > 0) at - cols else at
            // Into a short last row, the cell nearest below.
            NavKey.Down -> if (row < lastRow) minOf(at + cols, count - 1) else at
            // A list's row is one cell, where Home and End going nowhere read as broken: in a list
            // they are the top and the bottom, as in every list on the desktop.
            NavKey.RowStart -> if (cols == 1) 0 else rowStart
            NavKey.RowEnd -> if (cols == 1) count - 1 else rowEnd
            NavKey.First -> 0
            NavKey.Last -> count - 1
            NavKey.PageUp -> if (row == 0) at else at - minOf(rows, row) * cols
            NavKey.PageDown -> if (row == lastRow) at else minOf(at + minOf(rows, lastRow - row) * cols, count - 1)
        }
    }

    /** The [NavKey] for a key press, or null for keys the grid leaves alone. */
    fun keyFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean, mac: Boolean = OsInfo.isMac): NavKey? {
        if (alt || shift) return null
        val command = ctrl || meta
        // A Mac keyboard has no Home or End: Cmd+Up and Cmd+Down go to the top and the bottom.
        val macEnds = mac && meta && !ctrl
        return when (key) {
            Key.DirectionLeft -> if (command) null else NavKey.Left
            Key.DirectionRight -> if (command) null else NavKey.Right
            Key.DirectionUp -> if (macEnds) NavKey.First else if (command) null else NavKey.Up
            Key.DirectionDown -> if (macEnds) NavKey.Last else if (command) null else NavKey.Down
            Key.MoveHome -> if (command) NavKey.First else NavKey.RowStart
            Key.MoveEnd -> if (command) NavKey.Last else NavKey.RowEnd
            Key.PageUp -> if (command) null else NavKey.PageUp
            Key.PageDown -> if (command) null else NavKey.PageDown
            else -> null
        }
    }

    /** The context menu key, or Shift+F10, which is the same key on a keyboard without one. */
    fun isMenuKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key == Key.Menu) return true
        val native = event.nativeKeyEvent as? java.awt.event.KeyEvent
        if (native?.keyCode == java.awt.event.KeyEvent.VK_CONTEXT_MENU) return true
        return event.key == Key.F10 && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
    }
}

private val ARROWS = setOf(NavKey.Left, NavKey.Right, NavKey.Up, NavKey.Down)

/** What [KeyboardNav] needs to know about the lazy layout it drives. */
interface NavSurface {
    /** Cells per row; 1 for a list. */
    fun columns(): Int

    /** Rows fully or partly on screen, for Page Up and Page Down. */
    fun visibleRows(): Int

    fun isVisible(index: Int): Boolean

    suspend fun scrollTo(index: Int)
}

/**
 * Index based keyboard focus for a lazy grid or list: lazy cells off screen do not exist, so focus
 * cannot simply be moved to them. The arrow keys pick a target index, the layout scrolls it into
 * view, and the cell asks for focus once it is composed ([pending]).
 */
@Stable
class KeyboardNav(private val surface: NavSurface, private val scope: CoroutineScope) {

    /** The cell holding focus (or a child of it); -1 when focus is elsewhere. */
    var current by mutableIntStateOf(-1)
        internal set

    /** A cell that should take focus as soon as it is composed. */
    var pending by mutableStateOf<Int?>(null)
        internal set

    /** Sends focus to [index], scrolling to it first when it is off screen. */
    fun focus(index: Int) {
        if (index < 0) return
        pending = index
        if (!surface.isVisible(index)) scope.launch { surface.scrollTo(index) }
    }

    /** The grid's own keys, given how many cells there are. True when the key was the grid's. */
    fun onKey(event: KeyEvent, count: Int): Boolean {
        if (event.type != KeyEventType.KeyDown || count <= 0) return false
        val key = GridNav.keyFor(event.key, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
            ?: return false
        if (current < 0) return false
        val rows = (surface.visibleRows() - 1).coerceAtLeast(1)
        val target = GridNav.move(current, key, surface.columns(), count, rows)
        // On a television an arrow at the grid's edge goes on out of it, to the toolbar or the
        // bar above, as a remote expects (ShellState.onKey); on a desktop the grid keeps it.
        if (target == current && BigScreen.on && key in ARROWS) return false
        if (target != current) focus(target)
        return true
    }
}

/**
 * Home and End on a page's list whose cells are not walked by a [KeyboardNav] (Home's rows, the
 * Downloads list): the list scrolls to its top or its end. Bubbles up after the focused cell has
 * had the key, so a cell or a strip that uses Home itself keeps it.
 */
@Composable
fun Modifier.homeEndScrolls(list: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isShiftPressed) return@onKeyEvent false
        when (event.key) {
            Key.MoveHome -> scope.launch { list.scrollToItem(0) }
            Key.MoveEnd -> scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            else -> return@onKeyEvent false
        }
        true
    }
}

@Composable
fun rememberKeyboardNav(surface: NavSurface): KeyboardNav {
    val scope = rememberCoroutineScope()
    return remember(surface) { KeyboardNav(surface, scope) }
}

/**
 * A grid whose [count] cells start at item [offset]: a full width header before them counts as one
 * item, and a spinner row after them is not a cell.
 */
class GridSurface(private val grid: LazyGridState, private val offset: () -> Int, private val count: () -> Int) : NavSurface {
    private fun cells() = grid.layoutInfo.visibleItemsInfo.filter { it.index - offset() in 0 until count() }

    override fun columns(): Int = (cells().maxOfOrNull { it.column } ?: 0) + 1
    override fun visibleRows(): Int = cells().map { it.row }.distinct().size
    override fun isVisible(index: Int): Boolean {
        val info = grid.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index + offset() } ?: return false
        return item.offset.y >= info.viewportStartOffset && item.offset.y + item.size.height <= info.viewportEndOffset
    }
    override suspend fun scrollTo(index: Int) = grid.animateScrollToItem(index + offset())
}

/**
 * A grid whose cells are parted by full width headings (History's Today, Yesterday, Earlier):
 * [gridIndex] gives, for each cell in order, the grid item that draws it. The arrows still count
 * cells in order, so Up and Down across a heading land on the cell [GridNav] works out from the
 * flat order, which can be a column off the one straight above or below.
 */
class SectionedGridSurface(private val grid: LazyGridState, private val gridIndex: () -> IntArray) : NavSurface {
    private fun cells() = gridIndex().toHashSet().let { set -> grid.layoutInfo.visibleItemsInfo.filter { it.index in set } }

    override fun columns(): Int = (cells().maxOfOrNull { it.column } ?: 0) + 1
    override fun visibleRows(): Int = cells().map { it.row }.distinct().size
    override fun isVisible(index: Int): Boolean {
        val target = gridIndex().getOrNull(index) ?: return false
        val info = grid.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return false
        return item.offset.y >= info.viewportStartOffset && item.offset.y + item.size.height <= info.viewportEndOffset
    }
    override suspend fun scrollTo(index: Int) {
        gridIndex().getOrNull(index)?.let { grid.animateScrollToItem(it) }
    }
}

class ListSurface(private val list: LazyListState) : NavSurface {
    override fun columns(): Int = 1
    override fun visibleRows(): Int = list.layoutInfo.visibleItemsInfo.size
    override fun isVisible(index: Int): Boolean {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
    }
    override suspend fun scrollTo(index: Int) = list.animateScrollToItem(index)
}

/**
 * The keys of the whole grid or list: put on the lazy layout itself, around the cells. Read on the
 * way down (preview), because the lazy layout's own scrolling takes Page Up and Page Down on the
 * way back up; the cells have no use for the arrow keys, so nothing below loses them.
 */
fun Modifier.navKeys(nav: KeyboardNav, count: () -> Int): Modifier = onPreviewKeyEvent { nav.onKey(it, count()) }

/**
 * One cell: reports when focus is inside it, and takes focus when [KeyboardNav.pending] names it.
 * Put on the cell's focusable (clickable) element, with the [FocusRequester] it uses.
 */
@Composable
fun Modifier.navCell(nav: KeyboardNav, index: Int, requester: FocusRequester = remember { FocusRequester() }): Modifier {
    LaunchedEffect(nav.pending, index) {
        if (nav.pending == index) {
            runCatching { requester.requestFocus() }
            nav.pending = null
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { state ->
            if (state.hasFocus) nav.current = index else if (nav.current == index) nav.current = -1
        }
}

/**
 * The Chats page as the window draws it: a list of rows when narrow, a grid of cards when wide.
 * One keyboard model across both, so a resize does not lose the focused chat.
 */
class SwitchingSurface(private val list: NavSurface, private val grid: NavSurface) : NavSurface {
    var wide: Boolean = false
    private val now get() = if (wide) grid else list
    override fun columns(): Int = now.columns()
    override fun visibleRows(): Int = now.visibleRows()
    override fun isVisible(index: Int): Boolean = now.isVisible(index)
    override suspend fun scrollTo(index: Int) = now.scrollTo(index)
}
