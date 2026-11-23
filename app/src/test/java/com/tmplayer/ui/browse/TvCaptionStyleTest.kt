package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.TvTypeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A television's tile captions are at least 14 sp. TileCaption no longer raises a small style
 * itself (WP-04): the television's type scale carries the floor, so this now tests the scale for
 * the two styles a caption is set in (labelMedium for a wide tile, labelSmall for a poster).
 */
class TvCaptionStyleTest {

    @Test
    fun captionStylesAreAtLeast14SpOnTheTelevisionsScale() {
        val tv = TvTypeScale.tvTypography()
        val m3 = TvTypeScale.tvM3Typography()
        for (style in listOf(tv.labelMedium, tv.labelSmall, m3.labelMedium, m3.labelSmall)) {
            assertTrue("a caption of ${style.fontSize.value} sp", style.fontSize.value >= 14f)
        }
        assertEquals(14f, TvTypeScale.FLOOR_SP, 0f)
    }
}
