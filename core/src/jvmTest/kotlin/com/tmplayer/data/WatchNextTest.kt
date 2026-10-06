package com.tmplayer.data

import com.tmplayer.data.WatchNext.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextTest {

    private fun video(message: Long, durationSec: Int = 2_640) = WatchNext.Video(
        chatId = -100L, messageId = message, fileId = message.toInt(), title = "Show S01E0$message",
        chatTitle = "Show", sizeBytes = 1_000_000, durationSec = durationSec,
    )

    private val ep4 = video(4)
    private val ep5 = video(5)
    private val duration = 2_640_000L
    private val now = 1_700_000_000_000L

    private fun changes(
        position: Long,
        next: WatchNext.Video? = ep5,
        enabled: Boolean = true,
        supported: Boolean = true,
    ) = WatchNext.changesFor(ep4, position, duration, now, next, enabled, supported)

    @Test
    fun `off or unsupported writes nothing`() {
        assertTrue(changes(1_000_000, enabled = false).isEmpty())
        assertTrue(changes(1_000_000, supported = false).isEmpty())
    }

    @Test
    fun `nothing in the first three minutes`() {
        assertTrue(changes(179_000).isEmpty())
    }

    @Test
    fun `part way through is a continue entry at the position`() {
        val change = changes(1_000_000).single() as Change.Upsert
        assertEquals(WatchNext.Kind.Continue, change.entry.kind)
        assertEquals(1_000_000L, change.entry.positionMs)
        assertEquals(duration, change.entry.durationMs)
        assertEquals(now, change.entry.lastEngagementMs)
        assertEquals("-100_4", change.entry.internalId)
    }

    @Test
    fun `watched removes it and offers the next episode from the start`() {
        val result = changes((duration * 0.95).toLong())
        assertEquals(Change.Remove("-100_4"), result[0])
        val next = (result[1] as Change.Upsert).entry
        assertEquals(WatchNext.Kind.Next, next.kind)
        assertEquals(ep5, next.video)
        assertEquals(0L, next.positionMs)
        assertEquals(2_640_000L, next.durationMs)
    }

    @Test
    fun `watched with no next episode only removes`() {
        assertEquals(listOf(Change.Remove("-100_4")), changes((duration * 0.95).toLong(), next = null))
    }

    @Test
    fun `the watched line wins over the resume line`() {
        val at89 = changes((duration * 0.89).toLong()).single() as Change.Upsert
        assertEquals(WatchNext.Kind.Continue, at89.entry.kind)
        assertEquals(Change.Remove("-100_4"), changes((duration * 0.91).toLong(), next = null).single())
    }

    @Test
    fun `an unknown length still continues`() {
        val change = WatchNext.changesFor(ep4, 600_000, 0, now, ep5, enabled = true, supported = true).single()
        assertEquals(WatchNext.Kind.Continue, (change as Change.Upsert).entry.kind)
    }

    @Test
    fun `a video without a message is not offered`() {
        val loose = ep4.copy(chatId = 0, messageId = 0)
        assertTrue(WatchNext.changesFor(loose, 1_000_000, duration, now, ep5, enabled = true, supported = true).isEmpty())
    }
}
