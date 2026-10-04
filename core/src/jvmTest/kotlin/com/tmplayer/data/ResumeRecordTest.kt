package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeRecordTest {

    private fun encoded(
        fileId: Int = 42,
        title: String = "Harbour Notes 2026 720p.mkv",
        chatTitle: String = "Creator Clips",
        sizeBytes: Long = 1_500_000_000,
        durationSec: Int = 8176,
        updatedAt: Long = 1_700_000_000_000,
        localPath: String? = null,
    ) = ResumeRecord.encode(fileId, title, chatTitle, sizeBytes, durationSec, updatedAt, localPath)

    @Test
    fun `round trips every field`() {
        val record = ResumeRecord.decode("-100123_45", encoded(), 250_000L, 8_176_000L)!!

        assertEquals(-100123L, record.chatId)
        assertEquals(45L, record.messageId)
        assertEquals(42, record.fileId)
        assertEquals("Harbour Notes 2026 720p.mkv", record.title)
        assertEquals("Creator Clips", record.chatTitle)
        assertEquals(1_500_000_000L, record.sizeBytes)
        assertEquals(8176, record.durationSec)
        assertEquals(250_000L, record.positionMs)
        assertEquals(1_700_000_000_000L, record.updatedAt)
    }

    @Test
    fun `a record without a path reads back with none`() {
        val record = ResumeRecord.decode("1_2", encoded(), 0L, 0L)!!
        assertNull(record.localPath)
    }

    @Test
    fun `a download's path round trips as a seventh field`() {
        val path = "/data/user/0/com.tmplayer/files/downloads/Harbour Notes (2026) 720p.mkv"
        val record = ResumeRecord.decode("-100123_45", encoded(localPath = path), 0L, 0L)!!
        assertEquals(path, record.localPath)
        assertEquals("Harbour Notes 2026 720p.mkv", record.title)
        assertEquals(1_700_000_000_000L, record.updatedAt)
    }

    @Test
    fun `a line an older build wrote still decodes, with no path`() {
        // Six fields, exactly what every build before downloads had a folder wrote.
        val legacy = listOf("42", "Old.mkv", "Chat", "10", "60", "5").joinToString("\u001F")
        val record = ResumeRecord.decode("1_2", legacy, 0L, 0L)!!
        assertEquals("Old.mkv", record.title)
        assertNull(record.localPath)
    }

    @Test
    fun `a blank path is written as no path`() {
        assertEquals(encoded(), encoded(localPath = ""))
    }

    @Test
    fun `the timestamp is still found on a line with a path`() {
        assertEquals(1_700_000_000_000L, ResumeRecord.updatedAtOf(encoded(localPath = "/x/y.mkv")))
    }

    @Test
    fun `survives a title full of punctuation`() {
        val nasty = "Harbour Notes (2026) Malayalam (1080p WEB-Rip E-AC3).mkv"
        val record = ResumeRecord.decode("1_2", encoded(title = nasty), 90_000L, 0L)!!
        assertEquals(nasty, record.title)
    }

    @Test
    fun `fraction and remaining come off the duration`() {
        val record = ResumeRecord.decode("1_2", encoded(), 25_000L, 100_000L)!!
        assertEquals(0.25f, record.fraction, 0.001f)
        assertEquals(75_000L, record.remainingMs)
    }

    @Test
    fun `unknown duration reports no progress rather than guessing`() {
        val record = ResumeRecord.decode("1_2", encoded(), 25_000L, 0L)!!
        assertEquals(0f, record.fraction, 0.001f)
        assertEquals(0L, record.remainingMs)
    }

    @Test
    fun `position past the end never exceeds a full bar`() {
        val record = ResumeRecord.decode("1_2", encoded(), 150_000L, 100_000L)!!
        assertEquals(1f, record.fraction, 0.001f)
        assertEquals(0L, record.remainingMs)
    }

    @Test
    fun `rejects records an older build never wrote`() {
        assertNull(ResumeRecord.decode("1_2", null, 90_000L, 0L))
        assertNull(ResumeRecord.decode("1_2", "", 90_000L, 0L))
    }

    @Test
    fun `rejects a truncated or malformed line`() {
        assertNull(ResumeRecord.decode("1_2", "42title", 90_000L, 0L))
        assertNull(ResumeRecord.decode("1_2", encoded(fileId = 0), 90_000L, 0L))
        assertNull(ResumeRecord.decode("1_2", encoded(title = "  "), 90_000L, 0L))
    }

    @Test
    fun `rejects a key that is not a chat and message pair`() {
        assertNull(ResumeRecord.decode("nonsense", encoded(), 90_000L, 0L))
        assertNull(ResumeRecord.decode("1_2_3", encoded(), 90_000L, 0L))
        assertNull(ResumeRecord.decode("abc_2", encoded(), 90_000L, 0L))
    }

    @Test
    fun `rebuilds a playable item`() {
        val item = ResumeRecord.decode("-100123_45", encoded(), 250_000L, 0L)!!.toMediaItem()
        assertEquals(-100123L, item.chatId)
        assertEquals(45L, item.messageId)
        assertEquals(42, item.fileId)
        assertEquals(1_500_000_000L, item.sizeBytes)
    }
}
