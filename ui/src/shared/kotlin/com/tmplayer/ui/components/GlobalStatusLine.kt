package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
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
fun rememberGlobalLine(active: Boolean, syncKind: SyncStatus.Kind?, syncDismissed: Boolean): GlobalLine {
    val link by Td.link.collectAsState()
    val waitUntil by Td.floodWaitUntil.collectAsState()
    var line by remember { mutableStateOf<GlobalLine>(GlobalLine.Idle) }
    LaunchedEffect(active, link, waitUntil, syncKind, syncDismissed) {
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
            )
            line = next
            if (!GlobalStatusRules.ticks(next, link)) return@LaunchedEffect
            delay(1_000)
        }
    }
    return line
}

/**
 * The corner's pill for a [GlobalLine]: a spinner and a few words, in the snackbar's shape on a
 * phone and a pill on a television and a desktop. Never takes focus on a television: it says the
 * words only, and what it points to (Retry, Settings) is reached through the usual ways. Where a
 * pointer or a finger is the input, Retry and Open settings are buttons, and the sync line can be
 * closed ([onDismiss]). Draws nothing for [GlobalLine.Idle].
 */
@Composable
fun GlobalStatusPill(
    line: GlobalLine,
    modifier: Modifier = Modifier,
    syncStatus: SyncStatus? = null,
    onRetry: (() -> Unit)? = null,
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
        val words = when (shown) {
            GlobalLine.Idle -> ""
            GlobalLine.Updating -> s.stateUpdating
            GlobalLine.SlowAnswer -> s.commonSlowAnswer
            is GlobalLine.RateLimited -> s.stateRateLimited(shown.seconds)
            is GlobalLine.SyncNeedsYou -> SyncWords.status(syncStatus?.takeIf { it.kind == shown.kind } ?: SyncStatus(shown.kind, 0L), 0L, false)
        }
        Row(
            // Heard when it appears. A count down changes every second and is not read each time.
            Modifier
                .liveMessage(on = shown !is GlobalLine.RateLimited)
                .background(container, if (touch) MaterialTheme.shapes.extraSmall else CircleShape)
                .padding(horizontal = if (touch) 12.dp else 16.dp, vertical = if (touch) 4.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown !is GlobalLine.SyncNeedsYou) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = onContainer)
            Text(
                text = words,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = onContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (touch) {
                when (shown) {
                    GlobalLine.SlowAnswer -> onRetry?.let { retry -> TextButton(onClick = retry) { Text(s.commonRetry) } }
                    is GlobalLine.SyncNeedsYou -> {
                        onOpenSettings?.let { open -> TextButton(onClick = open) { Text(s.notifyOpenSettings) } }
                        onDismiss?.let { close -> TextButton(onClick = close) { Text(s.commonClose) } }
                    }
                    else -> Unit
                }
            }
        }
    }
}
