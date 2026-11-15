package com.tmplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.R
import com.tmplayer.data.ContinueWidget.Size
import com.tmplayer.data.ContinueWidget.Tile
import com.tmplayer.data.WatchNext
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What each tile looks like and what a tap does, with the widget's own layouts inflated the way a
 * launcher does it ([android.widget.RemoteViews.apply]).
 */
@RunWith(RobolectricTestRunner::class)
class ContinueWidgetViewsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val video = WatchNext.Video(
        chatId = -100L, messageId = 4L, fileId = 40, title = "Coast S01E04", chatTitle = "Nature",
        sizeBytes = 1_000_000, durationSec = 2_640,
    )
    private val playing = Tile.Video(video, title = "Coast S01E04", percent = 50, remainingSec = 1_320)

    @Before
    fun english() {
        Translator.select("en", listOf("en"))
    }

    private fun inflate(tile: Tile, size: Size, poster: Bitmap? = null): View {
        val views = ContinueWidgetViews.build(context, tile, size, poster)
        return views.apply(context, FrameLayout(context))
    }

    private fun View.text(id: Int) = findViewById<TextView>(id)

    @Test
    fun `a video shows its title, the chat and time left, and the bar`() {
        val view = inflate(playing, Size.Regular)
        assertEquals("Coast S01E04", view.text(R.id.widget_title).text.toString())
        assertEquals("Nature  ·  22m left", view.text(R.id.widget_detail).text.toString())
        val bar = view.findViewById<ProgressBar>(R.id.widget_progress)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(50, bar.progress)
        assertEquals("Continue Coast S01E04, 50% watched", view.findViewById<View>(R.id.widget_root).contentDescription.toString())
    }

    @Test
    fun `an unknown length shows only the chat`() {
        val view = inflate(playing.copy(remainingSec = 0), Size.Regular)
        assertEquals("Nature", view.text(R.id.widget_detail).text.toString())
    }

    @Test
    fun `the poster is drawn on a video tile and only there`() {
        val poster = Bitmap.createBitmap(8, 12, Bitmap.Config.RGB_565)
        val withPoster = inflate(playing, Size.Regular, poster).findViewById<ImageView>(R.id.widget_poster)
        assertNotNull(withPoster.drawable)
        // A locked tile is given a poster by mistake: it still shows the plain plate, never the picture.
        val locked = inflate(Tile.Locked, Size.Regular, poster).findViewById<ImageView>(R.id.widget_poster)
        assertTrue(locked.drawable !is android.graphics.drawable.BitmapDrawable)
    }

    @Test
    fun `the tiles without a video have friendly text and no bar`() {
        val table = listOf(
            Tile.Nothing to ("Nothing to continue" to "Tap to open TMPlayer"),
            Tile.SignIn to ("Sign in to TMPlayer" to "Tap to open TMPlayer"),
            Tile.Locked to ("TMPlayer is locked" to "Tap to unlock"),
        )
        for ((tile, expected) in table) {
            val view = inflate(tile, Size.Regular)
            assertEquals("$tile", expected.first, view.text(R.id.widget_title).text.toString())
            assertEquals("$tile", expected.second, view.text(R.id.widget_detail).text.toString())
            assertEquals("$tile", View.GONE, view.findViewById<View>(R.id.widget_progress).visibility)
        }
    }

    @Test
    fun `a locked tile carries nothing of the video`() {
        val view = inflate(Tile.Locked, Size.Regular)
        val shown = listOf(R.id.widget_title, R.id.widget_detail).joinToString { view.text(it).text.toString() }
        val said = view.findViewById<View>(R.id.widget_root).contentDescription.toString()
        for (secret in listOf("Coast", "Nature")) {
            assertTrue(secret, !shown.contains(secret) && !said.contains(secret))
        }
    }

    @Test
    fun `the strip has the title and the bar and no poster or detail line`() {
        val view = inflate(playing, Size.Compact)
        assertEquals("Coast S01E04", view.text(R.id.widget_title).text.toString())
        assertEquals(View.GONE, view.text(R.id.widget_detail).visibility)
        assertNull(view.findViewById<View>(R.id.widget_poster))
        assertEquals(50, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
        // The same for the tiles with nothing to play.
        for (tile in listOf(Tile.Nothing, Tile.SignIn, Tile.Locked)) inflate(tile, Size.Compact)
    }

    @Test
    fun `a title in another script and a very long one are kept whole for the view to cut`() {
        val long = "ふしぎな旅 ".repeat(40)
        val view = inflate(playing.copy(title = long), Size.Regular)
        assertEquals(long, view.text(R.id.widget_title).text.toString())
        assertEquals(2, view.text(R.id.widget_title).maxLines)
    }

    @Test
    fun `a video's tap is the launch Play next uses, the others open the app`() {
        val play = ContinueWidgetViews.intentFor(context, playing)
        assertEquals(WatchNextPublisher.ACTION_PLAY, play.action)
        assertEquals(video, WatchNextPublisher.videoFrom(play))
        assertTrue(play.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("com.tmplayer.MainActivity", play.component?.className)
        for (tile in listOf(Tile.Nothing, Tile.SignIn, Tile.Locked)) {
            val open = ContinueWidgetViews.intentFor(context, tile)
            assertEquals(Intent.ACTION_MAIN, open.action)
            assertNull("$tile", WatchNextPublisher.videoFrom(open))
            assertEquals("com.tmplayer.MainActivity", open.component?.className)
        }
    }
}
