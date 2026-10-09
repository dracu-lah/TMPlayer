package com.tmplayer.data

import com.tmplayer.data.SizeFilter.CEILING
import com.tmplayer.data.SizeFilter.GB
import com.tmplayer.data.SizeFilter.MB
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenBySizeTest {

    @Test
    fun `counts what the limits turn away and keeps the order of the rest`() {
        val sizes = listOf(30 * MB, 700 * MB, 0L, 10 * GB, 1 * GB)
        val split = SizeFilter.split(sizes, 50 * MB, 4 * GB) { it }
        assertEquals(listOf(700 * MB, 0L, 1 * GB), split.kept)
        assertEquals(2, split.hidden)
    }

    @Test
    fun `nothing is hidden with the limits wide open`() {
        val sizes = listOf(1L, 30 * MB, 20 * GB)
        val split = SizeFilter.split(sizes, 0, CEILING) { it }
        assertEquals(sizes, split.kept)
        assertEquals(0, split.hidden)
    }

    @Test
    fun `an empty page hides nothing`() {
        assertEquals(0, SizeFilter.split(emptyList<Long>(), 50 * MB, CEILING) { it }.hidden)
    }
}
