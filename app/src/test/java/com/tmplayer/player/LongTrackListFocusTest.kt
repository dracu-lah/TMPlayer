package com.tmplayer.player

import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

/**
 * A file with fifty subtitle tracks on a television (HX82): the picker draws a screenful and the
 * playing track on its first frame and the rest a frame later, so its start inside the player's
 * fragment transaction is short. Nothing of that may show: the playing track, far down the list,
 * is reached and left by the remote as before, and every track
 * and the timing lines under them are there.
 */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class LongTrackListFocusTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    class Host : FragmentActivity(), TrackPickerHost {
        private val stand = StandIn()
        override val player: Player get() = stand
        override fun syncDelaysNow() = SyncDelays()
        override fun stepDelay(trackType: Int, direction: Int) = Unit
        override fun subtitleStyleNow() = SubtitleStyle()
        override fun changeSubtitleStyle(style: SubtitleStyle) = Unit
        override suspend fun onlineTarget(): SubtitleTarget? = null
        override fun attachOnlineSubtitle(file: File, label: String) = Unit
    }

    private class StandIn : SimpleBasePlayer(Looper.getMainLooper()) {
        private var params = TrackSelectionParameters.DEFAULT
        private val formats = (1..TRACKS).map { n ->
            Format.Builder().setId("s$n").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLabel(name(n)).build()
        }
        private val group = TrackGroup("subs", *formats.toTypedArray())

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setTrackSelectionParameters(params)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("v").setDurationUs(60_000_000L)
                        .setTracks(Tracks(listOf(Tracks.Group(group, false, IntArray(TRACKS) { C.FORMAT_HANDLED }, BooleanArray(TRACKS) { it == PLAYING - 1 }))))
                        .build(),
                ),
            )
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun handleSetTrackSelectionParameters(parameters: TrackSelectionParameters): ListenableFuture<*> {
            params = parameters
            return Futures.immediateVoidFuture()
        }
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)) }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "windowFocusChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    @Test
    fun `the remote walks across the playing track far down a long list and every line is there`() {
        assertTrue("the list must be longer than the first frame", TRACKS > TrackPickerFragment.FIRST_FRAME_LINES * 3)
        FormFactor.override(true)
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        controller.start().resume().visible()
        leaveTouchMode(activity.window.decorView.parent)
        TrackPickerFragment.show(activity.supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
        idle()
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()

        // Focus on open is not asserted: under Robolectric the panel's window is still in touch mode
        // when the picker asks (ChatSubtitlesFocusTest starts its walk the same way). The remote's
        // walk across the line the first frame ended on is.
        rule.onNodeWithText(name(PLAYING)).requestFocus()
        idle()
        rule.onNodeWithText(name(PLAYING)).assertIsFocused()
        rule.onNodeWithText(name(PLAYING)).performKeyInput { pressKey(Key.DirectionDown) }
        idle()
        rule.onNodeWithText(name(PLAYING + 1)).assertIsFocused()
        rule.onNodeWithText(name(PLAYING + 1)).performKeyInput { pressKey(Key.DirectionUp) }
        idle()
        rule.onNodeWithText(name(PLAYING)).assertIsFocused()
        // The whole list, the last track and the timing under the tracks included.
        rule.onNodeWithText(name(1)).assertExists()
        rule.onNodeWithText(name(TRACKS)).assertExists()
        rule.onNodeWithText(L.tracksSectionTiming).assertExists()
    }

    @Test
    fun `the first frame draws a screenful, or down to the focused line`() {
        val first = TrackPickerFragment.FIRST_FRAME_LINES
        org.junit.Assert.assertEquals(first, TrackPickerFragment.firstFrameLines(50, -1))
        org.junit.Assert.assertEquals(first, TrackPickerFragment.firstFrameLines(50, 3))
        org.junit.Assert.assertEquals(40, TrackPickerFragment.firstFrameLines(50, 39))
        org.junit.Assert.assertEquals(5, TrackPickerFragment.firstFrameLines(5, -1))
        org.junit.Assert.assertEquals(0, TrackPickerFragment.firstFrameLines(0, -1))
    }

    private companion object {
        const val TRACKS = 50
        const val PLAYING = 40

        fun name(n: Int) = "Subtitle track ${n.toString().padStart(2, '0')}"
    }
}
