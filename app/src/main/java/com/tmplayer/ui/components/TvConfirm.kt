package com.tmplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
) {
    val cancelFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(820.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
