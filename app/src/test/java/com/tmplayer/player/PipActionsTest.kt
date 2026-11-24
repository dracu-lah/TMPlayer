package com.tmplayer.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-36: the corner window's three buttons on Android 8 to 10, none from 11, and the notification's seek sizes. */
@RunWith(RobolectricTestRunner::class)
class PipActionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun params(playing: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        PipActions.apply(builder, context, playing)
        return builder.build()
    }

    @Test
    @Config(sdk = [28])
    fun android9CarriesThreeActions() {
        val actions = params(playing = true).actions
        assertEquals(3, actions.size)
        // Playing: the middle one says Pause; paused: Play.
        assertEquals(com.tmplayer.i18n.L.commonPause, actions[1].title.toString())
        assertEquals(com.tmplayer.i18n.L.commonPlay, params(playing = false).actions[1].title.toString())
        assertEquals(com.tmplayer.i18n.L.notifySeekBack(10), actions[0].title.toString())
        assertEquals(com.tmplayer.i18n.L.notifySeekForward(10), actions[2].title.toString())
    }

    @Test
    @Config(sdk = [26])
    fun android8CarriesThreeActions() {
        assertEquals(3, params(playing = true).actions.size)
    }

    @Test
    @Config(sdk = [30])
    fun android11DrawsFromTheSessionSoNoActions() {
        assertTrue(params(playing = true).actions.orEmpty().isEmpty())
    }

    @Test
    @Config(sdk = [35])
    fun android15HasNoActionsEither() {
        assertTrue(params(playing = false).actions.orEmpty().isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun theWantedRange() {
        assertFalse(PipActions.wanted(25))
        assertTrue(PipActions.wanted(26))
        assertTrue(PipActions.wanted(29))
        assertFalse(PipActions.wanted(30))
    }

    @Test
    @Config(sdk = [28])
    fun actionsAreImmutableBroadcastsToThisPackageOnly() {
        val pending = params(playing = true).actions.map { it.actionIntent }
        assertEquals(3, pending.size)
        assertEquals(3, pending.toSet().size)
        val intent = Intent(PipActions.ACTION).setPackage(context.packageName).putExtra("kind", 2)
        assertEquals(PipActions.Kind.Forward, PipActions.kindOf(intent))
    }

    @Test
    fun aForeignOrBrokenBroadcastIsNothing() {
        assertNull(PipActions.kindOf(null))
        assertNull(PipActions.kindOf(Intent("someone.else")))
        assertNull(PipActions.kindOf(Intent(PipActions.ACTION)))
        assertNull(PipActions.kindOf(Intent(PipActions.ACTION).putExtra("kind", 99)))
    }

    @Test
    @Config(sdk = [33])
    fun theReceiverIsRegisteredNotExportedOnAndroid13() {
        val seen = mutableListOf<PipActions.Kind>()
        val receiver = PipActions.register(context, seen::add)
        context.sendBroadcast(Intent(PipActions.ACTION).setPackage(context.packageName).putExtra("kind", 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(PipActions.Kind.Back), seen)
        context.unregisterReceiver(receiver)
    }

    @Test
    @Config(sdk = [28])
    fun theNotificationSeeksCarryTheDoubleTapAmountBothWays() {
        for (ms in TouchPrefs.DOUBLE_TAP_CHOICES_MS) {
            val buttons = BackgroundPlayback.notificationButtons(ms, ms, sdk = 28)
            val back = buttons.first { it.playerCommand == androidx.media3.common.Player.COMMAND_SEEK_BACK }
            val forward = buttons.first { it.playerCommand == androidx.media3.common.Player.COMMAND_SEEK_FORWARD }
            assertEquals(com.tmplayer.i18n.L.notifySeekBack(ms / 1000), back.displayName.toString())
            assertEquals(com.tmplayer.i18n.L.notifySeekForward(ms / 1000), forward.displayName.toString())
            assertEquals(BackgroundPlayback.backIcon(ms), back.icon)
            assertEquals(BackgroundPlayback.forwardIcon(ms), forward.icon)
        }
    }
}
