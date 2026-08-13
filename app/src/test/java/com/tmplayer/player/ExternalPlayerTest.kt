package com.tmplayer.player

import com.tmplayer.data.LocalFileAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What another app is promised when it is handed one of these files, which is the whole of what
 * makes the handover honest rather than a video that stops in the middle with no explanation.
 */
class ExternalPlayerTest {

    @Test
    fun `a finished file is ready and says nothing`() {
        val state = ExternalPlayer.readiness(LocalFileAvailability.Complete, 1_000)
        assertEquals(ExternalPlayer.Readiness.Complete, state)
        assertNull(ExternalPlayer.caution(state, 1_000, 1_000))
    }

    @Test
    fun `a file with nothing on disk is not offered`() {
        assertEquals(
            ExternalPlayer.Readiness.Nothing,
            ExternalPlayer.readiness(LocalFileAvailability.Missing, 0),
        )
    }

    /** TDLib reports Partial the moment a download is asked for, before a byte has landed. */
    @Test
    fun `a download that has not started yet has nothing to hand over`() {
        assertEquals(
            ExternalPlayer.Readiness.Nothing,
            ExternalPlayer.readiness(LocalFileAvailability.Partial, 0),
        )
    }

    @Test
    fun `a part downloaded file is offered with how far it goes`() {
        val state = ExternalPlayer.readiness(LocalFileAvailability.Partial, 430)
        assertEquals(ExternalPlayer.Readiness.Partial, state)
        val caution = ExternalPlayer.caution(state, 430, 1_000)
        assertNotNull(caution)
        assertTrue(caution!!.contains("43%"))
    }

    @Test
    fun `a part downloaded file of unknown size still warns`() {
        val caution = ExternalPlayer.caution(ExternalPlayer.Readiness.Partial, 430, 0)
        assertNotNull(caution)
        assertTrue(caution!!.contains("still downloading"))
    }

    @Test
    fun `a size nobody knows yields no percentage`() {
        assertNull(ExternalPlayer.percentDownloaded(500, 0))
    }

    /**
     * The rounding that matters: a 12 GB file four megabytes short is 99.97 per cent, and saying
     * 100 there is the app promising a complete video a moment before it stops.
     */
    @Test
    fun `almost finished never rounds up to a hundred`() {
        assertEquals(99, ExternalPlayer.percentDownloaded(11_996_000_000, 12_000_000_000))
    }

    @Test
    fun `every byte on disk is a hundred`() {
        assertEquals(100, ExternalPlayer.percentDownloaded(1_000, 1_000))
    }

    @Test
    fun `a file longer than its declared size is still a hundred rather than more`() {
        assertEquals(100, ExternalPlayer.percentDownloaded(1_200, 1_000))
    }

    @Test
    fun `a nonsense negative count yields no percentage`() {
        assertNull(ExternalPlayer.percentDownloaded(-1, 1_000))
    }
}
