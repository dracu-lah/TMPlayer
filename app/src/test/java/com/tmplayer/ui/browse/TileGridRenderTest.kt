package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TileBadge
import com.tmplayer.ui.components.TileGrid
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
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
 * A chat's grid of the art first tiles (WP2 of the cinematic refresh), composed for real and drawn
 * to PNGs under `build/device-test/review/`: the phone upright and on its side, at font scale 1.3
 * and 2.0, and the television, each in the theme its qualifiers ask for. Release names come out
 * clean, a file with no picture wears its generated cover, a show its episode pill, and a Watch
 * later record its cleaned name too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileGridRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun picture(from: Int, to: Int, portrait: Boolean = false): ByteArray {
        val w = if (portrait) 36 else 64
        val h = if (portrait) 54 else 36
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // A bright bar a third of the way down, so a crop and a whole picture are easy to tell apart.
        Canvas(bitmap).drawRect(0f, h * 0.3f, w.toFloat(), h * 0.4f, Paint().apply { color = Color.WHITE })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun video(name: String, art: Boolean = true, onDevice: Boolean = false, portrait: Boolean = false): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7,
            messageId = id,
            fileId = id.toInt(),
            title = name,
            sizeBytes = 400_000_000 + id * 90_000_000,
            durationSec = 1_500 + (id * 431 % 4000).toInt(),
            mimeType = "video/mp4",
            thumbnailFileId = 0,
            miniThumbnail = if (art) picture(Color.rgb(0x2E, 0x5E, 0xAA), Color.rgb(0xF2, 0x8C, 0x28), portrait) else null,
            date = (1000 - id).toInt(),
            fileName = name,
            onDevice = onDevice,
        )
    }

    private fun render(tv: Boolean, out: String) {
        FormFactor.override(tv)
        val later = WatchLaterRecord.of(video("Gladiator.II.2024.1080p.WEB-DL.x265.mkv"), "Films", addedAt = 1)
        val items = listOf(
            later.toMediaItem(),
            video("Lecture_07_-_Limits_and_continuity.mp4", art = false),
            video("Harbour.Lights.S02E05.720p.mkv"),
            video("Harbour.Lights.S02E06.720p.mkv"),
            video("Night Train (2019) 1080p x265.mkv", onDevice = true),
            video("Organic Chemistry Chapter 12.mp4", art = false),
            video("Coast walk, day 2.mp4"),
            video("Module 3 Sourdough starter.mp4", art = false),
            video("Mountains.2021.2160p.HDR.mkv"),
            video("Birthday highlights.mp4", art = false),
            video("Paper.Moons.2018.1080p.BluRay.mkv", portrait = true),
            video("Low.Tide.2022.720p.WEB.mkv", art = false),
        )
        // As the chat's grid lays it out: the chat's own order, posters when enough entries are.
        val shelf = SeriesShelf.arrange(items)
        val posterKeys = SeriesShelf.posterKeys(shelf)
        val watch = SeriesWatch(point = { null }, finished = { false })

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
                    val touch = isTouch()
                    val edge = if (touch) 16.dp else Tv.SafeH
                    val landscape = maxWidth > maxHeight
                    val posters = TileGrid.posterColumns(maxWidth - edge * 2, touch, landscape)
                    val wide = TileGrid.wideColumns(maxWidth - edge * 2, touch, landscape)
                    val columns = TileGrid.columns(posters, wide)
                    val gap = if (touch) TileGrid.TouchGap else TileGrid.TvGap
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(start = edge, end = edge, top = if (touch) 16.dp else Tv.FocusClearance + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        items(
                            shelf,
                            key = { it.key },
                            span = { GridItemSpan(columns / if (it.key in posterKeys) posters else wide) },
                        ) { entry ->
                            when (entry) {
                                is ShelfEntry.Show -> SeriesCard(
                                    series = entry.series,
                                    progress = watch.progress(entry.series),
                                    onClick = {},
                                    dense = touch,
                                    poster = entry.key in posterKeys,
                                )
                                is ShelfEntry.File -> MediaCard(
                                    item = entry.item,
                                    watched = if (entry.item.messageId == 2L) WatchPoint(600_000, 2_400_000) else null,
                                    finished = entry.item.title.startsWith("Coast"),
                                    onClick = {},
                                    onFocused = {},
                                    dense = touch,
                                    poster = entry.key in posterKeys,
                                    badge = if (entry.item.onDevice) null else if (entry.item.title.startsWith("Mountains")) {
                                        { TileBadge("New") }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
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
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneDark() = render(tv = false, out = "tiles-phone-dark.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneLight() = render(tv = false, out = "tiles-phone-light.png")

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneLandscape() = render(tv = false, out = "tiles-phone-land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 1.3f)
    fun phoneFontScale13() = render(tv = false, out = "tiles-phone-font13.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 2.0f)
    fun phoneFontScale20() = render(tv = false, out = "tiles-phone-font20.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun television() = render(tv = true, out = "tiles-tv.png")

    // Large screens (U17): a tablet each way up and an unfolded foldable, laid out by the phone's
    // touch grid, which picks its columns from the width.
    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun tabletUpright() = render(tv = false, out = "tiles-tablet-port.png")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletLyingDown() = render(tv = false, out = "tiles-tablet-land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun foldableOpen() = render(tv = false, out = "tiles-foldable.png")
}
