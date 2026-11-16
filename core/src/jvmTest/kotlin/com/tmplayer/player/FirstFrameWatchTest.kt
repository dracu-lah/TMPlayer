package com.tmplayer.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstFrameWatchTest {

    /** Feeds one sample a second from [fromS] to [toS] seconds; the second it fired at, or null. */
    private fun FirstFrameWatch.feed(
        fromS: Int,
        toS: Int,
        ready: Boolean = true,
        audio: Boolean = true,
        buffering: Boolean = false,
        software: Boolean = false,
    ): Int? = (fromS..toS).firstOrNull { s ->
        sample(s * 1_000L, ready, audio, buffering = buffering, software = software)
    }

    @Test
    fun noPictureOnHardwareFiresAfterFourSeconds() {
        val watch = FirstFrameWatch().apply { arm() }
        assertEquals(4, watch.feed(0, 20))
        assertTrue(watch.done)
        assertEquals(null, watch.feed(21, 40), "once per arm")
        watch.arm()
        assertEquals(45, watch.feed(41, 60), "a hop arms it again")
    }

    @Test
    fun softwareWaitsLongerAndASeekAddsThree() {
        assertEquals(10, FirstFrameWatch().apply { arm() }.feed(0, 30, software = true))
        assertEquals(7, FirstFrameWatch().apply { arm(afterSeek = true) }.feed(0, 30))
        val seeked = FirstFrameWatch().apply { arm() }
        assertEquals(null, seeked.feed(0, 2))
        seeked.seeked()
        assertEquals(7, seeked.feed(3, 30), "the count goes on, the wait is longer")
    }

    @Test
    fun theFirstFrameEndsIt() {
        val watch = FirstFrameWatch().apply { arm() }
        assertEquals(null, watch.feed(0, 2))
        watch.firstFrame()
        assertEquals(null, watch.feed(3, 30))
    }

    @Test
    fun pausedBufferingOrNoSoundDoesNotCount() {
        val watch = FirstFrameWatch().apply { arm() }
        assertEquals(null, watch.feed(0, 2))
        assertEquals(null, watch.feed(3, 30, buffering = true))
        assertEquals(null, watch.feed(31, 40, audio = false))
        assertEquals(null, watch.feed(41, 50, ready = false))
        assertFalse(watch.sample(51_000, ready = true, audioPlaying = true, sourceWaiting = true))
        // 2 s counted before; each new stretch starts from its first sample.
        assertEquals(54, watch.feed(52, 60))
    }

    @Test
    fun hiddenOnPurposeAndCoverArtNeverFire() {
        val hidden = FirstFrameWatch().apply { arm() }
        assertFalse((0..30).any { hidden.sample(it * 1_000L, ready = true, audioPlaying = true, hidden = true) })
        val cover = FirstFrameWatch().apply { arm() }
        assertFalse((0..30).any { cover.sample(it * 1_000L, ready = true, audioPlaying = true, coverArt = true) })
        assertTrue(cover.done)
    }

    @Test
    fun aGapInSamplesIsNotWaiting() {
        val watch = FirstFrameWatch().apply { arm() }
        assertEquals(null, watch.feed(0, 2))
        // The app was away for a minute: the minute is not counted.
        assertFalse(watch.sample(62_000, ready = true, audioPlaying = true))
        assertEquals(64, watch.feed(63, 70))
    }

    @Test
    fun aClockGoingBackDoesNotCount() {
        val watch = FirstFrameWatch().apply { arm() }
        assertEquals(null, watch.feed(10, 12))
        assertFalse(watch.sample(5_000, ready = true, audioPlaying = true))
        assertEquals(7, watch.feed(6, 20))
    }
}
