package android.app

import android.content.pm.PackageManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Android's notification permission read from iOS's: denied until iOS says otherwise, while every
 * other permission stays granted; and outside an app (a test's bundle, with no app to notify for)
 * nothing is posted and nothing throws.
 */
class IosNotificationsTest {
    private val context = Application()

    @Test
    fun notificationsAreDeniedUntilIosAllowsThem() {
        assertFalse(IosNotifications.allowed)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun outsideAnAppNothingIsPostedAndAskingSaysNo() {
        var answer: Boolean? = null
        IosNotifications.ask { answer = it }
        assertEquals(false, answer)
        var read: Boolean? = null
        IosNotifications.refresh { read = it }
        assertEquals(false, read)
        IosNotifications.postQuiet("check", "Title", "Body")
        IosNotifications.remove("check")
        assertFalse(IosNotifications.allowed)
    }
}
