package com.tmplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus recipe per role (WP-67) held to numbers, with nothing drawn: in Light, Dark and Pure
 * black, on the television's plain focus and with High contrast focus, for every accent, what a
 * role draws when focused stands out from the page it rests on by 3:1 (the figures are
 * [FocusContrast]'s), the selected look is a different thing from the focus fill and shows at the
 * same time as it, and the button and row roles do not scale.
 *
 * One indicator per control (user, 2026-11-25): a role that fills shows the fill alone and draws a
 * ring only under High contrast, so the television's daylight fill is the accent itself and has to
 * stand 3:1 off the page by itself.
 */
class FocusRecipeTest {

    /** The widest TV tile art (Series' episode art and the guide's art are both 208 dp; no shared token exists). */
    private val TILE_DP = 208f

    private data class Mode(val name: String, val dark: Boolean, val black: Boolean)

    private val modes = listOf(Mode("light", false, false), Mode("dark", true, false), Mode("black", true, true))

    private val styles = listOf("tv" to FocusStyle.tv(highContrast = false), "high contrast" to FocusStyle.tv(highContrast = true))

    private fun each(check: (String, FocusRole, ColorScheme, Boolean, FocusStyle) -> Unit) {
        for (accent in Accent.entries) for (mode in modes) for ((styleName, style) in styles) for (role in FocusRole.entries) {
            check("${role.name} ${accent.name} ${mode.name} $styleName", role, TmSchemes.of(mode.dark, mode.black, accent), mode.dark, style)
        }
    }

    private fun surfaces(s: ColorScheme) = listOf(s.background, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh)

    private fun atLeast(what: String, got: Float) =
        assertTrue("$what: $got, wanted ${FocusContrast.Minimum}", got >= FocusContrast.Minimum)

    /** The best of what the role draws when focused, against one surface it rests on. */
    private fun focusedEdge(role: FocusRole, s: ColorScheme, dark: Boolean, style: FocusStyle, surface: Color): Float {
        val r = role.recipe
        var best = 0f
        if (r.ringShown(style, dark)) best = maxOf(best, FocusContrast.againstSurface(FocusPalette.ringFor(role, s, dark), surface))
        if (r.fills) best = maxOf(best, FocusContrast.againstSurface(FocusPalette.fill(s, dark, style.tv), surface))
        return best
    }

    @Test
    fun `focused and rest frames differ by 3 to 1 at the edge, in every role, theme and focus style`() = each { name, role, s, dark, style ->
        for (surface in surfaces(s)) {
            val got = focusedEdge(role, s, dark, style, surface)
            atLeast("$name against a surface", got)
        }
    }

    @Test
    fun `a ring on a fill stands out from the fill`() = each { name, role, s, dark, style ->
        if (role.recipe.fills && role.recipe.ringShown(style, dark)) {
            // The ring or, with High contrast, its keyline next to the fill.
            val onFill = maxOf(
                FocusContrast.againstSurface(FocusPalette.ring(s, dark), FocusPalette.fill(s, dark, style.tv)),
                FocusContrast.againstSurface(FocusInk.inner(dark), FocusPalette.fill(s, dark, style.tv)),
            )
            atLeast("$name ring on the fill", onFill)
        }
    }

    @Test
    fun `the ink on the fill reads on it`() = each { name, role, s, dark, style ->
        if (role.recipe.fills) atLeast("$name ink on the fill", FocusContrast.againstSurface(FocusPalette.onFill(s, dark, style.tv), FocusPalette.fill(s, dark, style.tv)))
    }

    @Test
    fun `the selected look differs from rest and from the focus fill, and shows with it`() = each { name, role, s, dark, style ->
        val r = role.recipe
        if (r.selected == SelectedLook.None) return@each
        // Differs from the rest frame: the accent mark stands out from the page.
        for (surface in surfaces(s)) atLeast("$name selected mark on a surface", FocusContrast.againstSurface(FocusPalette.selectedMark(s, dark, focused = false), surface))
        // Differs from the focused frame in what is drawn: no fill and no ring, a mark of its own.
        assertFalse("$name selected must not use the focus fill", r.selected.usesFill)
        assertFalse("$name selected must not use the focus ring", r.selected.usesRing)
        if (r.selected != SelectedLook.Tick) assertTrue("$name selected mark has a thickness", r.selected.mark > 0.dp)
        // Both at once: the mark still shows on the fill.
        if (r.fills) atLeast("$name selected mark on the fill", FocusContrast.againstSurface(FocusPalette.selectedMark(s, dark, focused = true, tv = style.tv), FocusPalette.fill(s, dark, style.tv)))
    }

    @Test
    fun `button and row and rail roles have scale 1, tile and avatar grow, nothing else does`() {
        for ((name, style) in styles) {
            for (role in listOf(FocusRole.Button, FocusRole.Row, FocusRole.RailItem, FocusRole.BarPill, FocusRole.BrandButton)) {
                assertEquals("$name ${role.name}", 1f, role.recipe.scale(style), 0f)
            }
            assertEquals(FocusStyle.TvScale, FocusRole.Tile.recipe.scale(style), 0f)
            assertEquals(FocusStyle.TvScale, FocusRole.Avatar.recipe.scale(style), 0f)
        }
        // The phone and the desktop keep what they had.
        assertEquals(FocusStyle.Standard.scale, FocusRole.Tile.recipe.scale(FocusStyle.Standard), 0f)
        assertEquals(1f, FocusRole.Button.recipe.scale(FocusStyle.Standard), 0f)
    }

    @Test
    fun `a grown tile stays inside the reserved clearance`() {
        for ((name, style) in styles) {
            val grown = TILE_DP * (FocusRole.Tile.recipe.scale(style) - 1f) / 2f
            assertTrue("$name: grown $grown vs clearance", grown <= Tv.FocusClearance.value)
        }
    }

    @Test
    fun `the user's pill rule stands, the bar pill shows no ring unless High contrast is on`() {
        val plain = FocusStyle.tv(highContrast = false)
        val high = FocusStyle.tv(highContrast = true)
        for (dark in listOf(true, false)) {
            assertFalse(FocusRole.BarPill.recipe.ringShown(plain, dark))
            assertTrue(FocusRole.BarPill.recipe.ringShown(high, dark))
            assertFalse("phone and desktop add nothing", FocusRole.BarPill.recipe.ringShown(FocusStyle.Standard, dark))
        }
    }

    @Test
    fun `a button, a row and a rail item draw the fill alone, a ring only under High contrast`() {
        val plain = FocusStyle.tv(highContrast = false)
        val high = FocusStyle.tv(highContrast = true)
        for (role in listOf(FocusRole.Button, FocusRole.Row, FocusRole.RailItem, FocusRole.BarPill)) {
            for (dark in listOf(true, false)) {
                assertFalse("${role.name} plain, dark=$dark", role.recipe.ringShown(plain, dark))
                assertTrue("${role.name} high contrast, dark=$dark", role.recipe.ringShown(high, dark))
                assertFalse("${role.name} on the phone", role.recipe.ringShown(FocusStyle.Standard, dark))
            }
        }
    }

    @Test
    fun `one indicator per control, a fill never comes with a ring unless High contrast asks for one`() {
        for ((name, style) in styles) for (dark in listOf(true, false)) for (role in FocusRole.entries) {
            val r = role.recipe
            if (r.fills && r.ringShown(style, dark)) assertTrue("$name ${role.name}: a fill and a ring", style.highContrast)
        }
    }

    @Test
    fun `a television's ring and edge are 2 dp at most and a tile grows 1_04 at most`() {
        val plain = FocusStyle.tv(highContrast = false)
        assertEquals(2.dp, plain.ring)
        assertEquals(2.dp, plain.edge)
        assertTrue(plain.scale <= 1.04f)
    }

    @Test
    fun `the avatar, the brand button and the rail carry their numbers`() {
        assertEquals(48.dp, FocusRole.Avatar.recipe.rect)
        // No gap ring (user, 2026-11-25): the brand button's ring is flush with its edge.
        assertEquals(0.dp, FocusRole.BrandButton.recipe.ringOffset)
        for ((_, style) in styles) assertEquals(0.dp, FocusRole.BrandButton.recipe.offset(style))
        assertEquals(SelectedLook.None, FocusRole.BrandButton.recipe.selected)
        assertEquals(SelectedLook.AccentBar, FocusRole.RailItem.recipe.selected)
        assertEquals(3.dp, FocusRecipe.RailBar)
        assertTrue("the rail's selected label is SemiBold", FocusRole.RailItem.recipe.selected.semiBold)
        assertEquals(3.dp, FocusRole.BarPill.recipe.selected.mark)
        assertFalse(SelectedLook.Tick.semiBold)
        assertEquals(SelectedLook.Indicator, FocusRole.BarPill.recipe.selected)
    }

    @Test
    fun `the rail bar mirrors in a right to left layout`() {
        val r = FocusRole.RailItem.recipe
        assertTrue(r.barOnLeft(LayoutDirection.Ltr))
        assertFalse(r.barOnLeft(LayoutDirection.Rtl))
    }

    @Test
    fun `the palette is the one Tone reads`() {
        // Tone.focusFill, Tone.onFocusFill and focusRingColor call FocusPalette (one source). Fill is primary in the dark theme and
        // on a television, primaryContainer in daylight on the phone; the ring is the ink on the fill in the dark and the accent in daylight.
        val s = TmSchemes.of(dark = false, black = false, Accent.entries.first())
        assertEquals(s.primaryContainer, FocusPalette.fill(s, dark = false))
        assertEquals(s.primary, FocusPalette.fill(s, dark = false, tv = true))
        assertEquals(s.onPrimary, FocusPalette.onFill(s, dark = false, tv = true))
        assertEquals(s.primary, FocusPalette.ring(s, dark = false))
        val d = TmSchemes.of(dark = true, black = false, Accent.entries.first())
        assertEquals(d.primary, FocusPalette.fill(d, dark = true))
        assertEquals(d.onPrimary, FocusPalette.ring(d, dark = true))
    }
}
