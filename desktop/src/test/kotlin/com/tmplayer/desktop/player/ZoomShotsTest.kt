package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.i18n.L
import com.tmplayer.player.PictureZoom
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The free zoom's figure over the picture, as Ctrl+wheel or Alt+= leaves it: the player's own text
 * flash saying "Zoom 135%", and the shortcut sheet's row. With TMPLAYER_SHOTS=<dir> the frame is
 * written there as `player-zoom-figure.png`.
 */
class ZoomShotsTest {

    @Test
    fun `the figure reads the zoom in the player's words`() {
        val zoom = PictureZoom.NONE.stepped(1f, 640f, 360f, PictureZoom.Frame(1280f, 720f, 1280f, 720f))
            .stepped(1f, 640f, 360f, PictureZoom.Frame(1280f, 720f, 1280f, 720f))
        val text = L.playerZoomPercent(L.messages.formatter.scalePercent(zoom.percent))
        assertEquals("Zoom 141%", text)
        ImageComposeScene(1280, 720, Density(1f)) {
            PlayerTheme {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF2F4F6F), Color(0xFFB0773A), Color(0xFF1B2A1F))),
                    ),
                ) {
                    FeedbackLayer(flash = Flash(Flash.Kind.Text, text, 1L), spinner = false, chip = null)
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(100)
            val png = scene.render(300_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            assertTrue(png.size > 1000)
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let {
                File(it).apply { mkdirs() }.resolve("player-zoom-figure.png").writeBytes(png)
            }
        }
    }

    @Test
    fun `the shortcut sheet names the zoom keys for each system`() {
        val row = { mac: Boolean -> PlayerKeys.sheet(mac).first { it.first == L.keysRowZoom }.second }
        assertEquals("Alt+=, Alt+-, Alt+0, Ctrl+wheel", row(false))
        assertEquals("Option+=, Option+-, Option+0, Ctrl+wheel", row(true))
    }
}
