package com.tmplayer.ui.connect

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.ProxyEntry
import com.tmplayer.data.ProxyKind
import com.tmplayer.data.ProxyLinks
import com.tmplayer.data.ProxySpec
import com.tmplayer.data.Td
import com.tmplayer.i18n.Messages
import androidx.compose.ui.platform.testTag
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.dpadLeavesFieldOnTv
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

/**
 * The proxy screen (CP77): paste a t.me/proxy, t.me/socks, tg://proxy or tg://socks link, or type
 * an MTProto, SOCKS5 or HTTP proxy in yourself; test it, switch to it, switch back to a direct
 * connection, remove it. TDLib stores the list in its own database, so it is there before sign in,
 * after a restart and after a sign out. Reached from the connecting help, its banner and Settings,
 * the same on a phone, a television and a desktop.
 */
@Composable
fun ProxyScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val form = deviceForm()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var entries by remember { mutableStateOf<List<ProxyEntry>>(emptyList()) }
    var pings by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val proxyOn by Td.proxyOn.collectAsState()
    val linkFocus = remember { FocusRequester() }

    // What the last Use, Remove or Stop answered when it failed, shown under the status line: a
    // press that fails with nothing said looks like a dead button.
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { entries = Td.proxies() }

    // One proxy action: its failure, a returned error or a thrown one, becomes the notice; the list
    // is read again either way so the screen shows what TDLib really holds.
    fun act(block: suspend () -> String?) {
        scope.launch {
            notice = null
            notice = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                s.errorsNoAnswer
            }
            runCatching { reload() }
        }
    }

    suspend fun ping(entry: ProxyEntry) {
        pings = pings + (entry.id to s.proxyPinging)
        val (ms, error) = Td.pingProxy(entry.spec)
        pings = pings + (entry.id to (ms?.let { s.proxyPingMs(it.toString()) } ?: error ?: s.proxyPingFailed))
    }

    // Added and switched on in one go, then tested, so the viewer sees at once whether it works.
    fun add(spec: ProxySpec, onAdded: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            // Busy always ends, whatever TDLib did: a stuck "Testing..." button cannot be pressed again.
            try {
                val error = Td.addProxy(spec, enable = true)
                if (error == null) {
                    onAdded()
                    reload()
                    entries.firstOrNull { it.spec == spec }?.let { ping(it) }
                } else {
                    linkError = error
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                linkError = s.errorsNoAnswer
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        if (form == DeviceForm.Tv) runCatching { linkFocus.requestFocus() }
    }

    val outer = when (form) {
        DeviceForm.Tv -> Modifier.padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        DeviceForm.Desktop -> Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
        DeviceForm.Phone -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
    }
    Box(modifier.fillMaxSize().background(Tone.background).then(outer), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuietButton(s.commonBack, icon = true, onClick = onClose)
            Text(s.proxyTitle, style = MaterialTheme.typography.headlineMedium, color = Tone.text)
            Text(s.proxyBlurb, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            Text(
                if (proxyOn) entries.firstOrNull { it.enabled }?.let { s.proxySettingsDetailOn(it.spec.address) } ?: s.proxyInUse else s.proxyDirect,
                style = MaterialTheme.typography.labelLarge,
                color = if (proxyOn) Tone.accent else Tone.muted,
            )

            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.danger, modifier = Modifier.liveMessage()) }

            val submitLink = {
                val spec = ProxyLinks.parse(link)
                if (spec == null) linkError = s.proxyLinkInvalid else add(spec) { link = "" }
            }
            ProxyLinkField(
                link = link,
                onChange = { link = it; linkError = null },
                error = linkError,
                onSubmit = { submitLink() },
                s = s,
                modifier = Modifier.focusRequester(linkFocus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SetupButton(if (busy) s.proxyPinging else s.proxyAdd, primary = true, onClick = submitLink)
                QuietButton(s.proxyByHand, onClick = { manual = !manual })
            }
            if (manual) {
                ManualProxy(busy = busy, onAdd = { spec -> add(spec) { manual = false } }, s = s)
            }

            Spacer(Modifier.height(4.dp))
            Text(s.proxySaved, style = MaterialTheme.typography.titleMedium, color = Tone.text)
            if (entries.isEmpty()) {
                Text(s.proxyNone, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
            for (entry in entries) {
                ProxyRow(
                    entry = entry,
                    ping = pings[entry.id],
                    onUse = { act { Td.enableProxy(entry.id).also { if (it == null) ping(entry) } } },
                    onTest = { scope.launch { runCatching { ping(entry) } } },
                    onRemove = { act { Td.removeProxy(entry.id) } },
                    s = s,
                )
            }
            if (proxyOn) {
                QuietButton(s.proxyStop, onClick = { act { Td.disableProxy() } })
            }
            Text(s.proxyTrust, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProxyRow(
    entry: ProxyEntry,
    ping: String?,
    onUse: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    s: Messages,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.enabled) {
                Icon(TmIcons.CheckCircle, contentDescription = s.proxyInUse, tint = Tone.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(entry.spec.address, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.weight(1f))
            Text(kindLabel(entry.spec.kind, s), style = MaterialTheme.typography.labelMedium, color = Tone.muted)
        }
        if (ping != null) Text(ping, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!entry.enabled) QuietButton(s.proxyUse, onClick = onUse)
            QuietButton(s.proxyTest, onClick = onTest)
            QuietButton(s.commonRemove, onClick = onRemove)
        }
    }
}

/** The box a proxy link is pasted into; a television's remote leaves it with the arrows. */
@Composable
fun ProxyLinkField(
    link: String,
    onChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    s: Messages,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = link,
        onValueChange = onChange,
        label = { Text(s.proxyLinkLabel) },
        placeholder = { Text("https://t.me/proxy?server=…") }, // i18n-ok: a link, the same in every language
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier.fillMaxWidth().dpadLeavesFieldOnTv().testTag(PROXY_LINK_TAG),
    )
}

/** The fields of a proxy typed in by hand, with the kind picked first so only its fields show. */
@Composable
fun ManualProxy(busy: Boolean, onAdd: (ProxySpec) -> Unit, s: Messages) {
    var kind by remember { mutableStateOf(ProxyKind.Mtproto) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in ProxyKind.entries) {
                SetupButton(kindLabel(k, s), primary = k == kind, onClick = { kind = k; error = null })
            }
        }
        val field: @Composable (String, String, KeyboardType, Boolean, ImeAction, (String) -> Unit) -> Unit = { label, value, type, masked, ime, change ->
            OutlinedTextField(
                value = value,
                onValueChange = { change(it); error = null },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                // Next between the fields and Done on the last; no autocorrect on an address or a secret.
                keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().dpadLeavesFieldOnTv().testTag(PROXY_FIELD_TAG + label),
            )
        }
        field(s.proxyServer, server, KeyboardType.Uri, false, ImeAction.Next) { server = it.trim() }
        field(s.proxyPort, port, KeyboardType.Number, false, ImeAction.Next) { port = it.filter(Char::isDigit).take(5) }
        if (kind == ProxyKind.Mtproto) {
            field(s.proxySecret, secret, KeyboardType.Ascii, false, ImeAction.Done) { secret = it.trim() }
        } else {
            field(s.proxyUsername, user, KeyboardType.Ascii, false, ImeAction.Next) { user = it }
            field(s.proxyPassword, pass, KeyboardType.Password, true, ImeAction.Done) { pass = it }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.danger, modifier = Modifier.liveMessage()) }
        SetupButton(if (busy) s.proxyPinging else s.proxyAdd, primary = true, onClick = {
            val spec = ProxySpec(kind, server, port.toIntOrNull() ?: 0, secret = secret, username = user, password = pass)
            if (spec.isComplete) onAdd(spec) else error = s.proxyFormInvalid
        })
    }
}

private fun kindLabel(kind: ProxyKind, s: Messages): String = when (kind) {
    ProxyKind.Mtproto -> s.proxyKindMtproto
    ProxyKind.Socks5 -> s.proxyKindSocks5
    ProxyKind.Http -> s.proxyKindHttp
}

/**
 * Whether the proxy screen is up. One switch for the window, since it is reached from three places
 * (the connecting help, its banner and Settings) and drawn by one [ProxyScreenHost] over them all.
 */
object ProxyScreenState {
    var open by mutableStateOf(false)
}

/** [ProxyScreen] over everything while [ProxyScreenState.open]; put it last in the window's `Box`. */
@Composable
fun ProxyScreenHost(modifier: Modifier = Modifier) {
    if (ProxyScreenState.open) ProxyScreen(onClose = { ProxyScreenState.open = false }, modifier = modifier)
}

/** Test tag of the proxy link box, and the prefix of each manual field's tag (plus its label). */
const val PROXY_LINK_TAG = "proxy-link"
const val PROXY_FIELD_TAG = "proxy-field:"
