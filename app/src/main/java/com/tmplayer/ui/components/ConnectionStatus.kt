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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

enum class ConnectionNotice {
    Hidden,
    Offline,
    Reconnecting,

    /** Online, and the line to Telegram is being reopened (back from the background, say). */
    Connecting,
}

/**
 * A status that never takes focus or blocks saved content and playback: "Reconnecting" and
 * "Connecting", a spinner and a few words. Offline is not drawn here: it is the Connect banner's
 * card ([com.tmplayer.ui.connect.OfflineBanner]) with Try again, placed by the host clear of the
 * tab bar (R4), so there is one thing that says offline and never a passive pill that duplicates it.
 */
@Composable
fun ConnectionStatus(notice: ConnectionNotice, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    AnimatedVisibility(
        visible = notice == ConnectionNotice.Reconnecting || notice == ConnectionNotice.Connecting,
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
            // Heard when it appears: "Reconnecting" is news a screen reader would otherwise miss.
            Modifier
                .liveMessage()
                .background(container, if (touch) M3.shapes.extraSmall else CircleShape)
                .padding(
                    horizontal = if (touch) 12.dp else 16.dp,
                    vertical = if (touch) 8.dp else 10.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(
                size = 20.dp,
                // The television's spinner keeps its accent colour.
                color = if (touch) onContainer else Color.Unspecified,
                strokeWidth = 2.dp,
            )
            Text(
                text = if (notice == ConnectionNotice.Reconnecting) s.stateReconnecting else s.stateConnectingShort,
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

/**
 * "Starting <title>" with a spinner, in the same place and shape as [ConnectionStatus], while a
 * press of Play is on its way to the player ([PlayPress]). Shown after a beat, so a player that
 * opens at once never flashes it; it never takes focus.
 */
@Composable
fun StartingStatus(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val title = PlayPress.title
    var shown by remember { mutableStateOf(false) }
    // Kept for the fade out, after the press itself is over.
    var last by remember { mutableStateOf("") }
    LaunchedEffect(title) {
        if (title == null) {
            shown = false
            return@LaunchedEffect
        }
        last = title
        delay(STARTING_SHOWN_AFTER_MS)
        shown = true
    }
    AnimatedVisibility(visible = shown && title != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val touch = isTouch()
        val container = if (touch) SnackbarDefaults.color else Tone.surfaceHigh.copy(alpha = 0.96f)
        val onContainer = if (touch) SnackbarDefaults.contentColor else Tone.text
        Row(
            // Heard when it appears: "Reconnecting" is news a screen reader would otherwise miss.
            Modifier
                .liveMessage()
                .background(container, if (touch) M3.shapes.extraSmall else CircleShape)
                .padding(horizontal = if (touch) 12.dp else 16.dp, vertical = if (touch) 8.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(size = 20.dp, color = if (touch) onContainer else Color.Unspecified, strokeWidth = 2.dp)
            Text(
                text = s.mainStarting(last),
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Long enough that a player opening at once never flashes the pill, short enough to answer the press. */
private const val STARTING_SHOWN_AFTER_MS = 180L
