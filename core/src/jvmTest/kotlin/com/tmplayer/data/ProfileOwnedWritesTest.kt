package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Work of one profile over a switch (HX88), with a fake clock of switches (the stamp and the open
 * folder are the test's): a sync started under A and finished after the switch to B writes
 * nothing into B (its lists, favourites, the account it syncs for), so B's next sync uploads only
 * B's; a moved file's record is addressed to its owner's own keys; a folder grant is kept while
 * any profile's downloads are in it.
 */
class ProfileOwnedWritesTest {

    private val dir: File = Files.createTempDirectory("owned-writes").toFile()
    private val folder = MutableStateFlow<String?>(null)
    private var open = AccountStamp(1L, null)
    private val raw = openSafeDataStore(File(dir, SettingsStore.FILE_NAME))
    private val settings = SettingsStore(AccountScopedDataStore(raw, folder, stamp = { open }))

    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    private fun switchToB() {
        open = b
        folder.value = b.folder
    }

    private val item = MediaItem(
        chatId = 9, messageId = 77, fileId = 5, title = "Clip", sizeBytes = 1_000, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 3, miniThumbnail = null, date = 10, fileName = "a.mp4",
    )

    @Test
    fun `a sync of A that lands after the switch to B writes nothing into B`() = runBlocking {
        // B already has its own lists and syncs for its own account.
        switchToB()
        settings.toggleFavorite(200L)
        settings.setResumeSyncAccount(222L)
        open = a
        folder.value = null
        // A's sync starts as A (its owner taken when it starts) ...
        withContext(AccountOwner(a)) {
            settings.toggleFavorite(100L)
            // ... the switch to B lands in the middle of the import ...
            switchToB()
            // ... and the rest of A's import, and its confirm, are dropped, never written into B.
            settings.toggleFavorite(101L)
            settings.saveResumePosition(100L, 5L, positionMs = 600_000L, durationMs = 3_600_000L)
            settings.setResumeSyncAccount(111L)
        }
        assertEquals("B's favourites are its own", setOf(200L), settings.favorites.first())
        assertEquals("B's account untouched", 222L, settings.resumeSyncAccountNow())
        assertEquals("nothing of A's places in B", 0L, settings.resumePosition(100L, 5L))
        // What B's next sync reads to upload: B's alone.
        assertEquals(setOf(200L), settings.favorites.first())
        open = a
        folder.value = null
        assertEquals("A kept what it wrote before the switch", setOf(100L), settings.favorites.first())
    }

    @Test
    fun `a sync asked for a profile no longer open does nothing at all`() = runBlocking {
        val stale = AccountStamp(Long.MAX_VALUE, "pgone1")
        val outcome = withContext(AccountOwner(stale)) { ResumeSync.sync(settings) }
        assertEquals(ResumeSync.Outcome.Off, outcome)
    }

    @Test
    fun `a moved file's record is written to its owner's keys even after the switch`() = runBlocking {
        settings.noteDownload(item, "Chat", localPath = "/old/a.mp4")
        switchToB()
        // The move of A's file finished after the switch: its record is addressed to A.
        withContext(AccountOwner(a) + AccountAddress(a.folder)) { settings.setDownloadPath(9, 77, "/new/a.mp4") }
        assertTrue("B has no record of A's file", settings.downloadsNow().isEmpty())
        open = a
        folder.value = null
        assertEquals("/new/a.mp4", settings.downloadsNow().single().localPath)
    }

    @Test
    fun `a folder grant is kept while any profile's downloads are in it`() = runBlocking {
        settings.noteDownload(item, "Chat", localPath = "content://tree/a/document/a.mp4")
        switchToB()
        settings.noteDownload(item.copy(chatId = 10, messageId = 88), "Chat", localPath = "content://tree/b/document/b.mp4")
        val paths = settings.downloadPathsOfEveryProfileNow().toSet()
        assertTrue("A's, though B is open", "content://tree/a/document/a.mp4" in paths)
        assertTrue("B's", "content://tree/b/document/b.mp4" in paths)
    }
}
