package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a clear-all asks, and that it reads the list as it is when answered (HX59). */
class ClearAllConfirmTest {

    @Test
    fun `an empty list asks nothing and clears nothing`() {
        assertFalse(ClearAllConfirm.needed(0))
        assertFalse(ClearAllConfirm.needed(-3))
        assertFalse(ClearAllConfirm.press(asking = false, count = 0))
        assertFalse(ClearAllConfirm.confirmClears(0))
    }

    @Test
    fun `a list with one card asks`() {
        assertTrue(ClearAllConfirm.press(asking = false, count = 1))
        assertTrue(ClearAllConfirm.confirmClears(1))
    }

    @Test
    fun `a second press while the question is up changes nothing`() {
        assertFalse(ClearAllConfirm.press(asking = true, count = 5))
    }

    @Test
    fun `the question closes when the list empties meanwhile and stays while it has cards`() {
        assertTrue(ClearAllConfirm.stillAsking(asking = true, count = 2))
        assertFalse(ClearAllConfirm.stillAsking(asking = true, count = 0))
        assertFalse(ClearAllConfirm.stillAsking(asking = false, count = 2))
    }

    @Test
    fun `history counts the cards of both lists and never goes negative`() {
        assertEquals(7, ClearAllConfirm.historyCards(3, 4))
        assertEquals(4, ClearAllConfirm.historyCards(-1, 4))
        assertEquals(0, ClearAllConfirm.historyCards(0, 0))
    }
}
