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
import com.tmplayer.data.SupportRegion
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.about.SupportDialog
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the shared support window does on the desktop: the browser, the clipboard (a refused one
 * leaves the ID on screen to copy by hand) and the star being remembered. A computer has no UPI
 * app, so there is no "Open in a UPI app": the code for the phone and the ID to copy are the way.
 */
@Composable
private fun rememberSupportActions(onStarred: () -> Unit = {}): SupportActions {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        SupportActions(
            openLink = { url ->
                OpenExternal.browse(url)
                true
            },
            copy = { text -> runCatching { clipboard.setText(AnnotatedString(text)) }.isSuccess },
            onStarred = onStarred,
        )
    }
}

/** The region's order on this computer: the language's region, since a computer has no SIM. */
private fun desktopOrder(): SupportRegion.Order = SupportRegion.order(java.util.Locale.getDefault().country, null)

/**
 * "Support TMPlayer" on the desktop, opened from Settings and About: the shared window with Buy Me
 * a Coffee and UPI as cards, the region's way first (D1). Nothing opens until a press.
 */
@Composable
fun SupportPopup(onClose: () -> Unit, onStarred: () -> Unit = {}) {
    SupportDialog(
        order = remember { desktopOrder() },
        title = About.SUPPORT_TITLE,
        text = About.SUPPORT_NOTE,
        actions = rememberSupportActions(onStarred = onStarred),
        onClose = onClose,
    )
}

/**
 * The gentle ask on the desktop, after real use and at most every two weeks (see [SupportReminder]):
 * the same shared window with "Maybe later" ([onLater]) and "Don't show again" ([onNever]). With
 * [asking] false it is the plain window with Close.
 */
@Composable
fun CoffeeDialog(
    counters: SupportReminder.Counters,
    asking: Boolean,
    onLater: () -> Unit,
    onNever: () -> Unit,
    onSupporter: (() -> Unit)? = null,
    onStarred: () -> Unit = {},
) {
    SupportDialog(
        order = remember { desktopOrder() },
        title = if (asking) About.reminderTitle(1) else About.SUPPORT_TITLE,
        text = if (asking) About.reminderText(1, counters) else About.SUPPORT_NOTE,
        actions = rememberSupportActions(onStarred = onStarred),
        onClose = onLater,
        onNever = if (asking) onNever else null,
        onSupporter = onSupporter,
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
    val playing = state.playerUp
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
            onStarred = { Background.scope.launch { runCatching { settings.markSupportStarred() } } },
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

private const val CARD_DELAY_MS = 4_000L
