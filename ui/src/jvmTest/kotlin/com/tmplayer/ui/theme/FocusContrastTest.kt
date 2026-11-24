package com.tmplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television's focus (T8, EG-F22) held to numbers, with no drawing: in light, dark and Pure
 * black, for every accent, the ring stands out from the page by 3:1, the high contrast line is the
 * ink that stands out from the page, and against the worst pixel of artwork (white or black) the
 * keyline or the ring still does. The figures are [FocusContrast]'s, the same functions the
 * draw code takes its inks from.
 */
class FocusContrastTest {

    private data class Mode(val name: String, val dark: Boolean, val black: Boolean)

    private val modes = listOf(Mode("light", false, false), Mode("dark", true, false), Mode("black", true, true))

    private fun each(check: (String, ColorScheme, Boolean) -> Unit) {
        for (accent in Accent.entries) {
            for (mode in modes) check("${accent.name} ${mode.name}", TmSchemes.of(mode.dark, mode.black, accent), mode.dark)
        }
    }

    private fun surfaces(s: ColorScheme) = listOf(
        "background" to s.background,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
    )

    private fun atLeast(what: String, got: Float) =
        assertTrue("$what: $got, wanted ${FocusContrast.Minimum}", got >= FocusContrast.Minimum)

    @Test
    fun `the accent ring stands out from every surface by 3 to 1`() = each { name, s, _ ->
        for ((on, colour) in surfaces(s)) atLeast("$name ring on $on", FocusContrast.againstSurface(s.primary, colour))
    }

    @Test
    fun `the outer line is the ink that stands out from every surface`() = each { name, s, dark ->
        for ((on, colour) in surfaces(s)) atLeast("$name outer line on $on", FocusContrast.againstSurface(FocusInk.outer(dark), colour))
        assertEquals(if (dark) Color.White else Color.Black, FocusInk.outer(dark))
    }

    @Test
    fun `the ring on a row that fills under focus stands out from its fill`() = each { name, s, dark ->
        val fill = if (dark) s.primary else s.primaryContainer
        val ink = if (dark) s.onPrimary else s.onPrimaryContainer
        atLeast("$name ring on the focus fill", FocusContrast.againstSurface(ink, fill))
        // And the line outside it, which sits on the page, not on the fill.
        atLeast("$name outer line against the fill's page", FocusContrast.againstSurface(FocusInk.outer(dark), s.background))
    }

    @Test
    fun `on a pale poster the keyline or the ring stands out, on a black one too`() = each { name, s, dark ->
        atLeast("$name against white art", FocusContrast.againstArt(Color.White, s.primary, dark))
        atLeast("$name against black art", FocusContrast.againstArt(Color.Black, s.primary, dark))
        atLeast("$name worst art", FocusContrast.worstAgainstArt(s.primary, dark))
    }

    @Test
    fun `the contrasting line stands out from bright art in daylight and its keyline in the dark`() {
        atLeast("light: black outer line on white art", FocusContrast.againstSurface(FocusInk.outer(false), Color.White))
        atLeast("dark: black keyline on white art", FocusContrast.againstSurface(FocusInk.inner(true), Color.White))
        // The two inks are opposites, so a bright pixel has the dark one beside it in either theme.
        for (dark in listOf(true, false)) atLeast("inks of a $dark theme", FocusContrast.againstSurface(FocusInk.outer(dark), FocusInk.inner(dark)))
    }

    @Test
    fun `a control that rests in the accent shows focus with a ring in the on-accent ink that stands 3 to 1 off the accent`() = each { name, s, dark ->
        // On a television the focus fill IS the accent, so a fill swap shows nothing: the premise of focusFillOver.
        assertEquals("$name tv focus fill is the accent", s.primary, FocusPalette.fill(s, dark, tv = true))
        atLeast("$name on-accent ring on the accent", FocusContrast.againstSurface(s.onPrimary, s.primary))
    }

    @Test
    fun `the television's focus is thin (2 dp, 1_04) and high contrast adds to it`() {
        val phone = FocusStyle.Standard
        val tv = FocusStyle.tv(highContrast = false)
        val high = FocusStyle.tv(highContrast = true)
        assertEquals(2.dp, phone.ring)
        assertEquals(3.dp, phone.edge)
        assertEquals(1.05f, phone.scale, 0f)
        assertTrue(!phone.tv && !phone.highContrast)
        assertTrue(tv.ring <= phone.ring && tv.edge < phone.edge && tv.scale <= 1.04f)
        assertEquals(2.dp, tv.ring)
        assertEquals(2.dp, tv.edge)
        assertTrue(tv.tv && !tv.highContrast)
        assertTrue(high.ring > tv.ring && high.edge > tv.edge && high.highContrast && high.tv)
        assertTrue(high.outer > 0.dp && high.inner > 0.dp && high.outer >= high.inner)
        assertEquals(tv.scale, high.scale, 0f)
        // Room a row leaves round a tile: 208 dp grown to the scale is under Tv.FocusClearance a side.
        assertTrue("the grown tile overshoots its clearance", 208f * (tv.scale - 1f) / 2f <= Tv.FocusClearance.value)
    }
}
