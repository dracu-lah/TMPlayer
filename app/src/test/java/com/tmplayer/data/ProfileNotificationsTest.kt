package com.tmplayer.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one choke point for notifications (HX88): the pure choice of real or neutral, what a flip of
 * the lock does to each kind, and a post while hidden carrying no title of the profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun tearDown() {
        ProfileNotifications.forgetAll()
    }

    private fun real(title: String): Notification {
        manager.createNotificationChannel(android.app.NotificationChannel("t", "t", NotificationManager.IMPORTANCE_LOW))
        return notificationBuilder(context, "t", NotificationManager.IMPORTANCE_LOW)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Episode 4 of a show")
            .build()
    }

    @Test
    fun `the device's notifications are always real, the profile's neutral while hidden`() {
        assertEquals(ProfileNotifications.Choice.Real, ProfileNotifications.choose(hidden = true, kind = ProfileNotifications.Kind.Device))
        assertEquals(ProfileNotifications.Choice.Neutral, ProfileNotifications.choose(hidden = true, kind = ProfileNotifications.Kind.Ongoing))
        assertEquals(ProfileNotifications.Choice.Neutral, ProfileNotifications.choose(hidden = true, kind = ProfileNotifications.Kind.Result))
        assertEquals(ProfileNotifications.Choice.Real, ProfileNotifications.choose(hidden = false, kind = ProfileNotifications.Kind.Result))
    }

    @Test
    fun `on hiding ongoing ones turn neutral and results come down, on showing ongoing ones are real again`() {
        assertEquals(ProfileNotifications.Choice.Neutral, ProfileNotifications.onFlip(hidden = true, kind = ProfileNotifications.Kind.Ongoing))
        assertNull(ProfileNotifications.onFlip(hidden = true, kind = ProfileNotifications.Kind.Result))
        assertEquals(ProfileNotifications.Choice.Real, ProfileNotifications.onFlip(hidden = false, kind = ProfileNotifications.Kind.Ongoing))
        assertEquals(ProfileNotifications.Choice.Real, ProfileNotifications.onFlip(hidden = true, kind = ProfileNotifications.Kind.Device))
    }

    @Test
    fun `the neutral notification carries the app's name and nothing of the profile`() {
        val neutral = ProfileNotifications.neutral(context, real("A private video title"))
        val title = neutral.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        assertEquals(ProfileNotifications.appName(context), title)
        assertNull(neutral.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(neutral.extras.toString().contains("A private video title"))
        assertEquals("t", neutral.channelId)
    }

    @Test
    fun `a republish while hidden posts the neutral one in place, and the real one when shown`() {
        ProfileNotifications.post(context, 77, real("A private video title"), ProfileNotifications.Kind.Ongoing)
        ProfileNotifications.post(context, 78, real("Downloaded: a private title"), ProfileNotifications.Kind.Result)
        ProfileNotifications.republish(context, hidden = true)
        val shade = shadowOf(manager)
        val ongoing = shade.getNotification(77)
        assertEquals(ProfileNotifications.appName(context), ongoing.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull("a result comes down when the profile hides", shade.getNotification(78))
        ProfileNotifications.republish(context, hidden = false)
        assertEquals("A private video title", shade.getNotification(77).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(shade.size() >= 1)
    }

    @Test
    fun `a switch takes down the last profile's notifications, the device's stay`() {
        ProfileNotifications.post(context, 81, real("Downloaded: a private title"), ProfileNotifications.Kind.Result)
        ProfileNotifications.post(context, 82, real("An update is ready"), ProfileNotifications.Kind.Device)
        val shade = shadowOf(manager)
        ProfileNotifications.leftProfile(context, open = "pnext1")
        assertNull("the profile left behind its PIN leaves nothing in the shade", shade.getNotification(81))
        assertEquals("An update is ready", shade.getNotification(82).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `signing a profile out takes all of its notifications down`() {
        ProfileNotifications.post(context, 83, real("A private video title"), ProfileNotifications.Kind.Ongoing)
        ProfileNotifications.clearProfile(context)
        assertNull(shadowOf(manager).getNotification(83))
    }

    @Test
    fun `the neutral copy is still dismissed on a tap and still says when it is swiped away`() {
        val delete = android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent("swiped"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val source = notificationBuilder(context, "t", NotificationManager.IMPORTANCE_LOW)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("A private video title")
            .setAutoCancel(true)
            .setDeleteIntent(delete)
            .build()
        val neutral = ProfileNotifications.neutral(context, source)
        assertTrue(neutral.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(delete, neutral.deleteIntent)
    }

    @Test
    fun `a cold start with the profile hidden takes down what an earlier process left`() {
        manager.notify(90, real("Left by the last process"))
        ProfileNotifications.clearLeftBehind(context)
        assertNull(shadowOf(manager).getNotification(90))
    }
}
