package com.tmplayer.ui.settings

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.delay

/**
 * "Keep this size?" (WP-10): the question after a change that can leave the screen unreadable. The
 * change is already applied; Undo (first focus, and what Back does) puts the old value back, Keep
 * keeps it, and when [KEEP_CHANGE_SECONDS] have passed since [startedAt] it is Undo. [startedAt] is a
 * `SystemClock.elapsedRealtime()` reading, so a rotation does not restart the countdown. On a phone
 * the same question is a dialog ([TvConfirm]).
 */
@Composable
internal fun KeepChangePrompt(
    startedAt: Long,
    title: String,
    body: (seconds: Int) -> String,
    keepLabel: String,
    undoLabel: String,
    onKeep: () -> Unit,
    onUndo: () -> Unit,
) {
    var left by remember { mutableIntStateOf(secondsLeft(startedAt)) }
    val undo by rememberUpdatedState(onUndo)
    LaunchedEffect(startedAt) {
        while (true) {
            left = secondsLeft(startedAt)
            if (left <= 0) {
                undo()
                return@LaunchedEffect
            }
            delay(TICK_MS)
        }
    }
    TvConfirm(
        title = title,
        message = body(left.coerceAtLeast(0)),
        confirmLabel = keepLabel,
        cancelLabel = undoLabel,
        destructive = false,
        icon = TmIcons.Refresh,
        // Back is Undo: the safe answer, and the first focus.
        onDismiss = onUndo,
        onConfirm = onKeep,
    )
}

/** How long the question waits before it answers Undo itself. */
internal const val KEEP_CHANGE_SECONDS = 10

private const val TICK_MS = 250L

private fun secondsLeft(startedAt: Long): Int {
    val elapsed = SystemClock.elapsedRealtime() - startedAt
    return KEEP_CHANGE_SECONDS - (elapsed / 1_000L).toInt()
}

/** Whether this process has already put back an interface size left unanswered by a dead one (WP-10). */
internal object TentativeSizeGuard {
    @Volatile var checked = false
}
