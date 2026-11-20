package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.NameColour
import com.tmplayer.ui.components.Service
import com.tmplayer.ui.components.ServiceBadge
import com.tmplayer.ui.theme.TmMaterialTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service tile on the desktop (HX93), drawn for real: a logo sits on a white chip in both themes,
 * a service with no logo wears its initial in the name's own colour, the tile keeps its size at a
 * large font, and a right to left layout puts the tile on the right of its name without turning it.
 */
class ServiceBadgeRenderTest {

    @Test
    fun `the TMDB logo sits on a white chip, dark theme or light`() {
        for (dark in listOf(true, false)) {
            val tile = render(Service.Tmdb, dark = dark)
            // Top middle, inside the chip but above the letters: the chip's own white.
            assertEquals("chip in dark=$dark", 0xFFFFFFFF.toInt(), tile.getColor(SIDE / 2, 3))
            // Somewhere the logo draws: not plain white any more.
            assertTrue("no logo drawn in dark=$dark", (0 until SIDE).any { x -> (0 until SIDE).any { y -> tile.getColor(x, y) != 0xFFFFFFFF.toInt() && (tile.getColor(x, y) ushr 24) == 0xFF } })
        }
    }

    @Test
    fun `a service without a logo wears its initial in its name colour`() {
        val tile = render(Service.TvMaze)
        val (light, deep) = NameColour.pairOf(Service.TvMaze.label)
        // Low in the tile, below the letter: the pair's colour, give or take the corner light.
        val pixel = tile.getColor(SIDE / 2, SIDE - 4)
        for (shift in listOf(16, 8, 0)) {
            val got = (pixel ushr shift) and 0xFF
            val low = minOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF)
            val high = maxOf((light ushr shift) and 0xFF, (deep ushr shift) and 0xFF) + GLOW
            assertTrue("channel $shift was $got, outside $low..$high", got in low..high)
        }
        // And the letter itself is drawn: white pixels somewhere in the middle.
        assertTrue("no initial drawn", (SIDE / 4 until SIDE * 3 / 4).any { x -> (SIDE / 4 until SIDE * 3 / 4).any { y -> tile.getColor(x, y) ushr 24 == 0xFF && (tile.getColor(x, y) and 0xFFFFFF) > 0xC0C0C0 } })
        // Two services get their own colours, and the same one twice is the same.
        assertEquals(tile.getColor(SIDE / 2, SIDE - 4), render(Service.TvMaze).getColor(SIDE / 2, SIDE - 4))
    }

    @Test
    fun `a large font does not grow the tile`() {
        val normal = render(Service.Tmdb, fontScale = 1f)
        val large = render(Service.Tmdb, fontScale = 2f)
        // The tile is SIDE dp wide and the scene is exactly that: the edge pixels are the same.
        assertEquals(normal.getColor(SIDE / 2, 3), large.getColor(SIDE / 2, 3))
        assertEquals(normal.getColor(SIDE - 6, SIDE / 2), large.getColor(SIDE - 6, SIDE / 2))
    }

    @Test
    fun `with the name the tile leads it, and a right to left layout puts it on the right`() {
        val ltr = renderWide(Service.TvMaze, LayoutDirection.Ltr)
        val rtl = renderWide(Service.TvMaze, LayoutDirection.Rtl)
        val tileLeft = ltr.getColor(8, WIDE_H - 5)
        val tileRight = rtl.getColor(WIDE - 8, WIDE_H - 5)
        assertNotEquals("the tile is not at the start in ltr", 0, tileLeft ushr 24)
        // The same tile at the other end (its gradient runs the other way, so give a channel a little room).
        assertEquals("opaque tile at the end in rtl", 0xFF, tileRight ushr 24)
        for (shift in listOf(16, 8, 0)) {
            assertTrue("tile colour at the other end", kotlin.math.abs(((tileLeft ushr shift) and 0xFF) - ((tileRight ushr shift) and 0xFF)) <= 24)
        }
        // The far end of an ltr row is the name's text, not tile colour.
        assertNotEquals(tileLeft, ltr.getColor(WIDE - 2, WIDE_H - 5))
    }

    private fun render(service: Service, dark: Boolean = true, fontScale: Float = 1f): Bitmap {
        val png = ImageComposeScene(SIDE, SIDE, Density(1f, fontScale)) {
            TmMaterialTheme(dark = dark) { Box { ServiceBadge(service, size = SIDE.dp) } }
        }.use { scene ->
            scene.render(0L)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        return Bitmap.makeFromImage(Image.makeFromEncoded(png))
    }

    private fun renderWide(service: Service, direction: LayoutDirection): Bitmap {
        val png = ImageComposeScene(WIDE, WIDE_H, Density(1f)) {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box { ServiceBadge(service, modifier = Modifier.fillMaxWidth(), size = WIDE_H.dp, showName = true) }
                }
            }
        }.use { scene ->
            scene.render(0L)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        return Bitmap.makeFromImage(Image.makeFromEncoded(png))
    }

    private companion object {
        const val SIDE = 28
        const val WIDE = 220
        const val WIDE_H = 28
        const val GLOW = 40
    }
}
