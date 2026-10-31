package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.NameColour
import com.tmplayer.ui.theme.TmMaterialTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat with no photo wears its own colour, not a grey disc (HX30 a): the colour comes out of
 * [NameColour], two names of different pairs are drawn differently, and the letter on it stays
 * white in either theme.
 */
class NameArtRenderTest {

    @Test
    fun `an avatar with no photo is painted in the name's own pair`() {
        // Pair 4 (forest) against pair 7 (olive), so the two cannot be told apart by chance.
        assertEquals(4, NameColour.indexOf(FOREST))
        assertEquals(7, NameColour.indexOf(OLIVE))
        val forest = centre(FOREST)
        assertInPair(FOREST, forest)
        assertInPair(OLIVE, centre(OLIVE))
        assertNotEquals("two pairs drew the same colour", forest, centre(OLIVE))
        // The same name twice is the same colour: nothing about the drawing is random.
        assertEquals(forest, centre(FOREST))
    }

    @Test
    fun `the colour is the same in a light theme`() {
        // The tile stands in for a picture, and pictures are dark in both themes.
        assertEquals(centre(FOREST, dark = true), centre(FOREST, dark = false))
    }

    /** Every channel of [pixel] between the pair's two stops, give or take the corner light. */
    private fun assertInPair(name: String, pixel: Int) {
        val (light, deep) = NameColour.pairOf(name)
        for (shift in listOf(16, 8, 0)) {
            val got = (pixel ushr shift) and 0xFF
            val low = minOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF)
            val high = maxOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF) + GLOW
            assertTrue("$name channel $shift was $got, outside $low..$high", got in low..high)
        }
        assertEquals("opaque", 0xFF, (pixel ushr 24) and 0xFF)
    }

    /**
     * A pixel of the colour under a 64 dp avatar for [name], with no photo and no mini thumbnail:
     * low in the circle, below the letter rather than through it.
     */
    private fun centre(name: String, dark: Boolean = true): Int {
        val png = ImageComposeScene(SIDE, SIDE, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Box { ChatAvatar(miniThumbnail = null, photoFileId = 0, title = name, size = SIDE.dp) }
            }
        }.use { scene ->
            scene.render(0L)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        return bitmap.getColor(SIDE / 2, SIDE - 10)
    }

    private companion object {
        const val SIDE = 64

        /** How much white the corner light can add to a channel: its alpha over 255, rounded up. */
        const val GLOW = 26

        const val FOREST = "Harbour Notes"
        const val OLIVE = "Quiet Reels"
    }
}
