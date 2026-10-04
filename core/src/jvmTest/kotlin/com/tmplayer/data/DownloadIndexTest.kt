package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The `dl_` records as the index of the viewer's downloads: what is written, what reads back, and
 * that none is ever forgotten behind the viewer's back.
 */
class DownloadIndexTest {

    private val dir = Files.createTempDirectory("tm-index").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    private fun item(id: Int) = MediaItem(
        chatId = -100L, messageId = id.toLong(), fileId = id, title = "Episode $id", sizeBytes = 1000L + id,
        durationSec = 60, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `a download noted without a path reads back as a legacy record`() = runBlocking {
        settings.noteDownload(item(1), "Chat")
        val record = settings.downloadRecord(-100L, 1L)!!
        assertEquals("Episode 1", record.title)
        assertEquals(1001L, record.sizeBytes)
        assertNull(record.localPath)
    }

    @Test
    fun `a download noted with a path reads back with it`() = runBlocking {
        settings.noteDownload(item(2), "Chat", "/home/me/Downloads/TMPlayer/Episode 2.mkv")
        assertEquals("/home/me/Downloads/TMPlayer/Episode 2.mkv", settings.downloadRecord(-100L, 2L)!!.localPath)
        assertEquals(listOf(2), settings.downloadsNow().map { it.fileId })
        assertEquals(settings.downloadsNow(), settings.downloadHistory.first())
    }

    @Test
    fun `the path can be set, changed and cleared without touching the rest`() = runBlocking {
        settings.noteDownload(item(3), "Chat")
        val before = settings.downloadRecord(-100L, 3L)!!

        assertTrue(settings.setDownloadPath(-100L, 3L, "/a/Episode 3.mkv"))
        val moved = settings.downloadRecord(-100L, 3L)!!
        assertEquals("/a/Episode 3.mkv", moved.localPath)
        assertEquals(before.copy(localPath = "/a/Episode 3.mkv"), moved)

        assertTrue(settings.setDownloadPath(-100L, 3L, null))
        assertNull(settings.downloadRecord(-100L, 3L)!!.localPath)
    }

    @Test
    fun `setting a path for a message never downloaded does nothing`() = runBlocking {
        assertFalse(settings.setDownloadPath(-100L, 99L, "/a/b.mkv"))
        assertNull(settings.downloadRecord(-100L, 99L))
        assertTrue(settings.downloadsNow().isEmpty())
    }

    @Test
    fun `a removed download is gone`() = runBlocking {
        settings.noteDownload(item(4), "Chat", "/a/4.mkv")
        settings.forgetDownload(-100L, 4L)
        assertNull(settings.downloadRecord(-100L, 4L))
    }

    @Test
    fun `downloads are never capped, unlike the resume history`() = runBlocking {
        val count = SettingsStore.MAX_HISTORY + 5
        for (id in 1..count) settings.noteDownload(item(id), "Chat", "/d/$id.mkv")
        assertEquals(count, settings.downloadsNow().size)
        assertEquals("/d/1.mkv", settings.downloadRecord(-100L, 1L)!!.localPath)
    }

    @Test
    fun `the migration flag is set once and stays`() = runBlocking {
        assertFalse(settings.downloadsMigratedNow())
        settings.markDownloadsMigrated()
        assertTrue(settings.downloadsMigratedNow())
    }
}
