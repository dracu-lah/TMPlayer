package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {

    @Test
    fun `progress reaches full exactly when the player has what it needs`() {
        assertEquals(0f, StreamStats.progress(0, 2_500), 0.001f)
        assertEquals(0.5f, StreamStats.progress(1_250, 2_500), 0.001f)
        assertEquals(1f, StreamStats.progress(2_500, 2_500), 0.001f)
    }

    @Test
    fun `progress never overshoots when the buffer runs ahead`() {
        assertEquals(1f, StreamStats.progress(60_000, 2_500), 0.001f)
    }

    @Test
    fun `eta is zero once enough is buffered`() {
        val eta = StreamStats.etaSeconds(
            bufferedAheadMs = 3_000,
            requiredMs = 2_500,
            bytesPerMs = 200.0,
            speedBytesPerSec = 1_000_000,
        )
        assertEquals(0L, eta)
    }

    @Test
    fun `eta is withheld rather than guessed when the connection is stalled`() {
        // A speed near zero would divide out to an absurd number of hours; saying nothing is
        // more honest than showing "about 940h left".
        val eta = StreamStats.etaSeconds(
            bufferedAheadMs = 0,
            requiredMs = 2_500,
            bytesPerMs = 200.0,
            speedBytesPerSec = 0,
        )
        assertNull(eta)
    }

    @Test
    fun `eta is withheld when the bitrate is unknown`() {
        val eta = StreamStats.etaSeconds(
            bufferedAheadMs = 0,
            requiredMs = 2_500,
            bytesPerMs = 0.0,
            speedBytesPerSec = 2_000_000,
        )
        assertNull(eta)
    }

    @Test
    fun `eta divides missing bytes by the measured speed`() {
        // 2500 ms still needed at 400 B/ms is 1 MB; at 500 KB/s that is 2 seconds.
        val eta = StreamStats.etaSeconds(
            bufferedAheadMs = 0,
            requiredMs = 2_500,
            bytesPerMs = 400.0,
            speedBytesPerSec = 500_000,
        )
        assertEquals(2L, eta)
    }

    @Test
    fun `bitrate comes from size over duration`() {
        // A 1.4 GB film running two hours is roughly 208 KB per second of playback.
        val perMs = StreamStats.bytesPerMs(1_400L * 1024 * 1024, 7_200)
        assertEquals(203.7, perMs, 1.0)
    }

    @Test
    fun `bitrate of an unknown file is zero rather than infinite`() {
        assertEquals(0.0, StreamStats.bytesPerMs(0, 7_200), 0.0001)
        assertEquals(0.0, StreamStats.bytesPerMs(1_000, 0), 0.0001)
    }

    @Test
    fun `clock formatting matches what a seek bar shows`() {
        assertEquals("0:00", StreamStats.formatClock(0))
        assertEquals("4:07", StreamStats.formatClock(247_000))
        assertEquals("1:23:45", StreamStats.formatClock(5_025_000))
    }

    @Test
    fun `speeds and sizes read the way a person would say them`() {
        assertEquals("…", StreamStats.formatSpeed(0))
        assertEquals("512 KB/s", StreamStats.formatSpeed(512 * 1024))
        assertEquals("2.0 MB/s", StreamStats.formatSpeed(2 * 1024 * 1024))
        assertEquals("1.5 MB", StreamStats.formatBytes(1536 * 1024))
        assertEquals("1.37 GB", StreamStats.formatBytes(1_470_000_000))
    }

    @Test
    fun `eta wording scales with how long the wait is`() {
        assertEquals("", StreamStats.formatEta(null))
        assertEquals("almost there", StreamStats.formatEta(0))
        assertEquals("about 30s left", StreamStats.formatEta(30))
        assertEquals("about 2m 5s left", StreamStats.formatEta(125))
        assertEquals("about 1h 1m left", StreamStats.formatEta(3_660))
    }
}

class SpeedMeterTest {

    @Test
    fun `the first sample has no baseline to measure against`() {
        val meter = SpeedMeter()
        assertEquals(0L, meter.sample(1_000_000, 1_000))
    }

    @Test
    fun `a steady download converges on the true rate`() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        // 1 MB per second, fed in ten 100 ms steps.
        var bytes = 0L
        for (step in 1..40) {
            bytes += 104_857
            meter.sample(bytes, step * 100L)
        }
        val measured = meter.bytesPerSec
        assertTrue("expected ~1 MB/s, got $measured", measured in 900_000..1_150_000)
    }

    @Test
    fun `a seek that moves the window backwards resets rather than reporting nonsense`() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        meter.sample(5_000_000, 1_000)
        assertTrue(meter.bytesPerSec > 0)

        // TDLib restarts the download at a new offset, so the prefix count drops.
        assertEquals(0L, meter.sample(1_000, 2_000))
    }

    @Test
    fun `samples with no elapsed time are ignored`() {
        val meter = SpeedMeter()
        meter.sample(0, 500)
        assertEquals(0L, meter.sample(1_000_000, 500))
    }

    @Test
    fun `reset clears the baseline`() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        meter.sample(1_000_000, 1_000)
        meter.reset()
        assertEquals(0L, meter.bytesPerSec)
        assertEquals(0L, meter.sample(9_000_000, 2_000))
    }
}
