package com.tmplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowAfterRemovingTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun theNextRowDownTakesTheRemote() {
        assertEquals("c", rowAfterRemoving(list, "b", setOf("a", "c", "d")))
    }

    @Test
    fun afterTheLastRowTheOneAboveDoes() {
        assertEquals("c", rowAfterRemoving(list, "d", setOf("a", "b", "c")))
    }

    @Test
    fun rowsThatLeftMeanwhileAreSkipped() {
        assertEquals("d", rowAfterRemoving(list, "b", setOf("a", "d")))
        assertEquals("a", rowAfterRemoving(list, "d", setOf("a")))
    }

    @Test
    fun noRowLeftMeansTheTab() {
        assertNull(rowAfterRemoving(listOf("a"), "a", emptySet()))
    }

    @Test
    fun aRowStillThereKeepsTheRemote() {
        assertEquals("b", rowAfterRemoving(list, "b", list.toSet()))
    }

    @Test
    fun aRowTheListNeverShowedMeansTheTab() {
        assertNull(rowAfterRemoving(list, "x", setOf("a")))
    }
}
