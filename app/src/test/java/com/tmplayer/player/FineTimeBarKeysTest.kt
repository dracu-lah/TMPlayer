package com.tmplayer.player

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A remote's Left and Right on the focused seek bar (WP-18): 10 s a press, a held key ramps through
 * the bare picture's steps and gate, stale repeats are dropped, and Media3 still commits exactly one
 * seek a second after the last key, so a held key is never a flood of seeks on a weak stick.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FineTimeBarKeysTest {

    private lateinit var bar: FineTimeBar
    private val commits = mutableListOf<Long>()
    private val moves = mutableListOf<Long>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        bar = FineTimeBar(activity)
        // What PlayerControls sets on a television.
        bar.remoteSteps = true
        root.addView(bar, FrameLayout.LayoutParams(960, 48))
        // What PlayerControls sets, for TalkBack's scroll actions.
        bar.setKeyTimeIncrement(Skip.BAR_MS)
        bar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                moves += position
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                moves += position
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) commits += position
            }
        })
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun load(durationMs: Long, positionMs: Long) {
        bar.setDuration(durationMs)
        bar.setPosition(positionMs)
    }

    /** Holds [keyCode] for [repeats] events, one every [everyMs] on Robolectric's clock. */
    private fun hold(keyCode: Int, repeats: Int, everyMs: Long = 50) {
        val down = SystemClock.uptimeMillis()
        for (repeat in 0 until repeats) {
            val now = SystemClock.uptimeMillis()
            bar.onKeyDown(keyCode, KeyEvent(down, now, KeyEvent.ACTION_DOWN, keyCode, repeat))
            idle(everyMs)
        }
    }

    @Test
    fun `40 held repeats on the bar give one seek, at the sum of the accepted steps`() {
        val start = 10 * 60_000L
        load(durationMs = 120 * 60_000L, positionMs = start)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 40)
        assertTrue("nothing commits while the key is held", commits.isEmpty())
        idle(1_500)
        assertEquals("one Media3 commit", 1, commits.size)
        // Repeats 0, 4, 8 ... 36 move, 200 ms apart (past the 150 ms gate), 10 s each before the ramp.
        val accepted = (0 until 40).map { RemoteQuirksSteps.step(it) }.filter { it > 0 }
        assertEquals(10, accepted.size)
        assertEquals(start + accepted.sum(), commits.single())
        assertEquals("one preview per accepted step", accepted.size, moves.size)
    }

    @Test
    fun `a tap moves 10 s and Left after a held Right goes back from where the scrub is`() {
        val start = 60 * 60_000L
        load(durationMs = 120 * 60_000L, positionMs = start)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 1)
        assertEquals(start + 10_000L, moves.last())
        hold(KeyEvent.KEYCODE_DPAD_LEFT, repeats = 1)
        assertEquals(start, moves.last())
        idle(1_500)
        assertEquals(listOf(start), commits)
    }

    @Test
    fun `a long hold ramps on a long video and stays at 10 s on a short one`() {
        load(durationMs = 120 * 60_000L, positionMs = 0)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 120)
        val longSteps = moves.zipWithNext { a, b -> b - a }
        assertTrue("steps $longSteps", 30_000L in longSteps && 36_000L in longSteps)
        idle(1_500)
        assertEquals(1, commits.size)

        moves.clear()
        commits.clear()
        load(durationMs = 20 * 60_000L, positionMs = 0)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 120)
        val shortSteps = moves.zipWithNext { a, b -> b - a }.filter { it > 0 }
        assertTrue("steps $shortSteps", shortSteps.all { it == 10_000L })
        idle(1_500)
        assertEquals(1, commits.size)
    }

    @Test
    fun `a stale repeat does not move the bar, and a repeat between steps keeps the focus`() {
        load(durationMs = 120 * 60_000L, positionMs = 0)
        val down = SystemClock.uptimeMillis()
        assertTrue(bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)))
        idle(400)
        val made = SystemClock.uptimeMillis() - 300
        // Repeat 4 would move, but it waited 300 ms in the queue of a slow stick.
        assertTrue(bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(down, made, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 4)))
        // Repeat 5 never moves (every fourth one does), but the bar still takes it.
        val now = SystemClock.uptimeMillis()
        assertTrue(bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(down, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 5)))
        assertEquals(listOf(10_000L), moves)
        idle(1_500)
        assertEquals(listOf(10_000L), commits)
    }

    @Test
    fun `TalkBack's scroll action moves 10 s after a key scrub, and the keys keep their stride`() {
        load(durationMs = 120 * 60_000L, positionMs = 0)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 1)
        idle(1_500)
        commits.clear()
        bar.setPosition(10_000L)
        bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(listOf(10_000L + Skip.BUTTON_MS), commits)
        // The 10 s was for that action alone: a keyboard's key still strides by the fixed increment.
        commits.clear()
        bar.remoteSteps = false
        bar.setPosition(0L)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 1)
        idle(1_500)
        assertEquals(listOf(Skip.BAR_MS), commits)
    }

    @Test
    fun `a phone or tablet keyboard on the bar keeps the fixed stride, one per press`() {
        bar.remoteSteps = false
        load(durationMs = 120 * 60_000L, positionMs = 0)
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 3)
        // Media3's own handling: every repeat strides by the increment PlayerControls set.
        assertEquals(listOf(Skip.BAR_MS, 2 * Skip.BAR_MS, 3 * Skip.BAR_MS), moves)
        idle(1_500)
        assertEquals(listOf(3 * Skip.BAR_MS), commits)
    }

    /** The steps the bar should take for a 2 hour video, from the rule itself. */
    private object RemoteQuirksSteps {
        fun step(repeat: Int): Long = com.tmplayer.data.RemoteQuirks.holdStepMs(repeat, PlayerRemoteKeys.TV_JUMP_MS, 120 * 60_000L)
    }
}
