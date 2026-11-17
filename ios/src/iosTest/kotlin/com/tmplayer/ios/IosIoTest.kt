@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tmplayer.ios

import com.tmplayer.platform.IosIo
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS build's Dispatchers.IO ([IosIo]) gives every task that blocks a thread of its own, as
 * the JVM's does, and lets its threads go once they have nothing to do.
 */
class IosIoTest {
    @Test
    fun aBurstOfBlockingTasksAllRunAtOnce() = runBlocking {
        // One thread made and left idle first: a burst then finds a thread waiting, the case where
        // waking that thread alone would queue every task behind the first.
        launch(IosIo) {}.join()
        platform.posix.usleep(50_000u)
        val tasks = 12
        val started = AtomicInt(0)
        val sawAll = AtomicInt(0)
        val jobs = List(tasks) {
            launch(IosIo) {
                started.incrementAndGet()
                // Blocks, as TDLib's wait for answers does, until every task is running.
                val until = platform.posix.time(null) + 5
                while (started.value < tasks && platform.posix.time(null) < until) platform.posix.usleep(1_000u)
                if (started.value == tasks) sawAll.incrementAndGet()
            }
        }
        jobs.joinAll()
        assertEquals(tasks, sawAll.value, "every task ran while the others blocked")
    }

    @Test
    fun aTaskThatThrowsLeavesThePoolWorking() = runBlocking {
        IosIo.dispatch(EmptyCoroutineContext, Runnable { error("a task that fails") })
        val ran = AtomicInt(0)
        launch(IosIo) { ran.incrementAndGet() }.join()
        assertEquals(1, ran.value)
    }

    @Test
    fun idleThreadsEnd() = runBlocking {
        List(4) { launch(IosIo) { platform.posix.usleep(20_000u) } }.joinAll()
        assertTrue(IosIo.running > 0)
        // Ten seconds idle ends a thread; a little more for the last one to notice.
        val until = platform.posix.time(null) + 15
        while (IosIo.running > 0 && platform.posix.time(null) < until) platform.posix.usleep(100_000u)
        assertEquals(0, IosIo.running, "no thread stays once there is nothing to do")
    }
}
