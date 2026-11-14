package com.tmplayer.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ReadArbiterTest {

    private fun readFully(reader: CastReader, count: Int): ByteArray {
        val out = ByteArray(count)
        var at = 0
        while (at < count) {
            val n = reader.read(out, at, minOf(64 * 1024, count - at))
            if (n < 0) break
            at += n
        }
        return out.copyOf(at)
    }

    @Test(timeout = 30_000)
    fun concurrentReadersShareOneWindow() {
        val source = PatternSource(64L * 1024 * 1024, readDelayMs = 1)
        val arbiter = ReadArbiter(source, handoverMs = 200, maxWaitMs = 2_000)
        val errors = CopyOnWriteArrayList<Throwable>()
        val random = Random(7)
        val threads = List(8) {
            val start = random.nextLong(0, source.length - 300_000)
            Thread {
                try {
                    arbiter.open(start).use { r -> assertArrayEquals(PatternSource.expected(start, 200_000), readFully(r, 200_000)) }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals("TDLib must only ever see one window", 1, source.maxOpen.get())
    }

    /** The index probe at the end finishes in one go while the body waits, then the body goes on. */
    @Test(timeout = 30_000)
    fun lastRequestWinsWithoutThrashing() {
        val total = 32L * 1024 * 1024
        val source = PatternSource(total, readDelayMs = 2)
        val arbiter = ReadArbiter(source, handoverMs = 500, maxWaitMs = 10_000)
        val body = arbiter.open(0)
        assertArrayEquals(PatternSource.expected(0, 128 * 1024), readFully(body, 128 * 1024))
        val bodyDone = AtomicLong()
        val bodyThread = Thread {
            bodyDone.set(readFully(body, 2 * 1024 * 1024).size.toLong())
        }
        val probeAt = total - 1024 * 1024
        val probe = arbiter.open(probeAt)
        bodyThread.start()
        assertArrayEquals(PatternSource.expected(probeAt, 1024 * 1024), readFully(probe, 1024 * 1024))
        probe.close()
        bodyThread.join()
        assertEquals(2L * 1024 * 1024, bodyDone.get())
        // Body at 0, the probe at the end, the body back once: not a move per buffer.
        assertTrue("opens ${source.opens}", source.opens.size <= 3)
        assertEquals(1, source.maxOpen.get())
    }

    @Test(timeout = 30_000)
    fun anOlderReaderIsNotStarved() {
        val source = PatternSource(32L * 1024 * 1024, readDelayMs = 1)
        val arbiter = ReadArbiter(source, handoverMs = 10_000, maxWaitMs = 300)
        val old = arbiter.open(0)
        val owner = arbiter.open(16L * 1024 * 1024)
        readFully(owner, 64 * 1024)
        val ownerThread = Thread {
            val end = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < end) readFully(owner, 64 * 1024)
        }
        ownerThread.start()
        val started = System.currentTimeMillis()
        assertArrayEquals(PatternSource.expected(0, 1000), readFully(old, 1000))
        assertTrue(System.currentTimeMillis() - started < 1_400)
        ownerThread.join()
    }

    @Test
    fun sequentialAndShortHopsCoalesce() {
        val source = PatternSource(10L * 1024 * 1024)
        val arbiter = ReadArbiter(source)
        arbiter.open(0).use { readFully(it, 1000) }
        // A new request where the last one stopped, then a hop ahead within the skip limit.
        arbiter.open(1000).use { assertArrayEquals(PatternSource.expected(1000, 500), readFully(it, 500)) }
        arbiter.open(100_000).use { assertArrayEquals(PatternSource.expected(100_000, 500), readFully(it, 500)) }
        assertEquals(listOf(0L), source.opens.toList())
        // Backwards or far ahead moves the window.
        arbiter.open(10).use { assertArrayEquals(PatternSource.expected(10, 10), readFully(it, 10)) }
        assertEquals(listOf(0L, 10L), source.opens.toList())
    }

    @Test
    fun idleCloseAndReopenAtTheSamePlace() {
        var now = 0L
        val source = PatternSource(10L * 1024 * 1024)
        val arbiter = ReadArbiter(source, idleMs = 30_000, clock = { now })
        val reader = arbiter.open(0)
        assertArrayEquals(PatternSource.expected(0, 5000), readFully(reader, 5000))
        now += 29_000
        assertFalse(arbiter.sweep())
        assertTrue(arbiter.holdsInner)
        now += 1_000
        assertTrue(arbiter.sweep())
        assertFalse("the window is let go during a long pause", arbiter.holdsInner)
        assertEquals(0, source.maxOpen.get() - 1)
        now += 3_600_000
        assertArrayEquals(PatternSource.expected(5000, 5000), readFully(reader, 5000))
        assertEquals(listOf(0L, 5000L), source.opens.toList())
    }

    @Test
    fun aFailedReadDropsTheWindowAndOnlyThatReaderSeesIt() {
        var fail = true
        val source = PatternSource(1024L * 1024, beforeRead = { if (fail && it >= 4096) throw IOException("cut") })
        val arbiter = ReadArbiter(source)
        val reader = arbiter.open(0)
        readFully(reader, 4096)
        val thrown = runCatching { readFully(reader, 10) }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertFalse(arbiter.holdsInner)
        fail = false
        arbiter.open(4096).use { assertArrayEquals(PatternSource.expected(4096, 100), readFully(it, 100)) }
    }

    @Test
    fun closedReadersAndArbiterRefuse() {
        val arbiter = ReadArbiter(PatternSource(1000))
        val reader = arbiter.open(0)
        reader.close()
        assertTrue(runCatching { reader.read(ByteArray(10), 0, 10) }.exceptionOrNull() is ReadArbiter.ReaderClosedException)
        arbiter.close()
        assertTrue(runCatching { arbiter.open(0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { ReadArbiter(PatternSource(1000)).open(-1) }.exceptionOrNull() is IOException)
        val atEnd = ReadArbiter(PatternSource(1000)).open(1000)
        assertEquals(-1, atEnd.read(ByteArray(10), 0, 10))
    }
}
