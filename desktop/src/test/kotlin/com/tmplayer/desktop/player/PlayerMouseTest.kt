package com.tmplayer.desktop.player

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
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
import kotlinx.coroutines.flow.first
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
    private var backs = 0

    private val shots = dir.resolve("shots")
    private val watched = com.tmplayer.data.WatchedStore(
        com.tmplayer.data.WatchedStore.openDataStore(dir.resolve(com.tmplayer.data.WatchedStore.FILE_NAME)),
    )

    /** False takes the player off the screen, the way Back does in the app. */
    private val shown = androidx.compose.runtime.mutableStateOf(true)

    private fun ComposeUiTest.open(
        idleLimitMs: Long = com.tmplayer.player.StillWatching.IDLE_LIMIT_MS,
        fromStart: Boolean = true,
    ) {
        setContent {
            Box(Modifier.size(1280.dp, 720.dp)) {
                if (shown.value) PlayerScreen(
                    media = media,
                    startFromBeginning = fromStart,
                    screenshotDir = { shots },
                    watched = watched,
                    onBack = { backs++ },
                    fullscreen = false,
                    onToggleFullscreen = { fullscreenToggles++ },
                    settings = settings,
                    prefs = prefs,
                    engineFactory = { engine },
                    idleLimitMs = idleLimitMs,
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
    fun `a pause keeps the controls up, and a resume lets them go sooner when they were down`() = runComposeUiTest {
        open()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(5_000)
        onNode(hasContentDescription("More")).assertDoesNotExist()
        // Space over the bare picture: the controls come up, the centre button says Play, and
        // they stay for the whole pause.
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(100)
        assertEquals(false, engine.state.value.playing)
        onNode(hasContentDescription("More")).assertExists()
        onNodeWithTag("play-pause").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
        mainClock.advanceTimeBy(20_000)
        onNode(hasContentDescription("More")).assertExists()
        // The resume: the button turns back, and the controls go after the quick 1.5 s.
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(true, engine.state.value.playing)
        onNode(hasContentDescription("More")).assertExists()
        onNodeWithTag("play-pause").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        mainClock.advanceTimeBy(1_000)
        onNode(hasContentDescription("More")).assertDoesNotExist()
    }

    @Test
    fun `a pause with the controls up keeps the usual time after the resume`() = runComposeUiTest {
        open()
        mainClock.autoAdvance = false
        onNodeWithTag("video").performMouseInput { moveTo(onPicture) }
        mainClock.advanceTimeBy(100)
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(100)
        assertEquals(false, engine.state.value.playing)
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(2_500)
        onNode(hasContentDescription("More")).assertExists()
        mainClock.advanceTimeBy(1_500)
        onNode(hasContentDescription("More")).assertDoesNotExist()
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
    fun `right click opens the menu with copy link, save to downloads and open in another app`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        waitForIdle()
        onNodeWithText("Playback options").assertExists()
        onNodeWithText("Subtitle style and timing").assertExists()
        onNodeWithText("Help").assertExists()
        onNodeWithText("Copy link").assertExists()
        onNodeWithText("Open in another app").assertExists()
        onNodeWithText("Save to Downloads").performClick()
        waitForIdle()
        assertEquals(1, media.downloads)
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Copy link").performClick()
        waitUntil(timeoutMillis = 2_000) { media.linkAsked }
    }

    /**
     * HX16: More opens the side panel in the player's own window on one click, with the first line
     * holding the keyboard; the player's keys do nothing behind it, Esc closes it, and a press
     * outside closes it without pausing the video.
     */
    @Test
    fun `more opens the side panel at once, and the keys behind it wait`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { moveTo(onPicture) }
        waitForIdle()
        onNode(hasContentDescription("More")).performClick()
        waitForIdle()
        onNodeWithTag("menu-panel").assertExists()
        onNodeWithText("More options").assertExists()
        onNodeWithText("Picture shape").assertExists()
        // W cycles the shape on the bare player; not with the panel up.
        onNodeWithTag("menu-panel").performKeyInput { pressKey(Key.W) }
        waitForIdle()
        assertEquals(com.tmplayer.player.VideoScale.Fit, engine.state.value.scale)
        onNodeWithTag("menu-panel").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag("menu-panel").assertDoesNotExist()
        assertEquals(0, backs)
        // A press on the picture beside it closes it and is no pause.
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        waitForIdle()
        onNodeWithTag("menu-panel").assertExists()
        val toggles = engine.count("toggle")
        onNodeWithTag("menu-panel-outside").performMouseInput { click(onPicture) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag("menu-panel").assertDoesNotExist()
        assertEquals(toggles, engine.count("toggle"))
    }

    /**
     * The shape page in the panel: a pick applies with the panel left open, a fixed ratio is not
     * saved for the next player, and Fill is.
     */
    @Test
    fun `the panel's shape page picks a ratio for this player only`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Picture shape").performClick()
        waitForIdle()
        onNodeWithText("16:9").performClick()
        waitForIdle()
        assertEquals(com.tmplayer.player.VideoScale.Ratio16x9, engine.state.value.scale)
        onNodeWithTag("menu-panel").assertExists()
        onNodeWithText("Fill").performClick()
        waitForIdle()
        assertEquals(com.tmplayer.player.VideoScale.Crop, engine.state.value.scale)
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.videoScaleNow() } == "Crop" }
        onNodeWithText("2.35:1").performClick()
        waitForIdle()
        Thread.sleep(200)
        assertEquals("Crop", runBlocking { settings.videoScaleNow() })
    }

    @Test
    fun `the menu switches volume boost on and off, kept as the setting`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Playback options").performClick()
        onNodeWithText("Volume boost").performClick()
        waitForIdle()
        assertEquals(1, engine.count("boost:true"))
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.volumeBoostNow() } }
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Playback options").performClick()
        onNodeWithText("Volume boost").performClick()
        waitForIdle()
        assertEquals(1, engine.count("boost:false"))
        waitUntil(timeoutMillis = 2_000) { runBlocking { !settings.volumeBoostNow() } }
    }

    @Test
    fun `the sleep timer is a page of the menu and reads what is left`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Playback options").performClick()
        onNodeWithText("Sleep timer").performClick()
        waitForIdle()
        onNodeWithText("End of this video").assertExists()
        onNodeWithText("30 minutes").performClick()
        waitForIdle()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Playback options").performClick()
        onNodeWithText("30 minutes left").assertExists()
    }

    @Test
    fun `still watching stops playback after the idle time, and keep watching opens it again`() = runComposeUiTest {
        open(idleLimitMs = 10_000L)
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(16_000)
        onNodeWithText("Still watching?").assertExists()
        assertEquals(1, engine.count("stop"))
        assertEquals(false, engine.state.value.playing)
        onNodeWithText("Keep watching").performClick()
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5_000) { engine.state.value.opened }
        waitForIdle()
        onNodeWithText("Still watching?").assertDoesNotExist()
        assertEquals(2, engine.count("open"))
    }

    @Test
    fun `question mark opens the shortcut sheet with the wheel as set`() = runComposeUiTest {
        prefs.update { it.copy(wheelSeeks = true) }
        open()
        onNode(isFocused()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
        waitForIdle()
        onNodeWithText("Keyboard shortcuts").assertExists()
        onNodeWithText("Up, Down, Shift+wheel").assertExists()
        onNodeWithText("Repeat A to B: start, end, off").assertExists()
        onNodeWithText("Previous, next chapter").assertExists()
    }

    /**
     * The player over a page, as in the app's window: the window's preview goes through
     * [PlayerKeyRoute] (Main.kt), and the page behind holds a focusable cell and a search field.
     */
    private fun ComposeUiTest.openOverPage(cell: androidx.compose.ui.focus.FocusRequester, field: androidx.compose.ui.focus.FocusRequester) {
        setContent {
            var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            Box(Modifier.size(1280.dp, 720.dp).onPreviewKeyEvent(PlayerKeyRoute::onWindowKey)) {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.size(100.dp).testTag("page-cell").focusRequester(cell).focusable())
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.testTag("page-search").focusRequester(field),
                    )
                }
                if (shown.value) PlayerScreen(
                    media = media,
                    startFromBeginning = true,
                    screenshotDir = { shots },
                    watched = watched,
                    onBack = { backs++ },
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

    @Test
    fun `the keys reach the player when the page behind it takes the focus`() = runComposeUiTest {
        val cell = androidx.compose.ui.focus.FocusRequester()
        val field = androidx.compose.ui.focus.FocusRequester()
        openOverPage(cell, field)
        // A cell of the page asks for the focus after the player opened (a detail pane's Play,
        // a row's pending cell): F still goes fullscreen, and the focus is back on the picture.
        runOnIdle { cell.requestFocus() }
        onNodeWithTag("page-cell").assertIsFocused()
        onNodeWithTag("page-cell").performKeyInput { pressKey(Key.F) }
        waitForIdle()
        assertEquals(1, fullscreenToggles)
        onNodeWithTag("page-cell").assertIsNotFocused()
        // The next key takes the usual way, through the picture's own focus.
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(1, engine.count("toggle"))
    }

    @Test
    fun `a search field left focused behind the player does not swallow its keys`() = runComposeUiTest {
        val cell = androidx.compose.ui.focus.FocusRequester()
        val field = androidx.compose.ui.focus.FocusRequester()
        openOverPage(cell, field)
        runOnIdle { field.requestFocus() }
        onNodeWithTag("page-search").assertIsFocused()
        onNodeWithTag("page-search").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(1, engine.count("toggle"))
        onNodeWithTag("page-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test
    fun `the window leaves keys alone once the player is closed`() = runComposeUiTest {
        val cell = androidx.compose.ui.focus.FocusRequester()
        val field = androidx.compose.ui.focus.FocusRequester()
        openOverPage(cell, field)
        shown.value = false
        waitForIdle()
        assertEquals(false, PlayerKeyRoute.isOpen)
        runOnIdle { field.requestFocus() }
        onNodeWithTag("page-search").performKeyInput { pressKey(Key.F) }
        waitForIdle()
        assertEquals(0, fullscreenToggles)
    }

    /** [command] is Ctrl, or Cmd on a Mac, where the player reads the chapter keys from Cmd. */
    private fun ComposeUiTest.press(key: Key, command: Boolean = false, shift: Boolean = false) {
        onNode(isFocused()).performKeyInput {
            when {
                command -> withKeyDown(if (com.tmplayer.desktop.os.OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) }
                shift -> withKeyDown(Key.ShiftLeft) { pressKey(key) }
                else -> pressKey(key)
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.seek(to: Long) {
        engine.seekTo(to)
        waitForIdle()
    }

    @Test
    fun `frame step works on a paused picture only`() = runComposeUiTest {
        open()
        press(Key.Period)
        assertEquals(0, engine.count("frame"))
        press(Key.K)
        press(Key.Period)
        press(Key.Comma)
        assertEquals(2, engine.count("frame"))
    }

    @Test
    fun `R marks the loop start, then its end, then turns it off`() = runComposeUiTest {
        open()
        seek(90_000)
        press(Key.R)
        // Only a whole loop goes to mpv; the start alone waits on the screen.
        assertEquals(0, engine.calls.count { it.startsWith("loop:") })
        onNodeWithText("Loop from 1:30. Press R again to end it").assertExists()
        // Marked back to front, the two land in time order.
        seek(30_000)
        press(Key.R)
        assertEquals(1, engine.count("loop:30000-90000"))
        onNodeWithText("Repeating 0:30 to 1:30").assertExists()
        press(Key.R)
        assertEquals(1, engine.count("loop:null-null"))
        onNodeWithText("Repeat off").assertExists()
    }

    @Test
    fun `Ctrl (Cmd on a Mac) and the arrows step through chapters, and seek a minute in a file without`() = runComposeUiTest {
        engine.chapters = listOf(Chapter(0, "Opening"), Chapter(120_000, "The Station"), Chapter(300_000, "Chapter 3"))
        open()
        seek(130_000)
        press(Key.DirectionRight, command = true)
        assertEquals(1, engine.count("seekTo:300000"))
        onNodeWithText("Chapter 3 of 3").assertExists()
        // Straight after the jump, back goes to the chapter before rather than this one's start.
        press(Key.DirectionLeft, command = true)
        assertEquals(1, engine.count("seekTo:120000"))
        onNodeWithText("Chapter 2 of 3: The Station").assertExists()
        // A minute in, back is the start of the same chapter.
        seek(180_000)
        press(Key.DirectionLeft, command = true)
        assertEquals(2, engine.count("seekTo:120000"))
    }

    @Test
    fun `a file without chapters seeks a minute on the chapter keys`() = runComposeUiTest {
        open()
        press(Key.DirectionRight, command = true)
        assertEquals(1, engine.count("seekBy:60000"))
    }

    @Test
    fun `S saves the frame to the screenshot folder, and refuses where saving is restricted`() = runComposeUiTest {
        open()
        seek(65_000)
        press(Key.S)
        waitUntil(timeoutMillis = 2_000) { engine.count("shot:Night Train 01-05.png:true") == 1 }
        press(Key.S, shift = true)
        waitUntil(timeoutMillis = 2_000) { engine.count("shot:Night Train 01-05 (2).png:false") == 1 }
        assertTrue(shots.resolve("Night Train 01-05.png").isFile)
        media.savable.value = false
        press(Key.S)
        waitForIdle()
        assertEquals(2, engine.calls.count { it.startsWith("shot:") })
    }

    @Test
    fun `the position is written with the tracks, speed and delays, every ten seconds and on pause`() = runComposeUiTest {
        engine.trackList.value = listOf(
            track(1, TrackType.Audio, "eng", selected = false),
            track(2, TrackType.Audio, "eng", selected = true),
            track(1, TrackType.Subtitle, "fre", selected = false),
        )
        open()
        seek(240_000)
        press(Key.RightBracket) // speed up a step
        press(Key.X) // subtitles a tenth later
        val speed = engine.state.value.speed
        assertTrue(speed > 1f)
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(10_500)
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.resumeRecord(5, 9) } != null }
        val record = runBlocking { settings.resumeRecord(5, 9) }!!
        assertEquals(240_000L, record.positionMs)
        assertEquals(1, record.state?.audioTrack)
        assertEquals(com.tmplayer.data.ResumeState.SUBTITLES_OFF, record.state?.subtitleTrack)
        assertEquals(speed, record.state?.speed)
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.syncDelays(5, 9) }.subtitleMs == 100L }

        // A pause writes at once, without waiting for the next beat.
        seek(260_000)
        press(Key.K)
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.resumePosition(5, 9) } == 260_000L }
    }

    @Test
    fun `past ninety percent the position goes at once, and the video is watched on the way out`() = runComposeUiTest {
        open()
        seek(240_000)
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(10_500)
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.resumePosition(5, 9) } == 240_000L }

        // 93 %: the resume point goes with the next beat, but nothing is marked while it plays,
        // so "Remove after watching" cannot take the file from under the last tenth.
        seek(560_000)
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(10_500)
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 2_000) { runBlocking { settings.resumePosition(5, 9) } == 0L }
        assertTrue(runBlocking { watched.history.first() }.isEmpty())

        shown.value = false
        waitForIdle()
        waitUntil(timeoutMillis = 2_000) { runBlocking { watched.isWatched(5, 9) } }
    }

    @Test
    fun `a resumed video opens with the tracks and speed it was left with`() = runComposeUiTest {
        val state = com.tmplayer.data.ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = -1, speed = 1.5f)
        runBlocking {
            // A wider memory underneath, which the file's own speed is read over.
            val chat = com.tmplayer.player.SpeedMemory.chatScope(5, 0)
            settings.rememberSpeed(com.tmplayer.player.SpeedMemory.Scopes(chat = chat), 1.25f)
            settings.saveResumePosition(
                5, 9, 400_000L, 600_000L,
                com.tmplayer.data.ResumeRecord.encode(3, "Night Train", "Film Club", 1L shl 30, 600, 1L, state = state),
            )
        }
        open(fromStart = false)
        assertEquals(state, engine.lastPrefs?.resume)
        assertEquals(1.5f, engine.lastPrefs?.speed)
        // The default rewind: three seconds before the saved point.
        assertEquals(400_000L - com.tmplayer.data.ResumeRewind.DEFAULT_MS, engine.state.value.positionMs)
    }

    @Test
    fun `cancel on the end countdown stops it, and the next episode is not started`() = runComposeUiTest {
        media.steps = Episodes(next = media.item.copy(messageId = 10, title = "Night Train 2"))
        open()
        mainClock.autoAdvance = false
        engine.end()
        mainClock.advanceTimeBy(1_500)
        onNodeWithText("Cancel").performClick()
        mainClock.advanceTimeBy(12_000)
        assertTrue(media.switchedTo.isEmpty())
        onNodeWithText("Watch again").assertExists()
    }

    @Test
    fun `the keys still work after watch again and start over`() = runComposeUiTest {
        runBlocking {
            settings.saveResumePosition(
                5, 9, 400_000L, 600_000L,
                com.tmplayer.data.ResumeRecord.encode(3, "Night Train", "Film Club", 1L shl 30, 600, 1L),
            )
        }
        open(fromStart = false)
        // The offer comes once the saved point has been read, which a slow runner (Windows on CI)
        // had not done yet when the click was made.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Start over").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start over").performClick()
        waitForIdle()
        press(Key.K)
        assertEquals(1, engine.count("toggle"))
        engine.end()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Watch again").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Watch again").performClick()
        waitForIdle()
        press(Key.K)
        assertEquals(2, engine.count("toggle"))
    }

    @Test
    fun `watch again after the sleep timer stopped the video at its end opens it again`() = runComposeUiTest {
        open()
        onNodeWithTag("video").performMouseInput { rightClick(onPicture) }
        onNodeWithText("Playback options").performClick()
        onNodeWithText("Sleep timer").performClick()
        onNodeWithText("End of this video").performClick()
        waitForIdle()
        engine.end()
        waitForIdle()
        assertEquals(1, engine.count("stop"))
        onNodeWithText("Keep watching").performClick()
        waitForIdle()
        onNodeWithText("Watch again").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.count("open") == 2 && engine.state.value.opened }
        assertEquals(0L, engine.state.value.positionMs)
    }

    @Test
    fun `the wheel over the shortcut sheet leaves the volume alone`() = runComposeUiTest {
        open()
        press(Key.Slash, shift = true)
        onNodeWithText("Keyboard shortcuts").assertExists()
        onNodeWithTag("video").performMouseInput { moveTo(onPicture); scroll(-1f) }
        waitForIdle()
        assertEquals(50, engine.state.value.volume)
        press(Key.Escape)
        onNodeWithTag("video").performMouseInput { moveTo(onPicture); scroll(-1f) }
        waitForIdle()
        assertEquals(55, engine.state.value.volume)
    }

    @Test
    fun `a remote walks into the controls with Down, Back leaves them, and Esc and Backspace leave the video`() = runComposeUiTest {
        com.tmplayer.desktop.ui.BigScreen.override = com.tmplayer.desktop.DesktopLayout.Tv
        try {
            open()
            onNode(isFocused()).assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            // Down on the picture: the controls come up and the remote is on one of them.
            onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNode(hasContentDescription("More")).assertExists()
            onNode(isFocused()).assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            val first = onNode(isFocused()).fetchSemanticsNode().id
            // The arrows walk the controls rather than seek.
            onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
            waitForIdle()
            assertTrue("Right moved to another control", onNode(isFocused()).fetchSemanticsNode().id != first)
            assertTrue(engine.calls.none { it.startsWith("seek") })
            // Back from the controls: the remote is on the picture again, the video still open.
            onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNode(isFocused()).assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            assertEquals(0, backs)
            // On the picture Esc and Backspace are the remote's Back: they leave the video.
            onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(1, backs)
            onNode(isFocused()).performKeyInput { pressKey(Key.Backspace) }
            waitForIdle()
            assertEquals(2, backs)
        } finally {
            com.tmplayer.desktop.ui.BigScreen.override = null
        }
    }

    private fun track(id: Int, type: TrackType, lang: String, selected: Boolean) = MediaTrack(
        id = id, type = type, title = null, language = lang, codec = null, channels = null,
        selected = selected, isDefault = false, external = false,
    )

    private class FakeEngine : PlaybackEngine {
        val calls = mutableListOf<String>()
        fun count(call: String) = calls.count { it == call }
        private val _state = MutableStateFlow(PlaybackStatus())
        override val state: StateFlow<PlaybackStatus> = _state
        val trackList = MutableStateFlow<List<MediaTrack>>(emptyList())
        override val tracks: StateFlow<List<MediaTrack>> = trackList
        var chapters = emptyList<Chapter>()
        var lastPrefs: OpenPrefs? = null

        override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
            calls += "open"
            lastPrefs = prefs
            _state.value = PlaybackStatus(
                opened = true, playing = true, durationMs = 600_000, positionMs = startAtMs,
                volume = prefs.volume, muted = prefs.muted, downmix = prefs.downmix, volumeBoost = prefs.volumeBoost,
                speed = prefs.speed, chapters = chapters,
            )
        }

        override fun play() { calls += "play"; _state.update { it.copy(playing = true) } }
        override fun pause() { calls += "pause"; _state.update { it.copy(playing = false) } }
        override fun stop() { calls += "stop"; _state.update { it.copy(playing = false, opened = false) } }
        override fun setVolumeBoost(on: Boolean) { calls += "boost:$on"; _state.update { it.copy(volumeBoost = on) } }
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
        override fun setSubtitleStyle(style: com.tmplayer.player.SubtitleStyle) { _state.update { it.copy(subtitleStyle = style) } }
        override fun setSubtitleDelay(ms: Long) { calls += "subDelay:$ms"; _state.update { it.copy(subtitleDelayMs = ms) } }
        override fun setAudioDelay(ms: Long) { calls += "audioDelay:$ms"; _state.update { it.copy(audioDelayMs = ms) } }
        override fun addSubtitle(path: String): Boolean { calls += "sub:$path"; return true }
        override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean {
            calls += "shot:${file.name}:$withSubtitles"
            file.writeBytes(byteArrayOf(1))
            return true
        }
        override fun setAbLoop(loop: AbLoop?) { calls += "loop:${loop?.startMs}-${loop?.endMs}" }

        /** Plays to the end, as mpv's end of file reports it. */
        fun end() = _state.update { it.copy(positionMs = it.durationMs, playing = false, ended = true) }
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
        override val savable = MutableStateFlow(true)
        override suspend fun open(): MediaData = UriMediaData("night-train.mkv")
        /** What the chat answers for the episodes either side; none unless a test sets them. */
        var steps = Episodes()
        val switchedTo = mutableListOf<MediaItem>()
        override suspend fun episodes() = steps
        override fun episode(other: MediaItem): PlayerMedia {
            switchedTo += other
            return this
        }
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
