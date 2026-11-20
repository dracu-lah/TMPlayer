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
    fun `a cold start with the profile hidden takes down the profile's notifications left, the device's stay`() {
        val left = real("Left by the last process").apply { extras.putString(ProfileNotifications.PROFILE_EXTRA, "pa") }
        manager.notify(90, left)
        val update = real("An update is ready").apply { extras.putBoolean(ProfileNotifications.DEVICE_EXTRA, true) }
        manager.notify(91, update)
        // Posted by a version before the markers: taken for a profile's.
        manager.notify(92, real("Unmarked, from an older version"))
        // On a device channel without the marker: the device's.
        manager.createNotificationChannel(android.app.NotificationChannel(AppNotices.CHANNEL_UPDATES, "u", NotificationManager.IMPORTANCE_LOW))
        manager.notify(
            93,
            notificationBuilder(context, AppNotices.CHANNEL_UPDATES, NotificationManager.IMPORTANCE_LOW)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Updated").build(),
        )
        ProfileNotifications.clearLeftBehind(context)
        val shade = shadowOf(manager)
        assertNull(shade.getNotification(90))
        assertNull("unmarked is a profile's unless on a device channel", shade.getNotification(92))
        assertEquals("An update is ready", shade.getNotification(91).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Updated", shade.getNotification(93).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `the first run after the upgrade clears the shade once`() {
        context.getSharedPreferences("profile_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        manager.notify(97, real("From the version before"))
        ProfileNotifications.clearOnceAfterUpgrade(context)
        assertNull(shadowOf(manager).getNotification(97))
        manager.notify(98, real("Posted after"))
        ProfileNotifications.clearOnceAfterUpgrade(context)
        assertEquals("Posted after", shadowOf(manager).getNotification(98).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `a flip never brings back the real titles of a job whose owner is no longer open`() {
        val stale = AccountStamp(Long.MAX_VALUE, "ppin01")
        ProfileNotifications.trackForTest(context, 99, real("Saving: a private title"), ProfileNotifications.Kind.Ongoing, stale)
        ProfileNotifications.republish(context, hidden = false)
        assertEquals(ProfileNotifications.appName(context), shadowOf(manager).getNotification(99).extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        ProfileNotifications.trackForTest(context, 100, real("Saved: a private title"), ProfileNotifications.Kind.Result, stale)
        ProfileNotifications.republish(context, hidden = false)
        assertNull(shadowOf(manager).getNotification(100))
    }

    @Test
    fun `a job started under a PIN profile posts nothing of it once another profile is open`() {
        val started = AccountStamp(1L, "ppin01")
        val now = AccountStamp(2L, null)
        assertFalse(ProfileNotifications.mayPost(ProfileNotifications.Kind.Ongoing, owner = started, current = now))
        assertFalse(ProfileNotifications.mayPost(ProfileNotifications.Kind.Result, owner = started, current = now))
        assertTrue("the device's notices are no profile's", ProfileNotifications.mayPost(ProfileNotifications.Kind.Device, owner = started, current = now))
        assertTrue(ProfileNotifications.mayPost(ProfileNotifications.Kind.Ongoing, owner = now, current = now))
        // Posted for an owner that is not the open profile's turn: nothing reaches the shade.
        ProfileNotifications.post(context, 95, real("Saving: a private title"), ProfileNotifications.Kind.Ongoing, owner = AccountStamp(Long.MAX_VALUE, "ppin01"))
        assertNull(shadowOf(manager).getNotification(95))
    }

    @Test
    fun `a profile's posted notification carries its profile, its neutral copy too`() {
        ProfileNotifications.post(context, 96, real("A private video title"), ProfileNotifications.Kind.Ongoing)
        val posted = shadowOf(manager).getNotification(96)
        assertTrue(posted.extras.containsKey(ProfileNotifications.PROFILE_EXTRA))
        assertTrue(ProfileNotifications.neutral(context, posted).extras.containsKey(ProfileNotifications.PROFILE_EXTRA))
    }
}
