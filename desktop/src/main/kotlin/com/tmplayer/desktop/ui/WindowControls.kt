package com.tmplayer.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.desktop.os.Bounds
import com.tmplayer.desktop.os.ResizeEdge
import com.tmplayer.desktop.os.WindowChromeMode
import com.tmplayer.desktop.os.WindowResize
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import java.awt.Cursor
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Toolkit
import kotlin.math.abs
import com.tmplayer.ui.components.tipFadeIn

/**
 * The window's own buttons, for a window whose title bar the app draws (see [WindowChromeMode]).
 * One per window, handed down through [LocalWindowChrome]; null (the tests, a desktop that draws
 * its own title bar) leaves every page as it was.
 *
 * [maximized] reads Compose state (the window's placement, or the shell's fullscreen on a tiling
 * window manager), so the middle button follows it.
 */
@Stable
class WindowChrome(
    val mode: WindowChromeMode,
    private val window: Frame?,
    val onMinimize: (() -> Unit)?,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit,
    private val maximized: () -> Boolean,
    /** A panel's own chrome: just a close button, and [onClose] shuts the panel, not the window. */
    val closeOnly: Boolean = false,
) {
    val isMaximized: Boolean get() = maximized()

    /** How many page headers carry the buttons now; with none, the window shows them on its own. */
    var headers by mutableIntStateOf(0)
        internal set

    /** How many full window covers (the loader, the setup, the proxy page) hide those headers now. */
    var covers by mutableIntStateOf(0)
        internal set

    /** The last floating bounds, for a maximized window dragged back to its size. */
    @Volatile
    private var floating: Bounds? = null

    init {
        window?.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = note()
            override fun componentMoved(e: java.awt.event.ComponentEvent) = note()
            private fun note() {
                val w = window
                if (w.extendedState and Frame.MAXIMIZED_BOTH == 0) floating = Bounds(w.x, w.y, w.width, w.height)
            }
        })
    }

    /** The empty part of a header moves the window, and a double click there maximizes it. */
    fun dragModifier(): Modifier {
        val frame = window ?: return Modifier
        if (mode != WindowChromeMode.Custom) return Modifier
        return Modifier.pointerInput(frame) { windowDrag(frame, { floating }, onToggleMaximize) }
    }
}

val LocalWindowChrome = compositionLocalOf<WindowChrome?> { null }

/** [LocalWindowChrome]'s drag, or nothing. */
@Composable
fun Modifier.windowDrag(): Modifier = LocalWindowChrome.current?.let { then(it.dragModifier()) } ?: this

/**
 * Minimize, maximize or restore, and close, in the Windows order, each the size of a header's
 * icon button. [onArt] is for the player's bar over the picture: white glyphs, a light hover.
 */
@Composable
fun WindowControls(modifier: Modifier = Modifier, onArt: Boolean = false) {
    val chrome = LocalWindowChrome.current ?: return
    val s = LocalStrings.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (chrome.closeOnly) {
            WindowButton(WindowGlyphs.Close, s.windowClose, onArt, danger = false, onClick = chrome.onClose)
            return@Row
        }
        chrome.onMinimize?.let { WindowButton(WindowGlyphs.Minimize, s.windowMinimize, onArt, danger = false, onClick = it) }
        val maximized = chrome.isMaximized
        WindowButton(
            if (maximized) WindowGlyphs.Restore else WindowGlyphs.Maximize,
            if (maximized) s.windowRestore else s.windowMaximize,
            onArt,
            danger = false,
            onClick = chrome.onToggleMaximize,
        )
        // Red under the pointer as Windows has it; a tiling window manager has no such habit.
        WindowButton(WindowGlyphs.Close, s.windowClose, onArt, danger = chrome.mode == WindowChromeMode.Custom, onClick = chrome.onClose)
    }
}

/** Counts a page header as carrying the buttons while it is composed. */
@Composable
internal fun RegisterWindowControls() {
    val chrome = LocalWindowChrome.current ?: return
    DisposableEffect(chrome) {
        chrome.headers++
        onDispose { chrome.headers-- }
    }
}

/**
 * Counts something drawn over the whole window, page headers and all, while it is composed: the
 * buttons then show on their own over it ([WindowControlsFallback]), since the header that carries
 * them is still composed underneath, out of sight.
 */
@Composable
internal fun CoverWindowControls() {
    val chrome = LocalWindowChrome.current ?: return
    DisposableEffect(chrome) {
        chrome.covers++
        onDispose { chrome.covers-- }
    }
}

/**
 * The buttons on their own, top right, for whatever is on screen without a page header (sign in,
 * the PIN pad, a page scrolled past its header) or over one ([CoverWindowControls]).
 */
@Composable
fun WindowControlsFallback(modifier: Modifier = Modifier) {
    val chrome = LocalWindowChrome.current ?: return
    if (chrome.headers > 0 && chrome.covers == 0) return
    Surface(
        modifier.padding(8.dp),
        shape = CircleShape,
        color = Tone.surface.copy(alpha = 0.92f),
    ) { WindowControls() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowButton(icon: ImageVector, label: String, onArt: Boolean, danger: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ink = if (onArt) Color.White else LocalContentColor.current
    val fill = when {
        !hovered -> Color.Transparent
        danger -> CLOSE_RED
        onArt -> Color(0x33FFFFFF)
        else -> ink.copy(alpha = 0.10f)
    }
    TooltipArea(
        tooltip = {
            Surface(modifier = Modifier.tipFadeIn(), shape = RoundedCornerShape(6.dp), color = Color(0xE6202022)) {
                Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 20.dp)),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(fill)
                .hoverable(hover)
                .clickable(onClick = onClick, onClickLabel = label)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (hovered && danger) Color.White else ink, modifier = Modifier.size(24.dp))
        }
    }
}

/** Windows' close button red. */
private val CLOSE_RED = Color(0xFFC42B1C)

/**
 * Moves [frame] with the pointer from a press on an empty spot (a press a button took is left
 * alone), and calls [onDoubleClick] on a second press in quick succession. A maximized window
 * comes back to its floating size under the pointer before it moves, as a native one does.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.windowDrag(frame: Frame, floating: () -> Bounds?, onDoubleClick: () -> Unit) {
    val doubleClickMs = (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500
    var lastPress = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        if (down.uptimeMillis - lastPress <= doubleClickMs) {
            lastPress = 0L
            down.consume()
            onDoubleClick()
            return@awaitEachGesture
        }
        lastPress = down.uptimeMillis
        var startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        var startWindow = frame.location
        var moving = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val now = MouseInfo.getPointerInfo()?.location ?: continue
            val dx = now.x - startPointer.x
            val dy = now.y - startPointer.y
            if (!moving) {
                if (abs(dx) < DRAG_SLOP && abs(dy) < DRAG_SLOP) continue
                moving = true
                lastPress = 0L
                if (frame.extendedState and Frame.MAXIMIZED_BOTH != 0) {
                    val grabX = startPointer.x - frame.x
                    val grabY = startPointer.y - frame.y
                    val maxWidth = frame.width
                    val size = floating() ?: Bounds(0, 0, frame.minimumSize.width, frame.minimumSize.height)
                    frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
                    val back = WindowResize.unmaximizeUnder(now.x, now.y, grabX, grabY, maxWidth, size.width, size.height)
                    frame.setBounds(back.x, back.y, back.width, back.height)
                    startPointer = now
                    startWindow = frame.location
                    change.consume()
                    continue
                }
            }
            frame.setLocation(startWindow.x + dx, startWindow.y + dy)
            change.consume()
        }
    }
}

private const val DRAG_SLOP = 4

/**
 * The invisible edges an undecorated window is resized by: [thickness] along each side and a
 * larger square at each corner, over everything else in the window, each with its own cursor.
 */
@Composable
fun ResizeEdges(frame: Frame, thickness: androidx.compose.ui.unit.Dp = 6.dp) {
    val corner = thickness * 2
    Box(Modifier.fillMaxSize()) {
        Edge(frame, ResizeEdge.Top, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(thickness))
        Edge(frame, ResizeEdge.Bottom, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(thickness))
        Edge(frame, ResizeEdge.Left, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(thickness))
        Edge(frame, ResizeEdge.Right, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(thickness))
        Edge(frame, ResizeEdge.TopLeft, Modifier.align(Alignment.TopStart).size(corner))
        Edge(frame, ResizeEdge.TopRight, Modifier.align(Alignment.TopEnd).size(corner))
        Edge(frame, ResizeEdge.BottomLeft, Modifier.align(Alignment.BottomStart).size(corner))
        Edge(frame, ResizeEdge.BottomRight, Modifier.align(Alignment.BottomEnd).size(corner))
    }
}

@Composable
private fun Edge(frame: Frame, edge: ResizeEdge, modifier: Modifier) {
    val cursor = remember(edge) { PointerIcon(Cursor(edge.cursor())) }
    Box(
        modifier
            .pointerHoverIcon(cursor, overrideDescendants = true)
            .pointerInput(frame, edge) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val start = Bounds(frame.x, frame.y, frame.width, frame.height)
                    val min = frame.minimumSize
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val now = MouseInfo.getPointerInfo()?.location ?: continue
                        val next = WindowResize.resize(start, edge, now.x - startPointer.x, now.y - startPointer.y, min.width, min.height)
                        frame.setBounds(next.x, next.y, next.width, next.height)
                        change.consume()
                    }
                }
            },
    )
}

private fun ResizeEdge.cursor(): Int = when (this) {
    ResizeEdge.Left -> Cursor.W_RESIZE_CURSOR
    ResizeEdge.Top -> Cursor.N_RESIZE_CURSOR
    ResizeEdge.Right -> Cursor.E_RESIZE_CURSOR
    ResizeEdge.Bottom -> Cursor.S_RESIZE_CURSOR
    ResizeEdge.TopLeft -> Cursor.NW_RESIZE_CURSOR
    ResizeEdge.TopRight -> Cursor.NE_RESIZE_CURSOR
    ResizeEdge.BottomLeft -> Cursor.SW_RESIZE_CURSOR
    ResizeEdge.BottomRight -> Cursor.SE_RESIZE_CURSOR
}
