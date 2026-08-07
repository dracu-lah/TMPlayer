package com.tmplayer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.tmButtonColors

/**
 * Shown once, before the QR code.
 *
 * TMPlayer needs no runtime permission — every Android permission it declares is granted at
 * install and it writes only inside its own private folder. That makes it more important, not
 * less, to say plainly what it will touch, because the system will never ask on its behalf.
 */
@Composable
fun IntroScreen(onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }

    Box(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = Tv.SafeV),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Welcome to TMPlayer",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                "It plays films from your own Telegram chats on this TV. Before you sign in, " +
                    "here is exactly what that involves.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
            )
            Spacer(Modifier.size(8.dp))

            Point(
                Icons.Filled.Lock,
                "Your account, on your terms",
                "Signing in links this TV as a Telegram device, the same as Telegram Desktop. " +
                    "Nothing is sent anywhere except Telegram's own servers.",
            )
            Point(
                Icons.Filled.PlayArrow,
                "Videos only",
                "TMPlayer reads your chats to list the videos in them. It never shows messages, " +
                    "and it never sends any.",
            )
            Point(
                Icons.Filled.Star,
                "One film on the device at a time",
                "A film is cached while you watch it and replaced by the next one, so an 8 GB " +
                    "stick does not fill up. You will be asked before anything is removed.",
            )

            Spacer(Modifier.size(10.dp))
            Button(
                onClick = onContinue,
                colors = tmButtonColors(),
                modifier = Modifier.focusRequester(focus),
            ) {
                Text("Continue to sign in")
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
    }
}
