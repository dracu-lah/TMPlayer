package com.tmplayer.desktop.profiles

import com.tmplayer.data.AccountDirs
import com.tmplayer.data.AccountStamp
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TdFiles
import com.tmplayer.data.WatchCacheRules
import com.tmplayer.desktop.StorageLayout
import com.tmplayer.desktop.profileFilesDir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's watch cache over two profiles' TDLib folders (HX88, invariants 3 and 4): the sweep
 * walks the folder of the profile it works for, resolved from that profile's folder name as the
 * desktop resolves it ([profileFilesDir], what DesktopServices hands the sweep), never the folder
 * the process opened with. A sweep as B never touches A's files, and a storage move carries the
 * open profile's folders only ([StorageLayout.forProfile]).
 */
class DesktopCacheProfilesTest {
    private val base = DesktopKitPaths()
    private val settings = SettingsStore(SettingsStore.openDataStore(Files.createTempDirectory("desktop-cache-profiles").resolve(SettingsStore.FILE_NAME).toFile()))
    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    private object NoFiles : TdFiles {
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) = storedFileId
        override suspend fun deleteFile(fileId: Int) = Unit
        override suspend fun localDownloadedBytes(fileId: Int) = 0L
        override suspend fun localPathAnyway(fileId: Int): String? = null
    }

    /** An old stray video in [root], older than the sweep's ten minutes. */
    private fun stray(root: File, name: String): File = root.resolve("videos/$name").apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(64))
        setLastModified(System.currentTimeMillis() - 3_600_000L)
    }

    /** As DesktopServices builds it, but over [base]: the platform's folder as the old fallback. */
    private fun rules(open: AccountStamp) = WatchCacheRules(
        settings,
        filesRoot = { base.filesDir },
        td = NoFiles,
        busy = { emptySet() },
        stamp = { open },
        rootFor = { owner -> profileFilesDir(base, owner.folder) },
    )

    @Test
    fun `each profile's cache is its own folder on the desktop layout`() {
        assertEquals(base.filesDir, profileFilesDir(base, null))
        assertEquals(AccountDirs.beside(base.filesDir, "pbbbbb"), profileFilesDir(base, "pbbbbb"))
        assertTrue(profileFilesDir(base, "pbbbbb").path.endsWith("tdlib-cache-pbbbbb"))
    }

    @Test
    fun `a sweep as B never touches A's files, and as A never touches B's`() = runBlocking {
        val rootA = profileFilesDir(base, a.folder)
        val rootB = profileFilesDir(base, b.folder)
        val ofA = stray(rootA, "a.mkv")
        val ofB = stray(rootB, "b.mkv")
        rules(b).sweep()
        assertTrue("A's file stays while B is open", ofA.exists())
        assertFalse("B's own stray goes", ofB.exists())
        val ofB2 = stray(rootB, "b2.mkv")
        rules(a).sweep()
        assertTrue("B's file stays while A is open", ofB2.exists())
        assertFalse("A's own stray goes", ofA.exists())
    }

    @Test
    fun `a sweep that may not act deletes nothing`() = runBlocking {
        val ofB = stray(profileFilesDir(base, b.folder), "b.mkv")
        WatchCacheRules(
            settings,
            filesRoot = { base.filesDir },
            td = NoFiles,
            busy = { emptySet() },
            stamp = { b },
            rootFor = { owner -> profileFilesDir(base, owner.folder) },
            mayAct = { false },
        ).sweep()
        assertTrue(ofB.exists())
    }

    @Test
    fun `a storage move carries the open profile's cache and downloads, and shares the updates`() {
        val platform = StorageLayout(File("/r/TMPlayer/tdlib-cache"), File("/r/TMPlayer/downloads"), File("/r/TMPlayer/updates"))
        assertEquals(platform, platform.forProfile(null))
        val ofB = platform.forProfile("pbbbbb")
        assertEquals(File("/r/TMPlayer/tdlib-cache-pbbbbb"), ofB.cacheDir)
        assertEquals(File("/r/TMPlayer/downloads-pbbbbb"), ofB.downloadsDir)
        assertEquals(platform.updatesDir, ofB.updatesDir)
    }
}
