package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureZoomTest {

    /** A 16:9 picture filling a 1920 by 1080 screen. */
    private val wide = PictureZoom.Frame(1920f, 1080f, 1920f, 1080f)

    /** The same picture on a phone held upright: a band across the middle of a tall screen. */
    private val upright = PictureZoom.Frame(1080f, 2340f, 1080f, 607.5f)

    @Test
    fun `a pinch around the centre grows the picture in place`() {
        val zoom = PictureZoom.NONE.pinch(1.5f, 960f, 540f, wide)
        assertEquals(1.5f, zoom.scale, 0.0001f)
        assertEquals(0f, zoom.panX, 0.0001f)
        assertEquals(0f, zoom.panY, 0.0001f)
        assertEquals(150, zoom.percent)
        assertTrue(zoom.zoomed)
    }

    @Test
    fun `the point under the fingers stays under them`() {
        // Pinched out around the top left quarter's centre.
        val zoom = PictureZoom.NONE.pinch(2f, 480f, 270f, wide)
        // The picture point at (480, 270) is (-480, -270) from the centre; at 2x it is drawn at
        // pan + 2 * (-480, -270), which has to be the focus again, (-480, -270) from the centre.
        assertEquals(480f, zoom.panX, 0.001f)
        assertEquals(270f, zoom.panY, 0.001f)
        val drawnX = zoom.panX + zoom.scale * -480f
        assertEquals(-480f, drawnX, 0.001f)
    }

    @Test
    fun `scale is held between 100 and 400 per cent`() {
        assertEquals(4f, PictureZoom.NONE.pinch(10f, 960f, 540f, wide).scale, 0.0001f)
        assertEquals(1f, PictureZoom.NONE.pinch(0.2f, 960f, 540f, wide).scale, 0.0001f)
        assertFalse(PictureZoom.NONE.pinch(0.2f, 960f, 540f, wide).zoomed)
    }

    @Test
    fun `the picture's edge never comes inside the screen's edge`() {
        val zoom = PictureZoom(2f).panBy(5000f, -5000f, wide)
        // At 2x the picture is 3840 by 2160: half the overflow is the reach.
        assertEquals(960f, zoom.panX, 0.001f)
        assertEquals(-540f, zoom.panY, 0.001f)
    }

    @Test
    fun `a side narrower than the screen stays centred`() {
        // Upright, 1.5x makes the band 1620 wide (overflowing) but only 911 tall (still inside).
        val zoom = PictureZoom.NONE.pinch(1.5f, 100f, 300f, upright).panBy(0f, 400f, upright)
        assertEquals(0f, zoom.panY, 0.0001f)
        assertTrue(zoom.panX > 0f)
        assertTrue(zoom.panX <= (1.5f * 1080f - 1080f) / 2f + 0.001f)
    }

    @Test
    fun `a drag at 100 per cent moves nothing`() {
        assertEquals(PictureZoom.NONE, PictureZoom.NONE.panBy(300f, 300f, wide))
    }

    @Test
    fun `just over 100 per cent settles on 100`() {
        assertEquals(PictureZoom.NONE, PictureZoom(1.04f, 10f, 5f).settled(wide))
        assertEquals(1.06f, PictureZoom(1.06f).settled(wide).scale, 0.0001f)
    }

    @Test
    fun `turning the phone keeps the scale and pulls the pan back inside`() {
        val zoom = PictureZoom(2f, 900f, 500f).clampedTo(wide)
        val turned = zoom.clampedTo(upright)
        assertEquals(2f, turned.scale, 0.0001f)
        // Upright the 2x picture is 2160 wide (reach 540) and 1215 tall (inside 2340: centred).
        assertEquals(540f, turned.panX, 0.001f)
        assertEquals(0f, turned.panY, 0.0001f)
    }

    @Test
    fun `nothing to measure is nothing to zoom`() {
        val empty = PictureZoom.Frame(0f, 1080f, 1920f, 1080f)
        assertEquals(PictureZoom.NONE, PictureZoom(2f, 100f, 100f).clampedTo(empty))
        assertEquals(PictureZoom.NONE, PictureZoom.NONE.pinch(2f, 0f, 0f, empty))
        val nan = PictureZoom.Frame(Float.NaN, 1080f, 1920f, 1080f)
        assertEquals(PictureZoom.NONE, PictureZoom(2f).clampedTo(nan))
        assertEquals(PictureZoom.NONE, PictureZoom.NONE.pinch(Float.NaN, 960f, 540f, wide))
        assertEquals(PictureZoom.NONE, PictureZoom.NONE.pinch(-1f, 960f, 540f, wide))
        assertEquals(PictureZoom.NONE, PictureZoom(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY).clampedTo(wide))
    }

    @Test
    fun `steps double the picture in four and settle on the way back`() {
        var zoom = PictureZoom.NONE
        repeat(4) { zoom = zoom.stepped(1f, 960f, 540f, wide) }
        assertEquals(2f, zoom.scale, 0.001f)
        repeat(4) { zoom = zoom.stepped(-1f, 960f, 540f, wide) }
        assertEquals(PictureZoom.NONE, zoom)
        // A wheel that reports a wild turn moves eight steps at most.
        assertEquals(4f, PictureZoom.NONE.stepped(1000f, 960f, 540f, wide).scale, 0.001f)
    }

    @Test
    fun `mpv gets a power of two and pans in the enlarged picture's size`() {
        assertEquals(0.0, PictureZoom.NONE.mpvZoom, 1e-9)
        assertEquals(1.0, PictureZoom(2f).mpvZoom, 1e-6)
        assertEquals(2.0, PictureZoom(4f).mpvZoom, 1e-6)
        val (x, y) = PictureZoom(2f, 960f, -540f).mpvPan(wide)
        // 960 px of a 3840 px wide picture is a quarter.
        assertEquals(0.25, x, 1e-6)
        assertEquals(-0.25, y, 1e-6)
        assertEquals(0.0 to 0.0, PictureZoom.NONE.mpvPan(wide))
    }
}
