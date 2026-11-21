package com.tmplayer.player

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Raju step 6.3: the audio button never looks unanswered. On a video with one sound track the real
 * [TrackPickerFragment] opens with the plain line "Only one audio track" (and the delay lines under
 * it), not a bare "No other audio" that reads as a fault; with two tracks both are listed and the
 * line is absent.
 */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class AudioButtonAnswerTest {

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
        var audioTracks = 1
        private val stand by lazy { StandIn(audioTracks) }

        override val player: Player get() = stand
        override fun syncDelaysNow() = SyncDelays()
        override fun stepDelay(trackType: Int, direction: Int) = Unit
        override fun subtitleStyleNow() = SubtitleStyle()
        override fun changeSubtitleStyle(style: SubtitleStyle) = Unit
        override suspend fun onlineTarget(): SubtitleTarget? = null
        override fun attachOnlineSubtitle(file: File, label: String) = Unit
    }

    private class StandIn(private val count: Int) : SimpleBasePlayer(Looper.getMainLooper()) {
        private val group = TrackGroup(
            "audio",
            *Array(count) { i ->
                Format.Builder().setId("a$i").setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(if (i == 0) "en" else "hi").build()
            },
        )

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("v").setDurationUs(60_000_000L)
                        .setTracks(
                            Tracks(
                                listOf(
                                    Tracks.Group(
                                        group, false, IntArray(count) { C.FORMAT_HANDLED },
                                        BooleanArray(count) { it == 0 },
                                    ),
                                ),
                            ),
                        )
                        .build(),
                ),
            )
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)) }
        rule.waitForIdle()
    }

    private fun open(audioTracks: Int) {
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.audioTracks = audioTracks
        activity.setContentView(R.layout.activity_player)
        controller.start().resume().visible()
        TrackPickerFragment.show(activity.supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_AUDIO)
        idle()
    }

    @Test
    fun `one sound track says so in plain words`() {
        open(audioTracks = 1)
        rule.onNodeWithText("Only one audio track").assertExists()
        assertEquals("Only one audio track", L.playerOnlyOneAudio)
    }

    @Test
    fun `two sound tracks are listed and the line is absent`() {
        open(audioTracks = 2)
        rule.onNodeWithText("English").assertExists()
        rule.onNodeWithText("Only one audio track").assertDoesNotExist()
    }

    @Test
    fun `the answer by number of tracks`() {
        assertEquals(L.playerNoAudioTracks, AudioButtonAnswer.line(0))
        assertEquals(L.playerOnlyOneAudio, AudioButtonAnswer.line(1))
        assertNull(AudioButtonAnswer.line(2))
    }
}
