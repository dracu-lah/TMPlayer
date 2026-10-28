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
}
