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
     * @param onOpenDownloads what pressing "Show Downloads" on a Linux notification, or a finished
     *   transfer's toast on Windows, does: raise the window and go to the Downloads page. Called on
     *   a D-Bus thread, or on the AWT thread for a toast.
     */
    fun create(window: () -> java.awt.Window?, onOpenDownloads: () -> Unit): CoalescingTransferNotifier {
        val platform: TransferNotifier = when {
            OsInfo.isLinux -> LinuxNotifications(onOpenDownloads)
            OsInfo.isWindows -> WindowsNotifications(window, onOpenDownloads)
            else -> NoTransferNotifier
        }
        notices = platform as? DesktopNotices
        val notifier = CoalescingTransferNotifier(platform)
        if (platform is DockProgress) {
            scope.launch { notifier.aggregate.collect { platform.dockProgress(it) } }
        }
        return notifier
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.tmplayer.platform.JobFailures.handler("DesktopTransferNotifier"))

    /** The same surface's one-off notices, once [create] has run; null where there is none. */
    @Volatile
    internal var notices: DesktopNotices? = null

    /** Brings the main window to the front, for a press on a notice. Set by the window. */
    @Volatile
    var raise: () -> Unit = {}
}
