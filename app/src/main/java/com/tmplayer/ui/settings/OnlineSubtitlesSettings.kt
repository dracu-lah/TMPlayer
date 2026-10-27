package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineStatus
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.ui.auth.PaneField
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment

/** Which of the section's prompts is open over Settings. */
enum class OnlineDialog { SignIn, SignOut, SubdlKey }

/**
 * Settings, Online subtitles: the viewer's OpenSubtitles account with what is left of today's
 * downloads, the machine translation switch, the optional SubDL key and the cache. Only drawn in
 * a build that carries an app key ([OnlineSubtitles.available]); a refused key turns the account
 * row into the sentence saying the feature is unavailable, with the cache still clearable.
 */
internal fun LazyListScope.onlineSubtitlesSection(onDialog: (OnlineDialog) -> Unit) {
    sectionTitle { LocalStrings.current.onlineTitle }
    item { AccountRow(onDialog) }
    item { MachineRow() }
    item { SubdlRow(onDialog) }
    item { MsoneRow() }
    item { CacheRow() }
}

/**
 * MSone's state at the gateway, on a device where its search was switched on by hand (`msone`
 * typed as the SubDL key): the same line the picker shows, the gateway's own words after it, and
 * a press that checks again.
 */
@Composable
private fun MsoneRow() {
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
    ActionRow(
        title = s.msoneTitle,
        subtitle = when {
            checking -> s.msoneChecking
            account.msoneMessage.isNotBlank() -> "$line ${account.msoneMessage}"
            else -> line
        },
        icon = TmIcons.Language,
        tint = if (account.msoneState == com.tmplayer.online.MsoneState.On) androidx.compose.ui.graphics.Color.Unspecified else Tone.muted,
        onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    val status = online.msoneStatus(force = true)
                    checking = false
                    toast(OnlineWords.msoneState(status.state))
                }
            }
        },
    )
}

@Composable
private fun AccountRow(onDialog: (OnlineDialog) -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val account by online.account.collectAsState()
    val status = online.status(account)
    val toast = rememberToast()
    when (status) {
        OnlineStatus.NotInBuild, OnlineStatus.Unavailable -> ActionRow(
            title = s.onlineTitle,
            subtitle = OnlineWords.status(status),
            icon = TmIcons.Warning,
            tint = Tone.muted,
            onClick = { toast(OnlineWords.status(status)) },
        )
        OnlineStatus.SignedOut, is OnlineStatus.Expired, is OnlineStatus.FreeQuotaUsed -> ActionRow(
            title = s.onlineSignIn,
            subtitle = OnlineWords.status(status),
            icon = TmIcons.Account,
            onClick = { onDialog(OnlineDialog.SignIn) },
        )
        is OnlineStatus.SignedIn, is OnlineStatus.QuotaUsed -> ActionRow(
            title = s.onlineSignOut,
            subtitle = OnlineWords.status(status),
            icon = TmIcons.Account,
            onClick = { onDialog(OnlineDialog.SignOut) },
        )
    }
}

@Composable
private fun MachineRow() {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val account by online.account.collectAsState()
    ToggleRow(
        title = s.onlineMachineToggle,
        subtitle = if (account.includeMachine) s.onlineMachineOn else s.onlineMachineOff,
        // A language's globe: machine translations are subtitles carried into another language.
        icon = TmIcons.Language,
        checked = account.includeMachine,
        onToggle = { online.setIncludeMachine(!account.includeMachine) },
    )
}

@Composable
private fun SubdlRow(onDialog: (OnlineDialog) -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val account by online.account.collectAsState()
    ActionRow(
        title = s.onlineSubdl,
        subtitle = when {
            account.subdlKey.isBlank() -> s.onlineSubdlNone
            account.subdlRefused -> s.onlineSubdlRefused
            else -> s.onlineSubdlSet
        },
        icon = TmIcons.Lock,
        tint = if (account.subdlRefused) Tone.muted else androidx.compose.ui.graphics.Color.Unspecified,
        onClick = { onDialog(OnlineDialog.SubdlKey) },
    )
}

@Composable
private fun CacheRow() {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }
    var asking by remember { mutableStateOf(false) }
    val clear: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { online.purge() }
            bytes = 0
            toast(s.onlineCacheCleared)
        }
    }
    ActionRow(
        title = s.onlineClearCache,
        subtitle = if (bytes > 0) s.onlineCacheDetail(Translator.messages.formatter.size(bytes)) else s.onlineCacheEmpty,
        icon = TmIcons.Delete,
        // Asked first: a subtitle fetched again is another download against the daily quota.
        onClick = { if (bytes > 0) asking = true else clear() },
    )
    if (asking) {
        TvConfirm(
            title = s.confirmOnlineCacheTitle,
            message = s.confirmOnlineCacheMessage(Translator.messages.formatter.size(bytes)),
            confirmLabel = s.commonClear,
            onConfirm = {
                asking = false
                clear()
            },
            onDismiss = { asking = false },
        )
    }
}

/** The prompt [dialog] names, over Settings. */
@Composable
fun OnlineSubtitlesDialog(dialog: OnlineDialog, onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return onClose()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    when (dialog) {
        OnlineDialog.SignIn -> {
            var username by remember { mutableStateOf(online.store.now.username) }
            var password by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            TvConfirm(
                title = s.onlineSignIn,
                message = s.onlineSignInMessage,
                confirmLabel = s.onlineSignInButton,
                destructive = false,
                icon = TmIcons.Account,
                onDismiss = onClose,
                onConfirm = {
                    if (busy || username.isBlank() || password.isEmpty()) return@TvConfirm
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
                },
                extra = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaneField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = s.onlineUsername,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        )
                        PaneField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = s.onlinePassword,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Danger) }
                        // A press is answered: the sign in is on its way, and a long one says so.
                        if (busy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                com.tmplayer.ui.components.Spinner(size = 18.dp, strokeWidth = 2.dp)
                                Text(s.onlineSigningIn, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                            }
                        }
                    }
                },
            )
        }
        OnlineDialog.SignOut -> TvConfirm(
            title = s.onlineSignOut,
            message = OnlineWords.status(online.status()),
            confirmLabel = s.onlineSignOut,
            destructive = false,
            onDismiss = onClose,
            onConfirm = {
                scope.launch {
                    online.signOut()
                    onClose()
                }
            },
        )
        OnlineDialog.SubdlKey -> {
            var key by remember { mutableStateOf(online.store.now.subdlKey) }
            TvConfirm(
                title = s.onlineSubdlTitle,
                message = s.onlineSubdlMessage,
                confirmLabel = s.commonOk,
                destructive = false,
                icon = TmIcons.Lock,
                onDismiss = onClose,
                onConfirm = {
                    // `msone` is the hidden switch for MSone's search, not a key.
                    online.setSubdlKey(key)?.let { shown -> toast(if (shown) s.msoneHiddenOn else s.msoneHiddenOff) }
                    onClose()
                },
                extra = {
                    PaneField(
                        value = key,
                        onValueChange = { key = it },
                        placeholder = s.onlineSubdl,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                },
            )
        }
    }
}
