package com.tmplayer.desktop.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.random.Random

/** The streaming input against a file that fills while it is read, like TDLib's partial file. */
class TdMediaDataTest {

    private lateinit var dir: File
    private lateinit var source: File
    private val opened = mutableListOf<GrowingFileBytes>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tdmediadata").toFile()
        source = File(dir, "source.mkv")
        source.writeBytes(Random(42).nextBytes(SIZE))
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun growing(rate: Long = 8L * 1024 * 1024, latency: Long = 100) =
        GrowingFileBytes(source, File(dir, "part-${opened.size}"), rate, latency).also { opened += it }

    private fun expected(at: Long, length: Int): ByteArray = RandomAccessFile(source, "r").use {
        ByteArray(length).also { b ->
            it.seek(at)
            it.readFully(b)
        }
    }

    private fun readFully(input: StreamInput, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = input.read(out, done, length - done)
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test
    fun `sequential reads return the file`() {
        val input = StreamInput(growing(), Job(), prefetchTail = false)
        assertEquals(SIZE.toLong(), input.size)
        val head = readFully(input, 3 * MB)
        assertArrayEquals(expected(0, 3 * MB), head)
        assertEquals(3L * MB, input.position)
    }

    @Test
    fun `a far seek re-aims the download and reads the right bytes`() {
        val bytes = growing()
        val input = StreamInput(bytes, Job(), prefetchTail = false)
        readFully(input, 64 * 1024)
        val far = 15L * MB + 123
        input.seekTo(far)
        val got = readFully(input, 100_000)
        assertArrayEquals(expected(far, 100_000), got)
        assertTrue("download should have moved", bytes.reaims >= 1)
    }

    @Test
    fun `the end of the file reads as -1`() {
        val input = StreamInput(growing(rate = 64L * 1024 * 1024), Job(), prefetchTail = false)
        input.seekTo(SIZE.toLong() - 10)
        assertArrayEquals(expected(SIZE.toLong() - 10, 10), readFully(input, 10))
        assertEquals(-1, input.read(ByteArray(4), 0, 4))
        assertEquals(0L, input.bytesRemaining)
    }

    @Test
    fun `the tail is fetched early and served from memory`() {
        val bytes = growing()
        val input = StreamInput(bytes, Job(), prefetchTail = true)
        readFully(input, 2 * MB)
        val ok = runBlocking { withTimeout(10_000) { input.tailReady.await() } }
        assertTrue(ok)
        val tailStart = TailPrefetch.startFor(SIZE.toLong())!!
        val reaimsAfterTail = bytes.reaims
        input.seekTo(SIZE.toLong() - 4096)
        assertArrayEquals(expected(SIZE.toLong() - 4096, 4096), readFully(input, 4096))
        input.seekTo(tailStart)
        assertArrayEquals(expected(tailStart, 1000), readFully(input, 1000))
        assertEquals("reads in the tail must not move the download", reaimsAfterTail, bytes.reaims)
    }

    @Test
    fun `a blocked read fails with an IOException when the session is cancelled`() {
        // A trickle, so the far read is certainly still waiting when the session ends.
        val session = Job()
        val input = StreamInput(growing(rate = 1024, latency = 60_000), session, prefetchTail = false)
        input.seekTo(20L * MB)
        var failure: Throwable? = null
        val reader = thread {
            try {
                input.read(ByteArray(16), 0, 16)
            } catch (e: Throwable) {
                failure = e
            }
        }
        Thread.sleep(300)
        session.cancel()
        reader.join(5_000)
        assertTrue("reader should have returned", !reader.isAlive)
        assertTrue("expected IOException, got $failure", failure is IOException)
    }

    @Test
    fun `close stops a waiting read and reads after it fail`() {
        val input = StreamInput(growing(rate = 1024, latency = 60_000), Job(), prefetchTail = false)
        input.seekTo(20L * MB)
        var failure: Throwable? = null
        val reader = thread {
            try {
                input.read(ByteArray(16), 0, 16)
            } catch (e: Throwable) {
                failure = e
            }
        }
        Thread.sleep(300)
        input.close()
        input.close()
        reader.join(5_000)
        assertTrue(failure is IOException)
        try {
            input.read(ByteArray(1), 0, 1)
            fail("read after close")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `media data closes its bytes once however often it is closed`() {
        var closes = 0
        val data = TdMediaData(growing(), "test/source.mkv", prefetchTail = false) { closes++ }
        assertEquals(SIZE.toLong(), data.fileLength())
        data.close()
        data.close()
        assertEquals(1, closes)
        try {
            runBlocking { data.createInput(Job()) }
            fail("createInput after close")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `tail prefetch wants a big matroska file`() {
        assertEquals(null, TailPrefetch.startFor(10L * MB))
        val start = TailPrefetch.startFor(100L * MB + 5)!!
        assertEquals(0L, start % TailPrefetch.ALIGN_BYTES)
        assertTrue(100L * MB + 5 - start >= TailPrefetch.TAIL_BYTES)
        assertTrue(TailPrefetch.wanted("Show.S01E02.1080p.mkv", ""))
        assertTrue(TailPrefetch.wanted("clip", "video/x-matroska"))
        assertTrue(!TailPrefetch.wanted("clip.mp4", "video/mp4"))
    }

    private companion object {
        const val MB = 1024 * 1024
        const val SIZE = 24 * MB
    }
}
