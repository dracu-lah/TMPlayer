package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Tone
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.data.Td
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.secondsLeft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Refresh the viewer asked for (WP-40), for Home and the chat list on every platform: the pull
 * of a phone, the account menu's row, F5 and Ctrl+R, the TV remote's menu and the desktop's window.
 * All of them go through [ask], so a refresh that is already running is not started twice, and all
 * of them read the same [status] for the status line ([WorkStatusLine], [WorkStatusPill]): Syncing
 * while it runs, "Up to date" for two seconds when it ends, or Telegram's wait with its seconds
 * when a flood wait is on (the refresh then holds off instead of making the wait longer).
 *
 * The ladder in [rememberWorkStatusTimer] decides when the words show (the indicator from 400 ms,
 * the words from 1 s), so a refresh that answers at once shows nothing, and a time box ends every
 * run: nothing spins for ever.
 */
@Stable
class AskedRefresh(
    private val floodWait: () -> Int = { Td.floodWaitRemainingSeconds() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** What the status line shows for this refresh; [WorkStatus.Idle] while none was asked. */
    var status: WorkStatus by mutableStateOf(WorkStatus.Idle)
        private set

    /** True from the press until the refresh ended; a press while true is ignored. */
    var running: Boolean by mutableStateOf(false)
        private set

    /** Counts the presses that changed [status], so the one effect that ends a run restarts for each. */
    internal var round: Int by mutableIntStateOf(0)
        private set

    /**
     * Starts [start] unless a refresh is running or Telegram asked to wait. Returns whether it
     * started. A flood wait is shown as the countdown and [start] is not called: Telegram answers a
     * request made during a wait by extending it.
     */
    fun ask(start: () -> Unit): Boolean {
        if (running) return false
        val wait = floodWait()
        if (wait > 0) {
            status = WorkStatus.RateLimited(clock() + wait * 1000L)
            round++
            return false
        }
        running = true
        status = WorkStatus.Syncing("")
        round++
        start()
        return true
    }

    /** The run ended: [done] false when the time box ran out. */
    internal fun finish(done: Boolean) {
        running = false
        status = if (done) WorkStatus.Updated(clock()) else WorkStatus.Failed("", keptAt = clock())
        round++
    }

    /** A wait that is over leaves the line. */
    internal fun clearWait() {
        if (status is WorkStatus.RateLimited) {
            status = WorkStatus.Idle
            round++
        }
    }

    internal fun waitLeftMs(): Long {
        val wait = status as? WorkStatus.RateLimited ?: return 0L
        return wait.secondsLeft(clock()) * 1000L
    }

    companion object {
        /** How long the work gets to show it is busy before it counts as done (a refresh that answers from the cache). */
        const val ACK_MS = 400L

        /** The longest a refresh may say Syncing; past it the line says it could not update, with Try again. */
        const val TIME_BOX_MS = 60_000L
    }
}

/**
 * An [AskedRefresh] for a page whose refresh sets [busy] while it fetches (Home's `refreshing`, the
 * chat list's). One effect ends each run: it waits a beat for [busy] to rise, then for it to fall.
 */
@Composable
fun rememberAskedRefresh(busy: Boolean): AskedRefresh {
    val asked = remember { AskedRefresh() }
    val busyNow by rememberUpdatedState(busy)
    LaunchedEffect(asked.round) {
        when {
            asked.running -> {
                delay(AskedRefresh.ACK_MS)
                val done = withTimeoutOrNull(AskedRefresh.TIME_BOX_MS) { snapshotFlow { busyNow }.first { !it } } != null
                asked.finish(done)
            }
            asked.status is WorkStatus.RateLimited -> {
                delay(asked.waitLeftMs().coerceAtLeast(1L))
                asked.clearWait()
            }
        }
    }
    return asked
}

/**
 * The asked refresh's line on a phone or a desktop window, in a container of its own so it reads
 * over a carousel or a list, drawn only once the ladder says it is due (an answer in under 400 ms
 * shows nothing, and no empty container flashes). A page places it over its top or foot, where it
 * never pushes a row; Try again runs [onRetry], which is the same guarded refresh.
 */
@Composable
fun AskedRefreshLine(
    status: WorkStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sendLogs: (@Composable () -> Unit)? = null,
) {
    val shown = rememberWorkStatusTimer(status, asked = true)
    if (!shown.visible) return
    WorkStatusLine(
        shown = shown,
        modifier = modifier.background(Tone.surfaceHigh, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp),
        asked = true,
        onRetry = onRetry,
        sendLogs = sendLogs,
    )
}
