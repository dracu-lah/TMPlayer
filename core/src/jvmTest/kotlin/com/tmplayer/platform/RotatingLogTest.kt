package com.tmplayer.platform

import com.tmplayer.data.Feedback
import com.tmplayer.data.LogExport
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RotatingLogTest {

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("rotating-log").toFile().also { it.deleteOnExit() }

    /** Files whose appends can be made to fail, to stand for a full disk. */
    private class Flaky(private val inner: LogFiles) : LogFiles by inner {
        @Volatile var failing = false
        var attempts = 0
        override fun append(text: String) {
            attempts++
            if (failing) throw IOException("No space left on device")
            inner.append(text)
        }
    }

    @Test
    fun linesComeBackInOrderAcrossARotation() {
        val log = RotatingLog(PlainLogFiles(tempDir()), maxBytesPerFile = 300)
        for (i in 1..40) log.write("line %03d".format(i))
        val lines = log.readLines()
        assertTrue(lines.size in 10..40, "kept ${lines.size}")
        assertEquals("line 040", lines.last())
        assertEquals(lines.sorted(), lines)
        assertEquals(lines.distinct(), lines)
    }

    @Test
    fun eachFileStaysNearTheCap() {
        val dir = tempDir()
        val log = RotatingLog(PlainLogFiles(dir), maxBytesPerFile = 1_000)
        repeat(500) { log.write("a line of some length $it") }
        for (f in dir.listFiles().orEmpty()) assertTrue(f.length() <= 1_000, "${f.name} is ${f.length()}")
        assertEquals(2, dir.listFiles().orEmpty().size)
    }

    @Test
    fun theNewestTwoMegabytesSurviveARestart() {
        val dir = tempDir()
        RotatingLog(PlainLogFiles(dir), 500).also { log -> repeat(60) { log.write("old $it") } }
        val reopened = RotatingLog(PlainLogFiles(dir), 500)
        reopened.write("after restart")
        assertEquals("after restart", reopened.readLines().last())
        assertTrue("old 59" in reopened.readLines())
    }

    @Test
    fun aLongLineIsCutAndMultiLineTextIsKept() {
        val log = RotatingLog(PlainLogFiles(tempDir()))
        log.write("x".repeat(50_000))
        log.write("first\n\tat Foo.bar(Foo.kt:1)")
        val lines = log.readLines()
        assertEquals(RotatingLog.MAX_LINE_CHARS + 3, lines[0].length)
        assertEquals(listOf("first", "\tat Foo.bar(Foo.kt:1)"), lines.drop(1))
    }

    @Test
    fun aLineCutByAKillDoesNotSwallowTheNext() {
        val dir = tempDir()
        File(dir, "tmplayer-app.log").writeText("complete\nhalf a li")
        val log = RotatingLog(PlainLogFiles(dir))
        log.write("next")
        assertEquals(listOf("complete", "half a li", "next"), log.readLines())
    }

    @Test
    fun aFullDiskNeverThrowsAndRecovers() {
        val flaky = Flaky(PlainLogFiles(tempDir()))
        val log = RotatingLog(flaky)
        log.write("before")
        flaky.failing = true
        repeat(1_000) { log.write("lost $it") }
        assertTrue(log.failures >= 1)
        // After the failure only one write in BACKOFF_LINES + 1 is even tried.
        assertTrue(flaky.attempts <= 1 + 1_000 / RotatingLog.BACKOFF_LINES + 1, "attempts ${flaky.attempts}")
        flaky.failing = false
        repeat(RotatingLog.BACKOFF_LINES + 1) { log.write("after") }
        assertEquals("before", log.readLines().first())
        assertTrue("after" in log.readLines())
    }

    @Test
    fun aMissingFolderOrUnreadableFileGivesNothingNotAnError() {
        val gone = File(tempDir(), "no/such/dir")
        val log = RotatingLog(PlainLogFiles(gone))
        log.write("creates the folder")
        assertEquals(listOf("creates the folder"), log.readLines())
        val broken = object : LogFiles by PlainLogFiles(tempDir()) {
            override fun readCurrent(): String = throw IOException("denied")
            override fun readPrevious(): String = throw IOException("denied")
            override fun append(text: String) = throw IOException("denied")
        }
        val bad = RotatingLog(broken)
        bad.write("x")
        assertEquals(emptyList(), bad.readLines())
        bad.clear()
    }

    @Test
    fun concurrentWritersNeverInterleaveALine() {
        val dir = tempDir()
        val log = RotatingLog(PlainLogFiles(dir), maxBytesPerFile = 20_000)
        val threads = 8
        val each = 400
        val start = CountDownLatch(1)
        val workers = List(threads) { t ->
            thread {
                start.await()
                repeat(each) { log.write("t$t n$it " + "#".repeat(60) + " end") }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        val lines = log.readLines()
        assertTrue(lines.isNotEmpty())
        val shape = Regex("""t\d n\d+ #{60} end""")
        assertTrue(lines.all { shape.matches(it) }, "an interleaved line: ${lines.firstOrNull { !shape.matches(it) }}")
        // Per writer, the lines kept are in the order written.
        for (t in 0 until threads) {
            val mine = lines.filter { it.startsWith("t$t ") }.map { it.substringAfter(" n").substringBefore(' ').toInt() }
            assertEquals(mine.sorted(), mine)
        }
        for (f in dir.listFiles().orEmpty()) assertTrue(f.length() <= 20_000)
    }

    @Test
    fun aReaderDuringWritesSeesWholeLinesOnly() {
        val log = RotatingLog(PlainLogFiles(tempDir()), maxBytesPerFile = 2_000)
        val writer = thread { repeat(2_000) { log.write("row $it =====") } }
        repeat(100) { assertTrue(log.readLines().all { it.startsWith("row ") && it.endsWith(" =====") }) }
        writer.join()
    }

    @Test
    fun clearRemovesBothFilesAndTheLogWorksAfter() {
        val dir = tempDir()
        val log = RotatingLog(PlainLogFiles(dir), 200)
        repeat(50) { log.write("line $it") }
        log.clear()
        assertEquals(emptyList(), log.readLines())
        assertEquals(0, dir.listFiles().orEmpty().size)
        log.write("again")
        assertEquals(listOf("again"), log.readLines())
    }

    @Test
    fun exportScrubsEveryLineOfBothFiles() {
        val log = RotatingLog(PlainLogFiles(tempDir()), 200)
        repeat(30) { log.write("I/Td: chat 98765432$it Kerala Crime Files S01E02.mkv from @someone_here") }
        val facts = Feedback.Facts("1.28.5", Feedback.Platform.Phone, "Pixel", "en")
        val text = LogExport.report(facts, log, known = listOf("Kerala Crime Files"), now = LocalDateTime.of(2026, 11, 15, 10, 0))
        assertTrue("I/Td: chat [number] [name] [file]" in text || "[name]" in text)
        assertTrue("98765432" !in text && "Kerala" !in text && "S01E02" !in text && "someone_here" !in text)
    }

    @Test
    fun theFileSinkWritesTheStampAndTheTraceAndStillForwards() {
        val log = RotatingLog(PlainLogFiles(tempDir()))
        val seen = mutableListOf<String>()
        val next = object : LogSink {
            override fun i(tag: String, message: String) { seen += "i $tag $message" }
            override fun w(tag: String, message: String, error: Throwable?) = throw IllegalStateException("sink broke")
        }
        val sink = FileLogSink(log, next) { 1_763_200_005_123L }
        sink.i("Player", "started")
        sink.w("Player", "failed", RuntimeException("boom"))
        assertEquals(listOf("i Player started"), seen)
        val lines = log.readLines()
        assertEquals("2025-11-15 09:46:45.123Z I/Player: started", lines[0])
        assertTrue(lines[1].startsWith("2025-11-15 09:46:45.123Z W/Player: failed"))
        assertTrue(lines.any { it.contains("RuntimeException: boom") })
        assertFailsWith<IllegalStateException> { next.w("t", "m", null) }
    }

    @Test
    fun stampIsRightAtTheEdges() {
        assertEquals("1970-01-01 00:00:00.000Z", FileLogSink.stamp(0))
        assertEquals("1970-01-01 00:00:00.000Z", FileLogSink.stamp(-5))
        assertEquals("2000-02-29 23:59:59.999Z", FileLogSink.stamp(951_868_799_999L))
        assertEquals("2026-12-31 12:00:00.000Z", FileLogSink.stamp(java.time.Instant.parse("2026-12-31T12:00:00Z").toEpochMilli()))
        assertEquals(10, FileLogSink.stamp(Long.MAX_VALUE).substringBefore(' ').length)
    }
}
