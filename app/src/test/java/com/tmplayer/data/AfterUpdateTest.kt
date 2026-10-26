package com.tmplayer.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * After TMPlayer has updated itself (HX5): the broadcast in the new version deletes the APK and
 * posts "Updated, tap to open" after a session install, and the first launch says "Updated to"
 * once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AfterUpdateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings get() = SettingsStore(context)
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val apk get() = File(context.cacheDir, "updates/TMPlayer-${BuildConfig.VERSION_NAME}.apk")

    @Before
    fun setUp() {
        Updates.enabled = true
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        apk.parentFile!!.mkdirs()
        apk.writeBytes(ByteArray(16))
    }

    private fun pending(session: Boolean, version: String = BuildConfig.VERSION_NAME) = runBlocking {
        settings.setUpdateInstalling(UpdateRestart.Pending(version, System.currentTimeMillis(), session, foreground = true).encode())
    }

    @Test
    fun `a session install is followed by a notification, then the notice once`() = runBlocking {
        pending(session = true)
        AfterUpdate.afterReplace(context)
        assertFalse("the APK is deleted", apk.exists())
        assertEquals(1, notifications.allNotifications.size)

        assertEquals(BuildConfig.VERSION_NAME, AfterUpdate.onLaunch(context))
        assertEquals("the notification goes once the app is open", 0, notifications.allNotifications.size)
        assertEquals("", settings.updateInstallingNow())
        assertNull("said once", AfterUpdate.onLaunch(context))
    }

    @Test
    fun `the system installer's screen has its own Open, so nothing is posted`() = runBlocking {
        pending(session = false)
        AfterUpdate.afterReplace(context)
        assertFalse(apk.exists())
        assertTrue(notifications.allNotifications.isEmpty())
        assertEquals(BuildConfig.VERSION_NAME, AfterUpdate.onLaunch(context))
    }

    @Test
    fun `an update from elsewhere says nothing`() = runBlocking {
        settings.setUpdateInstalling("")
        AfterUpdate.afterReplace(context)
        assertTrue(notifications.allNotifications.isEmpty())
        assertNull(AfterUpdate.onLaunch(context))
    }

    @Test
    fun `an install that has not happened yet keeps its record`() = runBlocking {
        pending(session = true, version = "99.0.0")
        assertNull(AfterUpdate.onLaunch(context))
        assertTrue(settings.updateInstallingNow().isNotEmpty())
    }
}
