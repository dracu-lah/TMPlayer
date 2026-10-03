package com.tmplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.desktop.os.NativeInventory
import com.tmplayer.desktop.os.SingleInstance
import com.tmplayer.desktop.os.WindowMemory
import com.tmplayer.desktop.ui.DesktopShell
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

fun main(args: Array<String>) {
    // A second launch hands its arguments (later, tg: links) to the first and leaves.
    var raise: () -> Unit = {}
    if (!SingleInstance.acquire(args.toList()) { java.awt.EventQueue.invokeLater { raise() } }) return
    NativeInventory.log()
    Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
    val settings = DesktopServices.settings
    // What an earlier run was in the middle of comes back paused, once there is an account to
    // ask TDLib about.
    Background.scope.launch {
        Td.awaitAuthorizedSession()
        OfflineDownloads.restore(settings)
    }
    application {
        val windowState = remember { WindowMemory.load() }
        LaunchedEffect(windowState) { WindowMemory.follow(windowState) }
        val shell = remember { ShellState(settings, DesktopServices.downloads) }
        // Fullscreen is the shell's to ask for and the window's to do; leaving it goes back to
        // whatever the window was before (floating or maximised).
        var beforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
        LaunchedEffect(shell.fullscreen) {
            if (shell.fullscreen && windowState.placement != WindowPlacement.Fullscreen) {
                beforeFullscreen = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!shell.fullscreen && windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = beforeFullscreen
            }
        }
        val quit = {
            WindowMemory.save(windowState)
            exitApplication()
        }
        Window(
            onCloseRequest = quit,
            state = windowState,
            title = "TMPlayer",
            icon = rememberVectorPainter(AppLogo.Mark),
            onPreviewKeyEvent = shell::onPreviewKey,
            onKeyEvent = shell::onKey,
        ) {
            window.minimumSize = java.awt.Dimension(960, 600)
            raise = {
                window.isVisible = true
                if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
                window.toFront()
                window.requestFocus()
            }
            DesktopTheme(settings) {
                DesktopShell(shell) { request, close ->
                    PlayerHost(
                        request = request,
                        onClose = {
                            shell.fullscreen = false
                            close()
                        },
                        settings = settings,
                        fullscreen = shell.fullscreen,
                        onToggleFullscreen = { shell.fullscreen = !shell.fullscreen },
                        onToggleAlwaysOnTop = { window.isAlwaysOnTop = !window.isAlwaysOnTop },
                        onQuit = quit,
                        onRaise = raise,
                    )
                }
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
