package com.tmplayer.ui.components

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Home carousel's picture ([EdgeBackdropOf]) for a source of each size, drawn for real on each
 * form factor and written to `build/device-test/review/edge-*.png` (HX55): a source wide enough
 * across the whole frame, one too small for it (drawn smaller at the end, the page's own colour
 * showing at the start), a tiny thumbnail, an upright poster and no picture, in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeBackdropRenderTest {

    private enum class Source(val w: Int, val h: Int) {
        Sharp(1280, 720),
        Soft(780, 439),
        Tiny(320, 180),
        Portrait(780, 1170),
        None(0, 0),
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun art(source: Source): BackdropArt? {
        if (source == Source.None) return null
        val bitmap = Bitmap.createBitmap(source.w, source.h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, source.w.toFloat(), source.h.toFloat(), Color.rgb(0x2E, 0x5E, 0xAA), Color.rgb(0xF2, 0x8C, 0x28), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, source.w.toFloat(), source.h.toFloat(), paint)
        return BackdropArt(picture = bitmap.asImageBitmap())
    }

    private fun draw(source: Source, name: String, tv: Boolean, frameHeight: Int): Pair<Bitmap, Int> {
        FormFactor.override(tv)
        Motion.reduced = true
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Box(Modifier.fillMaxWidth().height(frameHeight.dp)) {
                        EdgeBackdropOf(
                            cover = Cover("Night Train", null, 0),
                            art = { art(source) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/edge-$name-${source.name.lowercase()}.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        val frame = (frameHeight * activity.resources.displayMetrics.density).toInt()
        return bitmap to frame
    }

    /** Every source on one form factor: the soft one leaves the page's colour at the start of the frame. */
    private fun everySource(name: String, tv: Boolean, frameHeight: Int) {
        for (source in Source.entries) {
            val (bitmap, frame) = draw(source, name, tv, frameHeight)
            val below = bitmap.getPixel(6, (frame + 20).coerceAtMost(bitmap.height - 1))
            val start = bitmap.getPixel(6, frame / 2)
            when (source) {
                // Cap 1.5 x 780 = 1170 px of the width: the first pixels are the page's own ground.
                Source.Soft -> if (bitmap.width > 1170 * 1.15) assertEquals("$name soft: page colour at the start", below, start)
                else -> Unit
            }
            if (source == Source.Sharp || source == Source.Soft) {
                assertNotEquals("$name ${source.name}: the picture reaches the end", below, bitmap.getPixel(bitmap.width - 6, frame / 2))
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun `television 1080p, dark`() = everySource("tv-dark", tv = true, frameHeight = 280)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-notnight-xhdpi")
    fun `television 1080p, light`() = everySource("tv-light", tv = true, frameHeight = 280)

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun `wide window, dark`() = everySource("wide-dark", tv = false, frameHeight = 360)

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-notnight-xhdpi")
    fun `wide window, light`() = everySource("wide-light", tv = false, frameHeight = 360)

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land-night-xhdpi")
    fun `small window, dark`() = everySource("small-dark", tv = false, frameHeight = 200)

    @Test
    @Config(sdk = [35], qualifiers = "w914dp-h411dp-land-notnight-xhdpi")
    fun `phone on its side, light`() = everySource("phone-land-light", tv = false, frameHeight = 260)
}
