package com.tmplayer.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.desktop.os.AppExit
import com.tmplayer.desktop.os.DesktopTransferNotifier
import com.tmplayer.desktop.os.NativeFullscreen
import com.tmplayer.desktop.os.NonReparentingWm
import com.tmplayer.desktop.os.NativeInventory
import com.tmplayer.desktop.os.SingleInstance
import com.tmplayer.desktop.os.WindowMemory
import com.tmplayer.desktop.os.WindowsTitleBar
import com.tmplayer.desktop.ui.DesktopShell
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import kotlin.system.exitProcess

private const val QUIT_GRACE_MS = 3_000L

fun main(args: Array<String>) {
    // Loads every native library, reports, exits: no window, no second instance check.
    args.firstOrNull(SelfTest::matches)?.let { exitProcess(SelfTest.run(it)) }
    // Before the first window: sway and its kind never reparent, which AWT has to be told.
    NonReparentingWm.applyIfNeeded()
    // TDLib's Windows DLL needs the C++ runtime; take the bundled JVM's copy (see WindowsRuntime).
    WindowsRuntime.preload()
    // A second launch hands its arguments (later, tg: links) to the first and leaves.
    var raise: () -> Unit = {}
    if (!SingleInstance.acquire(args.toList()) { java.awt.EventQueue.invokeLater { raise() } }) return
    NativeInventory.log()
    Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
    val settings = DesktopServices.settings
    // What an earlier run was in the middle of comes back paused, once there is an account to
    // ask TDLib about.
    // A storage move a crash or a kill cut short is finished first: it needs the settings store
    // and the disk, not TDLib, which is already starting over the new folders.
    Background.scope.launch { runCatching { DesktopStorage.relocation.resumePending() } }
    Background.scope.launch {
        Td.awaitAuthorizedSession()
        DesktopServices.downloads.restore()
    }
    DesktopUpdates.configure()
    // Before anything can rewrite desktop.properties, which would drop the old update keys.
    Background.scope.launch { runCatching { DesktopUpdates.migrate(DesktopServices.prefs, settings) } }
    // Compose would end the process itself once the last window closes, with System.exit, which on
    // Linux can hang for good (see AppExit). It returns here instead, and AppExit ends it.
    application(exitProcessOnExit = false) {
        val windowState = remember { WindowMemory.load() }
        LaunchedEffect(windowState) { WindowMemory.follow(windowState) }
        val shell = remember { ShellState(settings, DesktopServices.downloads) }
        val quit = {
            // Saving is a courtesy; a failure there must never keep the app from closing.
            runCatching { WindowMemory.save(windowState) }
            // Should taking the window down hang (a native player that will not let go), the
            // process still ends.
            Thread {
                Thread.sleep(QUIT_GRACE_MS)
                AppExit.now()
            }.apply { isDaemon = true }.start()
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
            DesktopServices.selfUpdate.quit = quit
            // Transfers show outside the window too, once there is a window for the taskbar bar.
            LaunchedEffect(Unit) {
                DesktopStorage.closePlayer = { shell.closePlayer() }
                DesktopStorage.notifier = DesktopTransferNotifier.create({ window }) {
                    java.awt.EventQueue.invokeLater {
                        raise()
                        shell.go(com.tmplayer.desktop.ui.Destination.Downloads)
                    }
                }
            }
            // The update check, on every launch and before sign in too: the first one ten seconds
            // after the first frame, then every six hours while the window is open.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                DesktopServices.updates.run()
            }
            // Fullscreen is the shell's to ask for and the window's to do. The window system is
            // asked directly where it can be (see NativeFullscreen); elsewhere Compose's placement
            // does it, and leaving goes back to whatever the window was before.
            val native = remember(window) { NativeFullscreen(window) }
            var beforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
            LaunchedEffect(shell.fullscreen) {
                WindowMemory.freeze(WindowMemory.Hold.Fullscreen, shell.fullscreen)
                if (native.set(shell.fullscreen)) return@LaunchedEffect
                if (shell.fullscreen && windowState.placement != WindowPlacement.Fullscreen) {
                    beforeFullscreen = windowState.placement
                    windowState.placement = WindowPlacement.Fullscreen
                } else if (!shell.fullscreen && windowState.placement == WindowPlacement.Fullscreen) {
                    windowState.placement = beforeFullscreen
                }
            }
            raise = {
                window.isVisible = true
                if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
                window.toFront()
                window.requestFocus()
            }
            DesktopTheme(settings) {
                val colors = MaterialTheme.colorScheme
                LaunchedEffect(colors.background, colors.onBackground) {
                    // The handle exists once the window is shown, which is just after the first frame.
                    repeat(100) { if (!window.isDisplayable) delay(50) }
                    WindowsTitleBar.apply(
                        window,
                        dark = colors.background.luminance() < 0.5f,
                        caption = colors.background.toArgb() and 0xFFFFFF,
                        text = colors.onBackground.toArgb() and 0xFFFFFF,
                    )
                }
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
    // TDLib's, libmpv's and D-Bus's threads are not daemons; with the window gone, nothing is left
    // that should keep the process alive.
    AppExit.now()
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
