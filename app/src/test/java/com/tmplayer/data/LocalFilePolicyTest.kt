package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFilePolicyTest {

    @Test
    fun `completed flag without a real path is missing`() {
        assertEquals(
            LocalFileAvailability.Missing,
            LocalFilePolicy.evaluate(true, false, false, 0, 100, 100),
        )
    }

    @Test
    fun `an unfinished real file is partial`() {
        assertEquals(
            LocalFileAvailability.Partial,
            LocalFilePolicy.evaluate(false, true, true, 40, 100, 100),
        )
    }

    @Test
    fun `completed flag with a short file is still partial`() {
        assertEquals(
            LocalFileAvailability.Partial,
            LocalFilePolicy.evaluate(true, true, true, 90, 100, 100),
        )
    }

    @Test
    fun `a completed file of the expected size is available offline`() {
        assertEquals(
            LocalFileAvailability.Complete,
            LocalFilePolicy.evaluate(true, true, true, 100, 90, 100),
        )
    }

    @Test
    fun `unknown size still requires a nonempty regular file`() {
        assertEquals(
            LocalFileAvailability.Complete,
            LocalFilePolicy.evaluate(true, true, true, 1, 0, 0),
        )
    }
}
