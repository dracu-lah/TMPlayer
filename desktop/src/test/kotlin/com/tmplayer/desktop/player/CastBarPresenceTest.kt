package com.tmplayer.desktop.player

import com.tmplayer.cast.CastPresence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FQ8 on the desktop: one search per player session, held above the controls' reveal, so hiding and
 * showing the controls neither searches again nor forgets a TV that was found.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastBarPresenceTest {

    private val found = MutableStateFlow(false)
    private val underWay = MutableStateFlow(false)
    private var starts = 0
    private var stops = 0

    private fun presence() = CastBarPresence(
        start = { starts++ },
        stop = { stops++ },
        follow = combine(found, underWay) { a, b -> a to b },
        underWayNow = false,
        presence = CastPresence(clock = { 0L }, memory = CastPresence.Memory()),
    )

    @Test
    fun `one search per session, stopped by itself, and a found TV keeps the button`() {
        val scope = TestScope(StandardTestDispatcher())
        val bar = presence()
        val first = scope.launch { bar.run() }
        scope.runCurrent()
        assertEquals(1, starts)
        found.value = true
        scope.runCurrent()
        assertTrue(bar.shown.value)
        scope.advanceTimeBy(15_001)
        scope.runCurrent()
        assertEquals("stopped by itself", 1, stops)
        // The controls hide and come back: the composable that follows is made again.
        first.cancel()
        found.value = false
        val second = scope.launch { bar.run() }
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        assertEquals("no second search", 1, starts)
        assertEquals(1, stops)
        assertTrue("the TV found is not forgotten", bar.shown.value)
        second.cancel()
    }

    @Test
    fun `nothing found draws no button, a cast under way does`() {
        val scope = TestScope(StandardTestDispatcher())
        val bar = presence()
        val job = scope.launch { bar.run() }
        scope.advanceTimeBy(15_001)
        scope.runCurrent()
        assertFalse(bar.shown.value)
        underWay.value = true
        scope.runCurrent()
        assertTrue(bar.shown.value)
        job.cancel()
    }
}
