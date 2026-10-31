package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameColourTest {

    @Test
    fun theSameNameAlwaysWearsTheSamePair() {
        assertEquals(NameColour.indexOf("Mallu Movies"), NameColour.indexOf("Mallu Movies"))
        assertEquals(NameColour.pairOf("DiccoBot"), NameColour.pairOf("DiccoBot"))
    }

    @Test
    fun caseAndSpacingDoNotChangeIt() {
        val one = NameColour.indexOf("Mallu Movies")
        assertEquals(one, NameColour.indexOf("mallu movies"))
        assertEquals(one, NameColour.indexOf("MALLU MOVIES"))
        assertEquals(one, NameColour.indexOf("  Mallu   Movies  "))
        assertEquals(one, NameColour.indexOf("Mallu\tMovies"))
    }

    @Test
    fun everyNameGetsAPairAndNoneIsOutOfRange() {
        val names = listOf(null, "", "   ", "🎬🍿", "A", "മലയാളം സിനിമ", "فیلم‌ها", "日本語のチャット")
        for (name in names) {
            val index = NameColour.indexOf(name)
            assertTrue(index in NameColour.Pairs.indices, "$name gave $index")
            assertEquals(NameColour.Pairs[index], NameColour.pairOf(name))
        }
    }

    /**
     * The table the web app's `webapp/src/ui/colour.test.ts` asserts as well: the two hashes have
     * to agree name for name, or a chat is one colour in the apps and another in the browser.
     */
    @Test
    fun theWebAppsHashAgreesNameForName() {
        val expected = mapOf(
            "" to 1,
            "Mallu Movies" to 1,
            "Add-on Database: MSB" to 6,
            "DiccoBot" to 7,
            "Saved Messages" to 5,
            "മലയാളം സിനിമ" to 4,
            "فیلم‌ها" to 0,
            "10bitclub" to 5,
            "Zeyrox Movies Official" to 7,
            "Nevil" to 7,
            "🎬🎬" to 3,
            "A" to 0,
            "B" to 3,
            "C" to 7,
            "Films & Shows" to 0,
            "TMPlayer" to 1,
            "日本語のチャット" to 0,
        )
        for ((name, index) in expected) assertEquals(index, NameColour.indexOf(name), name)
    }

    @Test
    fun aLargeAccountsNamesFillThePalette() {
        val counts = IntArray(NameColour.Pairs.size)
        for (i in 0 until 4_000) counts[NameColour.indexOf("Chat $i name")]++
        // An even spread would be 500 each; nothing worse than a third off it, so no palette
        // colour is rare and none is most of a 3,000 chat list.
        for ((index, count) in counts.withIndex()) {
            assertTrue(count in 333..666, "pair $index got $count of 4000")
        }
    }

    @Test
    fun thePaletteIsTheEightPairsTheCoversWear() {
        assertEquals(8, NameColour.Pairs.size)
        assertEquals(0xFF34397A.toInt() to 0xFF292D64.toInt(), NameColour.Pairs.first())
        // Every stop opaque: the colour stands in for a picture, never lets the surface through.
        for ((light, deep) in NameColour.Pairs) {
            assertEquals(0xFF, (light ushr 24) and 0xFF)
            assertEquals(0xFF, (deep ushr 24) and 0xFF)
        }
    }
}
