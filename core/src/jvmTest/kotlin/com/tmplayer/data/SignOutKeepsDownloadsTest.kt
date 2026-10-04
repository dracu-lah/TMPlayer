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
 * Signing out forgets the account, and by default not the downloads: those are files in the
 * Downloads folder now, and a file with no record is one the app can neither play nor delete.
 */
class SignOutKeepsDownloadsTest {

    private val dir = Files.createTempDirectory("tm-signout").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    private fun item(id: Int) = MediaItem(
        chatId = -1L, messageId = id.toLong(), fileId = id, title = "Video $id", sizeBytes = 10,
        durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `downloads in the folder survive, everything about the account goes`() = runBlocking {
        settings.noteDownload(item(1), "Chat", "/d/Video 1.mp4")
        settings.noteDownload(item(2), "Chat")
        settings.rememberCachedVideo(item(3), "Chat")
        settings.toggleFavorite(42L)
        settings.markDownloadsMigrated()

        settings.clearEverything(keepDownloads = true)

        assertEquals(listOf("/d/Video 1.mp4"), settings.downloadsNow().map { it.localPath })
        // Still in TDLib's cache, which signing out empties: nothing would be left to point at.
        assertNull(settings.downloadRecord(-1L, 2L))
        assertTrue(settings.cachedVideosNow().isEmpty())
        assertTrue(settings.favorites.first().isEmpty())
        assertTrue(settings.downloadsMigratedNow())
    }

    @Test
    fun `asked to, it forgets the downloads as well`() = runBlocking {
        settings.noteDownload(item(1), "Chat", "/d/Video 1.mp4")
        settings.markDownloadsMigrated()

        settings.clearEverything()

        assertTrue(settings.downloadsNow().isEmpty())
        assertFalse(settings.downloadsMigratedNow())
    }
}
