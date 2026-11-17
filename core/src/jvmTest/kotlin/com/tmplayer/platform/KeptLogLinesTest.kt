package com.tmplayer.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS's kept log: TDLib's lines read as the app's, and the two merged by time. */
class KeptLogLinesTest {
    // A fixed way to write a moment, UTC, so the test does not hang on the machine's zone.
    private val stamp: (Long) -> String = { millis ->
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
    }

    @Test
    fun `a TDLib line takes the app's shape, with its level, thread and place`() {
        val lines = KeptLogLines.fromTdlib(
            listOf("[ 2][t 4][1763241031.664125060][AuthManager.cpp:1234][#1][!Td]\tLost authorization because of AUTH_KEY_UNREGISTERED"),
            stamp,
        )
        assertEquals(
            listOf("2025-11-15 21:10:31.664 tdlib t4 W/TDLib: [AuthManager.cpp:1234] Lost authorization because of AUTH_KEY_UNREGISTERED"),
            lines,
        )
    }

    @Test
    fun `each level has its letter`() {
        val levels = (0..5).map { level -> KeptLogLines.fromTdlib(listOf("[ $level][t 1][1.0][A.cpp:1] m"), stamp).single() }
        assertEquals(listOf("F", "E", "W", "I", "D", "V"), levels.map { it.substringAfter("t1 ").take(1) })
    }

    @Test
    fun `the rest of a message that ran over a line keeps the moment before it`() {
        val lines = KeptLogLines.fromTdlib(
            listOf("orphan before anything", "[ 1][t 2][1763241031.5][Net.cpp:9] first", "  and its second line", ""),
            stamp,
        )
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].startsWith("2025-11-15 21:10:31.500 tdlib t2   and its second line"))
    }

    @Test
    fun `the app's and TDLib's lines merge by time, the app's first at the same moment`() {
        val app = listOf(
            "2026-11-15 21:10:31.000 7968 t1 I/Td: auth: WaitPhoneNumber -> Phone",
            "2026-11-15 21:10:33.000 7968 t1 I/Td: auth: WaitCode -> Code",
        )
        val tdlib = listOf(
            "2026-11-15 21:10:32.000 tdlib t4 W/TDLib: [Session.cpp:1] reconnect",
            "2026-11-15 21:10:33.000 tdlib t4 E/TDLib: [Auth.cpp:2] same moment",
        )
        assertEquals(listOf(app[0], tdlib[0], app[1], tdlib[1]), KeptLogLines.merge(app, tdlib, max = 10))
    }

    @Test
    fun `the merge keeps the last lines only`() {
        val app = (1..5).map { "2026-11-15 21:10:3$it.000 1 t1 I/x: $it" }
        assertEquals(app.takeLast(3), KeptLogLines.merge(app, emptyList(), max = 3))
    }

    @Test
    fun `garbage from TDLib's file is skipped, not a failure`() {
        val garbage = listOf("[", "[ x][t y][z][w]", "[ 1][t 1][99999999999999999999999.1][A] m", "\u0000\u0001", "[ 1][t 1]")
        assertTrue(KeptLogLines.fromTdlib(garbage, stamp).isEmpty())
    }
}
