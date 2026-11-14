package com.tmplayer.data

import com.tmplayer.platform.LogFiles
import com.tmplayer.platform.LogSink
import com.tmplayer.platform.PlainLogFiles
import com.tmplayer.platform.RotatingLog
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's persistent log (L4): what each build keeps, that the disk is never waited on, and that a full one is survived. */
class AppLogTest {

    private val direct = AsyncLineWriter(Executor { it.run() })

    private class Recording : LogSink {
        val lines = mutableListOf<String>()
        override fun i(tag: String, message: String) { lines += "I/$tag $message" }
        override fun w(tag: String, message: String, error: Throwable?) { lines += "W/$tag $message" }
    }

    private fun temp(): File = File.createTempFile("applog", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `a beta, preview or debug build keeps detail and a stable one the summary`() {
        assertTrue(AppLogPolicy.defaultDetailed("release", "1.30.0-beta.2"))
        assertTrue(AppLogPolicy.defaultDetailed("preview", "1.30.0-test"))
        assertTrue(AppLogPolicy.defaultDetailed("debug", "1.30.0"))
        assertFalse(AppLogPolicy.defaultDetailed("release", "1.30.0"))
    }

    @Test
    fun `stable keeps warnings and the summary tags, detail keeps every line`() {
        val dir = temp()
        val log = RotatingLog(PlainLogFiles(dir))
        var detailed = false
        val next = Recording()
        val sink = AppLogSink(next, direct, { detailed }, { 0L }) { log }
        sink.i("Home", "chatty")
        sink.i("tm-form", "tv box")
        sink.w("Td", "failed", null)
        val stable = log.readLines()
        assertEquals(2, stable.size)
        assertTrue(stable[0].endsWith("I/tm-form: tv box"))
        assertTrue(stable[1].endsWith("W/Td: failed"))

        detailed = true
        sink.i("Home", "chatty")
        assertTrue(log.readLines().last().endsWith("I/Home: chatty"))
        // Logcat gets every call whatever the file keeps.
        assertEquals(4, next.lines.size)
    }

    @Test
    fun `the file is written off the calling thread, and a slow disk does not hold the caller`() {
        val release = CountDownLatch(1)
        val wrote = CountDownLatch(1)
        var writerThread = ""
        val slow = object : LogFiles {
            override fun <T> exclusive(block: () -> T): T = block()
            override fun currentSize() = 0L
            override fun append(text: String) {
                writerThread = Thread.currentThread().name
                release.await(5, TimeUnit.SECONDS)
                wrote.countDown()
            }
            override fun rotate() = Unit
            override fun readPrevious() = ""
            override fun readCurrent() = ""
            override fun deleteAll() = Unit
        }
        val sink = AppLogSink(Recording(), AsyncLineWriter.threaded(), { true }, { 0L }) { RotatingLog(slow) }
        val began = System.nanoTime()
        repeat(50) { sink.w("T", "line $it", null) }
        // 50 calls returned while the first write is still blocked on the disk.
        assertTrue((System.nanoTime() - began) / 1_000_000 < 2_000)
        release.countDown()
        assertTrue(wrote.await(5, TimeUnit.SECONDS))
        assertEquals("tm-app-log", writerThread)
        assertNotEquals(Thread.currentThread().name, writerThread)
    }

    @Test
    fun `a full disk loses lines and throws nothing`() {
        val failing = object : LogFiles {
            override fun <T> exclusive(block: () -> T): T = block()
            override fun currentSize(): Long = throw IOException("No space left on device")
            override fun append(text: String) = throw IOException("No space left on device")
            override fun rotate() = Unit
            override fun readPrevious() = ""
            override fun readCurrent() = ""
            override fun deleteAll() = Unit
        }
        val log = RotatingLog(failing)
        val sink = AppLogSink(Recording(), direct, { true }, { 0L }) { log }
        repeat(1_000) { sink.w("T", "x", RuntimeException("boom")) }
        assertTrue(log.failures >= 1)
        assertEquals(emptyList<String>(), log.readLines())
    }

    @Test
    fun `a log that cannot even be made does not reach the caller`() {
        val sink = AppLogSink(Recording(), direct, { true }, { 0L }) { throw IllegalStateException("no folder") }
        sink.w("T", "x", null)
        sink.i("T", "y")
    }

    @Test
    fun `a rejecting executor drops the line`() {
        val writer = AsyncLineWriter(Executor { throw java.util.concurrent.RejectedExecutionException() })
        val sink = AppLogSink(Recording(), writer, { true }, { 0L }) { RotatingLog(PlainLogFiles(temp())) }
        sink.w("T", "x", null)
    }
}
