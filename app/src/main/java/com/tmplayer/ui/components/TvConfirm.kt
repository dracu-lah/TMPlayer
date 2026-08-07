package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.tmButtonColors

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

    // In its own window, not just a Box on top of the layout.
    //
    // Drawn inline, a prompt is an ordinary sibling: it sits above whatever was composed before
    // it and underneath everything composed after, so the same component covered one screen and
    // appeared *behind* the next. A Dialog is a separate window, which puts every prompt over
    // the whole app — the navigation rail included — no matter where it is called from.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // Sized to the words, not to the screen. These prompts carry one line of
                // question and two buttons; a panel wide enough for a paragraph just leaves the
                // viewer's eye travelling across empty space to find the answer.
                Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = tmButtonColors(),
                        modifier = Modifier.focusRequester(cancelFocus),
                    ) {
                        Text(cancelLabel)
                    }
                    Button(
                        onClick = onConfirm,
                        colors = if (destructive) destructiveButtonColors() else tmButtonColors(),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }

        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
}

@Composable
private fun destructiveButtonColors() = ButtonDefaults.colors(
    containerColor = SurfaceDark,
    contentColor = Danger,
    focusedContainerColor = Danger,
    focusedContentColor = Color.White,
    pressedContainerColor = Danger,
    pressedContentColor = Color.White,
    disabledContainerColor = SurfaceDark,
    disabledContentColor = TextMuted,
)

private val Danger = Color(0xFFE5484D)
