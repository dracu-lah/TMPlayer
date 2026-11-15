package com.tmplayer.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.MediaItem
import com.tmplayer.data.TvArt
import com.tmplayer.data.WatchNext
import com.tmplayer.data.WatchNextPublisher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The picture the widget keeps: where it comes from, the one file it holds, and the chats it
 * never takes a picture from.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetArtTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var thumbnail: File
    private var asked = 0

    private fun video(message: Long) = WatchNext.Video(
        chatId = -100L, messageId = message, fileId = message.toInt(), title = "Coast S01E0$message", chatTitle = "Nature",
        sizeBytes = 1_000_000, durationSec = 2_640,
    )

    private fun item(message: Long, saved: Boolean = true, thumbnailFile: Int = 7): MediaItem =
        video(message).toMediaItem().copy(thumbnailFileId = thumbnailFile, canBeSaved = saved)

    @Before
    fun setUp() {
        WidgetArt.dir(context).deleteRecursively()
        WidgetArt.forgetTried()
        // A real, large JPEG, as Telegram's thumbnail or an online poster is.
        thumbnail = File(context.cacheDir, "thumb.jpg")
        val big = Bitmap.createBitmap(1_200, 1_800, Bitmap.Config.RGB_565)
        thumbnail.outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        asked = 0
        WatchNextPublisher.mediaLookup = { _, message -> asked++; item(message) }
        TvArt.thumbnailPath = { thumbnail.path }
        // Hermetic: no lookup of the title ever leaves the test.
        TvArt.onlinePictures = false
    }

    @After
    fun tearDown() {
        TvArt.onlinePictures = true
        TvArt.thumbnailPath = { null }
    }

    private fun files() = WidgetArt.dir(context).listFiles().orEmpty().toList()

    @Test
    fun `the thumbnail is kept as the one file and decoded small`() = runBlocking {
        assertTrue(WidgetArt.prepare(context, video(4)))
        assertEquals(1, files().size)
        val kept = WidgetArt.fileFor(context, video(4))
        assertNotNull(kept)
        // Kept byte for byte, not re-encoded.
        assertEquals(thumbnail.length(), kept!!.length())
        val bitmap = WidgetArt.bitmapFor(context, video(4))!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= WidgetArt.MAX_SIDE_PX * 2)
        assertTrue(bitmap.height > bitmap.width)
    }

    @Test
    fun `a new video replaces the old one's file`() = runBlocking {
        WidgetArt.prepare(context, video(4))
        WidgetArt.prepare(context, video(5))
        assertEquals(1, files().size)
        assertNull(WidgetArt.fileFor(context, video(4)))
        assertNotNull(WidgetArt.fileFor(context, video(5)))
    }

    @Test
    fun `a chat that restricts saving gets no picture and drops the old one`() = runBlocking {
        WidgetArt.prepare(context, video(4))
        WatchNextPublisher.mediaLookup = { _, message -> item(message, saved = false) }
        assertFalse(WidgetArt.prepare(context, video(5)))
        assertTrue(files().isEmpty())
        assertNull(WidgetArt.fileFor(context, video(5)))
        assertNull(WidgetArt.bitmapFor(context, video(5)))
    }

    @Test
    fun `Telegram not knowing the video, or it having no thumbnail, is a plain tile asked once`() = runBlocking {
        WatchNextPublisher.mediaLookup = { _, _ -> asked++; null }
        assertFalse(WidgetArt.prepare(context, video(4)))
        assertFalse(WidgetArt.prepare(context, video(4)))
        // Asked about once for the process: a slow account is not hammered at every redraw.
        assertEquals(1, asked)
        WatchNextPublisher.mediaLookup = { _, message -> item(message, thumbnailFile = 0) }
        assertFalse(WidgetArt.prepare(context, video(6)))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `an empty thumbnail file is no picture and leaves no file`() = runBlocking {
        thumbnail.writeBytes(ByteArray(0))
        assertFalse(WidgetArt.prepare(context, video(4)))
        assertTrue(files().isEmpty())
        assertNull(WidgetArt.bitmapFor(context, video(4)))
    }

    @Test
    fun `a thumbnail that never arrived is no picture`() = runBlocking {
        TvArt.thumbnailPath = { null }
        assertFalse(WidgetArt.prepare(context, video(4)))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `clearing takes every file and forgets what was tried`() = runBlocking {
        WidgetArt.prepare(context, video(4))
        WidgetArt.clear(context)
        assertTrue(files().isEmpty())
        assertTrue(WidgetArt.prepare(context, video(4)))
    }

    @Test
    fun `half a copy left by a crash is swept with the next one`() = runBlocking {
        WidgetArt.dir(context).mkdirs()
        File(WidgetArt.dir(context), "a_dead.img.part").writeBytes(ByteArray(10))
        WidgetArt.prepare(context, video(4))
        assertEquals(1, files().size)
        assertFalse(files().single().name.endsWith(".part"))
    }
}
