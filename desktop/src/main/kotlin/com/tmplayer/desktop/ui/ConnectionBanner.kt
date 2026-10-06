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
import androidx.compose.material3.Icon
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
import com.tmplayer.data.NetworkStatus
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/** What the connection pill says, if anything. */
enum class ConnectionNotice { Hidden, Offline, Reconnecting }

/**
 * The phone's offline rule, on the desktop: Offline once the computer has no network *and* TDLib
 * has no connection, held for a moment so a blip does not flash the pill; Reconnecting while the
 * network is back and TDLib is not yet; and when both are back, [onBackOnline] once (the chat list
 * reloads and the toast says so).
 */
@Composable
fun rememberConnectionNotice(network: NetworkStatus, telegramConnected: Boolean, onBackOnline: () -> Unit): ConnectionNotice {
    var notice by remember { mutableStateOf(ConnectionNotice.Hidden) }
    var wasOffline by remember { mutableStateOf(false) }
    val backOnline by rememberUpdatedState(onBackOnline)
    LaunchedEffect(network, telegramConnected) {
        when {
            network == NetworkStatus.Offline && !telegramConnected -> {
                delay(OFFLINE_SETTLE_MS)
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

/** A passive pill: it never takes focus, and saved videos and playback carry on under it. */
@Composable
fun ConnectionBanner(notice: ConnectionNotice, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    AnimatedVisibility(visible = notice != ConnectionNotice.Hidden, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .background(Tone.surfaceHigh.copy(alpha = 0.96f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (notice) {
                ConnectionNotice.Offline -> Icon(TmIcons.WifiOff, contentDescription = null, tint = Tone.text, modifier = Modifier.size(20.dp))
                ConnectionNotice.Reconnecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                ConnectionNotice.Hidden -> Unit
            }
            Text(
                when (notice) {
                    ConnectionNotice.Offline -> s.connectionOffline
                    ConnectionNotice.Reconnecting -> s.connectionReconnecting
                    ConnectionNotice.Hidden -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.text,
            )
        }
    }
}

private const val OFFLINE_SETTLE_MS = 750L
