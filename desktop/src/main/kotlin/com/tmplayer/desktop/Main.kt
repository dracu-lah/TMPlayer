package com.tmplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.desktop.ui.DesktopShell
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

fun main() {
    Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
    val settings = DesktopServices.settings
    // What an earlier run was in the middle of comes back paused, once there is an account to
    // ask TDLib about.
    Background.scope.launch {
        Td.awaitAuthorizedSession()
        OfflineDownloads.restore(settings)
    }
    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        val shell = remember { ShellState(settings, DesktopServices.downloads) }
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "TMPlayer",
            icon = rememberVectorPainter(AppLogo.Mark),
            onPreviewKeyEvent = shell::onPreviewKey,
            onKeyEvent = shell::onKey,
        ) {
            window.minimumSize = java.awt.Dimension(960, 600)
            DesktopTheme(settings) {
                DesktopShell(shell)
            }
        }
    }
}

/**
 * The theme the Settings choice asks for. "System" follows the OS where it says (Windows, macOS);
 * where it does not (most Linux desktops, until Phase 3 reads the portal), dark, which is the
 * right default for a window that plays video.
 */
@Composable
fun DesktopTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val choice by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val dark = when (choice) {
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
        ThemeChoice.System -> currentSystemTheme != SystemTheme.LIGHT
    }
    TmMaterialTheme(dark = dark, content = content)
}
