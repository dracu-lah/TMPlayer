package com.tmplayer.player

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
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

/** The speed button: the figure on its face, a tap that swaps, a long press that opens the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeedButtonTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as Player

    private var lists = 0
    private var swaps = 0
    private var canSwap = false

    private fun row(tv: Boolean): Pair<PlayerControls, ImageButton> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply { id = R.id.player_root }
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
            onPickSpeed = { lists++ },
            onCycleOrientation = {},
            onPlayEpisode = {},
            onQuickSpeed = {
                swaps++
                canSwap
            },
        )
        return controls to root.findViewById<View>(R.id.control_speed) as ImageButton
    }

    @Test
    fun `the face shows the figure off 1x and the icon at 1x`() {
        val (controls, button) = row(tv = false)
        val icon = button.drawable
        controls.showSpeed(1.25f)
        assertTrue(button.drawable is SpeedLabelDrawable)
        assertTrue(button.contentDescription.toString().contains("1.25"))
        controls.showSpeed(1f)
        assertEquals(icon, button.drawable)
        assertFalse(button.contentDescription.toString().contains("1x"))
    }

    @Test
    fun `on a phone a tap swaps when there is another speed and opens the list when there is not`() {
        val (_, button) = row(tv = false)
        canSwap = true
        button.performClick()
        assertEquals(1, swaps)
        assertEquals(0, lists)
        canSwap = false
        button.performClick()
        assertEquals(1, lists)
    }

    @Test
    fun `on a phone a long press opens the list`() {
        val (_, button) = row(tv = false)
        canSwap = true
        assertTrue(button.performLongClick())
        assertEquals(1, lists)
        assertEquals(0, swaps)
    }

    @Test
    fun `on a television OK always opens the list`() {
        val (_, button) = row(tv = true)
        canSwap = true
        button.performClick()
        assertEquals(1, lists)
        assertEquals(0, swaps)
    }
}
