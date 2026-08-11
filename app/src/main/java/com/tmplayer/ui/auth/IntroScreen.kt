package com.tmplayer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextAlign
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
import com.tmplayer.ui.components.AppMark
import com.tmplayer.ui.components.MarkSize
import com.tmplayer.ui.components.Pane
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction

/**
 * Shown once, before the QR code.
 *
 * TMPlayer needs no runtime permission: every Android permission it declares is granted at
 * install and it writes only inside its own private folder. That makes it more important, not
 * less, to say plainly what it will touch, because the system will never ask on its behalf.
 *
 * It is also the first thing anybody ever sees of the app, which is why the mark is here and on
 * only one other screen: this is the moment the app has to say what it is.
 */
@Composable
fun IntroScreen(onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }
    val touch = isTouch()

    if (touch) {
        Pane { Intro(touch = true, focus = focus, onContinue = onContinue) }
    } else {
        // Not [Pane] on the television: this pane runs to a 760dp column rather than the 640dp the
        // shared one uses, and widening it back would reflow the three points on the primary
        // device for the sake of sharing four lines of layout.
        //
        // Scrollable because the only focusable thing on this screen is the button at the bottom,
        // and raising the system font scale adds a wrapped line to several of these paragraphs,
        // which is enough to push it off the bottom.
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 56.dp, vertical = Tv.SafeV),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Intro(touch = false, focus = focus, onContinue = onContinue)
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The words themselves, which are the same on both devices; only their size is not.
 *
 * The television scale is drawn to be read across a room, and in the hand the same headline is a
 * shout, so each style steps down one rung on touch.
 */
@Composable
private fun ColumnScope.Intro(touch: Boolean, focus: FocusRequester, onContinue: () -> Unit) {
    if (touch) {
        // Centred over the heading rather than beside it: a portrait column has the height to
        // spare and no width to waste, and this reads as a title page.
        AppMark(MarkSize.HeroTouch, Modifier.align(Alignment.CenterHorizontally))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(MarkSize.Hero)
            Spacer(Modifier.width(20.dp))
            Text(
                "Welcome to TMPlayer",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
        }
    }

    if (touch) {
        Text(
            "Welcome to TMPlayer",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    Text(
        if (touch) {
            "TMPlayer plays videos from your own Telegram chats. Here's what that means before " +
                "you sign in."
        } else {
            "TMPlayer plays videos from your own Telegram chats on this TV. Here's what that " +
                "means before you sign in."
        },
        style = if (touch) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodyLarge
        },
        color = TextMuted,
    )
    Spacer(Modifier.size(8.dp))

    Point(
        touch,
        Icons.Filled.Lock,
        "You stay signed in to your own account",
        if (touch) {
            "Signing in adds this device to your Telegram devices, just like a computer does. " +
                "Your media comes from Telegram and nowhere else."
        } else {
            "Signing in adds this TV to your Telegram devices, just like a computer does. " +
                "Your media comes from Telegram and nowhere else."
        },
    )
    Point(
        touch,
        Icons.Filled.PlayArrow,
        "Videos only, never your messages",
        "TMPlayer reads your chats to list the videos in them. It never shows messages, " +
            "and it never sends any.",
    )
    Point(
        touch,
        Icons.Filled.Star,
        if (touch) "One video on this device at a time" else "One video on this TV at a time",
        if (touch) {
            "TMPlayer saves the video you're watching and deletes it when you start the next " +
                "one, so this phone doesn't fill up. It always asks you first."
        } else {
            "TMPlayer saves the video you're watching and deletes it when you start the " +
                "next one, so this TV doesn't fill up. It always asks you first."
        },
    )

    Spacer(Modifier.size(10.dp))
    TmButton(
        onClick = onContinue,
        modifier = Modifier.focusRequester(focus).paneAction(),
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

@Composable
private fun Point(touch: Boolean, icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(if (touch) 20.dp else 24.dp),
        )
        Spacer(Modifier.width(if (touch) 12.dp else 16.dp))
        Column {
            Text(
                title,
                style = if (touch) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = TextPrimary,
            )
            Text(
                body,
                style = if (touch) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = TextMuted,
            )
        }
    }
}
