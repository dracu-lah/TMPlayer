package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** The pure parts of the playback recorder (L1): the counters, the sampler, the cadence and the file. */
class PlaybackRecorderPartsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun clean() {
        TdReadCounters.reset()
    }

    // ---- TdReadCounters ----

    @Test
    fun countersHandOverAndStartAgainFromZero() {
        TdReadCounters.waited(120)
        TdReadCounters.waited(80)
        TdReadCounters.opened()
        TdReadCounters.opened()
        assertEquals(TdReadCounters.Reading(200L, 2), TdReadCounters.take())
        assertEquals(TdReadCounters.Reading.NONE, TdReadCounters.take())
    }

    @Test
    fun countersIgnoreNegativeWaitsAndCapAbsurdOnes() {
        TdReadCounters.waited(-5)
        TdReadCounters.waited(0)
        TdReadCounters.waited(Long.MAX_VALUE)
        assertEquals(TdReadCounters.MAX_ONE_WAIT_MS, TdReadCounters.take().waitMs)
    }

    @Test
    fun countersLoseNothingUnderThreads() {
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                repeat(1_000) {
                    TdReadCounters.waited(2)
                    TdReadCounters.opened()
                }
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()
        assertEquals(TdReadCounters.Reading(16_000L, 8_000), TdReadCounters.take())
    }

    // ---- TraceSampler ----

    private fun readings(cpuMs: Long = -1L, dropped: Int = 0, underruns: Int = 0, wait: Long = 0L) = TraceReadings(
        droppedFrames = dropped,
        underruns = underruns,
        bufferAheadMs = 8_000,
        bitrateKbps = 4_000,
        decoder = "c2.qti.avc.decoder",
        hardware = true,
        reading = TdReadCounters.Reading(wait, 1),
        cpuTimeMs = cpuMs,
        cores = 4,
    )

    @Test
    fun theFirstSampleCoversNoTimeAndLaterOnesCoverTheirWindow() {
        val sampler = TraceSampler()
        assertEquals(0L, sampler.sample(1_000, 10_000, readings()).intervalMs)
        assertEquals(5_000L, sampler.sample(6_000, 15_000, readings()).intervalMs)
    }

    @Test
    fun aClockThatWentBackwardsGivesZeroNotANegative() {
        val sampler = TraceSampler()
        sampler.rebase(20_000, 0)
        assertEquals(0L, sampler.sample(1_000, 10_000, readings()).intervalMs)
    }

    @Test
    fun cpuIsTheShareOfAllCoresOverTheWindow() {
        val sampler = TraceSampler()
        sampler.rebase(0, 1_000)
        // 5 s window, 4 cores = 20 000 core-ms; 5 000 ms used is 25 per cent.
        assertEquals(25, sampler.sample(5_000, 5_000, readings(cpuMs = 6_000)).cpuPercent)
        // More than all cores (a jumpy counter) stays at 100.
        assertEquals(100, sampler.sample(10_000, 10_000, readings(cpuMs = 60_000)).cpuPercent)
    }

    @Test
    fun cpuIsUnknownWhenNotReadOrBeforeAWindowExists() {
        val sampler = TraceSampler()
        assertEquals(TraceSample.UNKNOWN, sampler.sample(1_000, 1_000, readings(cpuMs = 500)).cpuPercent)
        assertEquals(TraceSample.UNKNOWN, sampler.sample(6_000, 6_000, readings(cpuMs = -1)).cpuPercent)
    }

    @Test
    fun theSampleCarriesTheReadingsAndIsClamped() {
        val sampler = TraceSampler()
        val s = sampler.sample(2_000, 2_000, readings(dropped = 7, underruns = 2, wait = 900).copy(decoder = "a/b c"))
        assertEquals(7, s.droppedFrames)
        assertEquals(2, s.underruns)
        assertEquals(900L, s.readWaitMs)
        assertEquals(1, s.reopens)
        assertEquals("a_b_c", s.decoder)
    }

    // ---- cadence and decoder names ----

    @Test
    fun testerBuildsReadEveryTwoSecondsAndReleasesEveryFive() {
        assertEquals(2_000L, TraceCadence.intervalMs("preview", "1.30.0-test"))
        assertEquals(2_000L, TraceCadence.intervalMs("debug", "1.30.0"))
        assertEquals(2_000L, TraceCadence.intervalMs("release", "1.30.0-beta.2"))
        assertEquals(5_000L, TraceCadence.intervalMs("release", "1.30.0"))
    }

    @Test
    fun softwareDecodersAreToldByName() {
        assertTrue(DecoderNames.isSoftware("c2.android.avc.decoder"))
        assertTrue(DecoderNames.isSoftware("OMX.google.h264.decoder"))
        assertTrue(DecoderNames.isSoftware("ffmpeg-hevc"))
        assertFalse(DecoderNames.isSoftware("c2.qti.avc.decoder"))
        assertFalse(DecoderNames.isSoftware("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertFalse(DecoderNames.isSoftware(""))
    }

    // ---- TraceFile ----

    private fun sample(at: Long, dropped: Int = 0) = TraceSample(atMs = at, intervalMs = 5_000, droppedFrames = dropped)

    @Test
    fun theFileKeepsTheLastFiveSessions() {
        val file = TraceFile(File(folder.root, "trace.jsonl"))
        repeat(8) { n ->
            file.startSession(1_000L * (n + 1))
            file.append(sample(1_000L * (n + 1) + 500, dropped = n))
        }
        val sessions = file.read()
        assertEquals(TraceLimits.MAX_SESSIONS, sessions.size)
        assertEquals(listOf(3, 4, 5, 6, 7), sessions.map { it.samples.single().droppedFrames })
    }

    @Test
    fun aKilledSessionLeavesAFileThatStillReads() {
        val target = File(folder.root, "trace.jsonl")
        val file = TraceFile(target)
        file.startSession(1_000)
        file.append(sample(2_000, 3))
        file.append(sample(3_000, 4))
        // The process died in the middle of the next line.
        target.appendText("""{"t":"s","at":4000,"iv":50""")
        val sessions = file.read()
        assertEquals(1, sessions.size)
        assertEquals(listOf(3, 4), sessions.single().samples.map { it.droppedFrames })
        // And the next session starts from a clean file.
        file.startSession(9_000)
        assertEquals(2, file.read().size)
        assertTrue(target.readText().lines().filter { it.isNotBlank() }.all { it.endsWith("}") })
    }

    @Test
    fun aHugeSessionIsCutBackToItsCap() {
        val file = TraceFile(File(folder.root, "trace.jsonl"))
        file.startSession(1)
        repeat(TraceLimits.MAX_SAMPLES_PER_SESSION * 3) { file.append(sample(10L + it)) }
        val samples = file.read().single().samples
        assertTrue(samples.size <= TraceLimits.MAX_SAMPLES_PER_SESSION)
        assertEquals(10L + TraceLimits.MAX_SAMPLES_PER_SESSION * 3 - 1, samples.last().atMs)
        assertTrue(File(folder.root, "trace.jsonl").length() <= TraceLimits.MAX_BYTES * 2L)
    }

    @Test
    fun aGarbageFileIsReplacedNotTrusted() {
        val target = File(folder.root, "trace.jsonl")
        target.writeText("not json at all\n\u0000\u0000{{{\n" + "x".repeat(5_000) + "\n")
        val file = TraceFile(target)
        file.startSession(5_000)
        file.append(sample(6_000, 2))
        val sessions = file.read()
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.single().samples.single().droppedFrames)
    }

    @Test
    fun aFolderThatCannotBeWrittenNeverThrowsAndGivesUp() {
        val blocker = File(folder.root, "blocker").apply { writeText("a file where a folder should be") }
        val file = TraceFile(File(blocker, "trace.jsonl"))
        file.startSession(1)
        repeat(10) { file.append(sample(it.toLong())) }
        assertTrue(file.givenUp)
        assertTrue(file.read().isEmpty())
        // A new session tries again from zero failures.
        file.startSession(2)
        assertFalse(file.givenUp)
    }

    @Test
    fun deleteRemovesTheFile() {
        val target = File(folder.root, "trace.jsonl")
        val file = TraceFile(target)
        file.startSession(1)
        file.append(sample(2))
        assertTrue(target.exists())
        file.delete()
        assertFalse(target.exists())
        assertTrue(file.read().isEmpty())
    }

    @Test
    fun theFileHoldsOnlyAPlainAlphabetSoNoPathOrSentenceCanRideAlong() {
        val file = TraceFile(File(folder.root, "trace.jsonl"))
        file.startSession(1)
        file.append(TraceSample(atMs = 2, decoder = "/sdcard/My Holiday.mkv", engine = "x y"))
        val text = File(folder.root, "trace.jsonl").readText()
        assertFalse(text.contains("/"))
        assertFalse(text.contains(" "))
    }
}
