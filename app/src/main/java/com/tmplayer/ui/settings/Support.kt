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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.tmplayer.data.SupportReminder
import com.tmplayer.i18n.L
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.update.readableUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Support TMPlayer": one QR code for the support page, which lists both donate links, the star
 * and the share, with its address under it. [from] tags the visit (`card1`, `settings`, `about`).
 *
 * A TV has no browser, so the code is the point there and Close holds the remote. A phone gets an
 * Open button under the code as well; the code is still useful on a phone for somebody who would
 * rather pay from another device.
 */
@Composable
fun SupportDialog(from: String, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val context = LocalContext.current
    val close = remember { FocusRequester() }
    val link = remember(from) { About.supportLinks(from).first() }

    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 440.dp else 520.dp)
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
                if (touch) s.supportNoteTouch(About.SUPPORT_NOTE) else s.supportNoteTv(About.SUPPORT_NOTE),
                style = MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                textAlign = TextAlign.Center,
            )
            SupportCode(link, onOpen = if (touch) ({ openLink(context, link.url) }) else null)
            Spacer(Modifier.height(4.dp))
            TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }
        }
    }

    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** The link's code on a white plate, what it is, and the address to type instead. */
@Composable
private fun SupportCode(link: About.Link, onOpen: (() -> Unit)?) {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link.url) {
        value = withContext(Dispatchers.Default) { QrCode.render(link.url, QR_PIXELS) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .widthIn(max = if (isTouch()) 260.dp else 230.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Corner.Large))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = s.supportQrFor(link.title),
                    // A scanner needs a quiet margin of the code's own white to find it.
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(link.title, style = MaterialTheme.typography.titleMedium, color = Tone.text, textAlign = TextAlign.Center)
        Text(readableUrl(link.url.substringBefore('?')), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
        if (onOpen != null) {
            TmButton(onClick = onOpen) { Label(s.commonOpen) }
        }
    }
}

/** The system share sheet with a line about TMPlayer and its site, for the second rung's "Share". */
fun shareTmplayer(context: android.content.Context) {
    val text = L.supportShareText(About.SITE)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, L.supportShareTitle)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * The gentle ask: a small card over the chat list or Downloads, never over the player, at a good
 * moment and at most three times for good (see [com.tmplayer.data.SupportReminder]). [rung] is
 * which of the three it is. Nothing behind it is blocked. On a TV it takes the remote's focus, on
 * the calm button, since a card the D-pad cannot reach could not be dismissed; Back is "Later".
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
    val touch = isTouch()
    val calm = remember { FocusRequester() }
    if (!touch) BackHandler(onBack = onLater)

    // Rung 1 and 3 ask for money; rung 2 asks for a star or a share, which a TV cannot do, so a
    // TV's second card opens the codes for the page that has both. The last card ends with "Done".
    val primaries: List<Pair<String, () -> Unit>> = when {
        rung == 2 && touch -> listOf(s.supportStar to onStar, s.supportShare to onShare)
        rung == 2 -> listOf(s.supportStarOrShare to onSupport)
        else -> listOf(s.supportSupport to onSupport)
    }
    val secondaries: List<Pair<String, () -> Unit>> =
        if (rung == 3) listOf(s.supportDone to onAlready) else listOf(s.supportLater to onLater, s.supportAlready to onAlready)

    Column(
        modifier
            .widthIn(max = if (touch) 520.dp else 560.dp)
            .floatingSurface()
            .padding(if (touch) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(About.reminderTitle(rung), style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(About.reminderText(rung, counters), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        if (touch) {
            // A phone held upright has no room for four buttons in a row: what the card asks for
            // shares the first row, the two quiet answers the second.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primaries.forEach { (label, action) ->
                    TmButton(onClick = action, modifier = Modifier.weight(1f)) { Label(label) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secondaries.forEach { (label, action) ->
                    TmSecondaryButton(onClick = action, modifier = Modifier.weight(1f)) { Label(label) }
                }
            }
        } else {
            // The card floats, so a button at rest takes the step above its fill (see FloatingTone.control).
            CompositionLocalProvider(LocalOnFloating provides true) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "I already support" first, then the calm button, which holds the focus.
                    secondaries.asReversed().forEachIndexed { index, (label, action) ->
                        val isCalm = index == secondaries.lastIndex
                        TmSecondaryButton(
                            onClick = action,
                            modifier = if (isCalm) Modifier.focusRequester(calm) else Modifier,
                        ) { Label(label) }
                    }
                    primaries.forEach { (label, action) -> TmButton(onClick = action) { Label(label) } }
                }
            }
        }
    }

    if (!touch) LaunchedEffect(Unit) { runCatching { calm.requestFocus() } }
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
