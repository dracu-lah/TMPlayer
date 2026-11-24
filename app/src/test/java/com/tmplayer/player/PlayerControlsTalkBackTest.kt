package com.tmplayer.player

import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * WP-17: what TalkBack reads on the player's row. The television's time readout is a named button
 * with the figures as its state, and a stall is spoken once, after half a second, on every form
 * factor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerControlsTalkBackTest {

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

    private fun open(isTv: Boolean): Shown {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.BLACK)
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = isTv,
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

    @Test
    fun `the television's time readout is a button that names what OK does and states where the video stands`() {
        val shown = open(isTv = true)
        val readout = shown.root.findViewById<View>(R.id.controls_time)
        assertEquals(L.playerShowTimeRemaining, readout.contentDescription)
        val info = readout.createAccessibilityNodeInfo()
        assertEquals("android.widget.Button", info.className)
        assertEquals(L.playerPositionOf("21:00", "43:54"), info.stateDescription)
        assertTrue(info.isClickable)
        assertTrue(info.isFocusable)
    }

    @Test
    fun `the readout names the other figure once OK has flipped it`() {
        val shown = open(isTv = true)
        val readout = shown.root.findViewById<View>(R.id.controls_time)
        shown.controls.showRemaining = true
        idleFor(1_000)
        assertEquals(L.playerShowTotalLength, readout.contentDescription)
    }

    @Test
    fun `a stall is spoken once after half a second, through the disc's live region, and the disc says Playing again after it`() {
        for (tv in listOf(false, true)) {
            val shown = open(isTv = tv)
            val disc = shown.root.findViewById<View>(R.id.center_play_pause)
            assertEquals(L.playerPlaying, ViewCompat.getStateDescription(disc))
            shown.controls.setBuffering(true)
            idleFor(400)
            assertNotEquals("tv=$tv: a short stall stays silent", L.playerBuffering, ViewCompat.getStateDescription(disc))
            idleFor(200)
            assertEquals("tv=$tv", L.playerBuffering, ViewCompat.getStateDescription(disc))
            shown.controls.setBuffering(false)
            idleFor(100)
            assertEquals("tv=$tv", L.playerPlaying, ViewCompat.getStateDescription(disc))
            assertEquals("tv=$tv: the disc stays a polite live region", View.ACCESSIBILITY_LIVE_REGION_POLITE, disc.accessibilityLiveRegion)
        }
    }

    @Test
    fun `a stall that ends within half a second is never spoken`() {
        val shown = open(isTv = false)
        val disc = shown.root.findViewById<View>(R.id.center_play_pause)
        shown.controls.setBuffering(true)
        idleFor(300)
        shown.controls.setBuffering(false)
        idleFor(1_000)
        assertEquals(L.playerPlaying, ViewCompat.getStateDescription(disc))
    }
}
