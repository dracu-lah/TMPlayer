package com.tmplayer.player

import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.fragment.app.FragmentActivity
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
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
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The Chapters page (WP-26) inside the player's More options on a television, by remote alone:
 * the page opens on the chapter playing, Down walks to the next, OK plays from that chapter's start
 * and closes the panel; Back returns to the line. A file with no chapters has no line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ChaptersPageFocusTest {

    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val chapters = listOf(Chapter(0, "Opening"), Chapter(95_000, "Part A"), Chapter(1_310_000, "Ending"))

    /** The fake host with a chaptered file: the playing position is inside "Part A". */
    private class ChapteredHost(
        private val base: PlayerMoreRenderTest.FakeHost,
        private val rows: List<ChapterRow>,
    ) : PlayerMoreHost by base {
        var jumpedTo: Long? = null
        override fun tvEntries() = PlayerMenu.tvEntries(pictureInPicture = false, chapters = true, chapterList = true)
        override fun chapter() = "Chapter 2 of 3: Part A"
        override fun chapterList() = rows
        override fun jumpToChapter(startMs: Long) {
            jumpedTo = startMs
        }
    }

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

    private fun open(host: PlayerMoreHost): Pair<PlayerMorePanel, FragmentActivity> {
        FormFactor.override(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_player, null) as FrameLayout
        val panel = PlayerMorePanel(activity, root, host)
        panel.open()
        activity.setContentView(root)
        leaveTouchMode(activity.window.decorView.parent)
        idle()
        return panel to activity
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun waitFocused(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        repeat(100) {
            idle()
            if (runCatching { rule.onNode(matcher).assertIsFocused() }.isSuccess) return
        }
        rule.onNode(matcher).assertIsFocused()
    }

    @Test
    fun `the page opens on the chapter playing, OK on another plays from its start and closes the panel`() {
        val host = ChapteredHost(PlayerMoreRenderTest.FakeHost(true, true), Chapters.rows(chapters, 100_000))
        val (panel, _) = open(host)
        rule.onNode(hasText(L.playerChapters)).requestFocus()
        idle()
        press(Key.DirectionCenter)
        waitFocused(hasText("Part A"))
        press(Key.DirectionDown)
        rule.onNode(hasText("Ending")).assertIsFocused()
        press(Key.DirectionCenter)
        idle()
        assertEquals(1_310_000L, host.jumpedTo)
        assertFalse(panel.isOpen)
    }

    @Test
    fun `Back from the page returns to the line and plays nothing`() {
        val host = ChapteredHost(PlayerMoreRenderTest.FakeHost(true, true), Chapters.rows(chapters, 100_000))
        val (panel, activity) = open(host)
        rule.onNode(hasText(L.playerChapters)).requestFocus()
        idle()
        press(Key.DirectionCenter)
        waitFocused(hasText("Part A"))
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(panel.isOpen)
        assertEquals(null, host.jumpedTo)
        panel.closeNow()
    }

    @Test
    fun `a file with no chapters has no Chapters line`() {
        assertFalse(PlayerMenuEntry.Chapters in PlayerMenu.tvEntries(pictureInPicture = false, chapters = false, chapterList = false))
        assertFalse(Chapters.listable(emptyList()))
    }
}
