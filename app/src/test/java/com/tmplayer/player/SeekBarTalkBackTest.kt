package com.tmplayer.player

import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-17: what TalkBack reads on the seek bar. Media3's own node says "12:03" with no word for what
 * it is; the app's says "Seek bar" with "12:03 of 1:42:10" as its state, and the scroll actions
 * travel 10 s ([Skip.BUTTON_MS]) while the keys keep their stride.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeekBarTalkBackTest {

    private lateinit var bar: FineTimeBar
    private val stops = mutableListOf<Long>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        bar = FineTimeBar(activity)
        root.addView(bar, FrameLayout.LayoutParams(960, 48))
        // What PlayerControls sets.
        bar.setKeyTimeIncrement(Skip.BAR_MS)
        bar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {}
            override fun onScrubMove(timeBar: TimeBar, position: Long) {}
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) stops += position
            }
        })
        bar.setDuration(6_130_000L)
        bar.setPosition(723_000L)
    }

    @Test
    fun `the bar is named and states where it stands`() {
        val info = bar.createAccessibilityNodeInfo()
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(L.playerSeekBar, info.contentDescription)
        assertEquals(L.playerPositionOf("12:03", "1:42:10"), info.stateDescription)
    }

    @Test
    fun `the state follows the position without being pushed`() {
        bar.setPosition(3_723_000L)
        assertEquals(L.playerPositionOf("1:02:03", "1:42:10"), bar.createAccessibilityNodeInfo().stateDescription)
    }

    @Test
    fun `scroll forward and back are offered and move 10 s`() {
        val ids = bar.createAccessibilityNodeInfo().actionList.map { it.id }
        assertTrue("scroll forward", 4096 in ids)
        assertTrue("scroll backward", 8192 in ids)
        assertTrue(bar.performAccessibilityAction(4096, null))
        assertEquals(listOf(733_000L), stops)
        bar.setPosition(733_000L)
        assertTrue(bar.performAccessibilityAction(8192, null))
        assertEquals(listOf(733_000L, 723_000L), stops)
    }

    @Test
    fun `the keys keep their own stride after a scroll action`() {
        bar.performAccessibilityAction(4096, null)
        stops.clear()
        bar.setPosition(723_000L)
        val down = android.os.SystemClock.uptimeMillis()
        bar.onKeyDown(
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent(down, down, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0),
        )
        bar.onKeyUp(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent(down, down, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))
        assertEquals(listOf(723_000L + Skip.BAR_MS), stops)
    }
}
