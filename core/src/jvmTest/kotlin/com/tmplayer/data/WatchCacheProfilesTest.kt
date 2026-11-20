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
}
