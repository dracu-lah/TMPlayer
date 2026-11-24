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
    fun `every button of the row, words under it or not, is the one the remote returns to`() {
        // PL-8 with the words under the icons: the labelled buttons sit in slots, so the row's own
        // test for "a button of mine" has to see through them.
        for (id in listOf(R.id.control_subtitles, R.id.control_audio, R.id.control_speed, R.id.control_shape, R.id.control_tv_more)) {
            val (controls, root) = row()
            controls.setLabelsShown(true)
            controls.show()
            val button = root.findViewById<View>(id)
            assertTrue("button $id can take the focus", button.requestFocus())
            root.findViewById<View>(R.id.controls_timebar).requestFocus()
            controls.focusRow()
            assertTrue("back on button $id", button.isFocused)
        }
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
    fun `Previous episode shows left of Next with an earlier episode, plays it and goes on the first one`() {
        val (controls, root) = row()
        controls.show()
        val previous = root.findViewById<View>(R.id.control_tv_previous_episode)
        val next = root.findViewById<View>(R.id.control_tv_next_episode)
        assertEquals(View.GONE, previous.visibility)
        controls.setEpisodes(episode(1), episode(3), "Previous: S01E01", "Next: S01E03", "S01E01", "S01E03")
        assertEquals(View.VISIBLE, previous.visibility)
        assertEquals("Previous: S01E01", previous.contentDescription)
        assertTrue(previous.isFocusable)
        val row = previous.parent as android.view.ViewGroup
        assertEquals("Previous sits just before Next", row.indexOfChild(next) - 1, row.indexOfChild(previous))
        previous.performClick()
        assertEquals(1L, played?.messageId)
        // The first episode: no Previous, Next stays.
        controls.setEpisodes(null, episode(2), "", "Next", "", "")
        assertEquals(View.GONE, previous.visibility)
        assertEquals(View.VISIBLE, next.visibility)
    }

    @Test
    fun `Right from Previous lands on Next and Left from Next on Previous`() {
        val (controls, root) = row()
        controls.show()
        val previous = root.findViewById<View>(R.id.control_tv_previous_episode)
        val next = root.findViewById<View>(R.id.control_tv_next_episode)
        controls.setEpisodes(episode(1), episode(3), "Previous", "Next", "", "")
        repeat(5) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        assertTrue(previous.requestFocus())
        assertTrue(previous.focusSearch(View.FOCUS_RIGHT) === next)
        assertTrue(next.focusSearch(View.FOCUS_LEFT) === previous)
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

    /**
     * WP-04 (engineer correction 19): the type floor makes the words under the row's icons 14 sp and
     * the system font may be 1.3 on top. `fitRow` and `giveUp` drop buttons silently on a phone when
     * text grows; the television's row must give none up: the five buttons stay shown, each with
     * room, and the whole row stays inside the 960 dp screen and its 48 dp safe sides (WP-05).
     */
    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `no button of the television row is given up at 960 dp and font 1_3`() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        try {
            val (controls, root) = row()
            controls.setLabelsShown(true)
            controls.show()
            repeat(10) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
            val density = root.resources.displayMetrics.density
            assertEquals(960f, root.width / density, 1f)
            for (id in listOf(R.id.control_subtitles, R.id.control_audio, R.id.control_speed, R.id.control_shape, R.id.control_tv_more)) {
                val button = root.findViewById<View>(id)
                assertTrue("button $id is shown", button.isShown)
                assertTrue("button $id has room (${button.width} x ${button.height} px)", button.width > 0 && button.height > 0)
                val at = IntArray(2).also { button.getLocationInWindow(it) }
                // WP-05: inside the 48 dp sides of the safe area, with the wider margins.
                assertTrue("button $id starts inside the 48 dp side (${at[0] / density} dp)", at[0] >= 48 * density)
                assertTrue("button $id ends inside the 48 dp side", at[0] + button.width <= root.width - 48 * density)
            }
            // The words under the icons are the floor's 14 sp, grown by the 1.3 font.
            val word = root.findViewById<android.widget.TextView>(R.id.label_speed)
            assertEquals(14f * 1.3f * density, word.textSize, 0.5f)
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }
}
