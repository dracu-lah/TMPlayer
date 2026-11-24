package com.tmplayer.player

import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * The row around a pause ([PauseControls]): up for the whole pause, on a television with the play
 * disc holding the remote, then gone after the resume, sooner when it was down before. No glyph
 * flashes over the picture: the disc's own turn is the answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseControlsRowTest {

    private var playing = true

    /** Just enough of a player for the row: whether it plays, and nothing loaded. */
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "isPlaying", "getPlayWhenReady" -> playing
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private fun row(tv: Boolean): Pair<PlayerControls, View> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply { id = R.id.player_root }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = tv,
            player = { player },
            onVisibility = {},
            onTogglePlay = { playing = !playing },
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
            onCycleShape = {
                shapes++
                // What the Mi TV did with a 3D video (HX22): the picture redrawn under the row
                // and the focus dropped onto the play disc.
                root.findViewById<View>(R.id.center_play_pause).requestFocus()
            },
        )
        return controls to root.findViewById(R.id.center_play_pause)
    }

    private var shapes = 0

    @Test
    fun `on a television OK on picture shape steps the shape and keeps the remote on it, never on the disc`() {
        val (controls, disc) = row(tv = true)
        controls.show()
        val shape = disc.rootView.findViewById<View>(R.id.control_shape)
        assertTrue(shape.requestFocus())
        shape.performClick()
        idle(1_000)
        assertEquals(1, shapes)
        assertTrue("the remote stays on picture shape", shape.isFocused)
        assertFalse(disc.isFocused)
        assertTrue("nothing paused", playing)
        // A pause from elsewhere while the remote is on a row button leaves it there.
        playing = false
        controls.paused()
        assertTrue(shape.isFocused)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `a pause keeps the row up for as long as it lasts`() {
        val (controls, disc) = row(tv = false)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        idle(60_000)
        assertTrue(controls.visible)
        assertEquals("Paused", ViewCompat.getStateDescription(disc))
    }

    @Test
    fun `a resume after a pause over the bare picture lets the row go sooner`() {
        val (controls, disc) = row(tv = false)
        playing = false
        controls.paused()
        playing = true
        controls.resumed(PauseControls.hideAfterResumeMs(controls.timeoutMs, downBeforePause = true))
        controls.onPlayingChanged()
        assertEquals("Playing", ViewCompat.getStateDescription(disc))
        idle(1_000)
        assertTrue(controls.visible)
        idle(1_000)
        assertFalse(controls.visible)
    }

    @Test
    fun `a resume with the row up before keeps the usual time`() {
        val (controls, _) = row(tv = false)
        controls.show()
        playing = false
        controls.paused()
        playing = true
        controls.resumed(PauseControls.hideAfterResumeMs(controls.timeoutMs, downBeforePause = false))
        controls.onPlayingChanged()
        idle(2_000)
        assertTrue(controls.visible)
        idle(2_000)
        assertFalse(controls.visible)
    }

    @Test
    fun `on a television the pause hands the play disc the remote, and OK resumes`() {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        assertTrue(disc.isFocused)
        disc.performClick()
        assertTrue(playing)
        controls.resumed(controls.timeoutMs)
        controls.onPlayingChanged()
        assertTrue(disc.isFocused)
    }

    @Test
    fun `on a television a pause shows the time of day in the corner and never an ends at line`() {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        // For the first ten seconds of the pause (WP-21: then the row, the clock with it, makes way for the mark).
        idle(9_000)
        assertTrue(controls.visible)
        val clock = disc.rootView.findViewById<android.widget.TextView>(R.id.controls_clock)
        assertEquals(View.VISIBLE, clock.visibility)
        val expected = android.text.format.DateFormat.getTimeFormat(clock.context).format(java.util.Date())
        // The minute may have turned between the two reads: compare the shape, not the minute.
        assertEquals(expected.length, clock.text.length)
        assertTrue(clock.text.any { it.isDigit() })
        val all = buildList {
            fun walk(v: View) {
                if (v is android.widget.TextView) add(v.text.toString())
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(disc.rootView)
        }
        assertTrue(all.none { it.contains("ends at", ignoreCase = true) })
    }

    @Test
    fun `a phone has no clock of its own, its status bar has the time`() {
        val (controls, disc) = row(tv = false)
        playing = false
        controls.paused()
        val clock = disc.rootView.findViewById<View>(R.id.controls_clock)
        assertEquals(View.GONE, clock.visibility)
    }

    // ---- the pause dim on a television (WP-21) ------------------------------------------------

    private fun mark(root: View): android.widget.TextView? =
        (root.findViewById<View>(R.id.player_controls).parent as android.view.ViewGroup).let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }
                .filterIsInstance<android.widget.TextView>()
                .firstOrNull { it.text == com.tmplayer.i18n.L.playerPaused }
        }

    @Test
    fun `on a television a pause of ten seconds takes the row away and leaves a small mark, any key brings the row back`() {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        idle(9_000)
        assertTrue("the row stays for the first ten seconds", controls.visible)
        assertTrue(mark(disc.rootView)?.visibility != View.VISIBLE)
        idle(2_000)
        assertFalse("then it goes", controls.visible)
        assertEquals(View.VISIBLE, mark(disc.rootView)?.visibility)
        assertTrue(controls.pauseMarkShown)
        // A key: the row, and the mark is gone.
        val remote = BarePicture(controls)
        remote.tap(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(controls.visible)
        assertEquals(View.GONE, mark(disc.rootView)?.visibility)
        // And a pause that goes on counts ten seconds again from that key.
        idle(9_000)
        assertTrue(controls.visible)
        idle(2_000)
        assertFalse(controls.visible)
    }

    /**
     * The activity's order for a key over the bare picture on a television, event by event, with
     * the real [PlayerRemoteKeys.PauseMarkKeys] first (PlayerActivity.dispatchKeyEvent; the
     * activity itself cannot start under Robolectric, its order is checked from the source below):
     * Back leaves, Left and Right seek, OK toggles on its release.
     */
    private inner class BarePicture(private val controls: PlayerControls) {
        val keys = PlayerRemoteKeys.PauseMarkKeys()
        var left = false
        var positionMs = 600_000L
        var okDown = false

        fun send(event: android.view.KeyEvent) {
            if (keys.onKey(event.keyCode, event.action, controls.pauseMarkShown) { controls.paused() }) return
            val down = event.action == android.view.KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> if (down) {
                    if (controls.visible) controls.hideAnimated() else left = true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && !controls.visible) positionMs += 10_000
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> when {
                    down && !controls.visible && event.repeatCount == 0 -> okDown = true
                    !down && okDown -> {
                        okDown = false
                        playing = !playing
                    }
                }
            }
        }

        fun tap(code: Int, repeats: Int = 0) {
            send(android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, code, 0))
            for (r in 1..repeats) send(android.view.KeyEvent(0L, 50L * r, android.view.KeyEvent.ACTION_DOWN, code, r))
            send(android.view.KeyEvent(0L, 50L * repeats + 50, android.view.KeyEvent.ACTION_UP, code, 0))
        }
    }

    /** The mark up after ten seconds of a pause on a television. */
    private fun dimmed(): Pair<PlayerControls, View> {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        idle(11_000)
        assertTrue(controls.pauseMarkShown)
        return controls to disc
    }

    @Test
    fun `with the pause mark up Back, a held Right and OK only bring the row back (PB2-03)`() {
        for (code in listOf(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER)) {
            val (controls, disc) = dimmed()
            val remote = BarePicture(controls)
            remote.tap(code, repeats = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 6 else 0)
            assertFalse("$code: the video is not left", remote.left)
            assertEquals("$code: no seek, not even from the repeats", 600_000L, remote.positionMs)
            assertFalse("$code: still paused, the release did not resume", playing)
            assertTrue("$code: the row is back", controls.visible)
            assertTrue("$code: the play disc has the remote", disc.isFocused)
            assertFalse(controls.pauseMarkShown)
            // The row is up now: the next Back puts it away, the one after leaves, as always.
            remote.tap(android.view.KeyEvent.KEYCODE_BACK)
            assertFalse(remote.left)
            remote.tap(android.view.KeyEvent.KEYCODE_BACK)
            assertTrue(remote.left)
        }
    }

    @Test
    fun `with the pause mark up the media keys keep their meaning`() {
        val (controls, _) = dimmed()
        val keys = PlayerRemoteKeys.PauseMarkKeys()
        for (code in listOf(
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_STOP,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_CAPTIONS,
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
        )) {
            assertFalse("$code passes", keys.onKey(code, android.view.KeyEvent.ACTION_DOWN, controls.pauseMarkShown) { controls.paused() })
        }
        assertTrue("the mark is still up", controls.pauseMarkShown)
    }

    @Test
    fun `the activity asks the pause mark first, before OK's release, Back and the arrows`() {
        val here = java.io.File("src/main/java/com/tmplayer/player/PlayerActivity.kt")
        val source = (if (here.exists()) here else java.io.File("app/${here.path}")).readText()
        val dispatch = source.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {").substringBefore("/** A key while the screen is locked")
        val mark = dispatch.indexOf("pauseMarkKeys.onKey(")
        assertTrue("dispatchKeyEvent asks PauseMarkKeys", mark > 0)
        assertTrue("{ controls?.paused() }" in dispatch)
        for (later in listOf("okPress.pausesOnRelease", "KeyEvent.KEYCODE_BACK ->", "KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT ->", "commitScan()", "KEYCODE_INFO && event.action == KeyEvent.ACTION_UP")) {
            assertTrue("$later comes after the pause mark", dispatch.indexOf(later) > mark)
        }
    }

    @Test
    fun `the mark steps to another corner every minute, and stays put under Reduce motion`() {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        idle(11_000)
        val gravity = { (mark(disc.rootView)!!.layoutParams as FrameLayout.LayoutParams).gravity }
        val first = gravity()
        idle(60_000)
        assertTrue("another corner after a minute", gravity() != first)
        com.tmplayer.ui.theme.Motion.reduced = true
        try {
            val (still, stillDisc) = row(tv = true)
            playing = false
            still.paused()
            still.onPlayingChanged()
            idle(11_000)
            val stayed = (mark(stillDisc.rootView)!!.layoutParams as FrameLayout.LayoutParams).gravity
            idle(120_000)
            assertEquals("it does not move", stayed, (mark(stillDisc.rootView)!!.layoutParams as FrameLayout.LayoutParams).gravity)
            assertTrue("it only dims", mark(stillDisc.rootView)!!.alpha < 1f)
        } finally {
            com.tmplayer.ui.theme.Motion.reduced = false
        }
    }

    @Test
    fun `playing again, a stopped screen and a phone never dim`() {
        val (controls, disc) = row(tv = true)
        playing = false
        controls.paused()
        controls.onPlayingChanged()
        // The screen stops (the activity's onStop): no timer survives it.
        controls.stopPauseDim()
        idle(30_000)
        assertTrue(controls.visible)
        assertTrue(mark(disc.rootView)?.visibility != View.VISIBLE)
        // Playing again: nothing dims, however long.
        playing = true
        controls.resumed(PauseControls.hideAfterResumeMs(controls.timeoutMs, downBeforePause = false))
        controls.onPlayingChanged()
        idle(60_000)
        assertTrue(mark(disc.rootView)?.visibility != View.VISIBLE)
        // A phone keeps the row up for the whole pause (above), and has no mark.
        val (phone, phoneDisc) = row(tv = false)
        playing = false
        phone.paused()
        phone.onPlayingChanged()
        idle(60_000)
        assertTrue(phone.visible)
        assertEquals(null, mark(phoneDisc.rootView))
    }

    // ---- the scan option's scrub mode (WP-79) -------------------------------------------------

    @Test
    fun `a scan moves the clock and the card to the virtual position and puts them away, a phone has none`() {
        val (controls, disc) = row(tv = true)
        playing = true
        controls.scanTo(754_000L)
        assertTrue("the row is raised in scrub mode", controls.visible)
        assertEquals(View.VISIBLE, controls.previewCard.visibility)
        val clock = com.tmplayer.i18n.L.messages.formatter.clock(754_000L)
        val texts = buildList {
            fun walk(v: View) {
                if (v is android.widget.TextView && v.visibility == View.VISIBLE) add(v.text.toString())
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(disc.rootView)
        }
        assertTrue("the time is on the card or the bar: $clock in $texts", texts.any { clock in it })
        controls.scanTo(900_000L)
        controls.endScan()
        assertEquals("the card is gone", View.GONE, controls.previewCard.visibility)
        val (phone, _) = row(tv = false)
        phone.scanTo(500_000L)
        assertEquals("nothing scans on a phone", View.GONE, phone.previewCard.visibility)
    }
}
