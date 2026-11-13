package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The byte target against the time ahead, with Media3's own [DefaultLoadControl] behind it, set
 * as the player sets it: a size target that wins over time, one second to start, four to resume.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AheadFirstLoadControlTest {

    private val playerId = PlayerId("test")
    private val target = 1024 * 1024
    private val ceiling = 4L * target

    private val default = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 50_000, 1_000, 4_000)
        .setTargetBufferBytes(target)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    private val control = AheadFirstLoadControl(
        default,
        playbackUs = 1_000_000,
        rebufferUs = 4_000_000,
        ceilingBytes = ceiling,
    )

    @Before
    fun prepare() {
        control.onPrepared(playerId)
    }

    /** Takes [bytes] of the player's allocator, as samples waiting in the buffer do. */
    private fun hold(bytes: Long) {
        val allocator = control.getAllocator(playerId)
        while (allocator.totalBytesAllocated < bytes) allocator.allocate()
    }

    /** A stream, as the player has one: one period of a `tdjoin` video, not a local file. */
    private val timeline: Timeline = SinglePeriodTimeline(
        /* durationUs = */ 7_368_000_000,
        /* isSeekable = */ true,
        /* isDynamic = */ false,
        /* useLiveConfiguration = */ false,
        /* manifest = */ null,
        MediaItem.fromUri("tdjoin://pieces?p=tdfile%3A%2F%2F1&s=100"),
    )
    private val period = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    private fun at(bufferedUs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        playerId,
        timeline,
        period,
        /* playbackPositionUs = */ 0,
        bufferedUs,
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ true,
        rebuffering,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
    )

    @Test
    fun `a soundtrack that starts late keeps the loading going past the target`() {
        // The budget full of picture with nothing to play it against: Media3 alone stops here.
        hold(2L * target)
        assertFalse(default.shouldContinueLoading(at(0)))
        assertTrue(control.shouldContinueLoading(at(0)))
        assertTrue(control.shouldContinueLoading(at(3_900_000)))
    }

    @Test
    fun `enough ahead leaves the target in charge`() {
        hold(2L * target)
        assertFalse(control.shouldContinueLoading(at(4_000_000)))
        assertFalse(control.shouldContinueLoading(at(20_000_000)))
    }

    @Test
    fun `under the target nothing changes`() {
        assertTrue(control.shouldContinueLoading(at(0)))
        assertTrue(control.shouldContinueLoading(at(10_000_000)))
    }

    @Test
    fun `the ceiling stops it`() {
        hold(ceiling)
        assertFalse(control.shouldContinueLoading(at(0)))
    }

    @Test
    fun `playback waits for time, not the byte target, while there is room`() {
        hold(2L * target)
        // Media3 alone starts on the full budget with 0.6 s of it playable.
        assertTrue(default.shouldStartPlayback(at(600_000)))
        assertFalse(control.shouldStartPlayback(at(600_000)))
        assertTrue(control.shouldStartPlayback(at(1_000_000)))
        // After a stall it is four seconds, as Media3 asks when the budget is not full.
        assertFalse(control.shouldStartPlayback(at(2_000_000, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(at(4_000_000, rebuffering = true)))
    }

    @Test
    fun `at the ceiling playback starts on what there is`() {
        hold(ceiling)
        assertTrue(control.shouldStartPlayback(at(600_000)))
    }

    @Test
    fun `under the target the start is Media3's own`() {
        assertFalse(control.shouldStartPlayback(at(600_000)))
        assertTrue(control.shouldStartPlayback(at(1_000_000)))
    }

    @Test
    fun `only Media3 giving up on a player that would not load reads as the ceiling`() {
        fun failure(cause: Throwable) = ExoPlaybackException.createForUnexpected(RuntimeException(cause), PlaybackException.ERROR_CODE_UNSPECIFIED)
        val notLoading = StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING, 4_000)
        assertTrue(AheadFirstLoadControl.isStuckAtCeiling(failure(notLoading)))
        assertTrue(AheadFirstLoadControl.isStuckAtCeiling(notLoading))
        assertFalse(AheadFirstLoadControl.isStuckAtCeiling(failure(StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 10_000))))
        assertFalse(AheadFirstLoadControl.isStuckAtCeiling(failure(java.io.IOException("Telegram did not respond in time"))))
        assertFalse(AheadFirstLoadControl.isStuckAtCeiling(null))
    }

    @Test
    fun `the ceiling is twice the target on a low memory device, else three fifths of the heap`() {
        val mb = 1024L * 1024
        assertEquals(40 * mb, AheadFirstLoadControl.ceilingFor((20 * mb).toInt(), lowMemory = true, maxHeapBytes = 192 * mb))
        assertEquals(256 * mb / 5 * 3, AheadFirstLoadControl.ceilingFor((64 * mb).toInt(), lowMemory = false, maxHeapBytes = 256 * mb))
        // A heap too small for three fifths to reach the target keeps the target.
        assertEquals(64 * mb, AheadFirstLoadControl.ceilingFor((64 * mb).toInt(), lowMemory = false, maxHeapBytes = 96 * mb))
    }
}
