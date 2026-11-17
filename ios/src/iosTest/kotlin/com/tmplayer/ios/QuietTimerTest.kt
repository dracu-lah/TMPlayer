package com.tmplayer.ios

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home carousel's page timer on iOS keeps the animation's arithmetic: the value reaches 1 when
 * the time is up, a pause leaves it where a linear animation would stand, and the next run takes
 * only the time left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietTimerTest {
    @Test
    fun theValueReachesOneWhenTheTimeIsUp() = runTest {
        val progress = Animatable(0f)
        val run = launch { QuietTimer.run(progress, 6_000, testScheduler.timeSource) }
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(0f, progress.value, "nothing moves until the time is up")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(run.isCompleted)
        assertEquals(1f, progress.value)
    }

    @Test
    fun aPauseKeepsThePlaceAndTheRestTakesTheTimeLeft() = runTest {
        val progress = Animatable(0f)
        val first = launch { QuietTimer.run(progress, 6_000, testScheduler.timeSource) }
        advanceTimeBy(1_500)
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(0.25f, progress.value, 0.001f)
        // Upstream's next run asks for the time left: 6 s times what is left of the value.
        val left = 1f - progress.value
        val second = launch { QuietTimer.run(progress, (6_000 * left).toLong(), testScheduler.timeSource) }
        advanceTimeBy(4_499)
        runCurrent()
        assertTrue(!second.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(1f, progress.value)
    }

    @Test
    fun noTimeLeftEndsAtOnce() = runTest {
        val progress = Animatable(1f)
        QuietTimer.run(progress, 0, testScheduler.timeSource)
        assertEquals(1f, progress.value)
    }
}
