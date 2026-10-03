package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScaleTest {

    @Test
    fun `the cycle is three stops long and comes back to fit`() {
        assertEquals(VideoScale.Crop, VideoScale.Fit.next())
        assertEquals(VideoScale.Stretch, VideoScale.Crop.next())
        assertEquals(VideoScale.Fit, VideoScale.Stretch.next())
    }

    @Test
    fun `the resize modes are the ones Media3 understands`() {
        // RESIZE_MODE_FIT, RESIZE_MODE_ZOOM and RESIZE_MODE_FILL respectively.
        assertEquals(0, VideoScale.Fit.resizeMode)
        assertEquals(4, VideoScale.Crop.resizeMode)
        assertEquals(3, VideoScale.Stretch.resizeMode)
    }
}
