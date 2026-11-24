package com.tmplayer.player

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/** WP-35: a press on the row, the seek buttons and play ticks once on a phone and never on a television. */
@RunWith(RobolectricTestRunner::class)
class PlayerControlsHapticTest {

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

    private fun open(tv: Boolean, ticks: MutableList<View>): FrameLayout {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.BLACK)
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = tv,
            player = { player },
            onVisibility = {},
            onTogglePlay = {},
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
        )
        controls.onTick = { ticks += it }
        controls.show()
        return root
    }

    @Test
    fun aPhonePressTicksOnceEach() {
        val ticks = mutableListOf<View>()
        val root = open(tv = false, ticks = ticks)
        listOf(R.id.center_seek_back, R.id.center_seek_forward, R.id.control_audio, R.id.control_subtitles).forEach {
            root.findViewById<View>(it).performClick()
        }
        assertEquals(4, ticks.size)
    }

    @Test
    fun aTelevisionNeverTicks() {
        val ticks = mutableListOf<View>()
        val root = open(tv = true, ticks = ticks)
        listOf(R.id.center_seek_back, R.id.center_seek_forward, R.id.control_audio).forEach {
            root.findViewById<View>(it).performClick()
        }
        assertEquals(0, ticks.size)
    }
}
