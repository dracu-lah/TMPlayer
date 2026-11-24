package com.tmplayer.desktop.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.StutterReport
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.ProvideStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop player's decoder lines (WP-25, PO-09): Force software decoding and Report stutter on
 * the Options page for a picture decoded here, never for a cast's; the switch hands the player
 * [MenuAction.ToggleSoftwareDecoding] and leaves the menu up with the new value, Report stutter
 * closes it; [SwitchingEngine] passes the switch to the engine playing; [DesktopStutter] reads the
 * decoder's numbers without trusting them.
 */
@OptIn(ExperimentalTestApi::class)
class DecoderMenuTest {

    private fun menu(status: PlaybackStatus, closed: () -> Unit, acted: (MenuAction) -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        ProvideStrings {
            PlayerTheme {
                PlayerMenu(
                    menu = MenuAt(MenuPage.Options, MenuAt.Anchor.Subtitles),
                    status = status,
                    tracks = emptyList(),
                    fullscreen = false,
                    ignoreClicks = false,
                    miniPlayerAvailable = false,
                    alwaysOnTopAvailable = false,
                    fromTelegram = true,
                    savable = true,
                    watchedLabel = null,
                    sleepTimer = null,
                    onOpenMenu = {},
                    onClose = closed,
                    onAction = acted,
                )
            }
        }
    }

    private val here = PlaybackStatus(opened = true, durationMs = 600_000, positionMs = 60_000, playing = true, decodedHere = true)

    @Test
    fun theSwitchAsksForSoftwareDecodingAndLeavesTheMenuUp() = runComposeUiTest {
        var action: MenuAction? = null
        var closed = 0
        setContent(menu(here, { closed++ }, { action = it }))
        onNodeWithText(L.settingsSoftwareDecoding).performClick()
        assertEquals(MenuAction.ToggleSoftwareDecoding, action)
        assertEquals(0, closed)
    }

    @Test
    fun reportStutterClosesTheMenu() = runComposeUiTest {
        var action: MenuAction? = null
        var closed = 0
        setContent(menu(here, { closed++ }, { action = it }))
        onNodeWithText(L.playerReportStutter).performClick()
        assertEquals(MenuAction.ReportStutter, action)
        assertEquals(1, closed)
    }

    @Test
    fun aCastHasNeitherLine() = runComposeUiTest {
        setContent(menu(here.copy(decodedHere = false), {}, {}))
        assertEquals(0, onAllNodesWithText(L.settingsSoftwareDecoding).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText(L.playerReportStutter).fetchSemanticsNodes().size)
    }

    /** PB2-20: a hwdec mpv refused leaves the state, the tick and the setting as they were, and says so. */
    @Test
    fun aRefusedSoftwareSwitchIsNotSaidOrKept() {
        val root = if (java.io.File("src/main/kotlin").exists()) java.io.File(".") else java.io.File("desktop")
        val mpv = java.io.File(root, "src/main/kotlin/com/tmplayer/desktop/player/MpvPlaybackEngine.kt").readText()
            .substringAfter("override fun setSoftwareDecoding(on: Boolean) {").substringBefore("\n    }\n")
        assertTrue(".isSuccess" in mpv)
        assertTrue("if (ok) _state.update { it.copy(softwareDecoding = on) }" in mpv)
        val screen = java.io.File(root, "src/main/kotlin/com/tmplayer/desktop/player/PlayerScreen.kt").readText()
            .substringAfter("MenuAction.ToggleSoftwareDecoding -> {").substringBefore("MenuAction.ReportStutter ->")
        val check = screen.indexOf("if (engine.state.value.softwareDecoding == on) {")
        assertTrue("the setting is written only after the check", check in 1 until screen.indexOf("prefs.update"))
        assertTrue("s.playerDecoderSwitchFailed" in screen)
        assertTrue(L.playerDecoderSwitchFailed.isNotBlank())
    }

    @Test
    fun theSwitchingEngineHandsTheSwitchToTheEnginePlaying() {
        val local = RecordingEngine()
        val switching = com.tmplayer.desktop.cast.SwitchingEngine(local)
        switching.setSoftwareDecoding(true)
        switching.setSoftwareDecoding(false)
        assertEquals(listOf(true, false), local.software)
        assertNull("nothing open, nothing to report", switching.stutterFacts("1.0.0"))
    }

    /** An engine that only records the software switch. */
    private class RecordingEngine : PlaybackEngine {
        val software = mutableListOf<Boolean>()
        override val state: kotlinx.coroutines.flow.StateFlow<PlaybackStatus> = kotlinx.coroutines.flow.MutableStateFlow(PlaybackStatus())
        override val tracks: kotlinx.coroutines.flow.StateFlow<List<MediaTrack>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override fun setSoftwareDecoding(on: Boolean) { software += on }
        override suspend fun open(data: org.openani.mediamp.source.MediaData, startAtMs: Long, prefs: OpenPrefs) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun togglePlay() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun frameStep(forward: Boolean) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun setScale(scale: com.tmplayer.player.VideoScale) = Unit
        override fun setDownmix(stereo: Boolean) = Unit
        override fun setVolumeBoost(on: Boolean) = Unit
        override fun setSubtitleStyle(style: com.tmplayer.player.SubtitleStyle) = Unit
        override fun setSubtitleDelay(ms: Long) = Unit
        override fun setAudioDelay(ms: Long) = Unit
        override fun addSubtitle(path: String) = false
        override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean) = false
        override fun setAbLoop(loop: AbLoop?) = Unit
        override fun details(): List<Pair<String, String>> = emptyList()
        override fun close() = Unit
    }

    private class Props(private val map: Map<String, Any?>) : MpvFormats.Props {
        override fun string(name: String) = map[name] as? String
        override fun int(name: String) = map[name] as? Int
        override fun double(name: String) = map[name] as? Double
        override fun bool(name: String) = map[name] as? Boolean
    }

    private val system = DesktopStutter.System("Windows 11", "10.0", "amd64", 8)

    @Test
    fun theStutterFactsAreTheDecodersNumbers() {
        val facts = DesktopStutter.facts(
            Props(
                mapOf(
                    "video-codec-name" to "hevc", "video-params/w" to 1920, "video-params/h" to 1080,
                    "container-fps" to 23.976, "video-params/pixelformat" to "yuv420p10", "hwdec-current" to "d3d11va",
                    "frame-drop-count" to 190, "decoder-frame-drop-count" to 10, "estimated-frame-number" to 1_400,
                    "video-bitrate" to 8_000_000.0, "file-format" to "matroska,webm",
                ),
            ),
            "1.40.0",
            system,
        )
        assertNotNull(facts)
        facts!!
        assertEquals("hevc", facts.codec)
        assertEquals(10, facts.bitDepth)
        assertEquals(1080, facts.sizeBucket)
        assertTrue(facts.hardware)
        assertEquals(200, facts.dropped)
        assertEquals(8_000, facts.bitrateKbps)
        assertTrue("200 of 1,400 dropped is a stutter worth sending", StutterReport.worthSending(facts))
        assertTrue(DesktopStutter.line(facts).contains("200 of 1400 frames dropped"))
    }

    @Test
    fun garbageNumbersNeverThrowAndNoPictureIsNoReport() {
        assertNull(DesktopStutter.facts(Props(emptyMap()), "1.40.0", system))
        val facts = DesktopStutter.facts(
            Props(
                mapOf(
                    "video-format" to "h264", "video-params/w" to -5, "video-params/h" to Int.MAX_VALUE,
                    "container-fps" to Double.NaN, "frame-drop-count" to -3, "estimated-frame-number" to -1,
                    "video-bitrate" to Double.POSITIVE_INFINITY, "hwdec-current" to "no",
                ),
            ),
            "1.40.0",
            system,
        )!!
        assertEquals(0, facts.width)
        assertEquals(0f, facts.fps)
        assertEquals(0, facts.dropped)
        assertFalse(facts.hardware)
        assertFalse("no frame rate, nothing dropped: a false alarm is not sent", StutterReport.worthSending(facts))
    }
}
