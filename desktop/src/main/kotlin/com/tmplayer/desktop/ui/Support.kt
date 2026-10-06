package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SupportReminder
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.platform.Background
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Support TMPlayer" on the desktop: both links, each with an Open button and a QR code for
 * paying from the phone instead. The words and addresses are [About]'s, as on the phone and TV.
 */
@Composable
fun SupportPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent) },
        title = { Text(About.SUPPORT_TITLE) },
        text = {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    s.supportPopupNote(About.SUPPORT_NOTE),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    About.supportLinks.forEach { link ->
                        SupportCode(link, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

@Composable
private fun SupportCode(link: About.Link, modifier: Modifier) {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link.url) {
        value = withContext(Dispatchers.Default) { QrCode.render(link.url, QR_PIXELS) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(PLATE).clip(RoundedCornerShape(Corner.Large)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it, contentDescription = s.updateQrFor(link.title), modifier = Modifier.fillMaxSize().padding(8.dp))
            }
        }
        Text(link.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            link.url.removePrefix("https://"),
            style = MaterialTheme.typography.bodySmall,
            color = Tone.muted,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = { OpenExternal.browse(link.url) }) { Text(s.commonOpen) }
    }
}

/**
 * The gentle reminder: a small card in the window's corner, after real use and at most once every
 * 60 days (see [SupportReminder]). Nothing behind it is blocked, and it is never over the player.
 */
@Composable
fun SupportCard(onSupport: () -> Unit, onNotNow: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .width(420.dp)
            .floatingSurface()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
            Text(About.REMINDER_TITLE, style = MaterialTheme.typography.titleMedium)
        }
        Text(About.REMINDER_TEXT, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = onNever) { Text(s.supportNever) }
            TextButton(onClick = onNotNow) { Text(s.supportNotNow) }
            Button(onClick = onSupport) { Text(s.supportSupport) }
        }
    }
}

/**
 * Counts each video the window starts, and puts the card up when [SupportReminder] says so. The
 * card is forced on with `-Dtmplayer.supportReminder=true` or `TMPLAYER_SUPPORT_REMINDER=1`.
 */
@Composable
fun SupportCardHost(state: ShellState, modifier: Modifier = Modifier) {
    var card by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf(false) }
    val settings = state.settings

    LaunchedEffect(Unit) {
        if (System.getProperty("tmplayer.supportReminder") == "true" || System.getenv("TMPLAYER_SUPPORT_REMINDER") == "1") {
            SupportReminder.forced = true
        }
        runCatching { settings.noteSupportFirstSeen(System.currentTimeMillis()) }
        snapshotFlow { state.nowPlaying }.filterNotNull().collect {
            Background.scope.launch { runCatching { settings.noteSupportPlay(System.currentTimeMillis()) } }
        }
    }
    val playing = state.nowPlaying != null
    LaunchedEffect(playing) {
        if (playing || card) return@LaunchedEffect
        delay(CARD_DELAY_MS)
        if (!state.updatePopup && runCatching { SupportReminder.claim(settings, System.currentTimeMillis()) }.getOrDefault(false)) {
            card = true
        }
    }

    if (card && !playing && !state.updatePopup) {
        SupportCard(
            onSupport = { card = false; popup = true },
            onNotNow = { card = false },
            onNever = {
                card = false
                Background.scope.launch { runCatching { settings.neverAskSupport() } }
            },
            modifier = modifier,
        )
    }
    if (popup) SupportPopup(onClose = { popup = false })
}

private val PLATE = 180.dp
private const val QR_PIXELS = 400
private const val CARD_DELAY_MS = 4_000L
