package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
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
import com.tmplayer.i18n.L
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
    /** False while there is no line to Telegram: a press then says so at once (F-18). */
    private val connected: () -> Boolean = { true },
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
        if (!connected()) {
            // Known at once: say it, rather than Updating for a minute and then nothing. The refresh
            // that runs when the connection is back is the page's own.
            status = WorkStatus.Failed(L.stateOfflineRefresh)
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
        status = if (done) WorkStatus.Updated(clock()) else WorkStatus.Failed(L.stateNoAnswer, keptAt = clock())
        round++
    }

    /** A wait that is over leaves the line. */
    internal fun clearWait() {
        if (status is WorkStatus.RateLimited) {
            status = WorkStatus.Idle
            round++
        }
    }

    /** True for the offline answer of [ask]: a failure with nothing kept, which leaves by itself. */
    internal fun offlineNote(): Boolean = (status as? WorkStatus.Failed)?.let { it.keptAt == null } == true

    /** The offline answer has been said long enough. */
    internal fun clearOfflineNote() {
        if (offlineNote() && !running) {
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

        /** How long "You are offline" stays after a press made while offline. */
        const val OFFLINE_NOTE_MS = 8_000L
    }
}

/**
 * An [AskedRefresh] for a page whose refresh sets [busy] while it fetches (Home's `refreshing`, the
 * chat list's). One effect ends each run: it waits a beat for [busy] to rise, then for it to fall.
 */
@Composable
fun rememberAskedRefresh(busy: Boolean): AskedRefresh {
    val asked = remember { AskedRefresh(connected = { Td.connected.value }) }
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
            asked.offlineNote() -> {
                delay(AskedRefresh.OFFLINE_NOTE_MS)
                asked.clearOfflineNote()
            }
        }
    }
    return asked
}

/** What a page's status line or pill shows ([pageWork]), and whether the viewer asked for it (the ladder's `asked`). */
@androidx.compose.runtime.Immutable
data class PageWork(val status: WorkStatus, val asked: Boolean)

/**
 * One status for a page from its asked refresh ([AskedRefresh.status]) and what its view model says
 * of its own work ([page]: the chat list's `listStatus`, Home's `status`), for WP-93. The asked
 * refresh speaks while it runs or holds off for Telegram's wait; then a failure or a partial answer
 * of the page beats its "Up to date" (a refresh that ended badly is never called up to date), and
 * the page's own automatic Syncing shows only once the asked "Up to date" has had its two seconds.
 * A time box failure of the asked refresh stands only while the page still has work running.
 */
fun pageWork(asked: WorkStatus, page: WorkStatus, now: Long): PageWork = when {
    asked is WorkStatus.Syncing || asked is WorkStatus.RateLimited -> PageWork(asked, true)
    // A failure that comes as the viewer's refresh ends is theirs to be told of (F-02); one that comes by itself is not.
    page is WorkStatus.Failed || page is WorkStatus.Partial || page is WorkStatus.RateLimited ->
        PageWork(page, asked is WorkStatus.Updated && now - asked.at in 0 until FAILED_PILL_MS)
    asked is WorkStatus.Updated && now - asked.at in 0 until WorkLadder.UPDATED_FOR_MS -> PageWork(asked, true)
    // The time box failure stands only while the page still works; the offline answer (nothing kept) stands by itself.
    asked is WorkStatus.Failed && (page != WorkStatus.Idle || asked.keptAt == null) -> PageWork(asked, true)
    page is WorkStatus.Syncing -> PageWork(page, false)
    else -> PageWork(WorkStatus.Idle, false)
}

/**
 * The pill's status while the host's connection pill (Connecting, Offline) is up: nothing, since both
 * sit in the one foot slot of a television and the connection's is the one the viewer needs (F-03).
 */
fun PageWork.unlessConnectionShown(connectionShown: Boolean): PageWork =
    if (connectionShown) PageWork(WorkStatus.Idle, false) else this

/** [status] with only what a page must still say after the work: a failure or Telegram's wait; the rest is Idle. */
fun WorkStatus.failureOnly(): WorkStatus =
    if (this is WorkStatus.Failed || this is WorkStatus.RateLimited) this else WorkStatus.Idle

/**
 * The asked refresh's line on a phone or a desktop window, in a container of its own so it reads
 * over a carousel or a list, drawn only once the ladder says it is due (an answer in under 400 ms
 * shows nothing, and no empty container flashes). A page places it over its top or foot, where it
 * never pushes a row; Try again runs [onRetry], which is the same guarded refresh.
 *
 * "Up to date" is a compact status badge, not a bar: the Success look of the toast family (a tinted
 * wash, a 1 dp border, a small tick that draws itself, still under less motion), as wide as its
 * words and at most [ToastLayout.BadgeMax], one line, shown [WorkLadder.UPDATED_FOR_MS] and gone
 * with a 120 ms fade. Updating is as wide as its words too; only a failure, which carries buttons,
 * takes the room the page gives it.
 */
@Composable
fun AskedRefreshLine(
    status: WorkStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sendLogs: (@Composable () -> Unit)? = null,
) {
    val shown = rememberWorkStatusTimer(status, asked = true)
    val badge = status is WorkStatus.Updated
    val still = rememberLessMotion()
    // Kept composed while it fades, so the badge leaves with the 120 ms fade rather than blinking out.
    AnimatedVisibility(
        visible = badge && shown.visible,
        modifier = modifier,
        enter = ToastMotion.enter(still),
        exit = ToastMotion.exit(),
    ) {
        UpToDateBadge(animateIcon = !still)
    }
    if (badge || !shown.visible) return
    val compact = status !is WorkStatus.Failed && status !is WorkStatus.Partial
    WorkStatusLine(
        shown = shown,
        modifier = modifier
            .then(if (compact) Modifier.widthIn(max = ToastLayout.BadgeMax) else Modifier)
            .background(Tone.surfaceHigh, RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp),
        asked = true,
        onRetry = onRetry,
        sendLogs = sendLogs,
        fillWidth = !compact,
    )
}

/** The Success badge "Up to date": one line, [ToastLayout.BadgeMax] wide at most, the words ellipsize past it. */
@Composable
fun UpToDateBadge(modifier: Modifier = Modifier, animateIcon: Boolean = true) {
    val words = L.stateUpdated
    ToastCard(
        kind = ToastKind.Success,
        message = words,
        modifier = modifier.widthIn(max = ToastLayout.BadgeMax),
        animateIcon = animateIcon,
        compact = true,
    )
}
