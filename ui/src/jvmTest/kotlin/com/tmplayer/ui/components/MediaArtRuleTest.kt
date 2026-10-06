package com.tmplayer.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule for a picture that is not its frame's shape: crop, unless the crop hides too much. */
class MediaArtRuleTest {

    private val wide = 16f / 9f

    @Test
    fun `a landscape frame crops into a 16 by 9 tile`() {
        assertFalse(letterboxes(1920, 1080, wide))
        assertFalse(letterboxes(1440, 1080, wide)) // 4:3 loses a quarter, still crops
        assertFalse(letterboxes(2560, 1080, wide)) // wider than the tile always crops
    }

    @Test
    fun `an upright or square picture is shown whole`() {
        assertTrue(letterboxes(1080, 1920, wide))
        assertTrue(letterboxes(1080, 1080, wide))
    }

    @Test
    fun `a square frame crops a square picture, and an unmeasured frame never letterboxes`() {
        assertFalse(letterboxes(640, 640, 1f))
        assertFalse(letterboxes(1080, 1920, 0f))
        assertFalse(letterboxes(0, 0, wide))
    }
}
