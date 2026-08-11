package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextPrimary

enum class ConnectionNotice {
    Hidden,
    Offline,
    Reconnecting,
}

/** A passive status only. It never takes focus or blocks saved content and playback. */
@Composable
fun ConnectionStatus(notice: ConnectionNotice, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = notice != ConnectionNotice.Hidden,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val touch = isTouch()
        Row(
            Modifier
                .background(SurfaceRaised.copy(alpha = 0.96f), RoundedCornerShape(24.dp))
                .padding(
                    horizontal = if (touch) 12.dp else 16.dp,
                    vertical = if (touch) 8.dp else 10.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (notice) {
                ConnectionNotice.Offline -> Icon(
                    TmIcons.WifiOff,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
                ConnectionNotice.Reconnecting -> Spinner(size = 20.dp, strokeWidth = 2.dp)
                ConnectionNotice.Hidden -> Unit
            }
            Text(
                text = when (notice) {
                    ConnectionNotice.Offline -> "Offline. Saved videos still work."
                    ConnectionNotice.Reconnecting -> "Back online. Reconnecting to Telegram..."
                    ConnectionNotice.Hidden -> ""
                },
                // A phone screen is narrower than this sentence at TV size, and the pill would run
                // off both edges rather than sit in a corner of the screen.
                style = if (touch) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = TextPrimary,
            )
        }
    }
}
