package com.tmplayer.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ConnectAdvice
import com.tmplayer.data.ConnectHint
import com.tmplayer.data.ConnectStep
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.NetworkFacts
import com.tmplayer.data.NetworkNote
import com.tmplayer.data.Td
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the connecting screen says it is doing right now, read off TDLib's own connection state, so
 * the line moves when something really moves and never claims progress that is not happening.
 */
fun connectStepLabel(step: ConnectStep, s: Messages): String = when (step) {
    ConnectStep.Starting -> s.connectStepStarting
    ConnectStep.WaitingForNetwork -> s.connectStepWaitingNetwork
    ConnectStep.ConnectingToProxy -> s.connectStepProxy
    ConnectStep.Connecting -> s.connectStepConnecting
    ConnectStep.CatchingUp -> s.connectStepCatchingUp
    ConnectStep.Opening -> s.connectStepOpening
}

fun connectHintLabel(hint: ConnectHint, s: Messages): String = when (hint) {
    ConnectHint.Offline -> s.connectHintOffline
    ConnectHint.CaptivePortal -> s.connectHintPortal
    ConnectHint.ProxyNotAnswering -> s.connectHintProxy
    ConnectHint.Vpn -> s.connectHintVpn
    ConnectHint.MaybeBlocked -> s.connectHintBlocked
    ConnectHint.NotAnswering -> s.connectHintNotAnswering
}

/** "This device reports: Wi-Fi, VPN on", or null where the platform reports nothing. */
fun networkLine(network: NetworkFacts, s: Messages): String? {
    val notes = ConnectAdvice.networkNotes(network).takeIf { it.isNotEmpty() } ?: return null
    val words = notes.map {
        when (it) {
            NetworkNote.None -> s.connectNetNone
            NetworkNote.WiFi -> s.connectNetWifi
            NetworkNote.Mobile -> s.connectNetMobile
            NetworkNote.Roaming -> s.connectNetRoaming
            NetworkNote.Other -> s.connectNetOther
            NetworkNote.Vpn -> s.connectNetVpn
            NetworkNote.Portal -> s.connectNetPortal
            NetworkNote.Unchecked -> s.connectNetUnchecked
        }
    }
    return s.connectNetwork(words.joinToString(", "))
}

/**
 * The status line under the loader: what is happening now (the mark above it moves while it does).
 * A live region, so a screen reader hears the step change rather than nothing at all.
 */
@Composable
fun LiveStatus(text: String, modifier: Modifier = Modifier) {
    // Not drawn: the loader is one quiet spinner (user, 2026-10-23), but a screen reader still
    // hears each step as it changes.
    Box(modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = text
    })
}

/**
 * Everything under the loader while the sign in waits on Telegram: the live step, and once
 * [Td.signInStalled] says the wait has gone on too long, what is most likely wrong with a Retry,
 * the proxy screen ([onOpenProxies]) and [sendLogs]. Retry has the focus on a television, so one
 * press of the remote's centre button does the likeliest useful thing.
 */
@Composable
fun ConnectingHelp(onOpenProxies: () -> Unit, sendLogs: @Composable () -> Unit) {
    val s = LocalStrings.current
    val link by Td.link.collectAsState()
    val stalled by Td.signInStalled.collectAsState()
    val network by Td.network.collectAsState()
    val proxyOn by Td.proxyOn.collectAsState()
    val ended by Td.sessionEnded.collectAsState()
    val evidence by Td.lineEvidence.collectAsState()
    val retry = rememberRetry()
    val hint = ConnectAdvice.hint(link, network, proxyOn, evidence)
    ConnectingHelpView(
        status = when {
            retry.busy -> s.connectRetrying
            // Telegram ended the saved sign in: not a network problem, and the sign in comes next.
            // Only when Telegram ended it (HX23): the viewer's own Sign out and a client being
            // replaced pass through the same logout states, and neither is that.
            ended -> s.connectSessionEnded
            else -> connectStepLabel(ConnectAdvice.step(link), s)
        },
        stalled = stalled && !retry.busy,
        hint = connectHintLabel(hint, s),
        network = networkLine(network, s),
        // "Use a proxy" only with a sign of a block; otherwise the button reads "Proxy settings".
        proxyOn = !ConnectAdvice.suggestsProxy(hint, proxyOn),
        onRetry = retry.run,
        onOpenProxies = onOpenProxies,
        sendLogs = sendLogs,
    )
}

/** [ConnectingHelp] as plain values, for a test to draw any of its states. */
@Composable
fun ConnectingHelpView(
    status: String,
    stalled: Boolean,
    hint: String,
    network: String?,
    proxyOn: Boolean,
    onRetry: () -> Unit,
    onOpenProxies: () -> Unit,
    sendLogs: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LiveStatus(status)
        AnimatedVisibility(stalled, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
            HelpBody(hint, network, proxyOn, onRetry, onOpenProxies, sendLogs)
        }
    }
}

@Composable
private fun HelpBody(
    hint: String,
    network: String?,
    proxyOn: Boolean,
    onRetry: () -> Unit,
    onOpenProxies: () -> Unit,
    sendLogs: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    val form = deviceForm()
    val focus = remember { FocusRequester() }
    Column(
        Modifier.widthIn(max = 480.dp).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s.connectSlowTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text, textAlign = TextAlign.Center)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
        if (network != null) {
            Text(network, style = MaterialTheme.typography.labelMedium, color = Tone.muted.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        val buttons: @Composable () -> Unit = {
            SetupButton(s.commonRetry, primary = true, modifier = Modifier.focusRequester(focus), onClick = onRetry)
            SetupButton(if (proxyOn) s.connectProxyOn else s.connectUseProxy, onClick = onOpenProxies)
        }
        if (form == DeviceForm.Phone) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
        sendLogs()
    }
    // A remote has nothing else to land on; a desktop and a phone are left as they are.
    if (form == DeviceForm.Tv) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The strip for a screen that is already drawn when Telegram goes out of reach: a sign in pane
 * with no line yet, or a signed in session whose network is up and Telegram is not answering
 * ([Td.unreachable]). Retry and the proxy screen, and the same hint the loader would give.
 */
@Composable
fun ConnectBanner(onOpenProxies: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val link by Td.link.collectAsState()
    val network by Td.network.collectAsState()
    val proxyOn by Td.proxyOn.collectAsState()
    val evidence by Td.lineEvidence.collectAsState()
    val retry = rememberRetry()
    // A block is said, and a proxy suggested, only with a sign of one (HX23): on the Mi TV this
    // banner told a session Telegram had ended to try a proxy, on a network that was fine.
    val hint = ConnectAdvice.hint(link, network, proxyOn, evidence)
    ConnectBannerView(
        text = if (retry.busy) s.connectRetrying else connectHintLabel(hint, s),
        proxyOn = !ConnectAdvice.suggestsProxy(hint, proxyOn),
        onRetry = retry.run,
        onOpenProxies = onOpenProxies,
        modifier = modifier,
    )
}

/** [ConnectBanner] as plain values. */
@Composable
fun ConnectBannerView(text: String, proxyOn: Boolean, onRetry: () -> Unit, onOpenProxies: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val phone = deviceForm() == DeviceForm.Phone
    Column(
        modifier
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.WifiOff, contentDescription = null, tint = Tone.caution, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.connectBanner, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        val buttons: @Composable () -> Unit = {
            QuietButton(s.commonRetry, onClick = onRetry)
            QuietButton(if (proxyOn) s.connectProxyOn else s.connectUseProxy, onClick = onOpenProxies)
        }
        if (phone) Row { buttons() } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

/** A Retry that says "Trying again" for a moment, so a press that changes nothing yet still shows. */
private class Retry(val run: () -> Unit, private val state: androidx.compose.runtime.MutableState<Boolean>) {
    val busy: Boolean get() = state.value
}

@Composable
private fun rememberRetry(): Retry {
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    return remember {
        Retry(
            run = {
                if (!busy.value) {
                    busy.value = true
                    scope.launch {
                        try {
                            Td.retryConnecting()
                            delay(RETRY_SHOWN_MS)
                        } finally {
                            busy.value = false
                        }
                    }
                }
            },
            state = busy,
        )
    }
}

/** Whether the sign in screens should carry [ConnectBanner]: stalled, past the loader. */
@Composable
fun signInBannerShown(): Boolean {
    val stalled by Td.signInStalled.collectAsState()
    val auth by Td.auth.collectAsState()
    val link by Td.link.collectAsState()
    return stalled && auth != com.tmplayer.data.AuthState.Connecting && ConnectAdvice.waiting(auth, link)
}

private const val PULSE_MS = 900
private const val FADE_MS = 300
private const val RETRY_SHOWN_MS = 2_500L
