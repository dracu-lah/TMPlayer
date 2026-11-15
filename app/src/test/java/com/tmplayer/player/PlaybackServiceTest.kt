package com.tmplayer.player

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.i18n.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * [PlaybackService] as Android sees it: declared as a media playback foreground service with the
 * permission Android 14 wants for it, going into the foreground with Media3's notification once
 * it holds a playing session, letting go of it on a pause, and saying "Playback stopped" when
 * Android refuses the foreground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var session: MediaSession? = null

    @After
    fun tearDown() {
        PlaybackService.stop(context)
        session?.release()
    }

    /** A player that plays, or pauses, one titled video, and does nothing else. */
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var playing = true

        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                ).build(),
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("video")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Episode one").build())
                        .setDurationUs(60_000_000)
                        .build(),
                ),
            )
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }

        fun pauseFromOutside() {
            playing = false
            invalidateState()
        }

        fun playFromOutside() {
            playing = true
            invalidateState()
        }
    }

    private fun idle(ms: Long = 500) {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 10)) }
    }

    private fun handOver(player: Player, onCannotContinue: () -> Unit = {}): PlaybackService {
        val built = MediaSession.Builder(context, player).setId("test-${System.nanoTime()}").build()
        session = built
        assertTrue(PlaybackService.start(context, built, playWhenReady = true, onStop = {}, onCannotContinue = onCannotContinue))
        val service = Robolectric.buildService(PlaybackService::class.java).create().startCommand(0, 1).get()
        idle()
        return service
    }

    @Test
    fun `declared as a media playback foreground service, not exported, with its permission`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, PlaybackService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        assertFalse(info.exported)
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("android.permission.WAKE_LOCK" in requested)
    }

    @Test
    fun `a playing session puts the service in the foreground with the media notification`() {
        val service = handOver(FakePlayer())
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertFalse(shadow.isForegroundStopped)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
        assertEquals("playback", notification!!.channelId)
        assertEquals("Episode one", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `a pause lets the foreground go at once, so the notification can be swiped away`() {
        val player = FakePlayer()
        val service = handOver(player)
        assertFalse(shadowOf(service).isForegroundStopped)
        // The activity's own listener hears the pause first and says so.
        PlaybackService.setPlayWhenReady(false)
        player.pauseFromOutside()
        idle()
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `refused the foreground, the activity is told and Playback stopped is posted`() {
        var told = false
        val player = FakePlayer().apply { playing = false }
        val built = MediaSession.Builder(context, player).setId("refused").build()
        session = built
        PlaybackService.start(context, built, playWhenReady = false, onStop = {}, onCannotContinue = { told = true })
        val service = Robolectric.buildService(PlaybackService::class.java).create().startCommand(0, 1).get()
        idle()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background"))
        player.playFromOutside()
        idle()
        assertTrue(told)

        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(NotificationManager::class.java)).apply {
            PlaybackService.notifyStopped(context)
            val stopped = allNotifications.single { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == L.notifyPlaybackStopped }
            assertEquals(L.notifyTapToResume, stopped.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertNotNull(stopped.contentIntent)
            PlaybackService.clearStopped(context)
            assertTrue(allNotifications.none { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == L.notifyPlaybackStopped })
        }
    }

    /** The intent a notification's tap opens, after a handover. */
    private fun tapAfterHandOver(): android.content.Intent {
        val built = MediaSession.Builder(context, FakePlayer()).setId("tap-${System.nanoTime()}").build()
        session = built
        PlaybackService.start(context, built, playWhenReady = true, onStop = {}, onCannotContinue = {})
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        PlaybackService.notifyStopped(context)
        val stopped = manager.allNotifications.last { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == L.notifyPlaybackStopped }
        PlaybackService.clearStopped(context)
        PlaybackService.stop(context)
        session?.release()
        session = null
        return shadowOf(stopped.contentIntent).savedIntent
    }

    @Test
    fun `a tap brings the app's task back as it was left`() {
        val tap = tapAfterHandOver()
        assertEquals(android.content.Intent.ACTION_MAIN, tap.action)
    }
}
