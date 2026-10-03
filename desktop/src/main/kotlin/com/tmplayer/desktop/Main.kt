package com.tmplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tmplayer.data.Td

fun main() {
    Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(onCloseRequest = ::exitApplication, state = state, title = "TMPlayer") {
            window.minimumSize = java.awt.Dimension(960, 600)
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val auth by Td.auth.collectAsState()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("TMPlayer ${BuildInfo.VERSION}: $auth")
                    }
                }
            }
        }
    }
}
