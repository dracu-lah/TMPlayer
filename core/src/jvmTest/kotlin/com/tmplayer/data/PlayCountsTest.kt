package com.tmplayer.data

import io.sentry.SentryEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The plays counted beside the stutter report (Sentry triage, 2026-11-18): local, bounded, bucketed, only with reporting on. */
class PlayCountsTest {

    private fun tempFile(): File = Files.createTempDirectory("plays").toFile().resolve("plays.json")

    private val day = 24L * 60 * 60 * 1000

    private val omx = PlayCounts.keyOf("armeabi-v7a", "OMX.MS.HEVC.Decoder", "media3")

    @Test
    fun countsAWeekAndForgetsWhatIsOlder() {
        var now = 1_000L * day
        val counts = PlayCounts(tempFile()) { now }
        repeat(3) { counts.note(omx) }
        counts.note(PlayCounts.keyOf("armeabi-v7a", "ffmpeg", "media3"))
        assertEquals(3 to 4, counts.week(omx))
        now += 6 * day
        counts.note(omx)
        assertEquals(4 to 5, counts.week(omx))
        // The first day falls out of the window.
        now += day
        assertEquals(1 to 1, counts.week(omx))
        now += 30 * day
        assertEquals(0 to 0, counts.week(omx))
    }

    @Test
    fun aNewProcessReadsTheSameFile() {
        val file = tempFile()
        PlayCounts(file) { 5 * day }.note(omx)
        assertEquals(1 to 1, PlayCounts(file) { 5 * day }.week(omx))
    }

    @Test
    fun keysAndCountsAreBounded() {
        val file = tempFile()
        val counts = PlayCounts(file) { 9 * day }
        repeat(PlayCounts.MAX_KEYS + 20) { counts.note(PlayCounts.keyOf("arm64-v8a", "decoder$it", "media3")) }
        assertEquals(PlayCounts.MAX_KEYS, counts.week("x").second)
        assertEquals(0, counts.week(PlayCounts.keyOf("arm64-v8a", "decoder${PlayCounts.MAX_KEYS + 5}", "media3")).first)
        assertTrue(file.length() < 4_096, "a few kilobytes at most: ${file.length()}")
        // A key is three cleaned words: a path or a title cannot ride in it.
        val dirty = PlayCounts.keyOf("/sdcard/Secret Show.mkv", "", "x\ny")
        assertFalse(dirty.contains('/'))
        assertEquals("unknown", dirty.split('|')[1])
    }

    @Test
    fun clearDeletesTheFile() {
        val file = tempFile()
        val counts = PlayCounts(file) { day }
        counts.note(omx)
        assertTrue(file.exists())
        counts.clear()
        assertFalse(file.exists())
        assertEquals(0 to 0, counts.week(omx))
    }

    @Test
    fun aBrokenFileIsNoCount() {
        val file = tempFile()
        for (garbage in listOf("", "{", "[]", "{\"days\":5}", "{\"days\":[{\"day\":\"x\",\"counts\":[]}]}", "{\"days\":[{\"day\":3,\"counts\":{\"k\":\"many\",\"j\":-4}}]}", "\u0000".repeat(100))) {
            file.parentFile.mkdirs()
            file.writeText(garbage)
            val counts = PlayCounts(file) { 3 * day }
            assertEquals(0 to 0, counts.week("k"), garbage)
            counts.note("k")
            assertEquals(1 to 1, counts.week("k"), garbage)
        }
        // Absurd counts are capped, a huge file is not read at all.
        file.writeText("{\"days\":[{\"day\":3,\"counts\":{\"k\":2147483647}}]}")
        assertEquals(PlayCounts.MAX_COUNT to PlayCounts.MAX_COUNT, PlayCounts(file) { 3 * day }.week("k"))
        file.writeText("{\"days\":[{\"day\":3,\"counts\":{\"k\":5}}]}" + " ".repeat(100_000))
        assertEquals(0 to 0, PlayCounts(file) { 3 * day }.week("k"))
    }

    @Test
    fun theReporterCountsOnlyWithReportingOnAndSendsBucketsOnly() {
        val dir = Files.createTempDirectory("plays").toFile()
        val plays = PlayCounts(dir.resolve("plays.json")) { 7 * day }
        val sent = mutableListOf<SentryEvent>()
        var on = false
        val reporter = StutterReporter({ on }, StutterLimits(dir.resolve("stutter.json")) { 7 * day }, sent::add, plays)
        reporter.played("armeabi-v7a", "OMX.MS.HEVC.Decoder", "media3")
        assertFalse(dir.resolve("plays.json").exists(), "nothing kept with reporting off")
        on = true
        repeat(12) { reporter.played("armeabi-v7a", "OMX.MS.HEVC.Decoder", "media3") }
        repeat(30) { reporter.played("armeabi-v7a", "ffmpeg", "media3") }
        val facts = StutterReport.Facts(
            codec = "hevc", profile = "hvc1.1.6.L120.B0", bitDepth = 8, width = 1920, height = 1080, fps = 25f,
            bitrateKbps = 0, container = "video/x-matroska", decoder = "OMX.MS.HEVC.Decoder", hardware = true,
            prediction = "HARDWARE", speedPercent = 60, dropped = 0, frames = 300, underruns = 0,
            manufacturer = "Changhong", model = "AI PONT", androidVersion = "11", cores = 4, abi = "armeabi-v7a",
            appVersion = "1.30.0", sizeBucket = 1080, symptom = "slow",
        )
        assertTrue(reporter.report(facts, "media3"))
        val tags = sent.single().tags!!
        assertEquals("10-19", tags["plays_7d_same"])
        assertEquals("20-49", tags["plays_7d_all"])
        assertEquals(StutterReport.ALLOWED_TAGS, tags.keys)
        reporter.forgetPlays()
        assertFalse(dir.resolve("plays.json").exists())
    }

    @Test
    fun bucketsNeverCarryTheNumber() {
        assertEquals("unknown", StutterReport.bucketOf(-1))
        assertEquals("0", StutterReport.bucketOf(0))
        assertEquals("1", StutterReport.bucketOf(1))
        assertEquals("2-4", StutterReport.bucketOf(4))
        assertEquals("5-9", StutterReport.bucketOf(5))
        assertEquals("10-19", StutterReport.bucketOf(19))
        assertEquals("20-49", StutterReport.bucketOf(20))
        assertEquals("50+", StutterReport.bucketOf(Int.MAX_VALUE))
        for (n in listOf(Int.MIN_VALUE, -1, 0, 1, 3, 7, 13, 33, 51, 9_999, Int.MAX_VALUE)) {
            assertTrue(StutterReport.bucketOf(n) in StutterReport.BUCKETS)
        }
        assertEquals("unknown", StutterReport.tagsOf(
            StutterReport.Facts(
                codec = "", profile = "", bitDepth = 8, width = 0, height = 0, fps = 0f, bitrateKbps = 0, container = "",
                decoder = "", hardware = true, prediction = "", speedPercent = 0, dropped = 0, frames = 0, underruns = 0,
                manufacturer = "", model = "", androidVersion = "", cores = 0, abi = "", appVersion = "", sizeBucket = 0,
            ),
        )["plays_7d_same"])
    }
}
