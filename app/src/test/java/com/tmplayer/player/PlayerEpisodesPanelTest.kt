package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.fragment.app.FragmentActivity
import com.tmplayer.R
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesState
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

/**
 * The player's episode list through the real [PlayerEpisodes] (W15, 2026-11-25), a season of 500
 * episodes and two more behind it: on a television a panel at the end opens with the episode playing
 * focused (deep in the list, so it is scrolled to), Down and Up walk the cards, Back closes it and
 * the player hears of it once; on a phone upright it is a bottom sheet with the season chips, the
 * episode playing in view, tapping another plays it; on its side the phone gets the panel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerEpisodesPanelTest {

    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun episode(season: Int, number: Int) = MediaItem(
        chatId = 1, messageId = (season * 1000 + number).toLong(), fileId = season * 1000 + number,
        title = "Harbour Notes S%02dE%03d".format(season, number), sizeBytes = 1_400_000_000,
        durationSec = 2634, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = 5_000_000 - (season * 1000 + number), fileName = "Harbour.Notes.S%02dE%03d.1080p.mkv".format(season, number),
    )

    private val items = (1..500).map { episode(1, it) } + (1..6).map { episode(2, it) } + (1..6).map { episode(3, it) }

    private fun state(playing: MediaItem): EpisodesState {
        val steps = EpisodeNeighbours.around(playing, items)
        return EpisodesState(
            series = steps.series!!,
            playing = playing,
            previous = steps.previous,
            previousLabel = steps.previousTag?.let { "Previous: ${it.label}" },
            next = steps.next,
            nextLabel = steps.nextTag?.let { "Next: ${it.label}" },
            previousCode = steps.previousTag?.code,
            nextCode = steps.nextTag?.code,
            autoplay = true,
        )
    }

    private val played = mutableListOf<Long>()
    private var closed = 0

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    private fun open(tv: Boolean, playing: MediaItem): PlayerEpisodes {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_player, null) as FrameLayout
        val panel = PlayerEpisodes(
            activity, root, flowOf(emptyMap()), flowOf(emptyMap<String, Any>()),
            EpisodesActions(onPlay = { played += it.messageId }, onToggleWatched = {}, onAutoplay = {}),
            onClosed = { closed++ },
        )
        activity.setContentView(root)
        panel.open(state(playing))
        idle()
        if (tv) ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
        return panel
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun waitFocused(text: String) {
        repeat(100) {
            idle()
            if (runCatching { focusedHas(text) }.isSuccess) return
        }
        focusedHas(text)
    }

    /** The card holding the remote's focus carries [text]: on a television a card is no merged node, the words are its children. */
    private fun focusedHas(text: String) {
        rule.onNode(isFocused()).assert(hasAnyDescendant(hasText(text)))
    }

    private fun back() {
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        idle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a television opens the panel on the episode playing, Down and Up walk the cards, Back closes it`() {
        val panel = open(tv = true, playing = items[299])
        assertTrue(panel.isOpen)
        // 300 of 500 deep: scrolled to and focused, not the top of the list.
        waitFocused("Episode 300")
        press(Key.DirectionDown)
        focusedHas("Episode 301")
        press(Key.DirectionDown)
        focusedHas("Episode 302")
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        focusedHas("Episode 300")
        press(Key.DirectionUp)
        focusedHas("Episode 299")
        assertEquals(0, closed)
        back()
        assertFalse(panel.isOpen)
        assertEquals("the player hears of the close once", 1, closed)
        assertTrue("opening and closing played nothing", played.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a television's card plays only on OK and names no file`() {
        open(tv = true, playing = items[9])
        waitFocused("Episode 10")
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(listOf(items[10].messageId), played)
        // No file name and no size on a card.
        assertEquals(0, rule.onAllNodes(hasText(".mkv", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("GB", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone upright gets a bottom sheet with the season chips and the episode playing in view`() {
        val panel = open(tv = false, playing = items[199])
        assertTrue(panel.isOpen)
        rule.onNodeWithText("Episode 200").assertIsDisplayed()
        rule.onNode(hasText("Season 2", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Season 3", substring = true)).assertIsDisplayed()
        // Another episode plays on a tap, the one playing only closes the list.
        rule.onNodeWithText("Episode 201").performClickAndIdle()
        assertEquals(listOf(items[200].messageId), played)
        back()
        assertFalse(panel.isOpen)
        assertEquals(1, closed)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `a phone on its side gets the panel at the end, the episode playing in view`() {
        val panel = open(tv = false, playing = items[149])
        assertTrue(panel.isOpen)
        rule.onNodeWithText("Episode 150").assertIsDisplayed()
        back()
        assertFalse(panel.isOpen)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndIdle() {
        performClick()
        idle()
    }

    /**
     * A television overscans: the panel's surface, its cards and the focused card's ring all lie inside
     * the safe area (Tv.SafeH at the end, Tv.SafeV top and bottom), the panel keeps its 32 percent
     * width, and the scrim (the dialog's window) still fills the screen.
     */
    private fun panelInsideSafeArea() {
        open(tv = true, playing = items[299])
        waitFocused("Episode 300")
        val dialog = ShadowDialog.getLatestDialog()!!
        val root = dialog.window!!.decorView
        val density = dialog.context.resources.displayMetrics.density
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        assertTrue("the window fills the screen: ${root.width} x ${root.height}", w > 0 && h > 0)
        val right = w - 48f * density + 1f
        val top = 27f * density - 1f
        val bottom = h - 27f * density + 1f
        val focused = rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("the focused card $focused is inside the safe area (right <= $right, $top..$bottom)", focused.right <= right && focused.top >= top && focused.bottom <= bottom)
        val cards = rule.onAllNodes(hasText("Episode ", substring = true)).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.width > 0f && it.height > 0f && it.left < w && it.top < h && it.bottom > 0f }
        assertTrue("the panel shows cards", cards.isNotEmpty())
        for (card in cards) {
            assertTrue("a card $card lies right of the safe line $right", card.right <= right)
        }
        // The panel is a third of the width: its cards start at 55 percent of the width or later.
        assertTrue("the panel is about 32 percent wide", cards.all { it.left >= w * 0.55f })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's panel and a focused card stay inside the safe area at 960 by 540`() = panelInsideSafeArea()

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `the television's panel and a focused card stay inside the safe area at 1920 by 1080`() = panelInsideSafeArea()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a frame of the television's panel`() {
        open(tv = true, playing = items[299])
        waitFocused("Episode 300")
        val dialog = ShadowDialog.getLatestDialog()
        val root = dialog?.window?.decorView ?: return
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/episodes/player-panel-tv.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
