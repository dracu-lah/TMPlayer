package com.tmplayer.desktop.os

import com.tmplayer.platform.CoalescingTransferNotifier
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The desktop's [TransferNotifier]: D-Bus notifications and the dock on Linux, toasts and the
 * taskbar button on Windows, nothing elsewhere; always behind the shared
 * [CoalescingTransferNotifier] policy, whose byte weighted [CoalescingTransferNotifier.aggregate]
 * drives the one bar on the app's icon.
 *
 * Creating one is cheap: the session bus, the notification server and the PowerShell host are all
 * reached for on the first notification, not before.
 */
object DesktopTransferNotifier {

    /**
     * @param window the main window, whose taskbar button carries the Windows progress bar; null
     *   while there is none.
     * @param onOpenDownloads what pressing "Show Downloads" on a Linux notification does: raise the
     *   window and go to the Downloads page. Called on a D-Bus thread.
     */
    fun create(window: () -> java.awt.Window?, onOpenDownloads: () -> Unit): CoalescingTransferNotifier {
        val platform: TransferNotifier = when {
            OsInfo.isLinux -> LinuxNotifications(onOpenDownloads)
            OsInfo.isWindows -> WindowsNotifications(window)
            else -> NoTransferNotifier
        }
        val notifier = CoalescingTransferNotifier(platform)
        if (platform is DockProgress) {
            scope.launch { notifier.aggregate.collect { platform.dockProgress(it) } }
        }
        return notifier
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
