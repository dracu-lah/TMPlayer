package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.DesktopCrashReports
import com.tmplayer.platform.Background
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The crash question (CP102g): the last run crashed with crash reports off, so the window asks
 * whether to send that one report, as the phone does. Send, Don't send, and an Always send box
 * that turns the switch on first. Esc or a click outside counts as Don't send, so a viewer who
 * waves it away is not asked about the same crash again. A run of crashes as the app starts is
 * asked about before the window instead (DesktopCrashReports).
 */
@Composable
fun CrashQuestionHost(settings: SettingsStore) {
    val asking by DesktopCrashReports.pending.collectAsState()
    if (!asking) return
    CrashQuestion(
        onSend = { always ->
            Background.scope.launch(Dispatchers.IO) {
                if (always) {
                    runCatching { settings.setCrashReports(true) }
                    runCatching { DesktopCrashReports.start(true) }
                }
                runCatching { DesktopCrashReports.sendPending() }
            }
        },
        onDismiss = { DesktopCrashReports.discardPending() },
    )
}

/** The question itself, for [CrashQuestionHost] and the render test. */
@Composable
internal fun CrashQuestion(onSend: (always: Boolean) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var always by remember { mutableStateOf(false) }
    ConfirmDialog(
        title = s.mainCrashTitle,
        message = s.mainCrashMessage,
        detail = s.mainCrashDetailDesktop,
        confirmLabel = s.mainCrashSend,
        cancelLabel = s.mainCrashDontSend,
        destructive = false,
        focusConfirm = true,
        extra = {
            CheckRow(always, { always = it }, s.mainCrashAlways)
            // The logs as well, sent the viewer's own way: the report carries the stack, these
            // carry what led up to it.
            SendLogsActions()
        },
        onConfirm = { onSend(always) },
        onDismiss = onDismiss,
    )
}
