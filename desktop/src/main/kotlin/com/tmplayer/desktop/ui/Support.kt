package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SupportReminder
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.platform.Background
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
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
        icon = { Icon(TmIcons.Favorite, contentDescription = null, tint = Tone.accent) },
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
 * The Buy Me a Coffee window on the desktop: the ask itself when [asking] (after real use, at most
 * every two weeks, see [SupportReminder]), or the same window opened from About.
 *
 * Buy Me a Coffee comes first and in its own colours: a card on their yellow with a code for paying
 * from the phone, which is also the link that opens the page in the browser. GitHub Sponsors and
 * a star on GitHub are the quieter links beside it. "Don't show again" ends the asks and the notification for good.
 */
@Composable
fun CoffeeDialog(
    counters: SupportReminder.Counters,
    asking: Boolean,
    onLater: () -> Unit,
    onNever: () -> Unit,
    onSupporter: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onLater,
        title = { Text(if (asking) About.reminderTitle(1) else About.SUPPORT_TITLE) },
        text = {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    if (asking) About.reminderText(1, counters) else About.SUPPORT_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val links: @Composable () -> Unit = {
                    TextButton(onClick = { OpenExternal.browse(About.SPONSORS) }) { Text(s.supportSponsorGithub) }
                    // Buy Me a Coffee takes cards and PayPal, so UPI stands beside it for anybody
                    // who pays that way (user, 2026-11-01). A computer has no UPI app, so it is the
                    // code for the phone in the viewer's hand and the ID to copy, never a button
                    // that would open nothing.
                    UpiRow()
                    // A star costs nothing and is worth asking for too (user, 2026-10-18).
                    TextButton(onClick = { OpenExternal.browse(About.SOURCE) }) {
                        Icon(TmIcons.StarOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.supportStarGithub)
                    }
                    // The browser build's way out for a viewer who already gives: no more
                    // windows, no coffee button, and About says thank you (coffeeOffersSupporter).
                    if (onSupporter != null) {
                        TextButton(onClick = onSupporter) { Text(s.webCoffeeAlready) }
                    }
                }
                // The card is the Buy Me a Coffee link as well as its code, so the window has one
                // way to it rather than a card and a button saying the same (user, 2026-10-18).
                // On a phone's width (the browser build) the links go under the card, not beside it.
                BoxWithConstraints {
                    if (maxWidth >= STACK_BELOW) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoffeeCode(onOpen = { OpenExternal.browse(About.COFFEE) })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { links() }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CoffeeCode(onOpen = { OpenExternal.browse(About.COFFEE) })
                            Spacer(Modifier.size(8.dp))
                            links()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text(if (asking) s.supportMaybeLater else s.commonClose) } },
        dismissButton = if (asking) ({ TextButton(onClick = onNever) { Text(s.supportDontShow) } }) else null,
    )
}

/** Buy Me a Coffee's own button: their yellow, their near-black words and their cup ([BmcBrand]). */
@Composable
fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(Corner.Medium),
        colors = ButtonDefaults.buttonColors(containerColor = BmcBrand.Yellow, contentColor = BmcBrand.Ink),
    ) {
        BmcCupMark(height = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(LocalStrings.current.supportBuyCoffee, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The code for Buy Me a Coffee on their yellow, with their cup, their name and the address in
 * their ink. It is also the link: a click opens the page in the browser, and while the pointer is
 * over it or the keyboard is on it, an arrow in the top end corner says it leads out of the app and
 * the card lifts a little.
 */
@Composable
private fun CoffeeCode(onOpen: () -> Unit) {
    val s = LocalStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = About.COFFEE) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.COFFEE, QR_PIXELS) }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val lit = hovered || focused
    val lift by animateFloatAsState(if (lit) 1.03f else 1f, label = "coffee-lift")
    val shape = RoundedCornerShape(Corner.Large)
    Box(
        Modifier
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(shape)
            .background(BmcBrand.Yellow)
            .then(if (focused) Modifier.border(2.dp, BmcBrand.Ink, shape) else Modifier)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = s.supportBuyCoffee },
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BmcCupMark(height = 18.dp)
                Text("Buy Me a Coffee", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = BmcBrand.Ink) // i18n-ok: brand name
            }
            Box(
                Modifier.size(150.dp).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it, contentDescription = s.supportScanCoffee, modifier = Modifier.fillMaxSize().padding(8.dp)) }
            }
            Text(
                About.COFFEE.removePrefix("https://"),
                style = MaterialTheme.typography.labelSmall,
                color = BmcBrand.Ink,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(
            visible = lit,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Icon(TmIcons.OpenExternal, contentDescription = null, tint = BmcBrand.Ink, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Counts the videos the window finishes, and puts the Buy Me a Coffee window up at a good moment:
 * back on Home, Chats or Downloads after a watch to the end, or after a download finished, or as
 * soon as it may after its notification was pressed. Never over the player or the update popup.
 * It is forced on with `-Dtmplayer.supportRung=N` (any N above 0) or `TMPLAYER_SUPPORT_RUNG=N`,
 * and `-Dtmplayer.supportReminder=true`.
 */
@Composable
fun SupportAskHost(state: ShellState) {
    var asking by remember { mutableStateOf(false) }
    var counters by remember { mutableStateOf(SupportReminder.Counters()) }
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
    val openRequested by SupportReminder.openRequested.collectAsState()
    val playing = state.nowPlaying != null
    val onGoodPage = state.destination == Destination.Home || state.destination == Destination.Chats ||
        state.destination == Destination.Downloads
    LaunchedEffect(moment, openRequested, playing, onGoodPage, state.updatePopup, asking) {
        if (playing || !onGoodPage || state.updatePopup || asking) return@LaunchedEffect
        if (openRequested) {
            // Its notification was pressed: the window the viewer asked for, counted as an ask.
            delay(CARD_DELAY_MS / 4)
            if (!SupportReminder.takeOpenRequest()) return@LaunchedEffect
            val now = System.currentTimeMillis()
            counters = runCatching { settings.supportCountersNow() }.getOrNull() ?: SupportReminder.Counters()
            runCatching { settings.recordSupportAsk(now) }
            SupportReminder.sessionShown = true
            asking = true
            return@LaunchedEffect
        }
        if (SupportReminder.forcedRung > 0) {
            if (forcedShown) return@LaunchedEffect
            delay(CARD_DELAY_MS)
            forcedShown = true
            counters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)
            asking = true
            return@LaunchedEffect
        }
        if (moment == 0L) return@LaunchedEffect
        delay(CARD_DELAY_MS)
        if (!SupportReminder.takeMoment(System.currentTimeMillis())) return@LaunchedEffect
        val before = runCatching { settings.supportCountersNow() }.getOrNull() ?: return@LaunchedEffect
        if (runCatching { SupportReminder.claim(settings, System.currentTimeMillis()) }.getOrDefault(false)) {
            counters = before
            asking = true
        }
    }

    if (asking && !playing && !state.updatePopup) {
        CoffeeDialog(
            counters = counters,
            asking = true,
            onLater = { asking = false },
            onNever = {
                asking = false
                if (SupportReminder.forcedRung == 0) Background.scope.launch { runCatching { settings.markSupportNever() } }
            },
            onSupporter = if (coffeeOffersSupporter) {
                {
                    asking = false
                    if (SupportReminder.forcedRung == 0) Background.scope.launch { runCatching { settings.markSupporter() } }
                }
            } else {
                null
            },
        )
    }
}

/**
 * Whether the asking window offers "I already support" as well. Off in the desktop app, whose
 * window ends with "Don't show again"; the browser build turns it on (web Main.kt), since its
 * coffee button is in sight all the time and a viewer who already gives needs a way to say so.
 */
var coffeeOffersSupporter: Boolean = false

/** Below this width the coffee window's links sit under the card. */
private val STACK_BELOW = 400.dp

private val PLATE = 180.dp
private const val QR_PIXELS = 400
private const val CARD_DELAY_MS = 4_000L

/**
 * Paying by UPI on a computer (user, 2026-11-01): the code a phone's UPI app reads, the ID beside
 * it, and a button that copies the ID for whoever would rather type it in their own app. There is
 * no `upi://` handler on Windows, Linux or macOS, so nothing here pretends to open one.
 */
@Composable
private fun UpiRow() {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = About.UPI_LINK) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.UPI_LINK, QR_PIXELS) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(104.dp).clip(RoundedCornerShape(Corner.Small)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            // A scanner needs a quiet margin of the code's own white to find it.
            bitmap?.let { Image(it, contentDescription = s.supportScanUpi, modifier = Modifier.fillMaxSize().padding(8.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.supportPayUpi, style = MaterialTheme.typography.titleSmall)
            Text(s.supportScanUpi, style = MaterialTheme.typography.bodySmall)
            Text(About.UPI_ID, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    // A refused clipboard leaves the ID on screen to copy by hand.
                    copied = runCatching { clipboard.setText(AnnotatedString(About.UPI_ID)) }.isSuccess
                },
            ) { Text(if (copied) s.supportUpiCopied else s.supportUpiCopy) }
        }
    }
}
