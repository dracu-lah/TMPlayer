package com.tmplayer.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-19, source level (the 11,000 line activity cannot start under Robolectric): which keys the skip
 * pill takes on a television, and what the row's focus chain does with it.
 *
 * Row down: OK skips while the pill shows; Up raises the row (or takes the "Back to" chip) and never
 * skips, since an intro silently skipped by the key that raises the controls is a surprise (designer
 * 28, engineer correction 13). Row up: the pill is focusable and Up from the bar lands on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkipKeysDispatchTest {

    private fun source(path: String): String {
        val here = File(path)
        return (if (here.exists()) here else File("app/$path")).readText()
    }

    private val activity get() = source("src/main/java/com/tmplayer/player/PlayerActivity.kt")

    @Test
    fun `with the row down OK skips and Up raises the row without skipping`() {
        val dispatch = activity.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {").substringBefore("/** A key while the screen is locked")
        val skip = dispatch.substringAfter("if (skipHasTheRemote() &&\n            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER").substringBefore("// A stretch set to skip by itself counts down")
        assertTrue("skipOffered()" in skip)
        assertFalse("Up never skips", "DPAD_UP" in skip)
        assertFalse("Down never skips", "DPAD_DOWN" in skip)
        // Up over the bare picture raises the row, and takes the undo chip first when one stands.
        val arrows = dispatch.substringAfter("KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {")
        assertTrue("undoSeek()" in arrows)
        assertTrue("controls?.show(focusButtons = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)" in arrows)
        assertFalse("skipOffered()" in arrows)
    }

    @Test
    fun `with the row up the pill is in the focus chain above the bar, only while a skip is offered`() {
        val reach = activity.substringAfter("private fun reachSkipPill() {").substringBefore("\n    }\n")
        assertTrue("controlsUp && pill.isShown && offeredSkip != null" in reach)
        assertTrue("controls?.reachSkipPill(if (onOffer) pill.viewId else null)" in reach)
        assertTrue("if (lost) controls?.focusBar()" in reach)
        // Recomputed on every tick of the pill and whenever the row comes or goes.
        assertTrue("reachSkipPill()" in activity.substringAfter("private fun updateSkipPill(").substringBefore("/** Cancel on the countdown"))
        assertTrue("reachSkipPill()" in activity.substringAfter("fun onControlsVisibilityChanged(visible: Boolean) {").substringBefore("liftSubtitles()"))
        val controls = source("src/main/java/com/tmplayer/player/PlayerControls.kt")
        assertTrue("timeBar.nextFocusUpId = pillId ?: R.id.center_play_pause" in controls)
    }

    @Test
    fun `the pill's words name the way to it, a phone's pill is unchanged`() {
        val pill = source("src/main/java/com/tmplayer/player/SkipPill.kt")
        val hint = pill.substringAfter("private fun renderHint() {").substringBefore("\n    }\n")
        assertTrue("rowUp && !view.hasFocus() -> L.playerSkipHintRowUp" in hint)
        assertTrue("else -> L.playerSkipOkHint" in hint)
        assertEquals("a phone shows no remote hint", 1, Regex("hint.visibility = if \\(tv\\) View.VISIBLE else View.GONE").findAll(hint).count())
    }

    // ---- behaviour: real KeyEvents through the same state the activity keeps ----

    /** OK over the bare picture, event by event: the pause (on release) or the handover (at 1.5 s). */
    private class Remote {
        val ok = PlayerRemoteKeys.OkPress()
        var pauses = 0
        var handovers = 0

        fun press(heldMs: Long, overPicture: Boolean, canceled: Boolean = false) {
            val code = KeyEvent.KEYCODE_DPAD_CENTER
            val first = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0)
            ok.onDown(first.repeatCount, 0L, overPicture)
            // Repeats every 50 ms from 500 ms, as a remote's key repeat runs.
            var at = 500L
            var repeat = 1
            while (at <= heldMs) {
                val event = KeyEvent(0L, at, KeyEvent.ACTION_DOWN, code, repeat)
                if (ok.onDown(event.repeatCount, event.eventTime - event.downTime, overPicture = false)) handovers++
                at += 50
                repeat++
            }
            val up = KeyEvent(0L, heldMs, KeyEvent.ACTION_UP, code, 0)
            if (ok.onUp(canceled || up.isCanceled)) pauses++
        }
    }

    @Test
    fun `a tap on OK over the bare picture pauses once, on its release, and never hands over`() {
        val remote = Remote()
        remote.press(heldMs = 120, overPicture = true)
        assertEquals(1, remote.pauses)
        assertEquals(0, remote.handovers)
    }

    @Test
    fun `OK held 2 s over the bare picture hands over once and does not also pause`() {
        val remote = Remote()
        remote.press(heldMs = 2_000, overPicture = true)
        assertEquals(1, remote.handovers)
        assertEquals("one press never both pauses and hands over", 0, remote.pauses)
    }

    @Test
    fun `OK held 1 s pauses on release like a tap, the handover needs 1_5 s`() {
        val remote = Remote()
        remote.press(heldMs = 1_000, overPicture = true)
        assertEquals(0, remote.handovers)
        assertEquals(1, remote.pauses)
    }

    @Test
    fun `OK held with the row up never hands over and never pauses by itself`() {
        val remote = Remote()
        remote.press(heldMs = 4_000, overPicture = false)
        assertEquals(0, remote.handovers)
        assertEquals(0, remote.pauses)
    }

    @Test
    fun `a cancelled OK over the picture pauses nothing`() {
        val remote = Remote()
        remote.press(heldMs = 100, overPicture = true, canceled = true)
        assertEquals(0, remote.pauses)
    }

    @Test
    fun `a short CC press is judged on its release, a held CC only opens the picker and never toggles`() {
        val cc = PlayerRemoteKeys.CaptionsKeyPress()
        // A tap: nothing on the way down, the release is the toggle.
        assertFalse(cc.onDown(0, longPress = false))
        assertTrue(cc.pending)
        assertTrue(cc.onUp(canceled = false))
        // A hold: the long press opens the picker once, the release then toggles nothing.
        var opens = 0
        assertFalse(cc.onDown(0, longPress = false))
        for ((repeat, long) in listOf(1 to false, 2 to false, 3 to true, 4 to false)) {
            if (cc.onDown(repeat, long)) opens++
        }
        assertEquals(1, opens)
        assertFalse(cc.pending)
        assertFalse("the release of a hold is not a toggle", cc.onUp(canceled = false))
        // A cancelled tap toggles nothing either.
        cc.onDown(0, false)
        assertFalse(cc.onUp(canceled = true))
    }
}
