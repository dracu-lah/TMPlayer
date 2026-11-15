package com.tmplayer.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The playback stats readout (PR5): never takes focus or a press, costs nothing off, goes away clean. */
@RunWith(RobolectricTestRunner::class)
class PlaybackStatsOverlayTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun built(tv: Boolean): Triple<FrameLayout, View, PlaybackStatsOverlay> {
        val root = FrameLayout(context)
        val controls = View(context)
        root.addView(controls)
        return Triple(root, controls, PlaybackStatsOverlay(root, controls, tv))
    }

    @Test
    fun `it is off by default and the switch remembers`() = runBlocking {
        val store = SettingsStore(context)
        assertFalse(store.playbackStatsNow())
        store.setPlaybackStats(true)
        assertTrue(store.playbackStatsNow())
        store.setPlaybackStats(false)
        assertFalse(store.playbackStatsNow())
    }

    @Test
    fun `on a phone and a television the box cannot take focus or a press, and sits under the controls`() {
        for (tv in listOf(false, true)) {
            val (root, controls, overlay) = built(tv)
            assertFalse(overlay.view.isFocusable)
            assertFalse(overlay.view.isClickable)
            assertEquals(View.GONE, overlay.view.visibility)
            assertTrue("under the controls", root.indexOfChild(overlay.view) < root.indexOfChild(controls))
        }
    }

    @Test
    fun `it shows its lines, hides on none and lets go of the view`() {
        val (root, _, overlay) = built(tv = false)
        overlay.show(listOf("ExoPlayer", "Dropped 0"))
        assertEquals(View.VISIBLE, overlay.view.visibility)
        assertEquals("ExoPlayer\nDropped 0", overlay.view.text.toString())
        overlay.show(emptyList())
        assertEquals(View.GONE, overlay.view.visibility)
        overlay.release()
        assertNull(overlay.view.parent)
        assertEquals(1, root.childCount)
        // A second release does nothing.
        overlay.release()
    }
}
