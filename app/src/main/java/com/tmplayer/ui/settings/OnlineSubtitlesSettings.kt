package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
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
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the section's prompts is open over Settings. */
enum class OnlineDialog { SignIn, SignOut, SubdlKey }

/**
 * Settings, Online subtitles: the viewer's OpenSubtitles account with what is left of today's
 * downloads, the machine translation switch, the optional SubDL key and the cache. Only drawn in
 * a build that carries an app key ([OnlineSubtitles.available]); a refused key turns the account
 * row into the sentence saying the feature is unavailable, with the cache still clearable.
 */
internal fun LazyListScope.onlineSubtitlesSection(onDialog: (OnlineDialog) -> Unit) {
    item { SectionTitle(LocalStrings.current.onlineTitle) }
    item { AccountRow(onDialog) }
    item { MachineRow() }
    item { SubdlRow(onDialog) }
    item { CacheRow() }
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
            icon = Icons.Filled.Warning,
            tint = Tone.muted,
            onClick = { toast(OnlineWords.status(status)) },
        )
        OnlineStatus.SignedOut, is OnlineStatus.Expired -> ActionRow(
            title = s.onlineSignIn,
            subtitle = OnlineWords.status(status),
            icon = Icons.Filled.AccountCircle,
            onClick = { onDialog(OnlineDialog.SignIn) },
        )
        is OnlineStatus.SignedIn, is OnlineStatus.QuotaUsed -> ActionRow(
            title = s.onlineSignOut,
            subtitle = OnlineWords.status(status),
            icon = Icons.Filled.AccountCircle,
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
        icon = ImageVector.vectorResource(R.drawable.ic_subtitles),
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
        icon = Icons.Filled.Lock,
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
    ActionRow(
        title = s.onlineClearCache,
        subtitle = if (bytes > 0) s.onlineCacheDetail(Translator.messages.formatter.size(bytes)) else s.onlineCacheEmpty,
        icon = Icons.Filled.Delete,
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { online.purge() }
                bytes = 0
                toast(s.onlineCacheCleared)
            }
        },
    )
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
                icon = Icons.Filled.AccountCircle,
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
                icon = Icons.Filled.Lock,
                onDismiss = onClose,
                onConfirm = {
                    online.setSubdlKey(key)
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
