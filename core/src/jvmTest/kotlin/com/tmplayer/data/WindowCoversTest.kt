package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS's app switcher covers (HX88, review P6 security finding): every window gets one, a window
 * that appears after the first was covered too, uncover takes them all off, a window gone is
 * forgotten.
 */
class WindowCoversTest {

    private val removed = mutableListOf<String>()
    private val covers = WindowCovers<String, String>(make = { "cover of $it" }, remove = { removed += it })

    @Test
    fun `a window that appears after the first was covered is covered too`() {
        covers.cover(listOf("main"))
        assertEquals(setOf("main"), covers.covered)
        // A second iPad window or scene, or an external display, since.
        covers.cover(listOf("main", "second"))
        assertEquals(setOf("main", "second"), covers.covered)
        // Covering again makes no second cover over a window.
        covers.cover(listOf("main", "second"))
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `uncover takes every cover off and clears the list`() {
        covers.cover(listOf("main", "second"))
        covers.uncover()
        assertEquals(setOf("cover of main", "cover of second"), removed.toSet())
        assertTrue(covers.covered.isEmpty())
        covers.uncover()
        assertEquals(2, removed.size)
    }

    @Test
    fun `a window that went away is forgotten`() {
        covers.cover(listOf("main", "second"))
        covers.cover(listOf("main"))
        assertEquals(setOf("main"), covers.covered)
        assertEquals(listOf("cover of second"), removed)
    }

    @Test
    fun `a cover that cannot be made leaves the rest covered`() {
        val flaky = WindowCovers<String, String>(make = { if (it == "bad") error("no view") else "cover of $it" }, remove = {})
        flaky.cover(listOf("bad", "main"))
        assertEquals(setOf("main"), flaky.covered)
    }
}
