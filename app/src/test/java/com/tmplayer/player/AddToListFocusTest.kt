package com.tmplayer.player

import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.fragment.app.FragmentActivity
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.nio.file.Files
import java.time.Duration

/**
 * "Add to a list" (HX43) inside the player on a television, by remote alone, through the real
 * More options panel: Down from Speed reaches the line, OK opens the page on the first
 * list, Down walks to New list, OK on a list toggles it (the page stays, the tick is the answer),
 * and Back returns to the list with the remote on the same line and playback untouched.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class AddToListFocusTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    private val dir = Files.createTempDirectory("tm-player-lists").toFile()
    private val lists = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists

    @After
    fun tearDown() {
        FormFactor.override(false)
        dir.deleteRecursively()
    }

    private val record = WatchLaterRecord(
        chatId = -100, messageId = 4, fileId = 7, title = "Night Train S01E04", fileName = "Night.Train.S01E04.mp4", chatTitle = "Chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = 0,
    )

    private val said = mutableListOf<String>()

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

    /** Idles until [done], and stops there: a plain loop with a return, not repeat, which would run all its turns. */
    private fun waitUntil(done: () -> Boolean) {
        for (turn in 0 until 100) {
            idle()
            if (done()) return
        }
    }

    private fun waitFocused(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        repeat(100) {
            idle()
            if (runCatching { rule.onNode(matcher).assertIsFocused() }.isSuccess) return
        }
        rule.onNode(matcher).assertIsFocused()
    }

    private fun target() = PlayerListTarget(record, lists) { said += it }

    @Test
    fun `the remote reaches Add to a list, walks the lists, toggles one and Back returns to the line`() {
        runBlocking {
            lists.create("Weekend")
            lists.create("Tutorials")
        }
        val host = PlayerMoreRenderTest.FakeHost(true, true, target())
        val (panel, activity) = open(host)
        val add = hasText(L.listsAddToDetail, substring = true)
        val speed = hasText(L.playerSpeed)

        // The arrow model: Down from Speed is Add to a list (the top group, WP-20).
        rule.onNode(speed).requestFocus()
        idle()
        press(Key.DirectionDown)
        rule.onNode(add).assertIsFocused()

        // OK opens the page on the first list.
        press(Key.DirectionCenter)
        waitFocused(hasText("Weekend"))
        press(Key.DirectionDown)
        rule.onNode(hasText("Tutorials")).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNode(hasTestTag(PLAYER_LISTS_NEW_TAG)).assertIsFocused()
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        rule.onNode(hasText("Weekend")).assertIsFocused()

        // OK puts the video in the list: the line becomes "Remove from list" and the page stays up.
        press(Key.DirectionCenter)
        waitUntil { runCatching { rule.onNode(hasText(L.listsRemove)).assertExists() }.isSuccess }
        rule.onNode(hasText(L.listsRemove)).assertExists()
        rule.onNode(hasText("Weekend")).assertIsFocused()
        assertTrue(said.joinToString(), said.any { it.contains("Weekend") })
        // Pressing the ticked line takes it out again, the same way the card menu does.
        press(Key.DirectionCenter)
        waitUntil { runCatching { rule.onNode(hasText(L.listsRemove)).assertDoesNotExist() }.isSuccess }
        rule.onNode(hasText(L.listsRemove)).assertDoesNotExist()

        // Back closes the page only: the panel stays up on the same line and the player is not told to close.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        waitFocused(add)
        assertTrue(panel.isOpen)
        assertEquals(0, host.closings)
        panel.closeNow()
    }

    @Test
    fun `with no list yet the page starts on New list`() {
        val (panel, _) = open(PlayerMoreRenderTest.FakeHost(true, true, target()))
        rule.onNode(hasText(L.playerSpeed)).requestFocus()
        idle()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        waitFocused(hasTestTag(PLAYER_LISTS_NEW_TAG))
        rule.onNode(hasText(L.listsNone)).assertExists()
        panel.closeNow()
    }
}
