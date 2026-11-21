package com.tmplayer.player

import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import org.junit.Assert.assertEquals
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
 * "In this chat" in the subtitles picker on a television, by remote alone (EG-F48): the real
 * [TrackPickerFragment] over a stand-in player with two embedded tracks. Down from Off walks the
 * tracks to the entry, OK opens the files posted in the chat with the best one focused, Down
 * reaches the next and OK chooses it, handing exactly that file to the host. With no files found
 * (offline, none, the kids lock) there is no entry, and the local lines are unchanged.
 */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ChatSubtitlesFocusTest {

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
        var found: List<ChatSubtitles.Hit> = emptyList()
        var chosen: ChatSubtitles.Hit? = null
        var refusal: String? = null
        private val stand = StandIn()

        override val player: Player get() = stand
        override fun syncDelaysNow() = SyncDelays()
        override fun stepDelay(trackType: Int, direction: Int) = Unit
        override fun subtitleStyleNow() = SubtitleStyle()
        override fun changeSubtitleStyle(style: SubtitleStyle) = Unit
        override suspend fun onlineTarget(): SubtitleTarget? = null
        override fun attachOnlineSubtitle(file: File, label: String) = Unit
        override suspend fun chatSubtitles(): List<ChatSubtitles.Hit> = found
        override suspend fun attachChatSubtitle(hit: ChatSubtitles.Hit): String? {
            chosen = hit
            return refusal
        }
    }

    private class StandIn : SimpleBasePlayer(Looper.getMainLooper()) {
        private var params = TrackSelectionParameters.DEFAULT
        private val group = TrackGroup(
            "subs",
            Format.Builder().setId("en").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build(),
            Format.Builder().setId("fr").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("fr").build(),
        )

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setTrackSelectionParameters(params)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("v").setDurationUs(60_000_000L)
                        .setTracks(Tracks(listOf(Tracks.Group(group, false, IntArray(2) { C.FORMAT_HANDLED }, booleanArrayOf(true, false)))))
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

    private fun hit(step: Long, name: String, language: String? = null, relation: ChatSubtitles.Relation = ChatSubtitles.Relation.Neighbour) =
        ChatSubtitles.Hit(
            doc = ChatSubtitles.Doc(step shl 20, step.toInt(), name, 40_000, 1_000),
            relation = relation,
            nameMatch = ChatSubtitles.NameMatch.Exact,
            language = language,
            forced = false,
            hearing = false,
            archive = false,
        )

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

    private fun open(found: List<ChatSubtitles.Hit>): Host {
        FormFactor.override(true)
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.found = found
        activity.setContentView(R.layout.activity_player)
        controller.start().resume().visible()
        leaveTouchMode(activity.window.decorView.parent)
        TrackPickerFragment.show(activity.supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
        idle()
        panelTakesKeys()
        return activity
    }

    /** The newest panel window leaves touch mode, as a remote's first key would do on a television. */
    private fun panelTakesKeys() {
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    private fun press(text: String, key: Key) {
        // "Off" is also the Background box's state further down the list: the track is the first.
        rule.onAllNodesWithText(text).onFirst().performKeyInput { pressKey(key) }
        idle()
    }

    @Test
    fun `the remote walks from the tracks to In this chat and picks the second file`() {
        val activity = open(listOf(hit(101, "Show.S01E02.eng.srt", "en"), hit(102, "Show.S01E02.fr.srt", "fr")))
        rule.onNodeWithText(L.tracksInChat).assertExists()

        rule.onAllNodesWithText(L.commonOff).onFirst().requestFocus()
        idle()
        // Off, the two embedded tracks, then the entry.
        press(L.commonOff, Key.DirectionDown)
        press("English", Key.DirectionDown)
        press("French", Key.DirectionDown)
        rule.onNodeWithText(L.tracksInChat).assertIsFocused()
        press(L.tracksInChat, Key.DirectionCenter)
        panelTakesKeys()

        // The best match is focused first; Down reaches the next; OK hands that file to the host.
        rule.onNodeWithText("Show.S01E02.eng.srt").assertIsFocused()
        press("Show.S01E02.eng.srt", Key.DirectionDown)
        rule.onNodeWithText("Show.S01E02.fr.srt").assertIsFocused()
        press("Show.S01E02.fr.srt", Key.DirectionCenter)
        assertEquals("Show.S01E02.fr.srt", activity.chosen?.doc?.fileName)
    }

    @Test
    fun `a file the chat no longer has is said and taken out of the list`() {
        val activity = open(listOf(hit(101, "Show.S01E02.eng.srt", "en"), hit(102, "Show.S01E02.fr.srt", "fr")))
        activity.refusal = L.tracksInChatGone
        rule.onAllNodesWithText(L.commonOff).onFirst().requestFocus()
        idle()
        press(L.commonOff, Key.DirectionDown)
        press("English", Key.DirectionDown)
        press("French", Key.DirectionDown)
        press(L.tracksInChat, Key.DirectionCenter)
        panelTakesKeys()
        press("Show.S01E02.eng.srt", Key.DirectionCenter)
        rule.onNodeWithText(L.tracksInChatGone).assertExists()
        rule.onNodeWithText("Show.S01E02.eng.srt").assertDoesNotExist()
        rule.onNodeWithText("Show.S01E02.fr.srt").assertExists()
    }

    @Test
    fun `with no files in the chat there is no entry and the local lines stay`() {
        open(emptyList())
        rule.onNodeWithText(L.tracksInChat).assertDoesNotExist()
        rule.onNodeWithText("English").assertExists()
    }

    @Test
    fun `the words under a file name`() {
        val forced = hit(1, "Show.S01E02.eng.forced.srt", "en").copy(forced = true)
        assertEquals("English  ·  " + L.tracksForced, ChatSubtitleLoader.detail(forced))
        val zip = hit(2, "Show.S01.subs.zip").copy(archive = true)
        assertEquals(L.tracksInChatCompressed, ChatSubtitleLoader.detail(zip))
        assertEquals(null, ChatSubtitleLoader.detail(hit(3, "subs.srt")))
    }
}
