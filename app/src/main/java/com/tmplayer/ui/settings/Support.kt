package com.tmplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.LocalOnFloating
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.update.readableUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Support TMPlayer": both ways to chip in, each as a QR code with its address under it.
 *
 * A TV has no browser, so the codes are the point there and Close holds the remote. A phone gets an
 * Open button under each code as well; the code is still useful on a phone for somebody who would
 * rather pay from another device.
 */
@Composable
fun SupportDialog(onClose: () -> Unit) {
    val touch = isTouch()
    val context = LocalContext.current
    val close = remember { FocusRequester() }

    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 560.dp else 860.dp)
        Column(
            Modifier
                .width(panel)
                .floatingSurface()
                .verticalScroll(rememberScrollState())
                .padding(if (touch) 20.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(About.SUPPORT_TITLE, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            Text(
                About.SUPPORT_NOTE + if (touch) {
                    " Open a link here, or scan a code on another device."
                } else {
                    " Point your phone's camera at a code."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 32.dp),
            ) {
                About.supportLinks.forEach { link ->
                    SupportCode(
                        link,
                        modifier = Modifier.weight(1f),
                        onOpen = if (touch) ({ openLink(context, link.url) }) else null,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label("Close") }
        }
    }

    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** One link: its code on a white plate, what it is, and the address to type instead. */
@Composable
private fun SupportCode(link: About.Link, modifier: Modifier, onOpen: (() -> Unit)?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link.url) {
        value = withContext(Dispatchers.Default) { QrCode.render(link.url, QR_PIXELS) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .widthIn(max = if (isTouch()) 260.dp else 210.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Corner.Large))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "QR code for ${link.title}",
                    // A scanner needs a quiet margin of the code's own white to find it.
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(link.title, style = MaterialTheme.typography.titleMedium, color = Tone.text, textAlign = TextAlign.Center)
        Text(readableUrl(link.url), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
        if (onOpen != null) {
            TmButton(onClick = onOpen) { Label("Open") }
        }
    }
}

/**
 * The gentle reminder: a small card over the chat list, never over the player, shown at most once
 * every 60 days after real use (see [com.tmplayer.data.SupportReminder]). Nothing behind it is
 * blocked. On a TV it takes the remote's focus, on "Not now", since a card the D-pad cannot reach
 * could not be dismissed; Back is "Not now" too.
 */
@Composable
fun SupportCard(
    onSupport: () -> Unit,
    onNotNow: () -> Unit,
    onNever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = isTouch()
    val notNow = remember { FocusRequester() }
    if (!touch) BackHandler(onBack = onNotNow)

    Column(
        modifier
            .widthIn(max = if (touch) 520.dp else 560.dp)
            .floatingSurface()
            .padding(if (touch) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(About.REMINDER_TITLE, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(About.REMINDER_TEXT, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        if (touch) {
            // A phone held upright has no room for three buttons in a row: the two everyday
            // answers share the width, and the final one sits under them.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TmSecondaryButton(onClick = onNotNow, modifier = Modifier.weight(1f)) { Label("Not now") }
                TmButton(onClick = onSupport, modifier = Modifier.weight(1f)) { Label("Support") }
            }
            TmSecondaryButton(onClick = onNever, modifier = Modifier.fillMaxWidth()) { Label("Don't ask again") }
        } else {
            // The card floats, so a button at rest takes the step above its fill (see FloatingTone.control).
            CompositionLocalProvider(LocalOnFloating provides true) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TmSecondaryButton(onClick = onNever) { Label("Don't ask again") }
                    TmSecondaryButton(onClick = onNotNow, modifier = Modifier.focusRequester(notNow)) { Label("Not now") }
                    TmButton(onClick = onSupport) { Label("Support") }
                }
            }
        }
    }

    if (!touch) LaunchedEffect(Unit) { runCatching { notNow.requestFocus() } }
}

/**
 * A button's words. The phone's Material 3 button sets the label colour through Material 3's own
 * content colour, which TV Material's Text does not read, so a touch label is a Material 3 Text.
 */
@Composable
private fun Label(text: String) {
    if (isTouch()) androidx.compose.material3.Text(text) else Text(text)
}

/** Pixels each code is drawn at, so it stays crisp on a 4K panel. */
private const val QR_PIXELS = 520
