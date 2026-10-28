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
}
