package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.components.MiniPlayerBar
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mini player's bar (U8) at a phone's width and a desktop's, in both themes, and what its
 * three targets and the swipe do. With TMPLAYER_SHOTS=<dir> the frames are written there.
 */
class MiniPlayerBarTest {

    private val longTitle = "A very long title that has to stay on one line and end in an ellipsis instead of pushing the buttons off the bar"

    @Test
    fun `the bar draws at a phone width and a desktop width in both themes`() {
        for (dark in listOf(true, false)) {
            for ((form, width) in listOf("phone" to 360, "desktop" to 720)) {
                for (playing in listOf(true, false)) {
                    shot("mini-$form-${if (dark) "dark" else "light"}-${if (playing) "playing" else "paused"}", width, 96, dark) {
                        Box(Modifier.fillMaxSize().background(Tone.background).padding(8.dp)) {
                            MiniPlayerBar(longTitle, playing, 0.4f, onExpand = {}, onTogglePlay = {}, onDismiss = {})
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a tap expands, the play button toggles, the cross dismisses`() {
        val log = mutableListOf<String>()
        ImageComposeScene(360, 96, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    MiniPlayerBar("Title", true, 0.5f, onExpand = { log += "expand" }, onTogglePlay = { log += "toggle" }, onDismiss = { log += "dismiss" })
                }
            }
        }.use { scene ->
            scene.render(0)
            // The bar spans 8 to 352 px; the cross is the last 48 dp, the play button the one before.
            fun tap(x: Float, y: Float = 36f) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.render(50_000_000L)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                scene.render(100_000_000L)
            }
            tap(60f)
            tap(352f - 24f - 48f)
            tap(352f - 24f)
            assertEquals(listOf("expand", "toggle", "dismiss"), log)
        }
    }

    @Test
    fun `a swipe down dismisses, a short one and a swipe up do not`() {
        fun swipe(dy: Float): List<String> {
            val log = mutableListOf<String>()
            ImageComposeScene(360, 160, Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        MiniPlayerBar("Title", true, 0.5f, onExpand = { log += "expand" }, onTogglePlay = {}, onDismiss = { log += "dismiss" })
                    }
                }
            }.use { scene ->
                scene.render(0)
                val x = 120f
                val y = 30f
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.render(20_000_000L)
                var moved = 0f
                while (kotlin.math.abs(moved) < kotlin.math.abs(dy)) {
                    moved += if (dy > 0) 6f else -6f
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x, y + moved))
                    scene.render(40_000_000L + moved.toLong() * 1_000_000L)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y + moved))
                scene.render(500_000_000L)
            }
            return log
        }
        assertEquals(listOf("dismiss"), swipe(60f))
        assertTrue(swipe(12f).isEmpty())
        assertTrue(swipe(-60f).isEmpty())
    }

    private fun shot(name: String, w: Int, h: Int, dark: Boolean, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) { content() }
        }.use { scene ->
            scene.render(0)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > 100)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }
}
