package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.PromoCoverBook
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A chat where one channel advert is on the cover of three unrelated videos, composed for real
 * (WP: adverts on covers). The advert is solid red and one video's own picture solid blue, so
 * the pixels say which was drawn: with the advert found (PromoCoverBook) no red is on screen, the
 * generated covers stand in and the blue one stays; with nothing found the red is drawn, which
 * keeps the check honest. The phone upright and on its side, and the television.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromoCoverRenderTest {
    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
        PromoCoverBook.clear()
    }

    private fun solid(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    private fun video(name: String, art: ByteArray): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 500_000_000, durationSec = 5_400,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = art, date = (1000 - id).toInt(), fileName = name,
        )
    }

    /** The count of strongly red and strongly blue pixels in the rendered grid. */
    private fun render(tv: Boolean, flag: Boolean, out: String): Pair<Int, Int> {
        FormFactor.override(tv)
        val ad = solid(Color.RED)
        val items = listOf(
            video("Harbour Notes (2026) Tamil 1080p.mkv", ad.copyOf()),
            video("Lantern Road (2025) Hindi 720p.mkv", ad.copyOf()),
            video("Quiet River (2024) 1080p.mkv", ad.copyOf()),
            video("Night Train (2019) 1080p x265.mkv", solid(Color.BLUE)),
        )
        if (flag) PromoCoverBook.observe(items)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                val touch = isTouch()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().background(Tone.background),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        MediaCard(item = item, watched = null, finished = false, onClick = {}, onFocused = {}, dense = touch, poster = false)
                    }
                }
            }
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var red = 0
        var blue = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            if (r > 200 && g < 60 && b < 60) red++
            if (b > 200 && r < 60 && g < 60) blue++
        }
        return red to blue
    }

    private fun check(tv: Boolean, name: String) {
        val (redBefore, _) = render(tv, flag = false, out = "promo-$name-plain.png")
        assertTrue("the control should show the advert ($redBefore)", redBefore > 1_000)
        PromoCoverBook.clear()
        val (red, blue) = render(tv, flag = true, out = "promo-$name.png")
        assertEquals("the advert must not be drawn", 0, red)
        assertTrue("the video's own picture stays ($blue)", blue > 1_000)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() = check(tv = false, name = "phone")

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneLandscape() = check(tv = false, name = "phone-land")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun television() = check(tv = true, name = "tv")
}
