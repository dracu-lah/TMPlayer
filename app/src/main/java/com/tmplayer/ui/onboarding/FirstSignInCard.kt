package com.tmplayer.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay

/**
 * The one card after the first sign in (CP42, see [FirstSignIn]): "Show everything" keeps the
 * sidebar as it is, "Only my folders" goes on to the same confirm prompt as the Settings switch.
 * It sits where the language card sits and looks like it. Focus starts on "Show everything", the
 * choice that changes nothing, and Back on a remote is the same answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstSignInCard(onEverything: () -> Unit, onOnlyFolders: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val touch = isTouch()
    val everything = remember { FocusRequester() }
    if (!touch) BackHandler(onBack = onEverything)
    // A phone on its side has the width and not the height, so there the words and the buttons
    // share one row and the card covers about a third less of the screen.
    val short = touch && LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
    val buttons: @Composable () -> Unit = {
        CompositionLocalProvider(LocalOnFloating provides true) {
            // Wraps rather than squeezing: two labels side by side outgrow a portrait phone in
            // the longer languages.
            FlowRow(
                if (short) Modifier else Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TmSecondaryButton(onClick = onOnlyFolders) { Label(s.groupsCardFolders) }
                TmButton(onClick = onEverything, modifier = Modifier.focusRequester(everything)) { Label(s.groupsCardEverything) }
            }
        }
    }
    val words: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(TmIcons.Folder, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(s.groupsCardTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(s.groupsCardBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    }
    if (short) {
        Row(
            modifier
                .widthIn(max = 760.dp)
                .floatingSurface()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { words() }
            buttons()
        }
    } else {
        Column(
            modifier
                .widthIn(max = if (touch) 520.dp else 600.dp)
                .floatingSurface()
                .padding(if (touch) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            words()
            buttons()
        }
    }
    // A moment late, so the chat list's own first focus, which lands as its rows arrive, does not
    // take the remote back off the card.
    if (!touch) {
        LaunchedEffect(Unit) {
            delay(FOCUS_DELAY_MS)
            runCatching { everything.requestFocus() }
        }
    }
}

private const val FOCUS_DELAY_MS = 400L

/** Below this height a touch screen gets the one-row card. */
private const val SHORT_SCREEN_DP = 480

/** A button's words, in the library the button is drawn with (see Support.kt). */
@Composable
private fun Label(text: String) {
    if (isTouch()) androidx.compose.material3.Text(text) else Text(text)
}
