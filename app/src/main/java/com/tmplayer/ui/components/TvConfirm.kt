package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary

/**
 * The one prompt shape used everywhere something is about to be deleted, signed out of, or
 * otherwise made hard to undo.
 *
 * Focus starts on Cancel. On a remote the confirm button is one careless press away, and the
 * cheap mistake should be the default.
 */
@Composable
fun TvConfirm(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    detail: String? = null,
    cancelLabel: String = "Cancel",
    destructive: Boolean = true,
    icon: ImageVector = if (destructive) Icons.Default.Warning else Icons.Default.Info,
) {
    val cancelFocus = remember { FocusRequester() }
    val touch = isTouch()

    // In its own window, not just a Box on top of the layout.
    //
    // Drawn inline, a prompt is an ordinary sibling: it sits above whatever was composed before
    // it and underneath everything composed after, so the same component covered one screen and
    // appeared *behind* the next. A Dialog is a separate window, which puts every prompt over
    // the whole app, the navigation rail included, no matter where it is called from.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                // A dialog window is not laid out inside the app's insets, so on a phone the panel
                // can otherwise sit under a notch or the gesture bar when the screen is short.
                .then(if (touch) Modifier.safeDrawingPadding() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // Sized to the words, not to the screen. These prompts carry one line of question and
            // two buttons; a panel wide enough for a paragraph just leaves the viewer's eye
            // travelling across empty space to find the answer. On a narrow phone that same 520dp
            // is wider than the display, so it is a ceiling here rather than a fixed width.
            val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)

            Column(
                Modifier
                    .width(panel)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .padding(
                        horizontal = if (touch) 22.dp else 32.dp,
                        vertical = if (touch) 22.dp else 28.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) Danger else Accent,
                        modifier = Modifier.size(if (touch) 24.dp else 28.dp),
                    )
                    Text(
                        title,
                        // The TV title is sized to be read from a sofa, which on a phone held at
                        // arm's length wraps a short question onto three lines.
                        style = if (touch) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        color = TextPrimary,
                    )
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                // Cancel is the quiet one and confirm is the loud one, which is the same ranking
                // the TV colours gave them, only stated by which button is used rather than by a
                // colour table each caller passes in.
                if (touch) {
                    // Stacked and full width: two buttons side by side in a panel this narrow are
                    // both cramped and off under the thumb, and the answer being committed to
                    // belongs nearest the bottom of the screen.
                    Column(
                        Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TmButton(
                            onClick = onConfirm,
                            destructive = destructive,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(confirmLabel)
                        }
                        TmSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(cancelLabel)
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        TmSecondaryButton(
                            onClick = onDismiss,
                            modifier = Modifier.focusRequester(cancelFocus),
                        ) {
                            Text(cancelLabel)
                        }
                        TmButton(onClick = onConfirm, destructive = destructive) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }

        // Only the remote needs somewhere for focus to start. A finger chooses by touching, and a
        // focus ring parked on Cancel would be the only thing on a phone drawing one.
        if (!touch) {
            LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        }
    }
}

/** As wide as this prompt ever gets, on any screen. */
private val PANEL_MAX = 520.dp


