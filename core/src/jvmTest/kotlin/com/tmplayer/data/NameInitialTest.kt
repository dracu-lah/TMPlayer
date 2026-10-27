package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameInitialTest {

    @Test
    fun skipsPunctuationSymbolsAndEmoji() {
        assertEquals("Y", NameInitial.of("(YEDHU)"))
        assertEquals("M", NameInitial.of("  movies hub"))
        assertEquals("F", NameInitial.of("★ Films"))
        assertEquals("C", NameInitial.of("🎬🍿 cinema"))
        assertEquals("4", NameInitial.of("[4K] Club"))
    }

    @Test
    fun keepsTheMarksOnALetter() {
        assertEquals("മോ", NameInitial.of("മോഹൻ"))
        assertEquals("É", NameInitial.of("école"))
    }

    @Test
    fun noLetterGivesNone() {
        assertNull(NameInitial.of("🎬🍿"))
        assertNull(NameInitial.of("***"))
        assertNull(NameInitial.of(""))
        assertNull(NameInitial.of("   "))
        assertNull(NameInitial.of(null))
    }
}
