package com.tmplayer.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.DesktopPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import java.nio.file.Files

/**
 * B2.3, the player's mouse, driven through the real [PlayerScreen] with a fake engine in place of
 * libmpv: click to play or pause after the double click window, double click fullscreen, the wheel,
 * the controls showing on movement and hiding after the set time, the timebar's hover time and
 * drag, the volume slider on hover, and the right click menu.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerMouseTest {

    private val dir = Files.createTempDirectory("tm-mouse").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties")).apply { update { it.copy(volume = 50) } }
    private val engine = FakeEngine()
    private val media = FakeMedia()
    private var fullscreenToggles = 0

    private fun ComposeUiTest.open() {
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                PlayerScreen(
                    media = media,
                    startFromBeginning = true,
                    onBack = {},
                    fullscreen = false,
                    onToggleFullscreen = { fullscreenToggles++ },
                    settings = settings,
                    prefs = prefs,
                    engineFactory = { engine },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { engine.state.value.opened }
        waitForIdle()
    }

    /** A point on the picture clear of the centre cluster and the two bars. */
    private val onPicture = Offset(200f, 360f)

    @Test
    fun `a click plays or pauses once the double click window has passed`() = runComposeUiTest {
        open()
        mainClock.autoAdvance = false
        onNodeWithTag("video").performMouseInput { click(onPicture) }
        mainClock.advanceTimeBy(150)
        assertEquals(0, engine.count("toggle"))
        mainClock.advanceTimeBy(300)
        assertEquals(1, engine.count("toggle"))
    }

    @Test
    fun `a double click is fullscreen and never a pause`() = runComposeUiTest {
        open()
        mainClock.autoAdvance = false
        onNodeWithTag("video").performMouseInput { doubleClick(onPicture) }
        mainClock.advanceTimeBy(600)
        assertEquals(1, fullscreenToggles)
        assertEquals(0, engine.count("toggle"))
    }

    @Test
    fun `the wheel is volume, kept across launches, and seeks with the setting`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { moveTo(onPicture); scroll(-1f) }
        waitForIdle()
        assertEquals(55, engine.state.value.volume)
        assertEquals(55, DesktopPrefs(dir.resolve("desktop.properties")).now.volume)
        onNodeWithTag("video").performMouseInput { scroll(1f, ScrollWheel.Horizontal) }
        waitForIdle()
        // A horizontal wheel (or a touchpad swipe) to the right goes forward.
        assertEquals(1, engine.count("seekBy:10000"))
        prefs.update { it.copy(wheelSeeks = true) }
        waitForIdle()
        onNodeWithTag("video").performMouseInput { scroll(-1f) }
        waitForIdle()
        assertEquals(2, engine.count("seekBy:10000"))
        assertEquals(55, engine.state.value.volume)
    }

    @Test
    fun `controls hide after the set time, and never when that is Never`() = runComposeUiTest {
        runBlocking { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = 2_000L) } }
        open()
        mainClock.autoAdvance = false
        onNodeWithTag("video").performMouseInput { moveTo(onPicture) }
        mainClock.advanceTimeBy(1_000)
        onNode(hasContentDescription("More")).assertExists()
        mainClock.advanceTimeBy(1_500)
        onNode(hasContentDescription("More")).assertDoesNotExist()

        runBlocking { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = 0L) } }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("video").performMouseInput { moveTo(onPicture + Offset(40f, 0f)) }
        mainClock.advanceTimeBy(10_000)
        onNode(hasContentDescription("More")).assertExists()
    }

    @Test
    fun `controls hide after the default time since the last movement and come back on a move`() = runComposeUiTest {
        open()
        mainClock.autoAdvance = false
        onNodeWithTag("video").performMouseInput { moveTo(onPicture) }
        mainClock.advanceTimeBy(1_000)
        onNode(hasContentDescription("More")).assertExists()
        mainClock.advanceTimeBy(3_000)
        onNode(hasContentDescription("More")).assertDoesNotExist()
        onNodeWithTag("video").performMouseInput { moveTo(onPicture + Offset(40f, 0f)) }
        mainClock.advanceTimeBy(500)
        onNode(hasContentDescription("More")).assertExists()
    }

    @Test
    fun `the timebar shows the time under the cursor and seeks once on release`() = runComposeUiTest {
        open()
        val bar = onNodeWithTag("timebar")
        bar.performMouseInput { moveTo(percentOffset(0.5f, 0.5f)) }
        waitForIdle()
        onNodeWithText("5:00").assertExists()
        bar.performMouseInput {
            moveTo(percentOffset(0.25f, 0.5f))
            press()
            moveTo(percentOffset(0.5f, 0.5f))
            moveTo(percentOffset(0.75f, 0.5f))
        }
        waitForIdle()
        assertEquals(0, engine.calls.count { it.startsWith("seekTo:") })
        bar.performMouseInput { release() }
        waitForIdle()
        val seeks = engine.calls.filter { it.startsWith("seekTo:") }.map { it.substringAfter(':').toLong() }
        assertEquals(1, seeks.size)
        assertTrue("seek landed at ${seeks.single()}", seeks.single() in 440_000L..460_000L)
    }

    @Test
    fun `hovering the volume button shows its slider`() = runComposeUiTest {
        open()
        val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        onNode(slider).assertDoesNotExist()
        onNode(hasContentDescription("Mute (M)")).performMouseInput { moveTo(center) }
        waitForIdle()
        onNode(slider).assertExists()
    }

    @Test
    fun `right click opens the menu with copy link and download`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        waitForIdle()
        onNodeWithText("Playback details").assertExists()
        onNodeWithText("Copy link").assertExists()
        onNodeWithText("Download").performClick()
        waitForIdle()
        assertEquals(1, media.downloads)
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Copy link").performClick()
        waitUntil(timeoutMillis = 2_000) { media.linkAsked }
    }

    @Test
    fun `question mark opens the shortcut sheet with the wheel as set`() = runComposeUiTest {
        prefs.update { it.copy(wheelSeeks = true) }
        open()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
        waitForIdle()
        onNodeWithText("Keyboard shortcuts").assertExists()
        onNodeWithText("Up, Down, Shift+wheel").assertExists()
    }

    private class FakeEngine : PlaybackEngine {
        val calls = mutableListOf<String>()
        fun count(call: String) = calls.count { it == call }
        private val _state = MutableStateFlow(PlaybackStatus())
        override val state: StateFlow<PlaybackStatus> = _state
        override val tracks: StateFlow<List<MediaTrack>> = MutableStateFlow(emptyList())

        override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
            _state.value = PlaybackStatus(
                opened = true, playing = true, durationMs = 600_000, positionMs = startAtMs,
                volume = prefs.volume, muted = prefs.muted, downmix = prefs.downmix,
            )
        }

        override fun play() { calls += "play"; _state.update { it.copy(playing = true) } }
        override fun pause() { calls += "pause"; _state.update { it.copy(playing = false) } }
        override fun togglePlay() { calls += "toggle"; _state.update { it.copy(playing = !it.playing) } }
        override fun seekTo(positionMs: Long) { calls += "seekTo:$positionMs"; _state.update { it.copy(positionMs = positionMs) } }
        override fun seekBy(deltaMs: Long) { calls += "seekBy:$deltaMs" }
        override fun frameStep(forward: Boolean) { calls += "frame" }
        override fun setSpeed(speed: Float) { _state.update { it.copy(speed = speed) } }
        override fun setVolume(percent: Int) { calls += "volume:$percent"; _state.update { it.copy(volume = percent) } }
        override fun setMuted(muted: Boolean) { _state.update { it.copy(muted = muted) } }
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun setScale(scale: com.tmplayer.player.VideoScale) { _state.update { it.copy(scale = scale) } }
        override fun setDownmix(stereo: Boolean) { _state.update { it.copy(downmix = stereo) } }
        override fun addSubtitle(path: String): Boolean { calls += "sub:$path"; return true }
        override fun details(): List<Pair<String, String>> = emptyList()
        override fun close() = Unit
    }

    private class FakeMedia : PlayerMedia {
        var downloads = 0
        @Volatile var linkAsked = false
        override val item = MediaItem(
            chatId = 5, messageId = 9, fileId = 3, title = "Night Train", sizeBytes = 1L shl 30, durationSec = 600,
            mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = "Night Train.mkv",
        )
        override val chatTitle = "Film Club"
        override suspend fun open(): MediaData = UriMediaData("night-train.mkv")
        override suspend fun episodes() = Episodes()
        override fun episode(other: MediaItem): PlayerMedia = this
        override val downloaded: StateFlow<Float?> = MutableStateFlow(null)
        override suspend fun tdlibVersion(): String? = null
        override fun release() = Unit
        override val fromTelegram: Boolean get() = true
        override suspend fun messageLink(): String {
            linkAsked = true
            return "https://t.me/c/5/9"
        }
        override fun download(): String {
            downloads++
            return "Downloading Night Train"
        }
    }
}
