package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CardLayoutTest {

    @Test
    fun `toggling goes both ways`() {
        assertEquals(CardLayout.Grid, CardLayout.List.toggled())
        assertEquals(CardLayout.List, CardLayout.Grid.toggled())
    }

    @Test
    fun `a stored name reads back`() {
        assertEquals(CardLayout.Grid, CardLayout.decode("Grid", CardLayout.List))
        assertEquals(CardLayout.List, CardLayout.decode("List", CardLayout.Grid))
    }

    @Test
    fun `nothing stored means the default for that screen`() {
        // The two screens disagree about what a sensible default is, which is why the fallback is
        // passed in rather than baked into the type.
        assertEquals(CardLayout.List, CardLayout.decode(null, CardLayout.List))
        assertEquals(CardLayout.Grid, CardLayout.decode(null, CardLayout.Grid))
    }

    @Test
    fun `a name from another build is not trusted`() {
        // Preferences outlive app versions: an arrangement that existed in some future build, or a
        // half-written value, must not leave the screen with nothing to draw.
        assertEquals(CardLayout.Grid, CardLayout.decode("Carousel", CardLayout.Grid))
        assertEquals(CardLayout.Grid, CardLayout.decode("", CardLayout.Grid))
        assertEquals(CardLayout.Grid, CardLayout.decode("grid", CardLayout.Grid))
    }
}
