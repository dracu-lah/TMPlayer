package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownmixTest {

    @Test
    fun `a phone folds 5 point 1 down to two channels`() {
        assertTrue(AudioDownmix.matrices(television = false).contains(6 to 2))
    }

    @Test
    fun `every layout is named, so a stereo file still configures`() {
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 7, 8 to 8), AudioDownmix.matrices(television = false))
    }

    @Test
    fun `a television keeps the processor out of the chain`() {
        assertEquals(emptyList<Pair<Int, Int>>(), AudioDownmix.matrices(television = true))
    }

}
