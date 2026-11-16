package com.tmplayer.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrozenOutputWatchTest {

    /**
     * A player at [speed] sampled every second from [fromS] to [toS]: the position starts at
     * [startMs], the rendered total grows by [renderedPerS] (0 when the decoder stopped).
     * Returns the second it fired at, or null.
     */
    private fun FrozenOutputWatch.play(
        fromS: Int,
        toS: Int,
        startMs: Long = fromS * 1_000L,
        speed: Float = 1f,
        renderedPerS: Long = 0,
        renderedAt: Long = 100,
        dropped: Long = 0,
        playing: Boolean = true,
    ): Int? = (fromS..toS).firstOrNull { s ->
        val n = s - fromS
        sample(
            wallMs = s * 1_000L,
            positionMs = startMs + if (playing) (n * 1_000L * speed).toLong() else 0L,
            playing = playing,
            renderedTotal = renderedAt + n * renderedPerS,
            droppedTotal = dropped,
            skippedTotal = 0,
            speed = speed,
        )
    }

    @Test
    fun normalPlayNeverFires() {
        val watch = FrozenOutputWatch()
        assertEquals(null, watch.play(0, 120, renderedPerS = 24))
    }

    @Test
    fun aStillSceneStillRendersSoItNeverFires() {
        // A static picture: the decoder keeps releasing frames, slowly. One a second is enough.
        assertEquals(null, FrozenOutputWatch().play(0, 120, renderedPerS = 1))
    }

    @Test
    fun outputStandingStillFiresOnceAfterThreeSeconds() {
        val watch = FrozenOutputWatch()
        assertEquals(3, watch.play(0, 30))
        assertTrue(watch.fired)
        assertEquals(null, watch.play(31, 60), "never twice for the same hop")
        watch.arm()
        assertEquals(64, watch.play(61, 90), "a hop arms it again")
    }

    @Test
    fun pausedOrBufferingDoesNotFire() {
        assertEquals(null, FrozenOutputWatch().play(0, 60, playing = false))
        val watch = FrozenOutputWatch()
        assertEquals(null, watch.play(0, 2))
        assertEquals(null, watch.play(3, 10, startMs = 2_000, playing = false))
        // The stretch starts again after the pause: three more seconds.
        assertEquals(14, watch.play(11, 30, startMs = 2_000))
    }

    @Test
    fun speedIsMediaTimeNotWallTime() {
        assertEquals(12, FrozenOutputWatch().play(0, 60, speed = 0.25f), "3 s of video at 0.25x is 12 s")
        assertEquals(2, FrozenOutputWatch().play(0, 60, speed = 2f), "and 1.5 s at 2x")
        assertEquals(null, FrozenOutputWatch().play(0, 60, speed = 0f))
    }

    @Test
    fun aSeekStartsTheCountAgain() {
        val watch = FrozenOutputWatch()
        assertEquals(null, watch.play(0, 2))
        // A jump between samples (a seek without the call) is not playback.
        assertEquals(null, watch.play(3, 4, startMs = 600_000))
        watch.seeked()
        assertEquals(null, watch.play(5, 7, startMs = 10_000))
        assertEquals(8, watch.play(8, 30, startMs = 13_000))
    }

    @Test
    fun aGapInSamplesStartsAgain() {
        val watch = FrozenOutputWatch()
        assertEquals(null, watch.play(0, 2))
        // The app away for a minute: the position ran on, but nothing was seen.
        assertEquals(null, watch.play(62, 64, startMs = 62_000))
        assertEquals(65, watch.play(65, 80, startMs = 64_000 + 1_000))
    }

    @Test
    fun aDecoderMadeAnewResetsTheCounters() {
        val watch = FrozenOutputWatch()
        assertEquals(null, watch.play(0, 2, renderedPerS = 24))
        // Totals fall back to 0 with a new codec, then stand still.
        assertEquals(6, watch.play(3, 30, startMs = 3_000, renderedAt = 0))
    }

    @Test
    fun droppedOrSkippedFramesAreOutput() {
        val watch = FrozenOutputWatch()
        var fired = false
        for (s in 0..30) {
            fired = fired || watch.sample(s * 1_000L, s * 1_000L, playing = true, renderedTotal = 50, droppedTotal = s.toLong(), skippedTotal = 0)
        }
        assertEquals(false, fired, "a slow decoder that drops is not frozen")
    }
}
