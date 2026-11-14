package com.tmplayer.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackTraceTest {

    private fun sample(at: Long, dropped: Int = 0, decoder: String = "c2.qti.avc.decoder") =
        TraceSample(atMs = at, intervalMs = 5_000, droppedFrames = dropped, bufferAheadMs = 8_000, decoder = decoder, engine = "exoplayer")

    @Test
    fun roundTripKeepsEveryField() {
        val s = TraceSample(
            atMs = 1_763_200_005_000, intervalMs = 5_000, droppedFrames = 7, underruns = 2, bufferAheadMs = 8_200,
            bitrateKbps = 4_500, decoder = "c2.qti.hevc.decoder", hardware = false, readWaitMs = 1_200, reopens = 3,
            tdSpeedBps = 2_000_000, cpuPercent = 61, memoryLevel = 10, thermal = 2, engine = "vlc",
        )
        val text = PlaybackTrace.encode(listOf(TraceSession(1_763_200_000_000, listOf(s))))
        assertEquals(listOf(TraceSession(1_763_200_000_000, listOf(s))), PlaybackTrace.read(text))
    }

    @Test
    fun emptyAndBlankTextsGiveNothing() {
        assertEquals(emptyList(), PlaybackTrace.read(""))
        assertEquals(emptyList(), PlaybackTrace.read("\n\n  \n"))
        assertEquals(emptyList(), PlaybackTraceRing().sessions())
        assertEquals("", PlaybackTrace.encode(emptyList()))
    }

    @Test
    fun aSessionWithNoSamplesIsKept() {
        val text = PlaybackTrace.sessionLine(5_000) + "\n"
        assertEquals(listOf(TraceSession(5_000, emptyList())), PlaybackTrace.read(text))
    }

    @Test
    fun aSessionKilledMidWriteStaysValid() {
        val whole = PlaybackTrace.encode(listOf(TraceSession(1_000, listOf(sample(2_000), sample(3_000), sample(4_000)))))
        // Cut anywhere: the earlier lines always survive, the cut one never makes a sample.
        for (cut in whole.indices) {
            val read = PlaybackTrace.read(whole.substring(0, cut))
            val complete = whole.substring(0, cut).count { it == '\n' }
            val samples = read.sumOf { it.samples.size }
            assertTrue(samples <= 3 && samples >= (complete - 1).coerceAtLeast(0), "cut at $cut gave $samples samples")
        }
    }

    @Test
    fun headCutOffFileStillReads() {
        val whole = PlaybackTrace.encode(listOf(TraceSession(1_000, listOf(sample(2_000), sample(3_000)))))
        val cut = whole.substring(whole.indexOf('\n') + 1 + 20)
        val read = PlaybackTrace.read(cut)
        assertEquals(1, read.size)
        assertEquals(listOf(3_000L), read.single().samples.map { it.atMs })
    }

    @Test
    fun clockGoingBackwardsIsHeldAtTheLastTime() {
        val ring = PlaybackTraceRing()
        ring.startSession(10_000)
        ring.add(sample(20_000))
        ring.add(sample(5_000))
        ring.add(sample(30_000))
        assertEquals(listOf(20_000L, 20_000L, 30_000L), ring.currentSamples().map { it.atMs })
        val read = PlaybackTrace.read(
            PlaybackTrace.sessionLine(10_000) + "\n" + PlaybackTrace.sampleLine(sample(20_000)) + "\n" +
                PlaybackTrace.sampleLine(sample(1)) + "\n",
        )
        assertEquals(listOf(20_000L, 20_000L), read.single().samples.map { it.atMs })
    }

    @Test
    fun negativeAndAbsurdValuesAreClamped() {
        val s = TraceSample(
            atMs = -5, intervalMs = Long.MAX_VALUE, droppedFrames = Int.MAX_VALUE, underruns = -3, bufferAheadMs = Long.MIN_VALUE,
            bitrateKbps = Int.MAX_VALUE, decoder = "OMX/a b\n\"x\"" + "y".repeat(500), readWaitMs = -1, reopens = -1,
            tdSpeedBps = Long.MAX_VALUE, cpuPercent = 4_000, memoryLevel = -9, thermal = 99, engine = "../etc",
        ).clamped()
        assertEquals(0L, s.atMs)
        assertEquals(TraceLimits.MAX_INTERVAL_MS, s.intervalMs)
        assertEquals(TraceLimits.MAX_COUNT, s.droppedFrames)
        assertEquals(0, s.underruns)
        assertEquals(0L, s.bufferAheadMs)
        assertEquals(TraceLimits.MAX_BITRATE_KBPS, s.bitrateKbps)
        assertEquals(TraceLimits.NAME_CHARS, s.decoder.length)
        assertTrue(s.decoder.startsWith("OMX_a_b__x_"))
        assertEquals(0L, s.readWaitMs)
        assertEquals(TraceLimits.MAX_SPEED_BPS, s.tdSpeedBps)
        assertEquals(100, s.cpuPercent)
        assertEquals(0, s.memoryLevel)
        assertEquals(TraceLimits.MAX_THERMAL, s.thermal)
        assertEquals(".._etc", s.engine)
        assertEquals(TraceSample.UNKNOWN, TraceSample(0, cpuPercent = -50, thermal = -7).clamped().let { minOf(it.cpuPercent, it.thermal) })
    }

    @Test
    fun aSampleLineHasNoFreeTextFieldAndNamesAreMachineNames() {
        val line = PlaybackTrace.sampleLine(sample(1_000, decoder = "Kerala Crime Files/S01E02 \"x\"\n" + "y".repeat(500)))
        // The only strings are the decoder and the engine: no spaces, quotes, slashes or breaks, and capped.
        assertTrue(' ' !in line && '/' !in line.substringAfter("\"dc\"") && '\n' !in line)
        assertTrue(line.length < 400, "line is ${line.length}")
        val keys = com.tmplayer.json.JSONObject(line).keySet()
        assertEquals(setOf("t", "at", "iv", "df", "ur", "ba", "br", "dc", "hw", "rw", "ro", "sp", "cp", "mm", "th", "en"), keys)
    }

    @Test
    fun aHugeSessionIsCappedPerSession() {
        val ring = PlaybackTraceRing(maxSamples = 100)
        ring.startSession(0)
        for (i in 1..10_000) ring.add(sample(i * 5_000L))
        assertEquals(100, ring.currentSamples().size)
        assertEquals(10_000 * 5_000L, ring.currentSamples().last().atMs)
        assertEquals(PlaybackTrace.encode(ring.sessions()).length, ring.size)
    }

    @Test
    fun oldestSessionsGoFirst() {
        val ring = PlaybackTraceRing(maxSessions = 3)
        for (i in 1..7) {
            ring.startSession(i * 1_000L)
            ring.add(sample(i * 1_000L + 1))
        }
        assertEquals(listOf(5_000L, 6_000L, 7_000L), ring.sessions().map { it.startedAtMs })
        assertEquals(PlaybackTrace.encode(ring.sessions()).length, ring.size)
    }

    @Test
    fun theByteCapDropsOldReadingsAndKeepsTheNewest() {
        val ring = PlaybackTraceRing(maxBytes = 2_000)
        for (s in 1..3) {
            ring.startSession(s * 1_000_000L)
            for (i in 1..40) ring.add(sample(s * 1_000_000L + i))
        }
        assertTrue(ring.size <= 2_000, "size ${ring.size}")
        assertEquals(PlaybackTrace.encode(ring.sessions()).length, ring.size)
        assertEquals(3_000_040L, ring.currentSamples().last().atMs)
        // A cap smaller than one line still keeps the newest reading.
        val tiny = PlaybackTraceRing(maxBytes = 1)
        tiny.add(sample(1)); tiny.add(sample(2))
        assertEquals(listOf(2L), tiny.currentSamples().map { it.atMs })
    }

    @Test
    fun readerHoldsTheSameCaps() {
        val ring = PlaybackTraceRing(maxSamples = 10_000, maxSessions = 10)
        for (s in 1..8) {
            ring.startSession(s * 1_000L)
            for (i in 1..800) ring.add(sample(s * 1_000L + i))
        }
        val read = PlaybackTrace.read(PlaybackTrace.encode(ring.sessions()))
        assertEquals(TraceLimits.MAX_SESSIONS, read.size)
        assertTrue(read.all { it.samples.size <= TraceLimits.MAX_SAMPLES_PER_SESSION })
    }

    @Test
    fun ringContinuesAFile() {
        val text = PlaybackTrace.encode(listOf(TraceSession(1_000, listOf(sample(2_000)))))
        val ring = PlaybackTraceRing.from(text)
        ring.add(sample(3_000))
        assertEquals(listOf(2_000L, 3_000L), ring.currentSamples().map { it.atMs })
    }

    @Test
    fun garbageLinesAreSkippedAndGoodOnesKept() {
        val good = PlaybackTrace.sampleLine(sample(2_000))
        val text = listOf(
            "not json", "{", "[1,2]", "{\"t\":\"s\"}", "{\"t\":\"s\",\"at\":\"x\"}", "{\"t\":5,\"at\":1}", "{\"t\":\"zzz\",\"at\":1}",
            "{\"t\":\"s\",\"at\":1e999,\"df\":\"many\",\"dc\":{},\"hw\":7,\"cp\":[]}",
            "x".repeat(100_000), "{\"t\":\"s\",\"at\":5,\"dc\":\"" + "d".repeat(5_000) + "\"}", good,
        ).joinToString("\n")
        val read = PlaybackTrace.read(text)
        assertTrue(read.sumOf { it.samples.size } >= 1)
        assertTrue(read.flatMap { it.samples }.any { it.atMs == 2_000L })
    }

    @Test
    fun randomTextNeverThrows() {
        val random = Random(7)
        val alphabet = "{}[]\":,.-0123456789etsaUdfrbpchmn\n\r \u0000é😀\\"
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 400)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            PlaybackTrace.read(text)
            PlaybackTrace.read(text, maxSessions = 0)
            PlaybackTraceRing.from(text)
        }
    }
}
