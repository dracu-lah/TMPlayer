package com.tmplayer.player

import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * WP-34: the phone's row waits longer while TalkBack or Switch Access is on, stays while the
 * reader's focus is on it, and the total's tap target is 48 dp tall.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerControlsReaderTimeoutTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getDuration" -> 2_634_000L
            "getCurrentPosition" -> 1_260_000L
            "isPlaying", "getPlayWhenReady" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private class Shown(val controls: PlayerControls, val root: FrameLayout)

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun open(touchExploration: Boolean, fontScale: Float = 1f): Shown {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val manager = activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(touchExploration)
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.BLACK)
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = false,
            player = { player },
            onVisibility = {},
            onTogglePlay = {},
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
        )
        controls.show()
        idleFor(1_000)
        return Shown(controls, root)
    }

    private fun focusEvent(shown: Shown, type: Int) {
        val container = shown.root.findViewById<android.view.ViewGroup>(R.id.player_controls)
        val child = shown.root.findViewById<View>(R.id.control_audio)
        container.requestSendAccessibilityEvent(child, AccessibilityEvent(type))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `without a reader the row goes after its usual time`() {
        val shown = open(touchExploration = false)
        idleFor(3_500L + 1_500)
        assertFalse(shown.controls.visible)
    }

    @Test
    @Config(sdk = [28], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `touch exploration on Android 9 gives the row three times the usual`() {
        val shown = open(touchExploration = true)
        idleFor(3_500L + 1_500)
        assertTrue("still up past the usual time", shown.controls.visible)
        idleFor(3_500L * 3)
        assertFalse(shown.controls.visible)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the row stays while the reader's focus is on it, and goes after the focus leaves`() {
        val shown = open(touchExploration = true)
        focusEvent(shown, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        idleFor(60_000)
        assertTrue("held while the reader is on a button", shown.controls.visible)
        focusEvent(shown, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        idleFor(3_500L * 3 + 1_500)
        assertFalse(shown.controls.visible)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a row put away with the reader on it goes by itself again the next time it shows (PB-11)`() {
        val shown = open(touchExploration = true)
        focusEvent(shown, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        // Back puts the row away; no focus cleared event comes from the gone view.
        shown.controls.hideAnimated()
        idleFor(1_000)
        shown.controls.show()
        idleFor(3_500L * 3 + 1_500)
        assertFalse("the row goes after its time again", shown.controls.visible)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the total is 48 dp tall at the system font and at twice it`() {
        for (scale in listOf(1f, 2f)) {
            val shown = open(touchExploration = false, fontScale = scale)
            val total = shown.root.findViewById<TextView>(R.id.times_duration)
            val dp = shown.root.resources.displayMetrics.density
            assertTrue("total is ${total.height} px at $scale", total.height >= (48 * dp).toInt())
        }
    }
}
