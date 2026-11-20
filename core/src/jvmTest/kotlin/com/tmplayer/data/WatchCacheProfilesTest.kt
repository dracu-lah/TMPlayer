package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The watch cache's sweep over two profiles' folders on disk (HX88): a sweep as B walks B's own
 * folder and never touches A's files, and the reverse; a switch while a sweep resolves its records
 * stops it before it deletes anything.
 */
class WatchCacheProfilesTest {

    private val dir = Files.createTempDirectory("tm-cache-profiles").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val rootA = dir.resolve("tdlib-files").apply { resolve("videos").mkdirs() }
    private val rootB = dir.resolve("tdlib-files-pbbbbb").apply { resolve("videos").mkdirs() }
    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")
    private var open = a

    /** An old stray video in [root], older than the sweep's ten minutes. */
    private fun stray(root: File, name: String): File = root.resolve("videos/$name").apply {
        writeBytes(ByteArray(64))
        setLastModified(System.currentTimeMillis() - 3_600_000L)
    }

    private class FakeTd(val onResolve: () -> Unit = {}) : TdFiles {
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int): Int {
            onResolve()
            return storedFileId
        }
        override suspend fun deleteFile(fileId: Int) = Unit
        override suspend fun localDownloadedBytes(fileId: Int) = 0L
        override suspend fun localPathAnyway(fileId: Int): String? = null
    }

    private fun rules(td: TdFiles = FakeTd()) = WatchCacheRules(
        settings,
        filesRoot = { if (open == a) rootA else rootB },
        td = td,
        busy = { emptySet() },
        stamp = { open },
    )

    @Test
    fun `a sweep as B never touches A's files, and as A never touches B's`() = runBlocking {
        val ofA = stray(rootA, "a.mkv")
        val ofB = stray(rootB, "b.mkv")
        open = b
        rules().sweep()
        assertTrue("A's file stays while B is open", ofA.exists())
        assertFalse("B's own stray goes", ofB.exists())
        val ofB2 = stray(rootB, "b2.mkv")
        open = a
        rules().sweep()
        assertTrue("B's file stays while A is open", ofB2.exists())
        assertFalse(ofA.exists())
    }

    @Test
    fun `a switch while the sweep resolves its records stops it before any delete`() = runBlocking {
        val ofA = stray(rootA, "a.mkv")
        // A record to resolve, so the sweep reaches TDLib, and the switch lands then.
        settings.rememberCachedVideo(
            MediaItem(chatId = 7, messageId = 1, fileId = 1, title = "Clip", sizeBytes = 64, durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0),
            "Chat",
        )
        val td = object : TdFiles {
            override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int): Int {
                open = b
                return storedFileId
            }
            override suspend fun deleteFile(fileId: Int) = Unit
            override suspend fun localDownloadedBytes(fileId: Int) = 0L
            override suspend fun localPathAnyway(fileId: Int): String? = rootA.resolve("videos/known.mkv").absolutePath
        }
        open = a
        rules(td).sweep()
        assertTrue("nothing of A's deleted once B opened mid sweep", ofA.exists())
    }

    @Test
    fun `in a switch's window the sweep keeps to the old profile's folder, though the current folder is already the next one's`() = runBlocking {
        val ofA = stray(rootA, "a.mkv")
        val ofB = stray(rootB, "b.mkv")
        // The switch has pointed the current folder at B; the stamp is still A's until the flip.
        open = a
        var switching = false
        val window = WatchCacheRules(
            settings,
            filesRoot = { rootB },
            td = FakeTd(),
            busy = { emptySet() },
            stamp = { open },
            rootFor = { owner -> if (owner == a) rootA else rootB },
            mayAct = { owner -> owner == open && !switching },
        )
        window.sweep()
        assertTrue("B's cached videos and paused downloads are never A's sweep's", ofB.exists())
        assertFalse("A's own stray goes", ofA.exists())
        // And while the switch is under way, nothing at all.
        val ofA2 = stray(rootA, "a2.mkv")
        switching = true
        window.sweep()
        window.clearAll()
        assertTrue(ofA2.exists())
        assertTrue(ofB.exists())
    }

    private fun clip(id: Int) = MediaItem(
        chatId = 7, messageId = id.toLong(), fileId = id, title = "Clip $id", sizeBytes = 64, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `a claim made for a profile no longer open deletes nothing`() = runBlocking {
        val deleted = mutableListOf<Int>()
        val td = object : TdFiles {
            override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) = storedFileId
            override suspend fun deleteFile(fileId: Int) { deleted += fileId }
            override suspend fun localDownloadedBytes(fileId: Int) = if (fileId in deleted) 0L else 64L
            override suspend fun localPathAnyway(fileId: Int): String? = null
        }
        var acting = true
        // The real stamp: the store drops writes of any other owner, as in the app.
        val claims = WatchCacheRules(settings, filesRoot = { rootA }, td = td, busy = { emptySet() }, mayAct = { acting })
        claims.claim(clip(1), "Chat")
        // The next video's claim lands after the profile changed (from the app's scope).
        acting = false
        claims.claim(clip(2), "Chat")
        assertTrue("nothing of the last profile deleted: $deleted", deleted.isEmpty())
        acting = true
        claims.claim(clip(3), "Chat")
        assertTrue("its own claim still makes room: ${settings.cachedVideosNow().map { it.fileId }}", deleted.isNotEmpty())
    }

    @Test
    fun `a stray outside the profile's own folder is never removed`() {
        val ofB = stray(rootB, "b.mkv")
        val rules = WatchCacheRules(settings, filesRoot = { rootA }, busy = { emptySet() }, stamp = { a }, rootFor = { rootA }, mayAct = { true })
        assertTrue(!rules.forget(WatchCacheRules.Stray(ofB.absolutePath, ofB.length(), ofB.lastModified()), a))
        assertTrue(ofB.exists())
    }

    @Test
    fun `wired without seams, a sweep during a switch under way deletes nothing`() = runBlocking {
        val ofA = stray(rootA, "a.mkv")
        val book = AccountBook(AccountStore(File(Files.createTempDirectory("switching").toFile(), Accounts.FILE_NAME)))
        Accounts.resetForTest(book)
        try {
            book.update { it.copy(switchingTo = it.activeId) }
            // The real seams: the stamp is Accounts', and the switch is read from the book.
            WatchCacheRules(settings, filesRoot = { rootA }, busy = { emptySet() }).sweep()
            assertTrue("a switch under way: nothing walked or deleted", ofA.exists())
            book.update { it.copy(switchingTo = null) }
            WatchCacheRules(settings, filesRoot = { rootA }, busy = { emptySet() }).sweep()
            assertFalse("once it is over the sweep runs as before", ofA.exists())
        } finally {
            Accounts.resetForTest()
        }
    }

    @Test
    fun `a switch between the size read and the delete stops the delete`() = runBlocking {
        val deleted = mutableListOf<Int>()
        var acting = true
        val td = object : TdFiles {
            override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) = storedFileId
            override suspend fun deleteFile(fileId: Int) { deleted += fileId }
            override suspend fun localDownloadedBytes(fileId: Int): Long {
                // The read suspends, and the switch lands then.
                acting = false
                return 64L
            }
            override suspend fun localPathAnyway(fileId: Int): String? = null
        }
        val claims = WatchCacheRules(settings, filesRoot = { rootA }, td = td, busy = { emptySet() }, mayAct = { acting })
        claims.claim(clip(1), "Chat")
        acting = true
        claims.claim(clip(2), "Chat")
        assertTrue("no delete on the live client once the profile changed: $deleted", deleted.isEmpty())
    }
}
