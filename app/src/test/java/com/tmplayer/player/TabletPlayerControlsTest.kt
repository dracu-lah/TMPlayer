package com.tmplayer.player

import android.graphics.Color
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
 * WP-37: the transport's sizes on a tablet (play 88 dp, steps 64, seek 56, bar touch 56, times 15 sp,
 * row buttons 56), the phone's unchanged, the rotate button gone where Android ignores it, and the
 * lock in the top bar while the row has no room for it.
 */
@RunWith(RobolectricTestRunner::class)
class TabletPlayerControlsTest {

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

    private class Shown(val controls: PlayerControls, val root: FrameLayout, val density: Float)

    private fun open(tv: Boolean = false, onLock: () -> Unit = {}): Shown {
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
            onLock = onLock,
        )
        controls.show()
        return Shown(controls, root, activity.resources.displayMetrics.density)
    }

    private fun Shown.width(id: Int): Int = root.findViewById<View>(id).layoutParams.width
    private fun Shown.height(id: Int): Int = root.findViewById<View>(id).layoutParams.height

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun aTabletGetsTheLargerTransport() {
        val s = open()
        assertEquals((88 * s.density).toInt(), s.width(R.id.center_play_pause))
        assertEquals((48 * s.density).toInt(), s.width(R.id.center_play_icon))
        assertEquals((64 * s.density).toInt(), s.width(R.id.center_previous))
        assertEquals((56 * s.density).toInt(), s.width(R.id.center_seek_back_frame))
        assertEquals((56 * s.density).toInt(), s.height(R.id.controls_timebar))
        assertEquals((56 * s.density).toInt(), s.width(R.id.control_audio))
        assertEquals(s.root.resources.getDimension(R.dimen.player_times_size), s.root.findViewById<android.widget.TextView>(R.id.times_position).textSize, 0.5f)
        assertEquals(15 * s.root.resources.configuration.fontScale * s.density, s.root.findViewById<android.widget.TextView>(R.id.times_position).textSize, 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun aPhoneKeepsItsSizes() {
        val s = open()
        assertEquals((72 * s.density).toInt(), s.width(R.id.center_play_pause))
        assertEquals((56 * s.density).toInt(), s.width(R.id.center_previous))
        assertEquals((48 * s.density).toInt(), s.width(R.id.center_seek_back_frame))
        assertEquals((48 * s.density).toInt(), s.height(R.id.controls_timebar))
        assertEquals((48 * s.density).toInt(), s.width(R.id.control_audio))
    }

    /** A television is wider than 600 dp and smallest width outranks the television qualifier: it must not turn into a tablet. */
    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun aTelevisionKeepsItsOwnSizes() {
        val s = open(tv = true)
        assertEquals((72 * s.density).toInt(), s.width(R.id.center_play_pause))
        assertEquals((26 * s.density).toInt(), s.height(R.id.controls_timebar))
        assertEquals((48 * s.density).toInt(), s.width(R.id.control_audio))
        assertEquals(14 * s.density, s.root.findViewById<android.widget.TextView>(R.id.times_position).textSize, 1f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun theRotateButtonHidesWhereAndroidIgnoresIt() {
        val s = open()
        val slot = s.root.findViewById<View>(R.id.control_rotate).parent as View
        assertEquals(View.VISIBLE, slot.visibility)
        s.controls.setRotateAvailable(false)
        assertEquals(View.GONE, slot.visibility)
        s.controls.setRotateAvailable(true)
        assertEquals(View.VISIBLE, slot.visibility)
    }

    @Test
    fun theRotateButtonNeverShowsOnATelevision() {
        val s = open(tv = true)
        val slot = s.root.findViewById<View>(R.id.control_rotate).parent as View
        s.controls.setRotateAvailable(true)
        assertEquals(View.GONE, slot.visibility)
    }

    @Test
    fun honouredOnlyOnAPhoneOutsideMultiWindow() {
        assertTrue(ScreenOrientation.honoured(tv = false, smallestWidthDp = 411, multiWindow = false))
        assertFalse(ScreenOrientation.honoured(tv = false, smallestWidthDp = 411, multiWindow = true))
        assertFalse(ScreenOrientation.honoured(tv = false, smallestWidthDp = 600, multiWindow = false))
        assertFalse(ScreenOrientation.honoured(tv = false, smallestWidthDp = 841, multiWindow = false))
        assertFalse(ScreenOrientation.honoured(tv = true, smallestWidthDp = 360, multiWindow = false))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h693dp-port-night-xhdpi")
    fun theLockIsInTheTopBarWhenTheRowHasNoRoom() {
        val s = open()
        assertFalse(s.controls.lockOnRow)
        assertTrue(s.controls.lockInTopBar)
        assertEquals(View.VISIBLE, s.root.findViewById<View>(R.id.control_lock_top).visibility)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun theLockIsOnTheRowWhenThereIsRoomAndNotInTheTopBar() {
        val s = open()
        assertTrue(s.controls.lockOnRow)
        assertFalse(s.controls.lockInTopBar)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h693dp-port-night-xhdpi")
    fun theTopBarLockPressesTheLock() {
        var locked = 0
        val s = open(onLock = { locked++ })
        s.root.findViewById<View>(R.id.control_lock_top).performClick()
        assertEquals(1, locked)
    }
}
