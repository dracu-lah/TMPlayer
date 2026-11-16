package com.tmplayer.cast

import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.ui.browse.PageDiscButton
import com.tmplayer.ui.components.AddTvDialog
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.delay

/**
 * The Cast picker, the app's picker sheet: the Chromecasts and Google TVs on the Wi-Fi and the TVs
 * that take a DLNA push (marked DLNA), found while it is open, the one in use marked, and Stop
 * casting under them during a cast. Without Wi-Fi or Ethernet it says to join the TV's network. Picking one starts
 * the session and the sheet stays up, the TV's line saying it is connecting, until the session is
 * up (then it closes) or has failed (then it says so, and another try is a tap away). Closed at
 * once, it left nothing on the screen to say anything was happening. The player (or the next video
 * played) goes to the TV once the session is up.
 */
@Composable
fun CastPickerSheet(support: CastSupport, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    // Android 17 and an app that targets it: the local network permission first. Everywhere else
    // (today's target 35 included) it is granted from the start and nothing is asked.
    var allowed by remember { mutableStateOf(LocalNetworkPermission.granted(context)) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!allowed) runCatching { ask.launch(LocalNetworkPermission.NAME) }.onFailure { denied = true } }
    DisposableEffect(support, allowed) {
        if (allowed) support.startDiscovery()
        onDispose { if (allowed) support.stopDiscovery() }
    }
    var adding by remember { mutableStateOf(false) }
    val devices by support.devices.collectAsState()
    val session by support.session.collectAsState()
    val offLan by support.offLan.collectAsState()
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(NONE_FOUND_AFTER_MS)
        waitedLong = true
    }
    val connected = session is CastSessionState.Connected
    // The TV just picked: the sheet waits for it.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(session, asked) {
        if (asked && session is CastSessionState.Connected) onDismiss()
    }
    val failed = (session as? CastSessionState.Failed)?.takeIf { asked }
    ChoiceSheet(
        title = s.castButton,
        onDismiss = onDismiss,
        onClose = onDismiss,
        note = when {
            failed != null -> s.castConnectFailed(failed.name)
            denied -> s.castLocalNetworkDenied
            devices.isNotEmpty() -> deviceCounts(devices).let { s.castCounts(cast = it.first, dlna = it.second) }
            offLan -> s.castWifiOnly
            waitedLong -> s.castNoneFoundPhone
            else -> s.castSearching
        },
    ) {
        devices.forEach { d ->
            ChoiceLine(
                title = d.name,
                detail = if (d.connecting) s.castConnecting(d.name) else deviceDetail(d, s.castDlnaLabel),
                selected = if (connected) d.connected else null,
                icon = TmIcons.Tv,
            ) {
                if (d.connected) {
                    onDismiss()
                } else if (!d.connecting) {
                    asked = true
                    support.connect(d.id)
                }
            }
        }
        if (support.manualTvs != null && allowed) {
            ChoiceLine(title = s.castAddEntry, icon = TmIcons.Add) { adding = true }
        }
        if (connected) {
            ChoiceLine(title = s.castStop, icon = TmIcons.Close) {
                support.endSession()
                onDismiss()
            }
        }
    }
    val manual = support.manualTvs
    if (adding && manual != null) {
        val saved by manual.saved.collectAsState()
        AddTvDialog(saved = saved, onAdd = manual::add, onRemove = manual::remove, onDismiss = { adding = false })
    }
}

/** How many Cast TVs and how many DLNA ones are listed: "Cast 1 · DLNA 0" tells a person DLNA found nothing. */
internal fun deviceCounts(devices: List<CastDeviceInfo>): Pair<Int, Int> {
    val dlna = devices.count { it.kind == CastKind.Dlna }
    return (devices.size - dlna) to dlna
}

/**
 * The detail page's Cast button, on the page's round disc: opens the picker, so the video plays on
 * the TV from the moment Play is pressed. Drawn only where casting exists (a phone or tablet; Google
 * Cast in the regular build, DLNA in both) and only once a TV has been found or a cast is under way
 * ([CastPresence], FQ8): a page in sight looks for 15 s and then stops by itself.
 */
@Composable
fun CastPageButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val support = remember(context) { Cast.support(context) }
    if (!support.available) return
    val shown by rememberCastShown(support)
    var open by remember { mutableStateOf(false) }
    // An open picker keeps its button, whatever the search decides meanwhile.
    if (!shown && !open) return
    val s = LocalStrings.current
    val session by support.session.collectAsState()
    val connected = session as? CastSessionState.Connected
    val label = connected?.let { s.castCastingTo(it.name) } ?: s.castButton
    // Filled in the hero colour while a TV is connected; the same size as the page's Back.
    PageDiscButton(TmIcons.Cast, label, { open = true }, modifier, active = connected != null, prominent = true)
    if (open) CastPickerSheet(support, onDismiss = { open = false })
}

/**
 * Whether a screen draws its Cast button ([CastPresence]): the bounded search runs while the
 * screen's lifecycle is started and stops when it is not, or after [CastPresenceScan.SCAN_MS].
 */
@Composable
fun rememberCastShown(support: CastSupport): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scan = remember(support) { CastPresenceScan(support, scope, canSearch = { LocalNetworkPermission.granted(context) }) }
    DisposableEffect(scan, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> scan.start()
                Lifecycle.Event.ON_STOP -> scan.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            scan.stop()
        }
    }
    return scan.shown.collectAsState()
}

/** "Model" for a Cast TV, "Model · DLNA" for one found by DLNA (the protocol's name, in small print). */
internal fun deviceDetail(d: CastDeviceInfo, dlnaLabel: String): String? {
    val parts = listOfNotNull(d.model.ifBlank { null }, dlnaLabel.takeIf { d.kind == CastKind.Dlna })
    return parts.joinToString(" \u00B7 ").ifBlank { null }
}

private const val NONE_FOUND_AFTER_MS = 8_000L
