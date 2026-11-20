package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The viewer's Cancel and the end of a switch's wait (HX88): exactly one wins, so a Cancel that
 * races the end of the wait never leaves the switch going ahead with the paused downloads resumed.
 */
class SwitchWaitTest {

    @Test
    fun `a Cancel in time calls the switch off, one after the wait does nothing`() {
        val early = SwitchWait().apply { begin() }
        assertTrue(early.cancel())
        assertFalse("the switch does not go on", early.commit())

        val late = SwitchWait().apply { begin() }
        assertTrue(late.commit())
        assertFalse("too late: nothing is resumed, the switch goes on", late.cancel())
    }

    @Test
    fun `racing on two threads, exactly one of Cancel and the switch wins, every time`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2_000) {
                val wait = SwitchWait().apply { begin() }
                val go = CountDownLatch(1)
                val cancelled = pool.submit<Boolean> { go.await(); wait.cancel() }
                val committed = pool.submit<Boolean> { go.await(); wait.commit() }
                go.countDown()
                val c = cancelled.get(5, TimeUnit.SECONDS)
                val m = committed.get(5, TimeUnit.SECONDS)
                assertEquals("one and only one wins", 1, listOf(c, m).count { it })
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
