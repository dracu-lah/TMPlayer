package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A profile's long jobs over a switch (HX88), with fakes: a job of profile A stops before the
 * switch opens B and the switch waits for it, so none of its writes or removals runs as B; a job
 * whose owner is already not open stops at once; a switch stops a job's service.
 */
class ProfileJobsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val a = AccountStamp(1L, "ppina1")
    private val b = AccountStamp(2L, null)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A fake Save to folder: copies one file at a time, checks its cancel between files, then removes what it saved. */
    private class FakeCopy(val owner: AccountStamp, val open: () -> AccountStamp) {
        @Volatile var cancelled = false
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        suspend fun run() {
            for (file in 1..50) {
                if (cancelled || !ProfileJobs.mayWrite(owner, open())) break
                delay(10)
                writes += "copied $file for ${owner.folder}"
            }
            // The removal of what was saved: only while the owner is open.
            if (ProfileJobs.mayWrite(owner, open())) writes += "removed for ${owner.folder}"
        }
    }

    @Test
    fun `a switch stops a job of the last profile and waits for it, so nothing of it runs as the next`() = runBlocking {
        var open = a
        val copy = FakeCopy(a) { open }
        val job = scope.launch { copy.run() }
        val hook = ProfileJobs.beforeSwitch(cancel = { copy.cancelled = true }, join = { job.join() })
        BeforeSwitch.add(hook)
        try {
            delay(60)
            // The switch: first the hooks, then the next profile opens.
            BeforeSwitch.runAll()
            assertFalse("the job has ended before the switch goes on", job.isActive)
            val before = copy.writes.toList()
            open = b
            delay(50)
            assertEquals("nothing written once B is open", before, copy.writes.toList())
            assertTrue(copy.writes.all { it.endsWith("ppina1") })
        } finally {
            BeforeSwitch.remove(hook)
        }
    }

    @Test
    fun `a job whose owner is not open skips its removal and every write`() = runBlocking {
        val copy = FakeCopy(a) { b }
        copy.run()
        assertTrue(copy.writes.isEmpty())
    }

    @Test
    fun `a service started for a profile no longer open stops at once`() = runBlocking {
        val stopped = AtomicInteger(0)
        ProfileJobs.stopOnSwitch(scope, owner = a, current = { b }, switches = MutableSharedFlow()) { stopped.incrementAndGet() }
        withTimeout(5_000) { while (stopped.get() == 0) yield() }
        assertEquals(1, stopped.get())
    }

    @Test
    fun `a running service stops at the first switch, once`() = runBlocking {
        val stopped = AtomicInteger(0)
        val switches = MutableSharedFlow<Any?>(extraBufferCapacity = 4)
        ProfileJobs.stopOnSwitch(scope, owner = a, current = { a }, switches = switches) { stopped.incrementAndGet() }
        withTimeout(5_000) { while (switches.subscriptionCount.value == 0) yield() }
        assertEquals(0, stopped.get())
        switches.emit("pnext1")
        switches.emit("pnext2")
        withTimeout(5_000) { while (stopped.get() == 0) yield() }
        delay(100)
        assertEquals(1, stopped.get())
    }

    @Test
    fun `the wait for a job that will not stop is bounded`() = runBlocking {
        val stubborn = scope.launch { while (isActive) delay(10_000) }
        val hook = ProfileJobs.beforeSwitch(cancel = {}, join = { stubborn.join() }, waitMs = 100L)
        val started = System.currentTimeMillis()
        hook()
        assertTrue(System.currentTimeMillis() - started < 5_000)
        stubborn.cancel()
    }
}
