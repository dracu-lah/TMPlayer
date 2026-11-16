package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.tmplayer.ui.components.rowAfterRemoving

/**
 * Rule 11 for a list the keyboard walks: when the row that holds focus is taken out (deleted,
 * moved, its record gone), focus goes to the row after it, else the one before, rather than
 * falling to the window. The rule itself is [rowAfterRemoving], shared with the television's
 * Downloads page.
 *
 * Every row wears [handoffRow] with its key; the page calls [rowsChanged] with the keys it now
 * lists, in order. A row taken out loses focus and leaves the list in either order, a moment
 * apart, so the two are matched within [WINDOW_NANOS] rather than by which comes first.
 */
@Stable
class RowFocusHandoff {
    /** The row focus is asked to land on once it is composed. */
    internal var landOn by mutableStateOf<String?>(null)

    /** The order before and after the last change, and what left between them. */
    private var order: List<String> = emptyList()
    private var before: List<String> = emptyList()
    private var now: Set<String> = emptySet()
    private var removed: Set<String> = emptySet()
    private var removedAt = Long.MIN_VALUE

    /** The row holding focus, or the one that lost it last, and when it lost it. */
    private var focused: String? = null
    private var lostAt = Long.MIN_VALUE

    internal fun gained(key: String) {
        focused = key
        lostAt = Long.MIN_VALUE
    }

    internal fun lost(key: String) {
        if (focused != key) return
        lostAt = System.nanoTime()
        check()
    }

    /** The page lists [rows] (in order). */
    fun rowsChanged(rows: List<String>) {
        before = order
        order = rows
        now = rows.toSet()
        removed = before.filterTo(HashSet()) { it !in now }
        removedAt = System.nanoTime()
        check()
    }

    private fun check() {
        val key = focused ?: return
        val at = System.nanoTime()
        val held = lostAt == Long.MIN_VALUE || at - lostAt < WINDOW_NANOS
        if (at - removedAt >= WINDOW_NANOS || !held || key !in removed) return
        removed = emptySet()
        focused = null
        landOn = rowAfterRemoving(before, key, now)
    }
}

/** This row's key, for [RowFocusHandoff]: it asks for focus when it is the one focus lands on. */
@Composable
internal fun Modifier.handoffRow(handoff: RowFocusHandoff, key: String): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(handoff.landOn) {
        if (handoff.landOn == key) {
            runCatching { requester.requestFocus() }
            handoff.landOn = null
        }
    }
    return this.focusRequester(requester).onFocusChanged { if (it.hasFocus) handoff.gained(key) else handoff.lost(key) }
}

private const val WINDOW_NANOS = 1_000_000_000L
