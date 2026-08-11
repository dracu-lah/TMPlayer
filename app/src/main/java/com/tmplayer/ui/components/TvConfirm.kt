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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
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
import com.tmplayer.ui.theme.Corner
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

    // A phone gets Material's own dialog. The hand-built panel below was close to it and still
    // missed the things a stock dialog brings for nothing: a tap on the scrim dismisses it (the
    // scrim here is part of the dialog's own content, so a tap on it went to the content and did
    // nothing at all), the predictive-back animation works, and a screen reader announces it as a
    // dialog rather than as a column of text that appeared.
    if (touch) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                M3Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (destructive) Danger else Accent,
                )
            },
            title = { M3Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Text(message, style = M3MaterialTheme.typography.bodyMedium)
                    if (detail != null) {
                        M3Text(
                            detail,
                            style = M3MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    M3Text(confirmLabel, color = if (destructive) Danger else Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { M3Text(cancelLabel, color = TextMuted) }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
        )
        return
    }

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
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            // Sized to the words, not to the screen. These prompts carry one line of question and
            // two buttons; a panel wide enough for a paragraph just leaves the viewer's eye
            // travelling across empty space to find the answer.
            val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)

            Column(
                Modifier
                    .width(panel)
                    .clip(RoundedCornerShape(Corner.ExtraLarge))
                    .background(SurfaceDark)
                    .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(Corner.ExtraLarge))
                    .padding(horizontal = 32.dp, vertical = 28.dp),
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
                        modifier = Modifier.size(28.dp),
                    )
                    Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                // Cancel is the quiet one and confirm is the loud one, which is the same ranking
                // the TV colours gave them, only stated by which button is used rather than by a
                // colour table each caller passes in.
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

        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
}

/** As wide as this prompt ever gets, on any screen. */
private val PANEL_MAX = 520.dp


