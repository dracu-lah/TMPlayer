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
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Tone

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
        // A phone already has a shape for "something happened and you cannot act on it": the
        // snackbar. Taking its inverse surface rather than a panel colour is what keeps this
        // legible when the scheme underneath is a light one, or the wallpaper's.
        val container = if (touch) SnackbarDefaults.color else Tone.surfaceHigh.copy(alpha = 0.96f)
        val onContainer = if (touch) SnackbarDefaults.contentColor else Tone.text
        Row(
            Modifier
                .background(container, if (touch) M3.shapes.extraSmall else CircleShape)
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
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
                ConnectionNotice.Reconnecting -> Spinner(
                    size = 20.dp,
                    // The television's spinner is left at its accent, where it always was.
                    color = if (touch) onContainer else Color.Unspecified,
                    strokeWidth = 2.dp,
                )
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
                color = onContainer,
            )
        }
    }
}
