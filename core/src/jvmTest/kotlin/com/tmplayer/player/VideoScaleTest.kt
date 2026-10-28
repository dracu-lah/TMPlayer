package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScaleTest {

    @Test
    fun `the cycle runs through every shape and comes back to fit`() {
        assertEquals(VideoScale.Crop, VideoScale.Fit.next())
        assertEquals(VideoScale.Stretch, VideoScale.Crop.next())
        assertEquals(VideoScale.Ratio16x9, VideoScale.Stretch.next())
        assertEquals(VideoScale.Fit, VideoScale.Square.next())
        assertEquals(8, VideoScale.entries.size)
    }

    @Test
    fun `saved names still load and a fixed shape is not carried to a new player`() {
        assertEquals(VideoScale.Crop, VideoScale.from("Crop"))
        assertEquals(VideoScale.Fit, VideoScale.from("nonsense"))
        assertEquals(VideoScale.Stretch, VideoScale.remembered("Stretch"))
        assertEquals(VideoScale.Fit, VideoScale.remembered("Ratio4x3"))
    }

    @Test
    fun `a pinch steps the first three only`() {
        assertEquals(VideoScale.Crop, VideoScale.Fit.pinch(wider = true))
        assertEquals(VideoScale.Stretch, VideoScale.Stretch.pinch(wider = true))
        assertEquals(VideoScale.Fit, VideoScale.Fit.pinch(wider = false))
        assertEquals(VideoScale.Crop, VideoScale.Ratio21x9.pinch(wider = true))
    }

    @Test
    fun `the resize modes are the ones Media3 understands`() {
        // RESIZE_MODE_FIT, RESIZE_MODE_ZOOM and RESIZE_MODE_FILL respectively.
        assertEquals(0, VideoScale.Fit.resizeMode)
        assertEquals(4, VideoScale.Crop.resizeMode)
        assertEquals(3, VideoScale.Stretch.resizeMode)
    }

    @Test
    fun `each shape sizes the picture inside a 16 by 9 screen`() {
        // A 2.39:1 film on a 1920 by 1080 screen.
        assertEquals(1920 to 803, VideoScale.Fit.pictureSize(1920, 1080, 2.39))
        assertEquals(2581 to 1080, VideoScale.Crop.pictureSize(1920, 1080, 2.39))
        assertEquals(1920 to 1080, VideoScale.Stretch.pictureSize(1920, 1080, 2.39))
        // A fixed shape ignores the picture's own aspect.
        assertEquals(1440 to 1080, VideoScale.Ratio4x3.pictureSize(1920, 1080, 2.39))
        assertEquals(1920 to 1080, VideoScale.Ratio16x9.pictureSize(1920, 1080, 2.39))
        assertEquals(1920 to 817, VideoScale.Ratio235.pictureSize(1920, 1080, 1.78))
        assertEquals(1080 to 1080, VideoScale.Square.pictureSize(1920, 1080, 1.78))
        // A 4:3 picture filled across a wide screen loses its top and bottom.
        assertEquals(1920 to 1440, VideoScale.Crop.pictureSize(1920, 1080, 4.0 / 3.0))
    }

    @Test
    fun `an unknown aspect or an empty box moves nothing`() {
        assertEquals(1920 to 1080, VideoScale.Fit.pictureSize(1920, 1080, 0.0))
        assertEquals(1920 to 1080, VideoScale.Crop.pictureSize(1920, 1080, Double.NaN))
        assertEquals(0 to 0, VideoScale.Fit.pictureSize(0, 1080, 1.78))
        assertEquals(1.0 to 1.0, VideoScale.Fit.boxShare(0, 0, 1.78))
    }

    @Test
    fun `a share of the box says what a VR eye covers in its half`() {
        // One 16:9 eye in a 960 by 1080 half: full width, about half the height.
        val (across, down) = VideoScale.Fit.boxShare(960, 1080, 16.0 / 9.0)
        assertEquals(1.0, across, 0.0)
        assertEquals(0.5, down, 0.01)
        assertEquals(1.0 to 1.0, VideoScale.Stretch.boxShare(960, 1080, 16.0 / 9.0))
        val (cropAcross, cropDown) = VideoScale.Crop.boxShare(960, 1080, 16.0 / 9.0)
        assertEquals(2.0, cropAcross, 0.01)
        assertEquals(1.0, cropDown, 0.0)
    }
}
