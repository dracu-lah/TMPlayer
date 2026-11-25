package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.GlobalStatusRules
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.SyncWords
import com.tmplayer.data.TelegramLink
import com.tmplayer.data.Td
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * The corner's line for what the line to Telegram is doing beyond offline and connecting (WP-100),
 * decided by [GlobalStatusRules] from the link and the flood wait that [Td] keeps, and the last
 * sync's kind. One effect: it looks again each second only while a count down or a catch up needs
 * it, and ends when nothing does, so an idle app spends nothing here. [active] false (signed out,
 * a profile locked, a connection state already shown) is [GlobalLine.Idle].
 */
@Composable
fun rememberGlobalLine(
    active: Boolean,
    syncKind: SyncStatus.Kind?,
    syncDismissed: Boolean,
    /** The sync gave up after three failed tries ([com.tmplayer.data.SyncStreak.stopped]). */
    syncStopped: Boolean = false,
): GlobalLine {
    val link by Td.link.collectAsState()
    val waitUntil by Td.floodWaitUntil.collectAsState()
    var line by remember { mutableStateOf<GlobalLine>(GlobalLine.Idle) }
    LaunchedEffect(active, link, waitUntil, syncKind, syncDismissed, syncStopped) {
        if (!active) {
            line = GlobalLine.Idle
            return@LaunchedEffect
        }
        val updatingSince = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val waitSeconds = ((waitUntil - now + 999) / 1000).toInt().coerceAtLeast(0)
            val next = GlobalStatusRules.decide(
                link = link,
                updatingForMs = if (link == TelegramLink.Updating) now - updatingSince else 0L,
                waitSeconds = waitSeconds,
                sync = syncKind,
                syncDismissed = syncDismissed,
                syncStopped = syncStopped,
            )
            line = next
            if (!GlobalStatusRules.ticks(next, link)) return@LaunchedEffect
            delay(1_000)
        }
    }
    return line
}

/**
 * The widest the status line gets on a television: one line, ellipsized, in the free space of the top bar
 * between the last tab and the app's mark (the bar's own layout gives it whatever is left, never more than this).
 */
val TV_STATUS_MAX_WIDTH = 560.dp

/** The smallest the status line's text is drawn on a television (a 10-foot read). */
private val TV_STATUS_TEXT = 16.sp

/**
 * The status pill for a [GlobalLine]: a spinner and a few words, in the snackbar's shape on a
 * phone and a pill on a television and a desktop. Never takes focus on a television: it says the
 * words only, and what it points to (Retry, Settings) is reached through the usual ways. On a
 * television it is one line of at most [TV_STATUS_MAX_WIDTH], ellipsized, which the top bar places in
 * its free space (never over a page); the long sentence is its description for a screen reader. Where a
 * pointer or a finger is the input, Retry and Open settings are buttons, and the sync line can be
 * closed ([onDismiss]). Draws nothing for [GlobalLine.Idle].
 */
@Composable
fun GlobalStatusPill(
    line: GlobalLine,
    modifier: Modifier = Modifier,
    syncStatus: SyncStatus? = null,
    onRetry: (() -> Unit)? = null,
    /** "Try again" on a sync that stopped after three failed tries: one more run, asked for by the viewer. */
    onTryAgainSync: (() -> Unit)? = null,
    /** "Send logs" beside it, where the platform can. */
    onSendLogs: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    // Kept for the fade out, after the line itself is gone.
    var last by remember { mutableStateOf<GlobalLine>(GlobalLine.Idle) }
    if (line != GlobalLine.Idle) last = line
    AnimatedVisibility(visible = line != GlobalLine.Idle, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val touch = isTouch()
        val container = if (touch) SnackbarDefaults.color else Tone.surfaceHigh.copy(alpha = 0.96f)
        val onContainer = if (touch) SnackbarDefaults.contentColor else Tone.text
        val shown = if (line != GlobalLine.Idle) line else last
        val said = when (shown) {
            GlobalLine.Idle -> ""
            GlobalLine.Updating -> s.stateUpdating
            GlobalLine.SlowAnswer -> s.commonSlowAnswer
            is GlobalLine.RateLimited -> s.stateRateLimited(shown.seconds)
            is GlobalLine.SyncNeedsYou ->
                if (shown.kind == SyncStatus.Kind.Failed) {
                    SyncWords.stopped(syncStatus ?: SyncStatus(shown.kind, 0L))
                } else {
                    SyncWords.status(syncStatus?.takeIf { it.kind == shown.kind } ?: SyncStatus(shown.kind, 0L), 0L, false)
                }
        }
        // A remote has no button to press here, so the words say where the way out is (WP-100). The row is named
        // with the string that titles it in Settings on every platform.
        val rowName = s.syncplaceSwitch
        val full = if (!touch && shown is GlobalLine.SyncNeedsYou) said + " " + s.stateSyncOpenSettings(rowName) else said
        // One line on a television: the short wording for a sync that needs the viewer, the long one read by a screen reader.
        val words = when {
            touch || shown !is GlobalLine.SyncNeedsYou -> full
            shown.kind == SyncStatus.Kind.Failed -> s.stateSyncStoppedTv(rowName)
            else -> s.stateSyncNeedsYouTv(rowName)
        }
        Row(
            // Heard when it appears. A count down changes every second and is not read each time.
            Modifier
                .then(if (touch) Modifier else Modifier.widthIn(max = TV_STATUS_MAX_WIDTH))
                .liveMessage(on = shown !is GlobalLine.RateLimited)
                .background(container, if (touch) MaterialTheme.shapes.extraSmall else CircleShape)
                .padding(horizontal = if (touch) 12.dp else 16.dp, vertical = if (touch) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown !is GlobalLine.SyncNeedsYou) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = onContainer)
            Text(
                text = words,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = if (it.fontSize.value >= TV_STATUS_TEXT.value) it.fontSize else TV_STATUS_TEXT) },
                color = onContainer,
                maxLines = if (touch) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    // The whole sentence for a screen reader, whatever the line shows of it.
                    .then(if (touch || full == words) Modifier else Modifier.semantics { contentDescription = full }),
            )
            if (touch) {
                when (shown) {
                    GlobalLine.SlowAnswer -> onRetry?.let { retry -> TextButton(onClick = retry) { Text(s.commonRetry) } }
                    is GlobalLine.SyncNeedsYou -> {
                        if (shown.kind == SyncStatus.Kind.Failed) {
                            // Stopped after three tries: the way out is another try or the logs, not Settings.
                            onTryAgainSync?.let { again -> TextButton(onClick = again) { Text(s.commonTryAgain) } }
                            onSendLogs?.let { logs -> TextButton(onClick = logs) { Text(s.logsSend) } }
                        } else {
                            onOpenSettings?.let { open -> TextButton(onClick = open) { Text(s.notifyOpenSettings) } }
                        }
                        onDismiss?.let { close -> TextButton(onClick = close) { Text(s.commonClose) } }
                    }
                    else -> Unit
                }
            }
        }
    }
}
