package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class WatchedWhenTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val now = at(2026, 10, 4, 9, 30)
    private fun label(then: Long) = WatchedWhen.label(then, now, zone)

    @Test
    fun `the last hour counts minutes`() {
        assertEquals("Just now", label(now - 20_000))
        assertEquals("1 minute ago", label(now - 60_000))
        assertEquals("45 minutes ago", label(now - 45 * 60_000))
    }

    @Test
    fun `a clock ahead of this one still reads as just now`() {
        assertEquals("Just now", label(now + 3_600_000))
    }

    @Test
    fun `days are calendar days, not 24 hour spans`() {
        assertEquals("Today", label(at(2026, 10, 4, 0, 5)))
        assertEquals("Yesterday", label(at(2026, 10, 3, 23, 0)))
        assertEquals("2 days ago", label(at(2026, 10, 2, 23, 59)))
        assertEquals("6 days ago", label(at(2026, 9, 28)))
    }

    @Test
    fun `a week or more is a date, with the year only when it differs`() {
        assertEquals("27 Sep", label(at(2026, 9, 27)))
        assertEquals("1 Jan", label(at(2026, 1, 1)))
        assertEquals("31 Dec 2025", label(at(2025, 12, 31)))
    }

    @Test
    fun `the phrase reads as a sentence whichever kind of label it carries`() {
        fun phrase(then: Long) = WatchedWhen.phrase(then, now, zone)
        assertEquals("Watched just now", phrase(now - 1_000))
        assertEquals("Watched 5 minutes ago", phrase(now - 5 * 60_000))
        assertEquals("Watched today", phrase(at(2026, 10, 4, 1, 0)))
        assertEquals("Watched yesterday", phrase(at(2026, 10, 3)))
        assertEquals("Watched 3 days ago", phrase(at(2026, 10, 1)))
        assertEquals("Watched on 27 Sep", phrase(at(2026, 9, 27)))
        assertEquals("Watched on 31 Dec 2025", phrase(at(2025, 12, 31)))
    }
}
