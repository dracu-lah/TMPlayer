package com.tmplayer.desktop.ui

import com.tmplayer.ui.components.ToastKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.tmplayer.desktop.os.LaunchAtLogin
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Open TMPlayer when you sign in" (WP-52). The switch shows what the OS holds, read when the
 * page opens and again after each press, never a stored copy; a press that did not take says so
 * and the switch stays where the OS says it is. Hidden where this run cannot register itself.
 */
@Composable
internal fun LaunchAtLoginRow(login: LaunchAtLogin = remember { LaunchAtLogin.system() }) {
    val s = LocalStrings.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LaunchAtLogin.State?>(null) }
    LaunchedEffect(login) { state = withContext(Dispatchers.IO) { login.state() } }
    val shown = state ?: return
    if (shown == LaunchAtLogin.State.Unavailable) return
    Toggle(
        s.settingsLaunchAtLogin,
        when (shown) {
            LaunchAtLogin.State.On -> s.settingsLaunchAtLoginOn
            LaunchAtLogin.State.OtherCopy -> s.settingsLaunchAtLoginOther
            else -> s.settingsLaunchAtLoginOff
        },
        shown == LaunchAtLogin.State.On,
        icon = TmIcons.Play,
    ) { on ->
        scope.launch {
            val result = withContext(Dispatchers.IO) { login.set(on) }
            state = result.state
            if (!result.ok) toast(s.settingsLaunchAtLoginFailed, ToastKind.Error)
        }
    }
}
