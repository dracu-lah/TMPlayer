package com.tmplayer.ui.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.TmDropdownMenu
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.onboarding.QuietButton

/**
 * The help at the foot of every sign in screen on a phone and a desktop (Raju pass 2, 2.4): words,
 * "Can't connect? Get help", where a three dot icon stood that nobody could tell was for the proxy.
 * It opens a short menu with [onProxy] (the proxy screen, for a network where Telegram is reached
 * only through one) and [onSendLogs] (for a sign in stuck with no error to Try again from). The
 * button is at least 48 dp tall and a menu line is 48 dp as well. A television shows both as
 * buttons instead, which a remote reaches without a menu.
 */
@Composable
fun SignInHelpLink(onProxy: () -> Unit, onSendLogs: () -> Unit, sendLogsLabel: String, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        QuietButton(s.connectGetHelp, onClick = { open = true })
        TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.loginProxySettings) },
                onClick = {
                    open = false
                    onProxy()
                },
            )
            DropdownMenuItem(
                text = { Text(sendLogsLabel) },
                onClick = {
                    open = false
                    onSendLogs()
                },
            )
        }
    }
}
