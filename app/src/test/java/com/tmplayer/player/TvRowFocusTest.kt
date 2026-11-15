package com.tmplayer.player

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * The television's row and its remote: where focus lands when the row comes up with Down, where it
 * returns after a sheet closes, and the Next episode button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvRowFocusTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as Player

    private var played: MediaItem? = null

    private fun row(): Pair<PlayerControls, View> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply { id = R.id.player_root }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        val controls = PlayerControls(
            root = root,
            isTv = true,
            player = { player },
            onVisibility = {},
            onTogglePlay = {},
            onPickSubtitles = {},
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = { played = it },
        )
        return controls to root
    }

    private fun episode(id: Long) = MediaItem(
        chatId = 1L, messageId = id, fileId = id.toInt(), title = "Show S01E0$id", sizeBytes = 1L,
        durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `the row raised by the bar key keeps the focus on the scrub bar`() {
        val (controls, root) = row()
        controls.show()
        assertTrue(root.findViewById<View>(R.id.controls_timebar).isFocused)
    }

    @Test
    fun `Down over the bare picture raises the row with the first button focused`() {
        val (controls, root) = row()
        controls.show(focusButtons = true)
        assertTrue(root.findViewById<View>(R.id.control_subtitles).isFocused)
        assertFalse(root.findViewById<View>(R.id.controls_timebar).isFocused)
    }

    @Test
    fun `a picker closing puts the remote back on the button that opened it`() {
        val (controls, root) = row()
        controls.show()
        val speed = root.findViewById<View>(R.id.control_speed)
        assertTrue(speed.requestFocus())
        // The picker takes the focus away, as a sheet over the row does.
        root.findViewById<View>(R.id.control_subtitles).requestFocus()
        speed.requestFocus()
        root.findViewById<View>(R.id.controls_timebar).requestFocus()
        // The last row button the remote stood on was the speed button.
        controls.focusRow()
        assertTrue("back on the speed button", speed.isFocused)
    }

    @Test
    fun `with no button visited the first one gets the focus`() {
        val (controls, root) = row()
        controls.show()
        controls.focusRow()
        assertTrue(root.findViewById<View>(R.id.control_subtitles).isFocused)
    }

    @Test
    fun `a fresh appearance of the row forgets the old button`() {
        val (controls, root) = row()
        controls.show()
        root.findViewById<View>(R.id.control_speed).requestFocus()
        controls.hideAnimated()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controls.show()
        controls.focusRow()
        assertTrue(root.findViewById<View>(R.id.control_subtitles).isFocused)
    }

    @Test
    fun `Next episode shows with a next episode, plays it and goes without one`() {
        val (controls, root) = row()
        controls.show()
        val next = root.findViewById<View>(R.id.control_tv_next_episode)
        assertEquals(View.GONE, next.visibility)
        controls.setEpisodes(null, episode(2), "", "Next: S01E02", "", "S01E02")
        assertEquals(View.VISIBLE, next.visibility)
        assertEquals("Next: S01E02", next.contentDescription)
        assertTrue(next.isFocusable)
        next.performClick()
        assertEquals(2L, played?.messageId)
        // A single episode, or the last one: no button.
        controls.setEpisodes(null, null, "", "", "", "")
        assertEquals(View.GONE, next.visibility)
    }

    @Test
    fun `the remote on Next episode when it goes lands back on the row`() {
        val (controls, root) = row()
        controls.show()
        val next = root.findViewById<View>(R.id.control_tv_next_episode)
        controls.setEpisodes(null, episode(2), "", "Next", "", "")
        assertTrue(next.requestFocus())
        controls.setEpisodes(null, null, "", "", "", "")
        val now = root.findFocus()
        assertTrue("focus stays on the row", now != null && now !== next)
        assertNull(played)
    }
}
