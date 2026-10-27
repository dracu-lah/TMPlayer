package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineStatus
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Online subtitles, on the desktop: the same rows as phone and TV (account and today's
 * downloads, machine translations, the SubDL key, the cache), drawn as this page's own rows. Only
 * in a build with the OpenSubtitles key.
 */
@Composable
internal fun OnlineSubtitlesGroup() {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current?.takeIf { it.inBuild } ?: return
    val account by online.account.collectAsState()
    val status = online.status(account)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var signingIn by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }

    Group(s.onlineTitle)
    when (status) {
        OnlineStatus.NotInBuild, OnlineStatus.Unavailable ->
            Setting(s.onlineTitle, OnlineWords.status(status), titleColor = Tone.muted, icon = TmIcons.Warning) {}
        OnlineStatus.SignedOut, is OnlineStatus.Expired, is OnlineStatus.FreeQuotaUsed ->
            Action(s.onlineSignIn, OnlineWords.status(status), TmIcons.Account, value = s.onlineSignInButton) { signingIn = true }
        is OnlineStatus.SignedIn, is OnlineStatus.QuotaUsed -> Action(
            s.onlineSignedIn(account.username),
            OnlineWords.quota(status),
            TmIcons.Account,
            titleColor = if (status is OnlineStatus.QuotaUsed) Tone.caution else androidx.compose.ui.graphics.Color.Unspecified,
            value = s.onlineSignOut,
        ) { signingOut = true }
    }
    Toggle(
        s.onlineMachineToggle,
        if (account.includeMachine) s.onlineMachineOn else s.onlineMachineOff,
        account.includeMachine,
        icon = TmIcons.Language,
    ) { online.setIncludeMachine(it) }
    Action(
        s.onlineSubdl,
        when {
            account.subdlKey.isBlank() -> s.onlineSubdlNone
            account.subdlRefused -> s.onlineSubdlRefused
            else -> s.onlineSubdlSet
        },
        TmIcons.Lock,
    ) { editingKey = true }
    MsoneSetting()
    Action(
        s.onlineClearCache,
        if (bytes > 0) s.onlineCacheDetail(Translator.messages.formatter.size(bytes)) else s.onlineCacheEmpty,
        TmIcons.Delete,
        end = RowEnd.None,
        enabled = bytes > 0,
    ) { clearingCache = true }
    if (clearingCache) {
        ConfirmDialog(
            title = s.confirmOnlineCacheTitle,
            message = s.confirmOnlineCacheMessage(Translator.messages.formatter.size(bytes)),
            confirmLabel = s.commonClear,
            onDismiss = { clearingCache = false },
            onConfirm = {
                clearingCache = false
                scope.launch {
                    withContext(Dispatchers.IO) { online.purge() }
                    bytes = 0
                    toast(s.onlineCacheCleared)
                }
            },
        )
    }

    // The same question the phone and the television ask: signing back in needs the password.
    if (signingOut) {
        ConfirmDialog(
            title = s.onlineSignOut,
            message = OnlineWords.status(status),
            confirmLabel = s.onlineSignOut,
            destructive = false,
            onDismiss = { signingOut = false },
            onConfirm = {
                signingOut = false
                scope.launch { online.signOut() }
            },
        )
    }
    if (signingIn) SignInDialog(onClose = { signingIn = false })
    if (editingKey) SubdlKeyDialog(onClose = { editingKey = false })
}

/**
 * MSone's state at the gateway, on a device where its search was switched on by hand (`msone`
 * typed as the SubDL key): the picker's line, the gateway's own words after it, and Check again.
 */
@Composable
private fun MsoneSetting() {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val account by online.account.collectAsState()
    if (!account.msoneShown) return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var checking by remember { mutableStateOf(false) }
    // The cached answer unless six hours have passed; a failure leaves the last one standing.
    LaunchedEffect(Unit) { online.msoneStatus() }
    val line = OnlineWords.msoneState(account.msoneState)
    Action(
        s.msoneTitle,
        when {
            checking -> s.msoneChecking
            account.msoneMessage.isNotBlank() -> "$line ${account.msoneMessage}"
            else -> line
        },
        TmIcons.Language,
        titleColor = if (account.msoneState == com.tmplayer.online.MsoneState.On) androidx.compose.ui.graphics.Color.Unspecified else Tone.muted,
        value = s.msoneCheckAgain,
        enabled = !checking,
    ) {
        checking = true
        scope.launch {
            val status = online.msoneStatus(force = true)
            checking = false
            toast(OnlineWords.msoneState(status.state))
        }
    }
}

@Composable
internal fun SignInDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var username by remember { mutableStateOf(online.store.now.username) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.onlineSignIn) },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.onlineSignInMessage)
                OutlinedTextField(username, { username = it }, label = { Text(s.onlineUsername) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(s.onlinePassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Tone.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    when (val result = online.signIn(username, password)) {
                        OnlineSubtitles.SignIn.Ok -> {
                            toast(s.onlineSignedIn(username.trim()))
                            onClose()
                        }
                        OnlineSubtitles.SignIn.WrongPassword -> error = s.onlineWrongPassword
                        is OnlineSubtitles.SignIn.Failed -> error = OnlineWords.notice(result.notice)
                    }
                    busy = false
                }
            }) { Text(if (busy) s.onlineSigningIn else s.onlineSignInButton) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}

@Composable
private fun SubdlKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val toast = rememberToast()
    var key by remember { mutableStateOf(online.store.now.subdlKey) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.onlineSubdlTitle) },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.onlineSubdlMessage)
                OutlinedTextField(key, { key = it }, label = { Text(s.onlineSubdl) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // `msone` is the hidden switch for MSone's search, not a key.
                online.setSubdlKey(key)?.let { shown -> toast(if (shown) s.msoneHiddenOn else s.msoneHiddenOff) }
                onClose()
            }) { Text(s.commonOk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}
