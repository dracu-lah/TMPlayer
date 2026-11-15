package com.tmplayer.ui.browse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate's wait counts from the moment the chat list is first needed, not from launch (ON-6). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsGateWaitTest {

    @Test
    fun `the wait starts when armed, not before`() = runTest {
        val armed = MutableStateFlow(false)
        var out = false
        val job = launch { waitedOutFlow(armed, 8_000).collect { out = it } }
        // Minutes pass at the sign in and the setup: the wait has not started.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertFalse(out)
        armed.value = true
        runCurrent()
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(out)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(out)
        job.cancel()
    }

    @Test
    fun `disarming starts the count over, as a second setup does`() = runTest {
        val armed = MutableStateFlow(true)
        var out = false
        val job = launch { waitedOutFlow(armed, 8_000).collect { out = it } }
        advanceTimeBy(9_000)
        runCurrent()
        assertTrue(out)
        armed.value = false
        runCurrent()
        assertFalse(out)
        armed.value = true
        runCurrent()
        advanceTimeBy(7_000)
        runCurrent()
        assertFalse(out)
        job.cancel()
    }
}
