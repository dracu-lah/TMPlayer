package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.data.secondsLeft
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * The one status the television shows at a time, in the top bar's free space (the shell's foot where no bar is
 * shown): the words that fit that slot whole ([words], never cut by an ellipsis), the long sentence for a screen
 * reader and for Settings ([description]), and whether the small spinner shows. Built by [TvStatusRules.pick].
 */
@Immutable
data class TvStatusSay(
    val kind: Kind,
    val words: String,
    val description: String,
    val spinner: Boolean,
) {
    /** In the order of the slot's priority, first wins. */
    enum class Kind { NeedsYou, SyncStopped, Offline, Connection, RateLimited, Updating }

    /** The offline card says offline (with Try again) where it stands; the slot draws nothing and holds every lower status back. */
    val drawn: Boolean get() = words.isNotEmpty()

    companion object {
        val OfflineBanner = TvStatusSay(Kind.Offline, "", "", spinner = false)
    }
}

/** Everything the slot reads, as plain values: the shell's line and sync, the page's work, the connection. */
@Immutable
data class TvStatusInput(
    val line: GlobalLine = GlobalLine.Idle,
    val sync: SyncStatus? = null,
    val work: PageWork = PageWork(WorkStatus.Idle, false),
    val connection: ConnectionNotice = ConnectionNotice.Hidden,
    /**
     * Whether a standing sync status (needs you, stopped) may be drawn now: false once its [TvStatusRules.STANDING_MS]
     * have passed, and on every page that must never show it (the player, Details, Search, Downloads, Chats). The
     * sync then lives in the avatar's dot and the account sheet's row. Transient statuses ignore it.
     */
    val standing: Boolean = true,
)

/** The television's status words and their priority, pure so every kind is tested for its text and its fit. */
object TvStatusRules {

    /** A wait shorter than this is said in seconds, past it in minutes, past [HOURS_FROM_SECONDS] in hours. */
    const val MINUTES_FROM_SECONDS = 120
    const val HOURS_FROM_SECONDS = 5_940

    /**
     * How long a standing sync status (needs you, stopped) stays in the bar after the app opens, after the sync's state
     * changes and after Settings is opened: then it folds into the avatar's dot and the account sheet's row.
     */
    const val STANDING_MS = 8_000L

    /**
     * What the slot says, by priority: the sync that needs the viewer, the sync that stopped, offline,
     * the line to Telegram coming back, a wait Telegram asked for, then work (updating, loading, up to date,
     * a failure the viewer asked to see). [work] is a status already run through the timing ladder and
     * filtered for what a pill says ([TvStatusRules.workShown]); [now] ticks a wait's seconds.
     */
    fun pick(m: Messages, input: TvStatusInput, work: ShownWork, now: Long, failureWords: String? = null): TvStatusSay? {
        val line = input.line
        val rowName = m.syncplaceSwitch
        if (line is GlobalLine.SyncNeedsYou && input.standing) {
            return if (line.kind == SyncStatus.Kind.Failed) {
                TvStatusSay(TvStatusSay.Kind.SyncStopped, m.stateSyncStoppedShortTv, m.stateSyncStoppedTv(rowName), spinner = false)
            } else {
                TvStatusSay(TvStatusSay.Kind.NeedsYou, m.stateSyncNeedsYouShortTv, m.stateSyncNeedsYouTv(rowName), spinner = false)
            }
        }
        if (input.connection == ConnectionNotice.Offline) return TvStatusSay.OfflineBanner
        when (input.connection) {
            ConnectionNotice.Reconnecting ->
                return TvStatusSay(TvStatusSay.Kind.Connection, m.stateReconnectingTv, m.stateReconnecting, spinner = true)
            ConnectionNotice.Connecting ->
                return TvStatusSay(TvStatusSay.Kind.Connection, m.stateConnectingTv, m.stateConnectingShort, spinner = true)
            else -> Unit
        }
        val status = work.status
        val seconds = when {
            line is GlobalLine.RateLimited -> line.seconds
            status is WorkStatus.RateLimited -> status.secondsLeft(now)
            else -> 0
        }
        if (seconds > 0) {
            return TvStatusSay(TvStatusSay.Kind.RateLimited, waitWords(m, seconds), WorkStatusWords.rateLimited(seconds), spinner = true)
        }
        when (line) {
            GlobalLine.Updating -> return TvStatusSay(TvStatusSay.Kind.Updating, m.stateUpdatingTv, m.stateUpdating, spinner = true)
            GlobalLine.SlowAnswer -> return TvStatusSay(TvStatusSay.Kind.Updating, m.stateSlowTv, m.commonSlowAnswer, spinner = true)
            else -> Unit
        }
        if (!work.visible) return null
        val said = WorkStatusWords.line(status, now)
        return when {
            work.slow && (status is WorkStatus.Loading || status is WorkStatus.Syncing) ->
                TvStatusSay(TvStatusSay.Kind.Updating, m.stateSlowTv, m.commonSlowAnswer, spinner = true)
            status is WorkStatus.Loading -> TvStatusSay(TvStatusSay.Kind.Updating, m.stateLoadingTv, said, spinner = true)
            status is WorkStatus.Syncing -> TvStatusSay(TvStatusSay.Kind.Updating, m.stateUpdatingTv, said, spinner = true)
            status is WorkStatus.Updated -> TvStatusSay(TvStatusSay.Kind.Updating, m.stateUpdated, said, spinner = false)
            status is WorkStatus.Failed -> TvStatusSay(TvStatusSay.Kind.Updating, m.stateFailedTv, failureWords ?: said, spinner = false)
            else -> null
        }
    }

    /**
     * The sentence of a standing sync status for the account sheet's row ("Sync stopped. Turn it on again in Settings: Sync
     * across my devices."), or null when [line] is no standing sync status.
     */
    fun standingSentence(m: Messages, line: GlobalLine): String? {
        if (line !is GlobalLine.SyncNeedsYou) return null
        val rowName = m.syncplaceSwitch
        return if (line.kind == SyncStatus.Kind.Failed) m.stateSyncStoppedTv(rowName) else m.stateSyncNeedsYouTv(rowName)
    }

    /** A wait past this many hours is just "Retry later": the words stay short whatever Telegram asks. */
    const val MAX_HOURS = 99

    /** "Retry in 42s", "Retry in 5m", "Retry in 3h": the shortest wording of a wait that is true, at most 13 characters (99m is the longest minutes, so a worst case still fits a large font). */
    fun waitWords(m: Messages, seconds: Int): String {
        val left = seconds.coerceAtLeast(1)
        return when {
            left < MINUTES_FROM_SECONDS -> m.stateRateLimitedTv(seconds = left)
            left < HOURS_FROM_SECONDS -> m.stateRateLimitedMinutesTv(minutes = (left + 59) / 60)
            else -> {
                val hours = (left.toLong() + 3599) / 3600
                if (hours > MAX_HOURS) m.stateRateLimitedLaterTv else m.stateRateLimitedHoursTv(hours = hours.toInt())
            }
        }
    }

    /**
     * [shown] as the pill says it: only once its words are due, and a failure only when the viewer asked for it
     * and not after [FAILED_PILL_MS] ([failureOver]); a partial answer stays the page foot's line.
     */
    fun workShown(shown: ShownWork, asked: Boolean, failureOver: Boolean, now: Long): ShownWork {
        val status = shown.status
        if (!shown.visible || !shown.labelled) return ShownWork.None
        if (status is WorkStatus.RateLimited && status.secondsLeft(now) == 0) return ShownWork.None
        if (status is WorkStatus.Partial) return ShownWork.None
        if (status is WorkStatus.Failed && (!asked || failureOver)) return ShownWork.None
        return shown
    }
}

/** The smallest the text is drawn on a television (a 10-foot read). */
private val TV_PILL_TEXT = 16.sp

/**
 * The television's one status pill ([TvStatusRules.pick]): a small spinner where work runs and a few words that fit
 * the slot whole. Never takes focus and holds no button; a screen reader gets the long sentence. If the words do
 * not fit the room the host gives (a very large font), it shows the first word and never an ellipsis mid sentence.
 * The top bar places it between the last tab and the app's mark; where no bar is shown the shell puts it in the
 * foot's safe area.
 */
@Composable
fun TvStatusPill(input: TvStatusInput, modifier: Modifier = Modifier) {
    val m = LocalStrings.current
    val reduced = rememberLessMotion()
    val shownWork = rememberWorkStatusTimer(input.work.status, input.work.asked)
    val status = shownWork.status
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One effect, only while a wait ticks; it ends with the wait.
    LaunchedEffect(status) {
        if (status !is WorkStatus.RateLimited) return@LaunchedEffect
        while (status.secondsLeft(now) > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val askedFailure = input.work.asked && status is WorkStatus.Failed
    var failureOver by remember(status) { mutableStateOf(false) }
    LaunchedEffect(status, askedFailure) {
        if (!askedFailure) return@LaunchedEffect
        delay(FAILED_PILL_MS)
        failureOver = true
    }
    val work = TvStatusRules.workShown(shownWork, input.work.asked, failureOver, now)
    val say = TvStatusRules.pick(m, input, work, now, failureWords = (status as? WorkStatus.Failed)?.message?.takeIf { it.isNotBlank() })
    // Kept for the fade out, after the status itself has gone.
    val kept = remember { arrayOf<TvStatusSay?>(null) }
    if (say != null && say.drawn) kept[0] = say
    val fade = if (reduced) 0 else 150
    AnimatedVisibility(
        visible = say != null && say.drawn,
        modifier = modifier.focusProperties { canFocus = false },
        enter = fadeIn(tween(fade)),
        exit = fadeOut(tween(fade)),
    ) {
        val shown = say?.takeIf { it.drawn } ?: kept[0] ?: return@AnimatedVisibility
        // Heard when it appears; a count down is not read every second.
        val live = if (shown.kind == TvStatusSay.Kind.RateLimited) Modifier else Modifier.liveMessage()
        Row(
            Modifier
                .widthIn(max = TV_STATUS_MAX_WIDTH)
                .then(live)
                .background(Tone.surfaceHigh.copy(alpha = 0.96f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown.spinner) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Tone.accent,
                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
            // Never cut mid sentence: when the room is too small the first word stands alone.
            var tight by remember(shown.kind, shown.words) { mutableStateOf(false) }
            Text(
                text = if (tight) shown.words.substringBefore(' ') else shown.words,
                style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = if (it.fontSize.value >= TV_PILL_TEXT.value) it.fontSize else TV_PILL_TEXT) },
                color = Tone.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { if (it.hasVisualOverflow) tight = true },
                modifier = Modifier
                    .weight(1f, fill = false)
                    // The whole sentence for a screen reader, whatever the line shows of it.
                    .semantics { contentDescription = shown.description },
            )
        }
    }
}

/**
 * Keeps [standing] true for [holdMs] each time [key] or [visits] changes, then false: the sync status that stands
 * (stopped, needs you) is on show for its first seconds when the app opens (the first composition), when its state
 * changes ([key]) and when Settings is opened ([visits] goes up), then it is the avatar's dot and the account sheet's
 * row. A null [key] (nothing stands) or no television clears it at once. One effect, ended by the wait.
 */
@Composable
fun TvSyncStandingEffect(
    key: Any?,
    visits: Int,
    tv: Boolean,
    standing: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
    holdMs: Long = TvStatusRules.STANDING_MS,
) {
    LaunchedEffect(key, visits, tv) {
        if (!tv || key == null) {
            standing.value = false
            return@LaunchedEffect
        }
        standing.value = true
        delay(holdMs)
        standing.value = false
    }
}
