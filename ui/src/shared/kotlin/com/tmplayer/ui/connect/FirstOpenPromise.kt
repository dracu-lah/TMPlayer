package com.tmplayer.ui.connect

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * The one line under the loader on a first open (Raju pass 2, 1.1): up to three seconds of a bare
 * spinner told a new viewer nothing about what was coming. It sits in the loader's `below` room, so
 * it never moves the spinner, and it shows only while this device has not signed in yet
 * ([SettingsStore.wasSignedIn], which a Sign out clears too) and not once the wait has gone on long
 * enough for [ConnectingHelp] to take the room.
 */
@Composable
fun FirstOpenPromise(settings: SettingsStore, modifier: Modifier = Modifier) {
    val first by produceState(false, settings) { value = runCatching { !settings.wasSignedIn() }.getOrDefault(false) }
    val stalled by Td.signInStalled.collectAsState()
    if (first && !stalled) FirstOpenPromiseView(modifier)
}

/** [FirstOpenPromise]'s line as plain words, for a test to draw it. */
@Composable
fun FirstOpenPromiseView(modifier: Modifier = Modifier) {
    Text(
        LocalStrings.current.stateFirstOpenPromise,
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 320.dp),
    )
}
