package com.tmplayer.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one bundle behind Send logs (L5): every part scrubbed, in order, under the cap, oldest sessions first out. */
class LogBundleTest {

    private val facts = Feedback.Facts("1.30.0", Feedback.Platform.AndroidTv, "Acme Box, Android 11", "en")
    private val at = LocalDateTime.of(2026, 11, 15, 10, 30)

    private fun session(n: Int, lines: Int = 3) = (0 until lines).map { "session $n line $it" }

    @Test
    fun `every part is present, in order, and a part with nothing in it is left out`() {
        val result = LogBundle.build(
            facts,
            LogBundle.Parts(
                crash = listOf("Earlier run crashed"),
                device = listOf("RAM 1 GB"),
                decoders = listOf("video/avc", "  c2.android.avc.decoder, software, up to 1920x1080"),
                sessions = listOf(session(1), session(2)),
                marks = listOf("Marked at x"),
                appLog = listOf("W/Td: failed"),
            ),
            now = at,
        )
        val kinds = result.parts.map { it.kind }
        assertEquals(
            listOf(
                LogBundle.Kind.CRASH, LogBundle.Kind.DEVICE, LogBundle.Kind.DECODERS,
                LogBundle.Kind.SESSIONS, LogBundle.Kind.MARKS, LogBundle.Kind.APP_LOG,
            ),
            kinds,
        )
        assertEquals(2, result.parts.first { it.kind == LogBundle.Kind.SESSIONS }.count)
        val text = result.text
        assertTrue(text.startsWith("TMPlayer logs\nApp version: 1.30.0\nPlatform:"))
        val order = listOf("== Earlier run", "== Device", "== Decoders", "== Playback sessions", "== Moments", "== App log")
            .map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertFalse("== Playback test" in text)
        assertFalse("== Logcat" in text)
        assertEquals(result.bytes, text.encodeToByteArray().size)
    }

    @Test
    fun `a video name in any part is taken out, not only in the log`() {
        val title = "Kerala Crime Files"
        val result = LogBundle.build(
            facts,
            LogBundle.Parts(
                crash = listOf("died opening $title"),
                sessions = listOf(listOf("started $title S01E02")),
                marks = listOf("marked while $title played"),
                playbackTest = listOf("tested $title.mkv"),
                appLog = listOf("I/Player: opening $title"),
                logcat = listOf("10-13 14:05 W Player: $title failed, chat -1001234567890"),
            ),
            known = listOf(title),
            now = at,
        )
        assertFalse(result.text.contains("Kerala", ignoreCase = true))
        assertFalse(result.text.contains("1001234567890"))
        assertTrue(result.text.contains("[name]"))
    }

    @Test
    fun `over the cap the oldest sessions go first and the report says so`() {
        val sessions = (1..5).map { n -> listOf("session $n header") + List(200) { "session $n reading $it with some padding text" } }
        val one = sessions.first().sumOf { it.length + 1 }
        val cap = one * 3 + 3_000
        val result = LogBundle.build(
            facts,
            LogBundle.Parts(sessions = sessions, appLog = listOf("W/Td: kept")),
            now = at,
            maxBytes = cap,
        )
        assertTrue(result.bytes <= cap)
        assertTrue(result.droppedSessions >= 2)
        assertEquals(0, result.droppedLogLines)
        assertFalse("session 1 header" in result.text)
        assertTrue("session 5 header" in result.text)
        assertTrue("older playback session(s)" in result.text)
        assertTrue("W/Td: kept" in result.text)
        assertTrue(result.trimmed)
    }

    @Test
    fun `when sessions are gone the oldest log lines go, and the newest stay`() {
        val log = (1..3000).map { "I/Home: line number $it of the log" }
        val result = LogBundle.build(facts, LogBundle.Parts(appLog = log), now = at, maxBytes = 40_000)
        assertTrue(result.bytes <= 40_000)
        assertTrue(result.droppedLogLines > 0)
        assertTrue("line number 3000 of the log" in result.text)
        assertFalse("line number 1 of the log" in result.text)
    }

    @Test
    fun `a cap smaller than the header alone still gives a whole line file under it`() {
        val result = LogBundle.build(facts, LogBundle.Parts(device = List(50) { "fact $it" }), now = at, maxBytes = 200)
        assertTrue(result.bytes <= 200)
        assertTrue(result.text.endsWith("\n"))
        assertTrue(result.text.startsWith("TMPlayer logs"))
    }

    @Test
    fun `the default cap leaves room under the upload's two megabytes`() {
        assertTrue(LogBundle.MAX_BYTES < 2 * 1024 * 1024)
        val huge = List(40_000) { "I/Home: " + "x".repeat(200) }
        val result = LogBundle.build(facts, LogBundle.Parts(appLog = huge, logcat = huge), now = at)
        assertTrue(result.bytes <= LogBundle.MAX_BYTES)
    }

    @Test
    fun `garbage lines go through without throwing`() {
        val weird = listOf("", "\u0000\u0001", "\uD800", "x".repeat(100_000), "@@@@@ +++ 1234567890123", "\r\n")
        val result = LogBundle.build(facts, LogBundle.Parts(crash = weird, device = weird, sessions = listOf(weird, emptyList()), appLog = weird, logcat = weird), now = at)
        assertTrue(result.text.isNotEmpty())
    }

    @Test
    fun `decoders read hardware first, the video's codec first, and an empty list says so`() {
        val lines = DecoderReport.lines(
            listOf(
                DecoderReport.Decoder("c2.android.hevc.decoder", "video/hevc", false, 4096, 2304, 0),
                DecoderReport.Decoder("c2.qti.avc.decoder", "video/avc", true, 4096, 2160, 120),
                DecoderReport.Decoder("c2.android.avc.decoder", "video/avc", false, 1920, 1088, 0),
                DecoderReport.Decoder("OMX.old.vp9", "video/x-vnd.on2.vp9", null),
            ),
            videoMime = "video/hevc",
        )
        assertEquals("video/hevc (the last video played)", lines[0])
        assertEquals("  c2.android.hevc.decoder, software, up to 4096x2304", lines[1])
        assertEquals("video/avc", lines[2])
        assertEquals("  c2.qti.avc.decoder, hardware, up to 4096x2160, up to 120 fps", lines[3])
        assertEquals("  c2.android.avc.decoder, software, up to 1920x1088", lines[4])
        assertTrue(lines.last().endsWith("size not told"))
        assertTrue("hardware or software, not told" in lines.last())
        assertEquals(listOf("No decoders listed for the video's codec."), DecoderReport.lines(emptyList()))
    }

    @Test
    fun `a decoder name with odd characters is kept to a plain alphabet`() {
        val line = DecoderReport.lines(listOf(DecoderReport.Decoder("evil\nname <b>", "video/avc", true))).last()
        assertFalse('\n' in line)
        assertFalse('<' in line)
    }
}
