package com.tmplayer.player

import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
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
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
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
 * The television's More menu in groups (WP-20), through the real panel and the remote: the headers
 * carry heading semantics and are never a focus stop, Page Down from Lock lands on Picture's first
 * line and on again to Sound's, Page Up goes back to the first line of the group and then the
 * one before, and Remote keys is the last line, under Help.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class PlayerMoreGroupsFocusTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
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

    private fun open(): PlayerMorePanel {
        FormFactor.override(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        val root = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_player, null) as FrameLayout
        val panel = PlayerMorePanel(activity, root, PlayerMoreRenderTest.FakeHost(true, true))
        panel.open()
        activity.setContentView(root)
        leaveTouchMode(activity.window.decorView.parent)
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
            if (runCatching { rule.onNodeWithText(text).assertIsFocused() }.isSuccess) return
        }
        rule.onNodeWithText(text).assertIsFocused()
    }

    @Test
    fun `the headers are headings and no focus stop, and Page keys jump from group to group`() {
        val panel = open()
        // The panel opens on Lock; the headed groups are there as headings, not as focus stops.
        waitFocused(L.playerLockScreen)
        for (title in listOf(L.playerMenuGroupPicture, L.playerMenuGroupSound, L.playerMenuGroupHelp)) {
            rule.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
            // Not focusable at all: the node has no focus property.
            rule.onNode(hasText(title) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused)).assertExists()
        }

        // Page Down from Lock: Picture's first line; again: Sound's first line.
        press(Key.PageDown)
        rule.onNodeWithText(L.playerPictureShape).assertIsFocused()
        press(Key.PageDown)
        rule.onNodeWithText(L.playerVolumeLevel).assertIsFocused()

        // Page Up from the first line of a group: the first line of the one before.
        press(Key.PageUp)
        rule.onNodeWithText(L.playerPictureShape).assertIsFocused()
        // From the middle of a group: this group's first line.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.PageUp)
        rule.onNodeWithText(L.playerPictureShape).assertIsFocused()
        // And up again to the top group's first line, Lock.
        press(Key.PageUp)
        rule.onNodeWithText(L.playerLockScreen).assertIsFocused()

        // The last group is Help, and Remote keys its last line.
        rule.onNodeWithText(L.playerRemoteKeys).requestFocus()
        idle()
        rule.onNodeWithText(L.playerRemoteKeys).assertIsFocused()
        panel.closeNow()
    }
}
