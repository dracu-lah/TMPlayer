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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.ui.theme.Motion
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
 * The phone's featured carousel at the top of Home, composed for real and drawn to PNGs under
 * `build/device-test/review/`: its stand-in while the starred chats load, then its pages, in both
 * themes. What sits under it must not move when the pages arrive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeaturedCarouselRenderTest {

    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 64f, 36f, Color.rgb(0x2E, 0x5E, 0xAA), Color.rgb(0xF2, 0x8C, 0x28), Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, 64f, 36f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun video(name: String, chatId: Long = 7) = nextId++.let { id ->
        MediaItem(
            chatId = chatId,
            messageId = id,
            fileId = id.toInt(),
            title = name,
            sizeBytes = 900_000_000 + id * 90_000_000,
            durationSec = 2_400,
            mimeType = "video/x-matroska",
            thumbnailFileId = 0,
            miniThumbnail = picture(),
            date = (1000 - id).toInt(),
            fileName = name,
        )
    }

    private fun picks(): List<FeaturedPick> = HomeFeatured.pick(
        SeriesShelf.arrange(
            listOf(
                video("Night.Train.2019.1080p.WEB-DL.mkv"),
                video("Harbour.Lights.S02E06.720p.mkv"),
                video("Harbour.Lights.S02E05.720p.mkv"),
                video("Paper.Moons.2018.1080p.BluRay.mkv", chatId = 8),
                video("Low.Tide.2022.720p.WEB.mkv", chatId = 8),
                video("Mountains.2021.2160p.HDR.mkv"),
                video("Quiet.Orchard.2020.1080p.mkv"),
            ),
        ),
    )

    private fun render(out: String, rtl: Boolean = false, tv: Boolean = false) {
        FormFactor.override(tv)
        // Still: the stand-in's shimmer is an endless animation the paused looper would never get
        // past, and the pages then hold still with their segments drawn static, as with Reduce motion.
        Motion.reduced = true
        val loading = compose(null, rtl, tv)
        draw(loading.first, "featured-loading-$out")
        val pages = compose(picks(), rtl, tv)
        draw(pages.first, "featured-$out")
        assertTrue(loading.second > 0f)
        assertEquals("the row under the carousel stays put as the pages arrive", loading.second, pages.second, 0.5f)
    }

    /** Home's top with the carousel showing [shown], and where the row under it starts. */
    private fun compose(shown: List<FeaturedPick>?, rtl: Boolean = false, tv: Boolean = false): Pair<ComponentActivity, Float> {
        var rowTop = -1f
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
            // The theme takes the direction from the app's language, English here, so a right to
            // left page is asked for inside it.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
                    val under = 24.dp
                    val height = if (tv) TV_FEATURED_HEIGHT else featuredHeight(maxWidth, maxHeight - under) + under
                    Column(Modifier.fillMaxSize()) {
                      if (tv) {
                        // The television's carousel: no status bar, a fixed height.
                        TvFeaturedCarousel(
                            picks = shown,
                            height = height,
                            start = 48.dp,
                            end = 48.dp,
                            watch = SeriesWatch(point = { null }, finished = { false }),
                            chatTitle = { if (it == 8L) "Film Club" else "Weekend Films" },
                            active = false,
                            onPlay = { _, _ -> },
                            onDetails = {},
                        )
                      } else {
                      // The carousel with the app's name over its top corner, as HomePane sets it.
                      Box {
                        FeaturedCarousel(
                            picks = shown,
                            height = height,
                            under = under,
                            start = 16.dp,
                            end = 16.dp,
                            watch = SeriesWatch(point = { null }, finished = { false }),
                            chatTitle = { if (it == 8L) "Film Club" else "Weekend Films" },
                            active = false,
                            onPlay = { _, _ -> },
                            onDetails = {},
                        )
                        PhoneHomeBrand(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = under + BRAND_TOP), onArt = true)
                      }
                      }
                        // The first row under it.
                        Box(
                            Modifier.fillMaxWidth().height(40.dp)
                                .onGloballyPositioned { rowTop = it.positionInRoot().y },
                        )
                    }
                }
            }
        }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        return activity to rowTop
    }

    private fun draw(activity: ComponentActivity, out: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun `television`() = render("tv", tv = true)

    @Test
    fun `five different pages from the starred chats`() {
        val picks = picks()
        assertEquals(HomeFeatured.LIMIT, picks.size)
        // The show is one page, newest first.
        assertEquals(1, picks.count { it.series != null })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, dark`() = render("phone-dark")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `phone, light`() = render("phone-light")

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `phone on its side`() = render("phone-land")

    @Test
    @Config(sdk = [35], qualifiers = "w914dp-h411dp-land-notnight-xhdpi")
    fun `phone on its side, light`() = render("phone-land-light")

    @Test
    @Config(sdk = [35], qualifiers = "ldrtl-w914dp-h411dp-land-night-xhdpi")
    fun `phone on its side, right to left`() = render("phone-land-rtl", rtl = true)

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land-night-xhdpi")
    fun `small phone on its side`() = render("phone-land-small")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun `tablet lying down`() = render("tablet-land")

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-notnight-xhdpi")
    fun `tablet upright`() = render("tablet-port")

    @Test
    fun `on its side Continue watching shows under the carousel`() {
        // A phone held sideways, about 914 by 411 dp, 24 dp of it the status bar.
        val under = 24.dp
        val window = 411.dp
        val carousel = featuredHeight(914.dp, window - under) + under
        assertTrue("a row's worth shows under it", window - carousel >= 100.dp)
        // Upright stays as it was.
        assertEquals(featuredHeight(411.dp, 867.dp), (867.dp * 0.7f).coerceAtMost(411.dp * 1.33f))
    }
}
