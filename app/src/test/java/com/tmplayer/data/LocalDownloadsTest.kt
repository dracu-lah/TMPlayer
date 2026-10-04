package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The download index read against the disk: present, missing, or still in TDLib's cache. */
class LocalDownloadsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val settings by lazy {
        SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
    }

    private fun item(id: Int, size: Long = 10) = MediaItem(
        chatId = 1L, messageId = id.toLong(), fileId = id, title = "Video $id", sizeBytes = size,
        durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `a record is present, missing or legacy by its path and the file`() {
        assertEquals(LocalDownloads.FileState.Legacy, LocalDownloads.stateOf(null, null, 10))
        assertEquals(LocalDownloads.FileState.Missing, LocalDownloads.stateOf("/x", null, 10))
        assertEquals(LocalDownloads.FileState.Missing, LocalDownloads.stateOf("/x", 0, 10))
        // Cut short by something else is as good as gone.
        assertEquals(LocalDownloads.FileState.Missing, LocalDownloads.stateOf("/x", 9, 10))
        assertEquals(LocalDownloads.FileState.Present, LocalDownloads.stateOf("/x", 10, 10))
        // An advertised size can be an estimate; a larger whole file is not missing.
        assertEquals(LocalDownloads.FileState.Present, LocalDownloads.stateOf("/x", 12, 10))
        assertEquals(LocalDownloads.FileState.Present, LocalDownloads.stateOf("/x", 5, 0))
    }

    @Test
    fun `only present downloads are offered to play, and deleting takes file and record`() = runBlocking {
        val here = File(folder.root, "Video 1.mp4").apply { writeBytes(ByteArray(10)) }
        settings.noteDownload(item(1), "Chat", here.path)
        settings.noteDownload(item(2), "Chat", File(folder.root, "gone.mp4").path)
        settings.noteDownload(item(3), "Chat")

        assertEquals(here, LocalDownloads.fileFor(settings, 1L, 1L))
        assertNull(LocalDownloads.fileFor(settings, 1L, 2L))
        assertNull(LocalDownloads.fileFor(settings, 1L, 3L))
        assertEquals(setOf("1:1"), LocalDownloads.presentIds(settings))
        assertEquals(10L, LocalDownloads.indexedBytes(settings))

        assertTrue(LocalDownloads.delete(settings, settings.downloadRecord(1L, 1L)!!))
        assertFalse(here.exists())
        assertNull(settings.downloadRecord(1L, 1L))
        // A missing file has nothing to delete, and its record goes.
        assertTrue(LocalDownloads.delete(settings, settings.downloadRecord(1L, 2L)!!))
        assertNull(settings.downloadRecord(1L, 2L))
        // A legacy record is not this function's to delete.
        assertFalse(LocalDownloads.delete(settings, settings.downloadRecord(1L, 3L)!!))
    }
}
