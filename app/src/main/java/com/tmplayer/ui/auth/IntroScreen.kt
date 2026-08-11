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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.components.TmButton

/**
 * Shown once, before the QR code.
 *
 * TMPlayer needs no runtime permission: every Android permission it declares is granted at
 * install and it writes only inside its own private folder. That makes it more important, not
 * less, to say plainly what it will touch, because the system will never ask on its behalf.
 */
@Composable
fun IntroScreen(onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }

    // Scrollable because the only focusable thing on this screen is the button at the bottom, and
    // raising the system font scale adds a wrapped line to several of these paragraphs, which is
    // enough to push it off the bottom.
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = Tv.SafeV),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Welcome to TMPlayer",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                "TMPlayer plays videos from your own Telegram chats on this TV. Here's what " +
                    "that means before you sign in.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
            )
            Spacer(Modifier.size(8.dp))

            Point(
                Icons.Filled.Lock,
                "You stay signed in to your own account",
                "Signing in adds this TV to your Telegram devices, just like a computer does. " +
                    "Your media comes from Telegram and nowhere else.",
            )
            Point(
                Icons.Filled.PlayArrow,
                "Videos only, never your messages",
                "TMPlayer reads your chats to list the videos in them. It never shows messages, " +
                    "and it never sends any.",
            )
            Point(
                Icons.Filled.Star,
                "One video on this TV at a time",
                "TMPlayer saves the video you're watching and deletes it when you start the " +
                    "next one, so this TV doesn't fill up. It always asks you first.",
            )

            Spacer(Modifier.size(10.dp))
            TmButton(
                onClick = onContinue,
                modifier = Modifier.focusRequester(focus),
            ) {
                // Trailing, unlike the leading icons elsewhere: it points where the button goes.
                Text("Continue to sign in")
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
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
