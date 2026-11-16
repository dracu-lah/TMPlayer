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
    fun version2KeepsFactsAndHopsThroughTheFile() {
        val facts = linkedMapOf("class" to "amlogic1/28/hevc", "dec.name" to "OMX.amlogic.hevc.decoder", "dev.sdk" to "28", "cnt.dropped" to "12")
        val hop = TraceHop(atMs = 3_000, evidence = "Frozen", from = "default", to = "ex")
        val session = TraceSession(1_000, listOf(sample(4_000)), facts, listOf(hop))
        val text = PlaybackTrace.encode(listOf(session))
        assertTrue("\"v\":2" in text)
        assertEquals(listOf(session), PlaybackTrace.read(text))
    }

    @Test
    fun aFileAnOlderAppWroteStillReadsWithNoFacts() {
        val v1 = "{\"t\":\"session\",\"v\":1,\"at\":1000}\n" + PlaybackTrace.sampleLine(sample(2_000)) + "\n"
        val read = PlaybackTrace.read(v1)
        assertEquals(1, read.size)
        assertEquals(emptyMap(), read.single().facts)
        assertEquals(emptyList(), read.single().hops)
        assertEquals(listOf(2_000L), read.single().samples.map { it.atMs })
    }

    @Test
    fun aNewerFileWithLinesAndKeysWeDoNotKnowStillReads() {
        val text = listOf(
            "{\"t\":\"session\",\"v\":9,\"at\":1000,\"extra\":1}",
            "{\"t\":\"f\",\"at\":1001,\"k\":{\"dev.sdk\":\"34\",\"unknown.key\":\"free text\",\"dev.model\":\"a\\nb\\\"c@d\"}}",
            "{\"t\":\"q\",\"at\":1002,\"new\":true}",
            PlaybackTrace.sampleLine(sample(2_000)),
        ).joinToString("\n")
        val session = PlaybackTrace.read(text).single()
        assertEquals(mapOf("dev.sdk" to "34", "dev.model" to "a_b_c_d"), session.facts)
        assertEquals(1, session.samples.size)
    }

    @Test
    fun aLongSetOfFactsStaysInLinesTheReaderTakes() {
        val facts = TraceFacts.KEYS.associateWith { "v".repeat(300) }
        val text = PlaybackTrace.encode(listOf(TraceSession(1_000, emptyList(), facts)))
        assertTrue(text.lines().all { it.length <= TraceLimits.MAX_LINE_CHARS })
        val read = PlaybackTrace.read(text).single().facts
        assertEquals(TraceFacts.KEYS, read.keys)
        assertTrue(read.values.all { it.length == TraceFacts.MAX_VALUE_CHARS })
    }

    @Test
    fun hopsAreCappedAndBadFactLinesSkipped() {
        val hops = (1..30).map { TraceHop(atMs = it * 1_000L, evidence = "Frozen", to = "rl") }
        val text = PlaybackTrace.encode(listOf(TraceSession(500, emptyList(), emptyMap(), hops)))
        assertEquals(TraceFacts.MAX_HOPS, PlaybackTrace.read(text).single().hops.size)
        val bad = listOf(
            PlaybackTrace.sessionLine(500), "{\"t\":\"f\",\"at\":600,\"k\":\"nope\"}", "{\"t\":\"f\",\"at\":600,\"k\":{\"dev.sdk\":{\"a\":1}}}",
            "{\"t\":\"h\"}", "{\"t\":\"f\",\"at\":-4,\"k\":{\"dev.sdk\":\"1\"}}",
        ).joinToString("\n")
        assertEquals(emptyMap(), PlaybackTrace.read(bad).single().facts)
    }

    @Test
    fun theRingCountsFactsAndHopsInItsBytesAndDropsThemWithTheirSession() {
        val ring = PlaybackTraceRing()
        ring.startSession(1_000)
        ring.addFacts(mapOf("dev.sdk" to "28", "bad.key" to "x"))
        ring.addFacts(mapOf("dev.sdk" to "29", "dec.hw" to "true"))
        ring.addHop(TraceHop(2_000, "Frozen", to = "rl"))
        ring.add(sample(3_000))
        assertEquals(mapOf("dev.sdk" to "29", "dec.hw" to "true"), ring.currentFacts())
        assertEquals(1, ring.currentHops().size)
        assertEquals(PlaybackTrace.encode(ring.sessions()).length, ring.size)
        val small = PlaybackTraceRing(maxSessions = 1)
        small.startSession(1)
        small.addFacts(mapOf("dev.sdk" to "28"))
        small.startSession(2)
        assertEquals(PlaybackTrace.encode(small.sessions()).length, small.size)
        assertEquals(emptyMap(), small.currentFacts())
        assertEquals(ring.sessions(), PlaybackTraceRing.from(PlaybackTrace.encode(ring.sessions())).sessions())
    }

    @Test
    fun aHopSaidAgainWithItsResultReplacesTheLineItUpdates() {
        val pending = TraceHop(5_000, "Frozen", "default", "ex")
        val ring = PlaybackTraceRing()
        ring.startSession(1_000)
        ring.addHop(pending)
        ring.addHop(pending.copy(result = TraceHop.WORKED))
        assertEquals(listOf(TraceHop.WORKED), ring.currentHops().map { it.result })
        assertEquals(PlaybackTrace.encode(ring.sessions()).length, ring.size)
        // The file has both lines, the reader keeps the later one.
        val text = PlaybackTrace.sessionLine(1_000) + "\n" + PlaybackTrace.hopLine(pending) + "\n" +
            PlaybackTrace.hopLine(pending.copy(result = TraceHop.FAILED)) + "\n"
        assertEquals(listOf(TraceHop.FAILED), PlaybackTrace.read(text).single().hops.map { it.result })
        assertTrue(pending.sameStep(pending.copy(result = "x")))
        assertEquals(false, pending.sameStep(pending.copy(to = "sw")))
    }

    @Test
    fun factLinesGroupAndOrderForTheBundle() {
        val lines = TraceFacts.lines(
            mapOf("dev.sdk" to "28", "class" to "k1", "dec.name" to "c2.a"),
            listOf(TraceHop(10_000, "Frozen", "default", "ex", "worked"), TraceHop(15_000, "Stutter", "ex", "sw")),
        )
        assertEquals("  Ladder: class=k1", lines[0])
        assertEquals("  Decoder: dec.name=c2.a", lines[1])
        assertEquals("  Device: dev.sdk=28", lines[2])
        assertEquals("  Hop +0s Frozen: default -> ex, worked", lines[3])
        assertEquals("  Hop +5s Stutter: ex -> sw, pending", lines[4])
        assertEquals(emptyList(), TraceFacts.lines(emptyMap(), emptyList()))
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
