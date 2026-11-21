package com.tmplayer.player

import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollTo
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
import com.tmplayer.online.HttpRequest
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineSubtitlesStore
import com.tmplayer.online.SubtitleCache
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.nio.file.Files
import java.time.Duration

/**
 * SubMaker in the subtitles picker on a television, by remote alone: the row beside the tracks,
 * the list from the viewer's own addon (a fake one, answering as the real one does), a "Make
 * <Language>" entry that asks once whether to spend the AI quota, and then the progress line,
 * which is also the Cancel control and can be reached and pressed with the arrow keys and OK.
 * The real [TrackPickerFragment] over a stand-in player.
 */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SubMakerPickerBase {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val base = "https://addon.test/tok-3f9a2b7c1d"
    private val dir: File = Files.createTempDirectory("tm-submaker-focus").toFile()

    /** Lists one ready subtitle and one translation; a translation address answers with its placeholder. */
    private val addon = HttpTransport { request: HttpRequest ->
        val path = request.url.removePrefix(base)
        when {
            path.startsWith("/subtitles/") -> HttpResponse(
                200,
                body = """{"subtitles":[
                    {"id":"sub_toolbox","lang":"Sub Toolbox","url":"$base/sub-toolbox/x"},
                    {"id":"os_1","lang":"eng","label":"Night.Sky.2022.1080p","url":"$base/sub/eng.srt"},
                    {"id":"translate_mal","lang":"Make Malayalam","url":"$base/translate/mal"}]}""".toByteArray(),
            )
            path.startsWith("/translate/") -> HttpResponse(
                200,
                body = "1\n00:00:00,000 --> 04:00:00,000\nTRANSLATION IN PROGRESS\n".toByteArray(),
            )
            else -> HttpResponse(404)
        }
    }

    val online = OnlineSubtitles(
        apiKey = "",
        store = OnlineSubtitlesStore(File(dir, "online.properties")),
        cache = SubtitleCache(File(dir, "cache")),
        appVersion = "1.0.0",
        http = addon,
    ).also {
        it.setSubMakerUrl("$base/manifest.json")
        // No polling here: the job stays where it began, which is what the progress line is checked on.
        it.translations.setForeground(false)
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        OnlineSubtitles.current = null
        online.translations.cancelAll()
        dir.deleteRecursively()
    }

    class Host : FragmentActivity(), TrackPickerHost {
        var online: OnlineSubtitles? = null
        var started: SubtitleHit? = null
        private val stand = StandIn()

        override val player: Player get() = stand
        override fun syncDelaysNow() = SyncDelays()
        override fun stepDelay(trackType: Int, direction: Int) = Unit
        override fun subtitleStyleNow() = SubtitleStyle()
        override fun changeSubtitleStyle(style: SubtitleStyle) = Unit
        override suspend fun onlineTarget(): SubtitleTarget? =
            SubtitleTarget(fileName = "Night.Sky.2022.1080p.mkv", sizeBytes = 1_000_000, imdb = "tt1234567")

        override fun attachOnlineSubtitle(file: File, label: String) = Unit

        override fun startTranslation(hit: SubtitleHit, target: SubtitleTarget): Boolean {
            started = hit
            return online?.translate(target.videoKey, hit, null) { _, _ -> } == true
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

    fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)) }
        rule.waitForIdle()
    }

    fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "windowFocusChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    fun open(tv: Boolean): Host {
        FormFactor.override(tv)
        OnlineSubtitles.current = online
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val activity = controller.get()
        activity.online = online
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        controller.start().resume().visible()
        if (tv) leaveTouchMode(activity.window.decorView.parent)
        TrackPickerFragment.show(activity.supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
        idle()
        if (tv) panelTakesKeys()
        return activity
    }

    fun panelTakesKeys() {
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    fun press(text: String, key: Key) {
        rule.onAllNodesWithText(text).onFirst().performKeyInput { pressKey(key) }
        idle()
    }

    /** Down from Off, past the two tracks, onto the SubMaker row, and OK: its list is open. */
    fun openSubMaker() {
        rule.onAllNodesWithText(L.commonOff).onFirst().requestFocus()
        idle()
        press(L.commonOff, Key.DirectionDown)
        press("English", Key.DirectionDown)
        press("French", Key.DirectionDown)
        rule.onNodeWithText(L.submakerSearch).assertIsFocused()
        press(L.submakerSearch, Key.DirectionCenter)
        panelTakesKeys()
    }

}

/** The television: the remote alone. */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SubMakerFocusTest : SubMakerPickerBase() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    fun `the remote reaches SubMaker, its list, and a ready subtitle is first and focused`() {
        open(tv = true)
        rule.onNodeWithText(L.submakerSearch).assertExists()
        openSubMaker()
        // The toolbox entry is not a row; the ready subtitle is focused, the translation is under it.
        rule.onNodeWithText("Night.Sky.2022.1080p").assertIsFocused()
        rule.onNodeWithText("sub_toolbox").assertDoesNotExist()
        rule.onNodeWithText(L.submakerTranslateTo("Malayalam")).assertExists()
    }

    @Test
    fun `a translation asks once, runs as a job, and its progress line can be reached and cancelled`() {
        val activity = open(tv = true)
        openSubMaker()
        press("Night.Sky.2022.1080p", Key.DirectionDown)
        rule.onNodeWithText(L.submakerTranslateTo("Malayalam")).assertIsFocused()
        press(L.submakerTranslateTo("Malayalam"), Key.DirectionCenter)
        panelTakesKeys()

        // The first time for a language it asks, naming the quota it spends, with Translate focused.
        rule.onNodeWithText(L.submakerAskTitle("Malayalam")).assertExists()
        assertNull(activity.started)
        rule.onNodeWithText(L.submakerAskConfirm).assertIsFocused()
        press(L.submakerAskConfirm, Key.DirectionCenter)
        panelTakesKeys()

        assertNotNull(activity.started)
        assertTrue(online.submakerAsked("Malayalam"))
        // The progress line replaces the entry, and the remote reaches it.
        val progress = L.submakerProgressWaiting("Malayalam")
        rule.onNodeWithText(progress).assertExists()
        rule.onNodeWithText(L.submakerCancel).assertExists()
        press("Night.Sky.2022.1080p", Key.DirectionDown)
        rule.onNodeWithText(progress).assertIsFocused()

        // OK on it cancels: the job is gone and the entry is back, ready to ask again without a question.
        press(progress, Key.DirectionCenter)
        rule.onNodeWithText(progress).assertDoesNotExist()
        rule.onNodeWithText(L.submakerTranslateTo("Malayalam")).assertExists()
        assertEquals(0, online.translations.states.value.size)
    }

    @Test
    fun `without an address there is no SubMaker row`() {
        online.setSubMakerUrl("")
        open(tv = true)
        rule.onNodeWithText(L.submakerSearch).assertDoesNotExist()
        rule.onNodeWithText("English").assertExists()
    }
}

/** A phone and a tablet by touch: the same steps with a finger, the progress line is the Cancel control. */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xxhdpi")
class SubMakerPhoneTest : SubMakerPickerBase() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    fun `a finger reaches SubMaker, asks once, shows the job and cancels it`() = touchSteps()
}

@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-xhdpi")
class SubMakerTabletTest : SubMakerPickerBase() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    fun `a finger reaches SubMaker, asks once, shows the job and cancels it`() = touchSteps()
}

@OptIn(ExperimentalTestApi::class)
private fun SubMakerPickerBase.tap(text: String) {
    rule.onAllNodesWithText(text).onFirst().performScrollTo().performClick()
    idle()
}

@OptIn(ExperimentalTestApi::class)
private fun SubMakerPickerBase.touchSteps() {
    val activity = open(tv = false)
    tap(L.submakerSearch)
    rule.onNodeWithText("Night.Sky.2022.1080p").assertExists()
    tap(L.submakerTranslateTo("Malayalam"))
    rule.onNodeWithText(L.submakerAskTitle("Malayalam")).assertExists()
    assertNull(activity.started)
    tap(L.submakerAskConfirm)
    assertNotNull(activity.started)
    val progress = L.submakerProgressWaiting("Malayalam")
    rule.onNodeWithText(progress).assertExists()
    rule.onNodeWithText(L.submakerCancel).assertExists()
    tap(progress)
    rule.onNodeWithText(progress).assertDoesNotExist()
    // Asked once: the language is remembered, so the entry starts the job at once the next time.
    tap(L.submakerTranslateTo("Malayalam"))
    rule.onNodeWithText(L.submakerAskTitle("Malayalam")).assertDoesNotExist()
    rule.onNodeWithText(progress).assertExists()
}
