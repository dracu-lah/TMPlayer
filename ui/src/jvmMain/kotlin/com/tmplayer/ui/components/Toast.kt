package com.tmplayer.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a short notice is shown on the desktop: the window's snackbar host, which the shell
 * provides. Android's toast has no desktop counterpart, and a snackbar is the Material answer.
 */
val LocalToastHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * The same call the shared screens make on Android, where it raises a toast. Here the notice is
 * shown on a scope of its own rather than the screen's, so, like a toast, it survives the screen
 * that raised it going away.
 */
@Composable
fun rememberToast(): (String) -> Unit {
    val host = LocalToastHost.current
    return remember(host) {
        { message: String ->
            if (host == null) {
                Logger.i("Toast", message)
            } else {
                noticeScope.launch {
                    host.currentSnackbarData?.dismiss()
                    host.showSnackbar(message)
                }
            }
        }
    }
}

private val noticeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
