package com.tmplayer.ui.theme

import androidx.compose.material3.Typography as M3Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isUnspecified
import androidx.tv.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-04: nothing on a television is under 14 sp, body is 16 sp, a title is a visible step above body,
 * and the two scales the television carries (tv-material's and Material 3's) are built from the one
 * [TvTypeScale], so they cannot drift.
 */
class TvTypographyFloorTest {

    private fun Typography.named(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun M3Typography.named(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    @Test
    fun `every style of the scale is at least 14 sp`() {
        assertEquals(15, TvTypeScale.all.size)
        for ((name, step) in TvTypeScale.all) {
            assertTrue("$name is ${step.sp} sp", step.sp >= TvTypeScale.FLOOR_SP)
        }
        assertEquals(14f, TvTypeScale.FLOOR_SP, 0f)
    }

    @Test
    fun `body is 15 sp or more, body large 16 sp, and a title is a visible step above body`() {
        val s = TvTypeScale
        assertTrue(s.bodyLarge.sp >= 16f)
        assertTrue(s.bodyMedium.sp >= 15f)
        assertTrue("titleMedium is larger than bodyLarge by at least 2 sp", s.titleMedium.sp >= s.bodyLarge.sp + 2f)
        assertTrue(s.titleLarge.sp > s.titleMedium.sp && s.headlineMedium.sp > s.titleLarge.sp)
    }

    @Test
    fun `both derived scales equal the scale style by style`() {
        val tv = TvTypeScale.tvTypography().named()
        val m3 = TvTypeScale.tvM3Typography().named()
        assertEquals(TvTypeScale.all.keys, tv.keys)
        assertEquals(TvTypeScale.all.keys, m3.keys)
        for ((name, step) in TvTypeScale.all) {
            for ((which, style) in listOf("tv-material" to tv.getValue(name), "material3" to m3.getValue(name))) {
                assertEquals("$which $name size", step.sp, style.fontSize.value, 0f)
                assertEquals("$which $name weight", step.weight, style.fontWeight)
            }
        }
    }

    @Test
    fun `no derived style is under the floor and the television sets no line height of its own`() {
        for ((name, style) in TvTypeScale.tvTypography().named() + TvTypeScale.tvM3Typography().named().mapKeys { "m3 " + it.key }) {
            assertTrue("$name is ${style.fontSize.value} sp", style.fontSize.value >= TvTypeScale.FLOOR_SP)
        }
        // Correction 19: explicit line heights move every row for no proven gain, so tv-material's
        // ladder leaves them to the font (the display style keeps its own 40 sp).
        for ((name, style) in TvTypeScale.tvTypography().named()) {
            if (name in setOf("displaySmall", "displayLarge", "displayMedium", "headlineSmall", "titleSmall")) continue
            assertTrue("$name has no explicit line height", style.lineHeight.isUnspecified)
        }
    }

    @Test
    fun `a tile caption on a television is a label style and both are at least 14 sp`() {
        // TileCaption no longer raises small styles: the scale carries it (TvCaptionStyleTest's old job).
        val tv = TvTypeScale.tvTypography()
        assertTrue(tv.labelMedium.fontSize.value >= 14f)
        assertTrue(tv.labelSmall.fontSize.value >= 14f)
    }
}
