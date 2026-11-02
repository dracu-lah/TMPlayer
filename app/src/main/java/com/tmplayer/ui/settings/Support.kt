package com.tmplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.i18n.L
import kotlinx.coroutines.launch
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
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

/**
 * The Buy Me a Coffee window: the ask itself when [asking] (at a good moment, at most every two
 * weeks, see [SupportReminder]), or the same window opened from About to pay from the phone.
 *
 * Buy Me a Coffee comes first and in its own colours: on a phone a big yellow button that opens it
 * in the browser, on a television a large code to it on their yellow. GitHub Sponsors is the quieter way
 * under it. A TV has no browser, so the code is the way there, with a small one for GitHub
 * Sponsors beside the words; the remote starts on "Maybe later", so a stray OK costs nothing.
 * "Don't show again" ends the asks and the notification for good. Back is "Maybe later".
 */
@Composable
fun CoffeeDialog(
    counters: SupportReminder.Counters,
    asking: Boolean,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val context = LocalContext.current
    val calm = remember { FocusRequester() }
    val title = if (asking) About.reminderTitle(1) else About.SUPPORT_TITLE
    val text = if (asking) About.reminderText(1, counters) else About.SUPPORT_NOTE

    FloatingWindow(onDismiss = onLater, ignoreRelease = true) {
        if (touch) {
            val panel = min(maxWidth - PhonePad.Side * 2, 440.dp)
            Column(
                Modifier
                    .width(panel)
                    .floatingSurface()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                // No scan code on a phone: the button opens the page on the screen the code would
                // be on (user, 2026-10-18). The television, with no browser, keeps its code.
                CoffeeButton(onClick = { openLink(context, About.COFFEE) }, modifier = Modifier.fillMaxWidth())
                // Buy Me a Coffee takes cards and PayPal only, so UPI stands beside it for anybody
                // who pays that way (user, 2026-11-01).
                UpiPay(modifier = Modifier.fillMaxWidth())
                // The two free or quieter ways side by side: a sponsorship, and a star, which costs
                // nothing and is worth asking for as much as a coffee (user, 2026-10-18).
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(onClick = { openLink(context, About.SPONSORS) }) {
                        androidx.compose.material3.Text(s.supportSponsorGithub)
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            openLink(context, About.SOURCE)
                            // A star given is never asked for again (CP76).
                            val store = SettingsStore(context.applicationContext)
                            com.tmplayer.App.backgroundScope.launch { runCatching { store.markSupportStarred() } }
                        },
                    ) {
                        androidx.compose.material3.Icon(TmIcons.StarOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        androidx.compose.material3.Text(s.supportStarGithub)
                    }
                }
                if (asking) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = onNever, modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Text(s.supportDontShow, textAlign = TextAlign.Center)
                        }
                        TmSecondaryButton(onClick = onLater, modifier = Modifier.weight(1f)) { Label(s.supportMaybeLater) }
                    }
                } else {
                    TmSecondaryButton(onClick = onLater) { Label(s.commonClose) }
                }
            }
        } else {
            val panel = min(maxWidth - PhonePad.Side * 2, 820.dp)
            Row(
                Modifier.width(panel).floatingSurface().padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoffeeCode(plate = 260.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                    SponsorsCode()
                    // A television has no browser and no UPI app, so UPI is a code for the phone
                    // in the viewer's hand, with the ID under it to type by hand (user, 2026-11-01).
                    UpiCode()
                    Text(s.supportStarNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                    // The card floats, so a button at rest takes the step above its fill.
                    CompositionLocalProvider(LocalOnFloating provides true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TmSecondaryButton(onClick = onLater, modifier = Modifier.focusRequester(calm)) {
                                Text(if (asking) s.supportMaybeLater else s.commonClose)
                            }
                            if (asking) TmSecondaryButton(onClick = onNever) { Text(s.supportDontShow) }
                        }
                    }
                }
            }
        }
    }

    if (!touch) {
        BackHandler(onBack = onLater)
        LaunchedEffect(Unit) { runCatching { calm.requestFocus() } }
    }
}

/**
 * Buy Me a Coffee's own button: their yellow, their near-black words and their cup, as their
 * official buttons are drawn ([BmcBrand]). For a phone, where it opens the page in the browser.
 */
@Composable
fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    if (!isTouch()) {
        // The remote's version, for About, where it opens the code: the same yellow at rest and
        // under the focus, which TV Material marks by growing the button and ringing it in ink.
        androidx.tv.material3.Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 56.dp),
            shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(Corner.Medium)),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = BmcBrand.Yellow,
                contentColor = BmcBrand.Ink,
                focusedContainerColor = BmcBrand.Yellow,
                focusedContentColor = BmcBrand.Ink,
                pressedContainerColor = BmcBrand.Yellow,
                pressedContentColor = BmcBrand.Ink,
            ),
            border = androidx.tv.material3.ButtonDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    androidx.compose.foundation.BorderStroke(3.dp, BmcBrand.Ink),
                    shape = RoundedCornerShape(Corner.Medium),
                ),
            ),
        ) {
            BmcCupMark(height = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(s.supportBuyCoffee, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(Corner.Medium),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = BmcBrand.Yellow,
            contentColor = BmcBrand.Ink,
        ),
    ) {
        BmcCupMark(height = 24.dp)
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Text(
            s.supportBuyCoffee,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The code for Buy Me a Coffee on their yellow: the cup and the name over a white plate with the
 * code, and the address under it in their ink. The yellow holds whatever the app's theme is.
 */
@Composable
private fun CoffeeCode(plate: Dp) {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = About.COFFEE) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.COFFEE, QR_PIXELS) }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(Corner.Large))
            .background(BmcBrand.Yellow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BmcCupMark(height = 22.dp)
            BasicText("Buy Me a Coffee", style = TextStyle(color = BmcBrand.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)) // i18n-ok: brand name
        }
        Box(
            Modifier.size(plate).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = s.supportScanCoffee,
                    // A scanner needs a quiet margin of the code's own white to find it.
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
        }
        BasicText(
            readableUrl(About.COFFEE),
            style = TextStyle(color = BmcBrand.Ink, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
}

/** GitHub Sponsors on a TV: a small code and its address, the quieter way beside the big one. */
@Composable
private fun SponsorsCode() {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = About.SPONSORS) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.SPONSORS, QR_PIXELS / 2) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(Corner.Small)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it, contentDescription = s.supportQrFor(s.supportSponsorGithub), modifier = Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.supportSponsorGithub, style = MaterialTheme.typography.titleSmall, color = Tone.text)
            Text(readableUrl(About.SPONSORS), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }
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

/**
 * Paying by UPI on a phone (user, 2026-11-01; HX75, 2026-11-02). The button hands [About.UPI_LINK]
 * to whichever UPI app is installed, and the system asks which one when there are several. The
 * window with the code, the ID and Copy opens as well, every time: the app that opened may be the
 * wrong one, or none may open ([ActivityNotFoundException] is caught and the window then says so),
 * and the viewer can still pay from their own app. Close, Back and a tap outside close it.
 *
 * Nothing is unlocked and nothing is checked afterwards: the app never learns whether anybody paid,
 * as with the coffee link.
 */
@Composable
private fun UpiPay(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    TmSecondaryButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(About.UPI_LINK))
            failed = runCatching { context.startActivity(intent) }.isFailure
            showing = true
        },
        modifier = modifier.heightIn(min = 56.dp),
    ) { Label(s.supportPayUpi) }

    if (showing) UpiDialog(failed = failed, onClose = { showing = false })
}

/** The window under "Pay by UPI": the code for another phone, the ID to copy, and Close. */
@Composable
private fun UpiDialog(failed: Boolean, onClose: () -> Unit) {
    val s = LocalStrings.current
    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        Column(
            Modifier
                .width(min(maxWidth - PhonePad.Side * 2, 440.dp))
                .floatingSurface()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.supportPayUpi, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
            Text(
                if (failed) s.supportUpiNoApp else s.supportUpiNote,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                textAlign = TextAlign.Center,
            )
            UpiPlate(plate = 200.dp)
            Text(s.supportScanUpi, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
            UpiIdRow()
            TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }
        }
    }
}

/** The UPI ID in full, with a button that copies it, for anybody typing it into their own app. */
@Composable
private fun UpiIdRow() {
    val s = LocalStrings.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.Text(
            "${s.supportUpiId}: ${About.UPI_ID}",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = Tone.text,
            textAlign = TextAlign.Center,
        )
        androidx.compose.material3.TextButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                // A refused or missing clipboard leaves the ID on screen to copy by hand.
                copied = runCatching {
                    clipboard?.setPrimaryClip(ClipData.newPlainText(About.UPI_ID, About.UPI_ID)) != null
                }.getOrDefault(false)
            },
        ) {
            androidx.compose.material3.Text(if (copied) s.supportUpiCopied else s.supportUpiCopy)
        }
    }
}

/** The UPI code on its white plate, the size a camera across a room can still read. */
@Composable
private fun UpiPlate(plate: Dp) {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = About.UPI_LINK) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.UPI_LINK, QR_PIXELS) }
    }
    Box(
        Modifier.size(plate).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            // A scanner needs a quiet margin of the code's own white to find it.
            Image(it, contentDescription = s.supportScanUpi, modifier = Modifier.fillMaxSize().padding(10.dp))
        }
    }
}

/** UPI on a television: the code, what it is for, and the ID under it. */
@Composable
private fun UpiCode() {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        UpiPlate(plate = 96.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.supportPayUpi, style = MaterialTheme.typography.titleSmall, color = Tone.text)
            Text(s.supportScanUpi, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            Text(About.UPI_ID, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }
}
