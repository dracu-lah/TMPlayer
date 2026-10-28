package com.tmplayer.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.data.SessionCheck
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.platform.Logger
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.Setup
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A session Telegram ended, as each app's shell meets it (HX23). Called with every sign in state,
 * before the sign in screen arms its setup: the setup's flag is one of the preferences [forget]
 * clears.
 *
 * Signed in, the device is marked as having an account ([SettingsStore.markSignedIn]). On a sign
 * in screen, an account still marked, or a logout Telegram started while the app ran
 * ([Td.sessionEnded]), means the session ended elsewhere: what belonged to it goes as Sign out
 * clears it ([forget], the platform's own, downloads kept), and the sign in screens say why
 * ([SessionEndedNote]). A Sign out clears the mark first, so it is never taken for this.
 */
suspend fun handleSessionEnd(auth: AuthState, settings: SettingsStore, forget: suspend () -> Unit) {
    if (auth is AuthState.Ready) {
        cleared = false
        runCatching { settings.markSignedIn() }
        return
    }
    val signInStep = Setup.isSignInStep(auth)
    if (!signInStep) return
    // Seen through to the end, one at a time: the screen moves on (choice, then the QR code)
    // while the preferences are being cleared, and a half cleared account is the worst of both.
    withContext(NonCancellable) {
        forgetting.withLock {
            val marked = runCatching { settings.wasSignedIn() }.getOrDefault(false)
            if (!SessionCheck.endedElsewhere(signInStep, marked) && !Td.sessionEnded.value) return@withLock
            if (!marked && cleared) return@withLock
            Logger.i("SessionEnd", "the session ended elsewhere; clearing what was the account's")
            Td.markSessionEnded()
            runCatching { forget() }.onFailure { Logger.w("SessionEnd", "clearing the ended session failed", it) }
            runCatching { settings.forgetSignedIn() }
            cleared = true
        }
    }
}

private val forgetting = Mutex()

/** Set once the ended session was cleared, so the next sign in screen of the same wait does not clear again. */
@Volatile
private var cleared = false

/**
 * Over the sign in screens after Telegram ended the session ([Td.sessionEnded]): the one line that
 * says why the viewer is signing in again, in place of any talk of the network. Nothing to press,
 * so nothing takes the remote's focus.
 */
@Composable
fun SessionEndedNote(modifier: Modifier = Modifier) {
    val ended by Td.sessionEnded.collectAsState()
    if (!ended) return
    val s = LocalStrings.current
    Row(
        modifier
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.SignOut, contentDescription = null, tint = Tone.caution, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(s.errorsSignedOut, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
    }
}
