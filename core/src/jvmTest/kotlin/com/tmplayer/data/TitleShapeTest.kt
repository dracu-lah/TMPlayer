package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleShapeTest {

    @Test
    fun `letters go by case and digits to nine`() {
        assertEquals("Aa Aa", TitleShape.of("Night Train"))
        assertEquals("A9A9", TitleShape.of("S01E02"))
        assertEquals("9a", TitleShape.of("720p"))
        assertEquals("AaAa9A9A", TitleShape.of("XEjHWs39Z6A"))
    }

    @Test
    fun `separators are kept and their runs collapse`() {
        assertEquals("@A_Aa_Aa_9.a9", TitleShape.of("@CC_Night_Train_2024.mp4"))
        assertEquals("_@A_Aa", TitleShape.of("_@CC__Night"))
        assertEquals("Aa (9) [9a]", TitleShape.of("Night  (2024)   [1080p]"))
        // Any white space is a plain space.
        assertEquals("Aa Aa", TitleShape.of("Night \t Train"))
    }

    @Test
    fun `two names stamped the same way share a shape`() {
        assertEquals(
            TitleShape.of("@CC_Left_Right_Left.mp4"),
            TitleShape.of("@CC_Honey_Bee_Celebrations.mp4"),
        )
        assertEquals(TitleShape.of("Kanguva 20241080p"), TitleShape.of("Amber 19991080p"))
    }

    @Test
    fun `a script without case is x and a digit of any script is nine`() {
        assertEquals("x", TitleShape.of("മിഥുനം"))
        assertEquals("Aa x", TitleShape.of("Mithunam മിഥുനം"))
        assertEquals("9", TitleShape.of("١٢٣"))
        assertEquals("9", TitleShape.of("१२"))
        assertEquals("9", TitleShape.of("１２"))
    }

    @Test
    fun `a character outside the basic plane is a star`() {
        // Mathematical monospace "AsH", an emoji, and a lone surrogate.
        assertEquals("* Aa", TitleShape.of("𝙰𝚜𝙷 Hitler"))
        assertEquals("*", TitleShape.of("😀😀"))
        assertEquals("a*a", TitleShape.of("a\uD800b"))
    }

    @Test
    fun `empty and huge names are handled`() {
        assertEquals("", TitleShape.of(""))
        assertEquals("", TitleShape.of("\u0000"))
        val huge = "Ab-".repeat(100_000)
        assertTrue(TitleShape.of(huge).length <= TitleShape.MAX_LENGTH)
        assertEquals(TitleShape.MAX_LENGTH, TitleShape.of("a-".repeat(1_000)).length)
        assertEquals("a", TitleShape.of("a".repeat(500_000)))
    }
}
