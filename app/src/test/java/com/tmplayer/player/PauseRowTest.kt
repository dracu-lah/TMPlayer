package com.tmplayer.player

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
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
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * PB-01 (WP-19): Play/Pause under the More panel or a track picker on a television keeps the
 * remote on the panel. The real row is drawn beside a focusable stand in for the panel, and the
 * player's pause and resume go through [PauseRow] as the activity sends them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseRowTest {

    private var playing = true
    private var panelOpen = false
    private var said = 0

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

    private class Rig(val controls: PlayerControls, val pauseRow: PauseRow, val panel: View, val disc: View)

    private fun rig(): Rig {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply { id = R.id.player_root }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        // The More panel or a picker: a focusable view of its own in the same window.
        val panel = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(panel, FrameLayout.LayoutParams(200, 200))
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = true,
            player = { player },
            onVisibility = {},
            onTogglePlay = { playing = !playing },
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
            onCycleShape = {},
        )
        val pauseRow = PauseRow(
            controls = { controls },
            controlsUp = { controls.visible },
            blocked = { false },
            stillWatching = { false },
            underPanel = { panelOpen },
            pausedNow = { !playing },
            sayPaused = { said++ },
        )
        return Rig(controls, pauseRow, panel, root.findViewById(R.id.center_play_pause))
    }

    /** What the activity does for Play/Pause under a panel: the row is asked first, then the player pauses and reports it. */
    private fun Rig.playPauseKey() {
        if (playing) pauseRow.raiseForPause()
        playing = !playing
        pauseRow.changed(playWhenReady = playing, endOfItem = false)
    }

    @Test
    fun `a pause under the panel with the row down keeps the remote on the panel, and the row comes up once it closes`() {
        val rig = rig()
        panelOpen = true
        assertTrue(rig.panel.requestFocus())
        rig.playPauseKey()
        assertFalse(playing)
        assertTrue("the panel keeps the remote", rig.panel.isFocused)
        assertFalse("the row stays down under the panel", rig.controls.visible)
        assertFalse(rig.disc.isFocused)
        assertEquals("the pause is said over the picture", 1, said)
        assertTrue(rig.pauseRow.owed)
        // The panel closes: the row comes up for the pause, its disc holding the remote.
        panelOpen = false
        rig.pauseRow.pay()
        assertFalse(rig.pauseRow.owed)
        assertTrue(rig.controls.visible)
        assertTrue(rig.disc.isFocused)
    }

    @Test
    fun `a resume under the panel with the row down leaves the focus on the panel and owes nothing`() {
        val rig = rig()
        panelOpen = true
        assertTrue(rig.panel.requestFocus())
        rig.playPauseKey()
        rig.playPauseKey()
        assertTrue(playing)
        assertTrue(rig.panel.isFocused)
        assertFalse(rig.controls.visible)
        assertFalse(rig.pauseRow.owed)
        panelOpen = false
        rig.pauseRow.pay()
        assertFalse("nothing to raise for a video playing again", rig.controls.visible)
    }

    @Test
    fun `a pause from a headset under the panel, with the row up behind it, does not take the focus either`() {
        val rig = rig()
        rig.controls.show()
        panelOpen = true
        assertTrue(rig.panel.requestFocus())
        playing = false
        rig.pauseRow.changed(playWhenReady = false, endOfItem = false)
        assertTrue(rig.panel.isFocused)
        assertFalse(rig.disc.isFocused)
        assertTrue(rig.pauseRow.owed)
    }

    @Test
    fun `with no panel the pause still brings the row up with the disc focused, and the resume lets it go`() {
        val rig = rig()
        rig.playPauseKey()
        assertTrue(rig.controls.visible)
        assertTrue(rig.disc.isFocused)
        assertFalse(rig.pauseRow.owed)
        rig.playPauseKey()
        assertTrue(playing)
        assertTrue(rig.disc.isFocused)
    }
}
