package android.app

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * iOS's notifications, as Android's `POST_NOTIFICATIONS` permission and quiet notices. The
 * permission is iOS's own: asked for where upstream asks for Android's (after the first video
 * watched to the end, on queuing a download, on turning a notice on), read where upstream reads
 * it. iOS answers both questions asynchronously, so the last answer is kept for upstream's
 * synchronous checks and read again whenever the app comes back to the front, where the viewer
 * may have changed it in Settings.
 */
object IosNotifications {
    /** Whether iOS lets the app post, as last read. Nothing is allowed before the first answer. */
    @Volatile
    var allowed: Boolean = false
        private set

    /** Reads the permission again; [done] is told the answer on the main thread. */
    fun refresh(done: ((Boolean) -> Unit)? = null) {
        val center = center() ?: return done?.invoke(false) ?: Unit
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            allowed = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional ||
                status == UNAuthorizationStatusEphemeral
            done?.let { tell -> onMain { tell(allowed) } }
        }
    }

    /**
     * Asks iOS for the permission, as Android's permission request: iOS shows its question the
     * first time only, and after that answers with the viewer's choice at once.
     */
    fun ask(done: (Boolean) -> Unit) {
        val center = center() ?: return done(false)
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            allowed = granted
            onMain { done(granted) }
        }
    }

    /**
     * A notice that waits in Notification Center without sound, banner or lighting the screen, as
     * Android's low importance channel shows one: [id] names it, so posting again replaces it and
     * [remove] takes it down. A tap opens the app as it was left. Nothing when not [allowed].
     */
    fun postQuiet(id: String, title: String, body: String) {
        val center = center() ?: return
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            allowed = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional ||
                status == UNAuthorizationStatusEphemeral
            if (!allowed) return@getNotificationSettingsWithCompletionHandler
            val content = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(body)
                setSound(null)
                setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive)
            }
            center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, content, null)) { error ->
                if (error != null) android.util.Log.w("Notifications", "could not post $id: ${error.localizedDescription}")
            }
        }
    }

    /** Takes down [id]'s notice, shown or waiting. */
    fun remove(id: String) {
        val center = center() ?: return
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    /**
     * For a developer's check: where iOS lets the app's notices show (each setting as iOS's number:
     * 2 enabled, 1 disabled, 0 not supported; summary 1 is the scheduled summary) and the ones it
     * holds as delivered now.
     */
    fun describe(done: (String) -> Unit) {
        val center = center() ?: return done("no notification center")
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val where = settings?.let {
                "status ${it.authorizationStatus}, alert ${it.alertSetting}, centre ${it.notificationCenterSetting}, " +
                    "lock screen ${it.lockScreenSetting}, style ${it.alertStyle}, summary ${it.scheduledDeliverySetting}"
            } ?: "no settings"
            center.getDeliveredNotificationsWithCompletionHandler { delivered ->
                val ids = delivered.orEmpty().mapNotNull { (it as? platform.UserNotifications.UNNotification)?.request?.identifier }
                onMain { done("$where; delivered: $ids") }
            }
        }
    }

    /** The app's notification center; none outside an app (a unit test's bundle has no app to notify for). */
    private fun center(): UNUserNotificationCenter? =
        if (platform.Foundation.NSBundle.mainBundle.bundleIdentifier == null) null
        else UNUserNotificationCenter.currentNotificationCenter()

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
}
