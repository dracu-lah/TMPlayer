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
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }

    Group(s.onlineTitle)
    when (status) {
        OnlineStatus.NotInBuild, OnlineStatus.Unavailable ->
            Setting(s.onlineTitle, OnlineWords.status(status), titleColor = Tone.muted) {}
        OnlineStatus.SignedOut, is OnlineStatus.Expired, is OnlineStatus.FreeQuotaUsed -> Setting(s.onlineSignIn, OnlineWords.status(status)) {
            OutlinedButton(onClick = { signingIn = true }) { Text(s.onlineSignInButton) }
        }
        is OnlineStatus.SignedIn, is OnlineStatus.QuotaUsed -> Setting(
            s.onlineSignedIn(account.username),
            OnlineWords.quota(status),
            titleColor = if (status is OnlineStatus.QuotaUsed) Tone.caution else androidx.compose.ui.graphics.Color.Unspecified,
        ) {
            OutlinedButton(onClick = { scope.launch { online.signOut() } }) { Text(s.onlineSignOut) }
        }
    }
    Setting(s.onlineMachineToggle, if (account.includeMachine) s.onlineMachineOn else s.onlineMachineOff) {
        Switch(checked = account.includeMachine, onCheckedChange = { online.setIncludeMachine(it) })
    }
    Setting(
        s.onlineSubdl,
        when {
            account.subdlKey.isBlank() -> s.onlineSubdlNone
            account.subdlRefused -> s.onlineSubdlRefused
            else -> s.onlineSubdlSet
        },
    ) {
        OutlinedButton(onClick = { editingKey = true }) { Text(s.commonChange) }
    }
    Setting(s.onlineClearCache, if (bytes > 0) s.onlineCacheDetail(Translator.messages.formatter.size(bytes)) else s.onlineCacheEmpty) {
        OutlinedButton(enabled = bytes > 0, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { online.purge() }
                bytes = 0
                toast(s.onlineCacheCleared)
            }
        }) { Text(s.commonClear) }
    }

    if (signingIn) SignInDialog(onClose = { signingIn = false })
    if (editingKey) SubdlKeyDialog(onClose = { editingKey = false })
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
            }) { Text(s.onlineSignInButton) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}

@Composable
private fun SubdlKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
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
                online.setSubdlKey(key)
                onClose()
            }) { Text(s.commonOk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}
