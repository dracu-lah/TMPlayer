package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * The pointer in the big-screen mode (plan 8.7): hidden after it has rested [CURSOR_IDLE_MS], and
 * at once when a key is pressed, since a key there is the remote; shown again the moment the
 * mouse (an air mouse, a phone's touchpad app) moves.
 *
 * Nothing ticks while it rests: once hidden the wait is on a channel the next movement fills, and
 * while the pointer moves it wakes once per [CURSOR_IDLE_MS] at most.
 */
@Stable
class CursorIdle(private val idleMs: Long = CURSOR_IDLE_MS, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var hidden by mutableStateOf(false)
        private set

    private var lastMove = now()
    private var lastAt: Offset? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The pointer at [at]. Compose also reports a "move" when the page scrolls under a still
     * pointer, to update what it hovers; that is not the viewer, so only a new place counts.
     */
    fun moved(at: Offset) {
        if (at == lastAt) return
        lastAt = at
        lastMove = now()
        if (hidden) hidden = false
        wake.trySend(Unit)
    }

    /** A key: the remote is in use, so the pointer goes now rather than in a few seconds. */
    fun keyed() {
        hidden = true
    }

    suspend fun run() {
        while (true) {
            if (hidden) {
                wake.receive()
                continue
            }
            val left = idleMs - (now() - lastMove)
            if (left > 0) delay(left) else hidden = true
        }
    }
}

/** The blank cursor the player hides the pointer with too, one transparent pixel. */
private val BlankCursor: PointerIcon by lazy {
    PointerIcon(
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            java.awt.Point(0, 0),
            "tmplayer-hidden", // i18n-ok: an AWT cursor's internal name
        ),
    )
}

/**
 * [CursorIdle] over the window's content while [enabled]. The icon modifier is always there, so
 * changing it reaches a pointer that has not moved; while shown it leaves every control its own.
 */
@Composable
fun Modifier.idleCursor(enabled: Boolean): Modifier {
    val idle = remember { CursorIdle() }
    LaunchedEffect(idle, enabled) { if (enabled) idle.run() }
    val hide = enabled && idle.hidden
    return this
        .then(
            if (!enabled) {
                Modifier
            } else {
                Modifier
                    .pointerInput(idle) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Move || event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                                    event.changes.firstOrNull()?.let { idle.moved(it.position) }
                                }
                            }
                        }
                    }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown) idle.keyed()
                        false
                    }
            },
        )
        .pointerHoverIcon(if (hide) BlankCursor else PointerIcon.Default, overrideDescendants = hide)
}
