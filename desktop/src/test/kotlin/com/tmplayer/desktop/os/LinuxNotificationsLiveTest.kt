package com.tmplayer.desktop.os

import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Calls the real notification server once, so the method signatures are proven against a daemon
 * rather than only marshalled. Skipped unless `TMPLAYER_LIVE_DBUS=1`; it shows one transient
 * notification for a moment and closes it.
 */
class LinuxNotificationsLiveTest {

    @Test
    fun notifyAndCloseAgainstTheSessionServer() {
        assumeTrue(OsInfo.isLinux && System.getenv("TMPLAYER_LIVE_DBUS") == "1")
        DBusConnectionBuilder.forSessionBus().withShared(false).build().use { conn ->
            val server = conn.getRemoteObject(LinuxNotifications.SERVICE, LinuxNotifications.OBJECT_PATH, FdoNotifications::class.java)
            println("capabilities: ${server.GetCapabilities()}")
            val id = server.Notify(
                LinuxNotifications.APP_NAME, UInt32(0), LinuxNotifications.ICON, "TMPlayer live test",
                LinuxNotificationText.progressBody(42, 100, null), emptyList(),
                LinuxNotificationText.hints(LinuxNotificationText.CATEGORY_PROGRESS, urgency = 0, percent = 42),
                1_000,
            )
            assertTrue(id.toLong() > 0)
            server.CloseNotification(id)
            conn.sendMessage(
                UnityLauncherEntry.Update(
                    LinuxNotifications.LAUNCHER_PATH,
                    LinuxNotificationText.LAUNCHER_APP_URI,
                    LinuxNotificationText.launcherProperties(null),
                ),
            )
        }
    }
}
