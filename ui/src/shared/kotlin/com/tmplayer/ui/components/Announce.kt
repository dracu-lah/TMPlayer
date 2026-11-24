package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Says [text] once to the screen reader when it first shows and again only when it changes, for a
 * status line's start and end (WP-91). Blank says nothing.
 *
 * The line itself is a polite live region ([liveMessage]), which TalkBack and the desktop's
 * screen readers hear. VoiceOver on iOS is not known to hear a Compose live region (LS-33), so iOS
 * posts an announcement as well ([platformAnnounce]); on Android and the desktop that call does
 * nothing.
 */
@Composable
fun AnnounceOnce(text: String) {
    LaunchedEffect(text) {
        if (text.isNotBlank()) runCatching { platformAnnounce(text) }
    }
}
