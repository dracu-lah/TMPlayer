package com.tmplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.tvHoldsFocus
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay

/**
 * The quick tour's offer as a Tips card (R3): a quiet card at the foot of Home, never a dialog and
 * never in front of the first video. "Show me" opens the tour; "Not now" turns the card off for
 * good, one tap and one answer (there is no "ask again in a week"), and Settings can still open
 * the tour. On a phone the words sit over the buttons; on a television and the desktop it is one
 * slim line, the remote lands on Show me ([tvHoldsFocus]) and Back is Not now.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TipsCard(onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val form = deviceForm()
    val phone = form == DeviceForm.Phone
    val first = remember { FocusRequester() }
    val remote = !isTouch()
    if (remote) BackHandler(onBack = onDismiss)
    // A television's window may not hold focus yet when the card comes up (a cold start can take
    // seconds to give it): ask again until the remote is on Show me.
    var remoteOnShow by remember { mutableStateOf(false) }
    LaunchedEffect(remote) {
        if (!remote) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            if (remoteOnShow) return@LaunchedEffect
            if (it > 0) delay(FOCUS_RETRY_MS)
            runCatching { first.requestFocus() }
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton(s.tipsDismiss, onClick = onDismiss)
            SetupButton(
                s.tipsShow,
                primary = true,
                modifier = Modifier.focusRequester(first).onFocusChanged { remoteOnShow = it.isFocused },
                onClick = onShow,
            )
        }
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(TmIcons.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
                Text(s.tipsTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
            }
            Text(s.tipsBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }
    Box(
        modifier
            // Whenever the remote comes into the card, from the window gaining focus or a key, it
            // lands on Show me, not on the first button in the row.
            .focusProperties { enter = { first } }
            .tvHoldsFocus(first)
            .floatingSurface()
            .padding(if (phone) PaddingValues(16.dp) else PaddingValues(horizontal = 24.dp, vertical = 14.dp)),
    ) {
        if (phone) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                text(Modifier)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { buttons() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                text(Modifier.weight(1f))
                buttons()
            }
        }
    }
}
