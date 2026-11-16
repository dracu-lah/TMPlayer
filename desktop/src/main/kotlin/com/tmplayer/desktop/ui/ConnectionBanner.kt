package com.tmplayer.desktop.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.connect.OfflineBanner
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/** What the connection pill says, if anything. */
enum class ConnectionNotice { Hidden, Offline, Reconnecting }

/**
 * The phone's offline rule, on the desktop: Offline once the settled offline answer says so (the
 * computer has no network *and* TDLib has no connection, held for 2 s each way by
 * [com.tmplayer.data.OfflineSettle], so a blip does not flash); Reconnecting while the network is
 * back and TDLib is not yet; and when both are back, [onBackOnline] once (the chat list reloads).
 */
@Composable
fun rememberConnectionNotice(offline: Boolean, telegramConnected: Boolean, onBackOnline: () -> Unit): ConnectionNotice {
    var notice by remember { mutableStateOf(ConnectionNotice.Hidden) }
    var wasOffline by remember { mutableStateOf(false) }
    val backOnline by rememberUpdatedState(onBackOnline)
    LaunchedEffect(offline, telegramConnected) {
        when {
            offline -> {
                wasOffline = true
                notice = ConnectionNotice.Offline
            }
            wasOffline && !telegramConnected -> notice = ConnectionNotice.Reconnecting
            wasOffline -> {
                notice = ConnectionNotice.Hidden
                wasOffline = false
                backOnline()
            }
            else -> notice = ConnectionNotice.Hidden
        }
    }
    return notice
}

/**
 * Offline is the Connect banner's card with Try again and Downloads ([OfflineBanner]); Reconnecting
 * is a passive pill that never takes focus. Saved videos and playback carry on under either.
 */
@Composable
fun ConnectionBanner(notice: ConnectionNotice, modifier: Modifier = Modifier, onOpenDownloads: (() -> Unit)? = null) {
    val s = LocalStrings.current
    if (notice == ConnectionNotice.Offline) {
        OfflineBanner(modifier, onOpenDownloads)
        return
    }
    AnimatedVisibility(visible = notice == ConnectionNotice.Reconnecting, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .background(Tone.surfaceHigh.copy(alpha = 0.96f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(s.connectionReconnecting, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
        }
    }
}
