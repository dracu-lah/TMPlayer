package com.tmplayer.tvux

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.tmplayer.data.FormFactor
import com.tmplayer.player.PlaybackMarks
import com.tmplayer.player.PlayerRemoteKeys
import com.tmplayer.player.TrackKeys
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The TV key ownership table (`docs/agents/tv-keys.md`, WP-69): one test per cell marked "today",
 * so a package that binds a key a second time breaks the test of the cell it took, and has to change
 * the table with it.
 *
 * Three kinds of check, as the screens allow. A tile is composed and driven with real key events
 * (the same `holdable` every grid, chat row and picker tile wears). The player cannot be started
 * under Robolectric (the 10,000 line activity), so its cells read the source, as
 * `BackStopsThePlayerTest` does. And a scan lists which files bind each key at all, so a second
 * claimant of Menu, Info, Search, Page, Channel or Stop shows up here first.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvKeyOwnershipTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val log = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    // ---- Tile, chat row, picker tile: the compose cells ----

    private fun showTiles(tv: Boolean = true) {
        FormFactor.override(tv)
        rule.setContent {
            // A clickable only takes focus in keyboard mode, which a remote or a keyboard puts the window in.
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TmMaterialTheme(dark = true) {
                Row {
                    for (name in listOf("a", "b")) {
                        val source = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .size(120.dp)
                                .testTag(name)
                                .holdable(source, onClick = { log += "click:$name" }, onHold = { log += "hold:$name" }),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tile(name: String) = rule.onNodeWithTag(name).also { it.requestFocus() }

    @Test
    fun `tile, Menu opens the item options once for a press and its release`() {
        showTiles()
        // Down and up both, as a remote sends them: one request, not two. The tile acts on the press.
        tile("a").performKeyInput {
            keyDown(Key.Menu)
            keyUp(Key.Menu)
        }
        rule.waitForIdle()
        assertEquals(listOf("hold:a"), log)
    }

    @Test
    fun `tile, Info opens the item options too, and OK is not also pressed`() {
        showTiles()
        tile("a").performKeyInput { pressKey(Key.Info) }
        rule.waitForIdle()
        assertEquals(listOf("hold:a"), log)
    }

    @Test
    fun `tile, a short OK opens, a long OK opens the options and does not also open`() {
        showTiles()
        tile("a").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("click:a"), log)
        log.clear()
        tile("b").performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertEquals(listOf("hold:b"), log)
    }

    @Test
    fun `tile, Left and Right move along the row (the player's arrows are PlayerRemoteKeysTest and FineTimeBarKeysTest)`() {
        showTiles()
        tile("a").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("b").assertIsFocused()
        rule.onNodeWithTag("b").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithTag("a").assertIsFocused()
        assertTrue("moving claims no press", log.isEmpty())
    }

    @Test
    fun `a keyboard's Menu and Shift+F10 are the same request on a phone or tablet`() {
        showTiles(tv = false)
        tile("a").performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        assertEquals(listOf("hold:a"), log)
        log.clear()
        tile("a").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.F10)
            keyUp(Key.ShiftLeft)
        }
        rule.waitForIdle()
        assertEquals(listOf("hold:a"), log)
        log.clear()
        tile("a").performKeyInput { pressKey(Key.F10) }
        rule.waitForIdle()
        assertTrue("F10 alone is not Menu", log.isEmpty())
    }

    // ---- Menu: the unclaimed key, the player, its panels ----

    @Test
    fun `Menu nobody took opens the account menu where the bar is, else the profile picker with two profiles or more, once`() {
        val source = text("src/main/java/com/tmplayer/MainActivity.kt")
        val up = source.substringAfter("override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {").substringBefore("KEYCODE_ESCAPE")
        assertTrue("KEYCODE_MENU" in up)
        // WP-02: the bar's account menu first, for every profile count, once the picker is not up.
        assertTrue("TvBarSignals.askAccountMenu()" in up)
        assertTrue("flow.entered && !flow.asked" in up)
        // Where no bar is shown (Settings, a phone): the picker, with two profiles or more.
        assertTrue("ProfilePicker.atLaunch(flow.switcher.profiles.value)" in up)
        assertTrue("flow.ask()" in up)
        // It runs in onKeyUp of the activity, after every screen had the key: a tile and the bar take
        // Menu first (Holdable.kt, TvTopBar.kt), so this is the fallback for a place with no item action.
    }

    @Test
    fun `player, Menu opens the player menu on release where the quirk says so, row down or up alike`() {
        val branch = playerDispatch().substringAfter("// Fire TV's Menu key is the player's options").substringBefore("// The fast-forward key let go")
        assertTrue("remoteQuirks.menuOpensPlayerMenu" in branch)
        assertTrue("event.action == KeyEvent.ACTION_UP" in branch)
        assertTrue("showOverflow(statusOverlay)" in branch)
        assertTrue("a held Menu is the long press, not the options", "!menuHeld" in branch)
        assertFalse("the row being up or down does not change what Menu does", "controlsUp" in branch)
    }

    @Test
    fun `player panels, Menu closes the More panel only, where the quirk says so, and every other key is the panel's`() {
        val panel = playerDispatch().substringAfter("morePanel?.takeIf { it.isOpen }?.let { panel ->").substringBefore("// Fire TV's Menu key is the player's options")
        assertTrue("event.keyCode == KeyEvent.KEYCODE_MENU && remoteQuirks.menuOpensPlayerMenu" in panel)
        assertTrue("panel.close()" in panel)
        assertTrue("return super.dispatchKeyEvent(event)" in panel)
        // Only More is covered here: a picker (TrackPickerFragment) hands every key to its own view.
        assertTrue("if (pickerOpen) return super.dispatchKeyEvent(event)" in playerDispatch())
    }

    @Test
    fun `player, a held Menu or a held OK with the row up hands the video to another app`() {
        val dispatch = playerDispatch()
        val held = dispatch.substringAfter("if (isLongPressOnTheVideo(event)) {").substringBefore("return true")
        assertTrue("if (event.keyCode == KeyEvent.KEYCODE_MENU) menuHeld = true" in held)
        assertTrue("openInAnotherApp()" in held)
        val rule = text("src/main/java/com/tmplayer/player/PlayerActivity.kt")
            .substringAfter("private fun isLongPressOnTheVideo(event: KeyEvent): Boolean {").substringBefore("\n    }")
        assertTrue("if (!event.isLongPress) return false" in rule)
        assertTrue("if (statusOverlay.visibility == View.VISIBLE) return false" in rule)
        assertTrue("a held Menu is always the handover", "KeyEvent.KEYCODE_MENU -> true" in rule)
        assertTrue("a held OK only with the row up", "KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> controlsUp" in rule)
    }

    // ---- Info, CC, Audio, Play/Pause, Stop, Page and Channel in the player ----

    @Test
    fun `player, a short Info shows the details on release and a held Info reports a stutter`() {
        assertTrue(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_INFO, isLongPress = true))
        assertFalse(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_INFO, isLongPress = false))
        assertFalse(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_MENU, isLongPress = true))
        assertTrue(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, downMs = 0, upMs = 100, canceled = false, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, downMs = 0, upMs = 900, canceled = false, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, downMs = 0, upMs = 100, canceled = true, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_MENU, downMs = 0, upMs = 100, canceled = false, longPressMs = 500))
        val dispatch = playerDispatch()
        val tap = dispatch.substringAfter("// Info let go before its long press").substringBefore("if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)")
        assertTrue("event.keyCode == KeyEvent.KEYCODE_INFO && event.action == KeyEvent.ACTION_UP && FormFactor.isTv(this)" in tap)
        assertTrue("showPlaybackDetails()" in tap)
        assertFalse("the row being up or down does not change what Info does", "controlsUp" in tap)
        val held = dispatch.substringAfter("// Hold Info: Report stutter").substringBefore("if (isLongPressOnTheVideo(event))")
        assertTrue("FormFactor.isTv(this)" in held)
        assertTrue("reportStutter()" in held)
    }

    @Test
    fun `player, CC and Audio each open their own picker, once per press`() {
        assertEquals(C.TRACK_TYPE_TEXT, TrackKeys.pickerFor(KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(C.TRACK_TYPE_AUDIO, TrackKeys.pickerFor(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))
        assertNull(TrackKeys.pickerFor(KeyEvent.KEYCODE_INFO))
        val branch = playerDispatch().substringAfter("KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {").substringBefore("KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->")
        assertTrue("event.repeatCount == 0" in branch)
        assertTrue("showTrackPicker(type)" in branch)
        assertTrue("statusOverlay.visibility != View.VISIBLE" in branch)
    }

    @Test
    fun `player, Play and Pause toggle once per press`() {
        val toggle = playerDispatch().substringAfter("KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {").substringBefore("KeyEvent.KEYCODE_MEDIA_PLAY -> {")
        assertTrue("if (event.repeatCount == 0) togglePlayback()" in toggle)
    }

    @Test
    fun `player, Channel up and down step the video like Next and Previous, once per press`() {
        assertEquals(true, PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(false, PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(true, PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(false, PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertNull("Page keys do not step the episode", PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_PAGE_UP))
        val step = playerDispatch().substringAfter("KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,").substringBefore("KeyEvent.KEYCODE_MEDIA_STOP")
        assertTrue("KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN" in step)
        assertTrue("PlayerRemoteKeys.episodeStep(event.keyCode)" in step)
        assertTrue("if (event.repeatCount == 0 && !stepEpisode(forward))" in step)
    }

    @Test
    fun `player, Stop saves the place and leaves like Back, once per press`() {
        assertTrue(PlayerRemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(PlayerRemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_PAUSE))
        val stop = playerDispatch().substringAfter("KeyEvent.KEYCODE_MEDIA_STOP -> {").substringBefore("// Back peels")
        assertTrue("if (event.repeatCount == 0) leaveByBack()" in stop)
    }

    @Test
    fun `player, Page keys step a chapter or a minute, once per press, row up or down`() {
        assertEquals(true, PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_DOWN, tv = true))
        assertEquals(false, PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_UP, tv = true))
        assertNull("a phone keyboard's Page keys pass on", PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_DOWN, tv = false))
        val page = playerDispatch().substringAfter("KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {").substringBefore("KeyEvent.KEYCODE_MEDIA_STOP")
        assertTrue("PlayerRemoteKeys.pageStep(event.keyCode, FormFactor.isTv(this))" in page)
        assertTrue("once per press", "event.repeatCount == 0" in page)
        // Not behind the row: the case sits in the dispatch's key switch, before the arrows' row-down check.
        assertFalse("!controlsUp" in page)
    }

    // ---- Back ----

    @Test
    fun `player, Back with the row down leaves and with the row up puts the row down`() {
        val source = text("src/main/java/com/tmplayer/player/PlayerActivity.kt")
        assertTrue("if (!peelOneOverlay()) leaveByBack()" in source)
        val branch = playerDispatch().substringAfter("// Back peels the overlays off in the order").substringBefore("KeyEvent.KEYCODE_DPAD_RIGHT")
        assertTrue("statusOverlay.visibility != View.VISIBLE && controlsUp && backPeelsTheRow()" in branch)
        assertTrue("controls?.hideAnimated()" in branch)
        val rule = source.substringAfter("private fun backPeelsTheRow(): Boolean =").substringBefore("\n\n")
        assertTrue("on a television the row takes the first Back", "FormFactor.isTv(this)" in rule)
        val leave = source.substringAfter("private fun leaveByBack() {").substringBefore("\n    }")
        assertTrue("finish()" in leave)
    }

    @Test
    fun `bar, Back on a page goes up to the bar, on the bar of another page goes Home, on Home's bar leaves`() {
        val browse = text("src/main/java/com/tmplayer/ui/browse/BrowseScreen.kt")
        val page = browse.substringAfter("BackHandler(enabled = !tvBarHasFocus) {").substringBefore("}\n")
        assertTrue("barFocus.requestFocus()" in page)
        val bar = browse.substringAfter("BackHandler(enabled = tvBarHasFocus && !homeShown) {").substringBefore("barRefocus++")
        assertTrue("onPickTab(BrowseSection.of(BrowseTab.Home))" in bar)
        // Home's own bar has no handler of its own: Back falls through to the activity and leaves.
        assertFalse("BackHandler(enabled = tvBarHasFocus && homeShown)" in browse)
        assertFalse("BackHandler(enabled = tvBarHasFocus)" in browse)
    }

    // ---- Search ----

    @Test
    fun `bar, Search opens the TV search from the browse shell and nowhere else on the TV`() {
        val browse = text("src/main/java/com/tmplayer/ui/browse/BrowseScreen.kt")
        val branch = browse.substringAfter("event.key == Key.Search) {").substringBefore("} else")
        assertTrue("tvSearchOpen = true" in branch)
        // On a phone the activity swallows the key (SearchKeyGuardTest); the TV shell is its only taker.
        assertEquals(listOf("BrowseScreen.kt"), claimants("""(?<![A-Za-z])Key\.Search\b"""))
    }

    // ---- Who binds each key at all ----

    @Test
    fun `the files that bind Menu, Info, Search, Page, Channel and Stop are the ones the table lists`() {
        assertEquals(
            listOf("AddChatsScreen.kt", "Holdable.kt", "MainActivity.kt", "PlayerActivity.kt", "ProfilePicker.kt", "TvMenu.kt", "TvTopBar.kt"),
            claimants("""(?<![A-Za-z])Key\.Menu\b|KEYCODE_MENU\b"""),
        )
        assertEquals(
            listOf("Holdable.kt", "PlaybackMarks.kt", "PlayerActivity.kt", "PlayerRemoteKeys.kt"),
            claimants("""(?<![A-Za-z])Key\.Info\b|KEYCODE_INFO\b"""),
        )
        assertEquals(
            listOf("BrowseScreen.kt", "SearchKeyGuard.kt"),
            claimants("""(?<![A-Za-z])Key\.Search\b|KEYCODE_SEARCH\b"""),
        )
        assertEquals(
            listOf("PageKeys.kt", "PlayerActivity.kt", "PlayerRemoteKeys.kt"),
            claimants("""(?<![A-Za-z])Key\.(PageUp|PageDown|ChannelUp|ChannelDown)\b|KEYCODE_(PAGE_UP|PAGE_DOWN|CHANNEL_UP|CHANNEL_DOWN)\b"""),
        )
        assertEquals(
            listOf("PlayerActivity.kt", "PlayerRemoteKeys.kt"),
            claimants("""(?<![A-Za-z])Key\.MediaStop\b|KEYCODE_MEDIA_STOP\b"""),
        )
    }

    // ---- Helpers ----

    /** Gradle runs unit tests from the module directory; an IDE may start from the repository. */
    private fun repo(): File = if (File("app/src/main").exists()) File(".") else File("..")

    private fun text(path: String) = File(repo(), "app/$path").readText()

    /** The body of `PlayerActivity.dispatchKeyEvent`, from its first line to the end of the method. */
    private fun playerDispatch(): String =
        text("src/main/java/com/tmplayer/player/PlayerActivity.kt")
            .substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {")
            .substringBefore("/** A key while the screen is locked")

    /** File names, sorted, of the app's and the shared UI's main sources that match [pattern]. */
    private fun claimants(pattern: String): List<String> {
        val roots = listOf(File(repo(), "app/src/main"), File(repo(), "ui/src/shared"))
        assertTrue("sources not found from ${File(".").absolutePath}", roots.all { it.exists() })
        val regex = Regex(pattern)
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && regex.containsMatchIn(it.readText()) }.map { it.name }.toList()
        }.distinct().sorted()
    }
}
