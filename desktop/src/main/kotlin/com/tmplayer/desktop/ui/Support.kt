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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Support TMPlayer" on the desktop: one link to the support page, which lists both donate links,
 * the star and the share, with an Open button and a QR code for paying from the phone instead.
 * [from] tags the visit (`card1`, `settings`, `about`). The words and address are [About]'s.
 */
@Composable
fun SupportPopup(from: String, onClose: () -> Unit) {
    val s = LocalStrings.current
    val link = remember(from) { About.supportLinks(from).first() }
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent) },
        title = { Text(About.SUPPORT_TITLE) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    s.supportPopupNote(About.SUPPORT_NOTE),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SupportCode(link, Modifier.fillMaxWidth())
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
            link.url.substringBefore('?').removePrefix("https://"),
            style = MaterialTheme.typography.bodySmall,
            color = Tone.muted,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = { OpenExternal.browse(link.url) }) { Text(s.commonOpen) }
    }
}

/**
 * The gentle ask: a small card in the window's corner, at a good moment and at most three times
 * for good (see [SupportReminder]). [rung] is which of the three it is. Nothing behind it is
 * blocked, and it is never over the player. "Star" opens the repository and "Share" copies the
 * site's link with a line about TMPlayer, since a desktop has no share sheet.
 */
@Composable
fun SupportCard(
    rung: Int,
    counters: SupportReminder.Counters,
    onSupport: () -> Unit,
    onStar: () -> Unit,
    onShare: () -> Unit,
    onLater: () -> Unit,
    onAlready: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(440.dp)
            .floatingSurface()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
            Text(About.reminderTitle(rung), style = MaterialTheme.typography.titleMedium)
        }
        Text(About.reminderText(rung, counters), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            if (rung == 3) {
                TextButton(onClick = onAlready) { Text(s.supportDone) }
            } else {
                TextButton(onClick = onAlready) { Text(s.supportAlready) }
                TextButton(onClick = onLater) { Text(s.supportLater) }
            }
            if (rung == 2) {
                OutlinedButton(onClick = {
                    copied = true
                    onShare()
                }) { Text(if (copied) s.supportLinkCopied else s.supportShare) }
                Button(onClick = onStar) { Text(s.supportStar) }
            } else {
                Button(onClick = onSupport) { Text(s.supportSupport) }
            }
        }
    }
}

/**
 * Counts the videos the window finishes, and puts the ask up at a good moment: back on Home, Chats
 * or Downloads after a watch to the end, or after a download finished. Never over the player or the
 * update popup. A card is forced on with `-Dtmplayer.supportRung=N` (1 to 3) or
 * `TMPLAYER_SUPPORT_RUNG=N`, and `-Dtmplayer.supportReminder=true` forces the first.
 */
@Composable
fun SupportCardHost(state: ShellState, modifier: Modifier = Modifier) {
    var rung by remember { mutableStateOf(0) }
    var counters by remember { mutableStateOf(SupportReminder.Counters()) }
    var popup by remember { mutableStateOf<String?>(null) }
    var forcedShown by remember { mutableStateOf(false) }
    val settings = state.settings

    LaunchedEffect(Unit) {
        val wanted = System.getProperty("tmplayer.supportRung")?.toIntOrNull()
            ?: System.getenv("TMPLAYER_SUPPORT_RUNG")?.toIntOrNull()
        if (wanted != null && wanted > 0) {
            SupportReminder.forcedRung = wanted
        } else if (System.getProperty("tmplayer.supportReminder") == "true" || System.getenv("TMPLAYER_SUPPORT_REMINDER") == "1") {
            SupportReminder.forced = true
        }
        runCatching { settings.noteSupportFirstSeen(System.currentTimeMillis()) }
        SupportReminder.finished.collect { done ->
            runCatching { settings.noteSupportCompleted(done.key, done.at) }
        }
    }
    val moment by SupportReminder.moment.collectAsState()
    val playing = state.nowPlaying != null
    val onGoodPage = state.destination == Destination.Home || state.destination == Destination.Chats ||
        state.destination == Destination.Downloads
    LaunchedEffect(moment, playing, onGoodPage, state.updatePopup, rung) {
        if (playing || !onGoodPage || state.updatePopup || rung != 0) return@LaunchedEffect
        val forced = SupportReminder.forcedRung
        if (forced > 0) {
            if (forcedShown) return@LaunchedEffect
            delay(CARD_DELAY_MS)
            forcedShown = true
            counters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)
            rung = forced
            return@LaunchedEffect
        }
        if (moment == 0L) return@LaunchedEffect
        delay(CARD_DELAY_MS)
        if (!SupportReminder.takeMoment(System.currentTimeMillis())) return@LaunchedEffect
        val before = runCatching { settings.supportCountersNow() }.getOrNull() ?: return@LaunchedEffect
        val shown = runCatching { SupportReminder.claim(settings, System.currentTimeMillis()) }.getOrDefault(0)
        if (shown > 0) {
            counters = before
            rung = shown
        }
    }

    if (rung > 0 && !playing && !state.updatePopup) {
        val shown = rung
        SupportCard(
            rung = shown,
            counters = counters,
            onSupport = { rung = 0; popup = "card$shown" },
            onStar = {
                rung = 0
                OpenExternal.browse(About.SOURCE)
            },
            onShare = {
                // No share sheet on a desktop: the line goes to the clipboard, ready to paste.
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    java.awt.datatransfer.StringSelection(com.tmplayer.i18n.L.supportShareText(About.SITE)),
                    null,
                )
                rung = 0
            },
            onLater = { rung = 0 },
            onAlready = {
                rung = 0
                if (SupportReminder.forcedRung == 0) Background.scope.launch { runCatching { settings.markSupporter() } }
            },
            modifier = modifier,
        )
    }
    popup?.let { from -> SupportPopup(from = from, onClose = { popup = null }) }
}

private val PLATE = 180.dp
private const val QR_PIXELS = 400
private const val CARD_DELAY_MS = 4_000L
