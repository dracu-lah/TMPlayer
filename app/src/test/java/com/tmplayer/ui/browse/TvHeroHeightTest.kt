package com.tmplayer.ui.browse

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television's Home hero is about 55 percent of the window (user, 2026-11-25: the hero was
 * three quarters of the screen and covered Continue watching), worked out from the window alone.
 */
class TvHeroHeightTest {

    @Test
    fun `at 1080p the heading and the carousel together take under 60 percent of the window`() {
        val window = 540.dp
        val total = TV_BAR_HEIGHT + tvFeaturedHeight(window)
        assertTrue("hero is $total of $window", total.value / window.value < 0.60f)
        // The first row's title and its cards now stand whole under it.
        assertTrue("room under the hero: ${window - total}", (window - total).value >= 200f)
    }

    @Test
    fun `the height never goes under the floor that keeps Play, the name, the facts and the overview`() {
        assertEquals(TV_FEATURED_MIN_HEIGHT, tvFeaturedHeight(300.dp))
        assertEquals(TV_FEATURED_MIN_HEIGHT, tvFeaturedHeight(540.dp))
    }

    @Test
    fun `a very tall window is capped at the old height`() {
        assertEquals(TV_FEATURED_HEIGHT, tvFeaturedHeight(1080.dp))
        assertEquals(TV_FEATURED_HEIGHT, tvFeaturedHeight(2160.dp))
    }

    @Test
    fun `it is a pure function of the window, so it is the same before and after the pages arrive`() {
        assertEquals(tvFeaturedHeight(540.dp), tvFeaturedHeight(540.dp))
    }

    @Test
    fun `a short hero shows two lines of overview and a roomy one three`() {
        assertFalse(tvHeroRoomy(tvFeaturedHeight(540.dp)))
        assertTrue(tvHeroRoomy(TV_FEATURED_HEIGHT))
    }
}
