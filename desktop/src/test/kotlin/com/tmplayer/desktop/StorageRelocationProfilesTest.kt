package com.tmplayer.desktop

import com.tmplayer.data.AccountStamp
import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A storage move and the profiles (HX88, review D2): it works for the profile that began it, stops
 * before its next file once that profile is no longer the open one, lets a switch wait for it, and
 * takes the other profiles' caches at the old root with it.
 */
class StorageRelocationProfilesTest {

    private val tmp = Files.createTempDirectory("tm-relocate-profiles").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(File(tmp, "store/" + SettingsStore.FILE_NAME)))
    private val prefs = DesktopPrefs(File(tmp, "desktop.properties"))
    private val defaults = StorageLayout(File(tmp, "home/cache"), File(tmp, "home/Downloads/TMPlayer"), File(tmp, "home/updates"))
    private val newRoot = File(tmp, "big").apply { mkdirs() }

    private fun layout(root: File?): StorageLayout = if (root == null) defaults else {
        val base = File(root, "TMPlayer")
        StorageLayout(File(base, "cache"), File(base, "downloads"), File(base, "updates"))
    }

    private val host = object : StorageRelocation.Host {
        override suspend fun pause() = Unit
        override suspend fun clearCache() = Unit
        override suspend fun restart(root: File?) = Unit
        override suspend fun resume() = Unit
    }

    private val notifier = object : TransferNotifier {
        override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) = Unit
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = Unit
        override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) = Unit
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = Unit
        override fun cancel(id: Long) = Unit
        override val capabilities = emptySet<TransferNotifier.Capability>()
    }

    private val a = AccountStamp(1L, null)

    private fun item(id: Long, size: Long) = MediaItem(
        chatId = -100, messageId = id, fileId = id.toInt(), title = "Episode $id", sizeBytes = size,
        durationSec = 0, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private suspend fun download(id: Long, bytes: Int = 1000): File {
        val file = File(defaults.downloadsDir.apply { mkdirs() }, "Episode $id.mkv")
        file.writeBytes(ByteArray(bytes) { it.toByte() })
        settings.noteDownload(item(id, bytes.toLong()), "Chat", file.absolutePath)
        return file
    }

    @Test
    fun `a move whose profile is no longer open stops before the next file`() = runBlocking {
        val first = download(1)
        val second = download(2)
        var open = true
        val mover: suspend (File, File, String, (Long, Long) -> Unit) -> File = { src, dir, name, progress ->
            val moved = DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
            // A switch while the first file was in hand.
            open = false
            moved
        }
        val relocation = StorageRelocation(
            settings, prefs, host, ::layout, { notifier }, mover = mover,
            owner = { a }, mayAct = { it == a && open }, profileFolders = { listOf(null) },
        )

        val outcome = relocation.move(newRoot)

        assertEquals(1, outcome.moved)
        assertTrue(outcome.stop is StorageRelocation.Stop.Cancelled)
        // The one in hand landed and is recorded; the other was never touched.
        assertEquals(1, layout(newRoot).downloadsDir.list()!!.size)
        val (stayed, id) = if (first.isFile) first to 1L else second to 2L
        assertFalse(first.isFile && second.isFile)
        assertEquals(stayed.absolutePath, settings.downloadRecord(-100, id)!!.localPath)
    }

    @Test
    fun `nothing is read or moved when the profile was left before the downloads' turn`() = runBlocking {
        val file = download(3)
        val relocation = StorageRelocation(
            settings, prefs, host, ::layout, { notifier },
            owner = { a }, mayAct = { false }, profileFolders = { listOf(null) },
        )

        val outcome = relocation.move(newRoot)

        assertEquals(0, outcome.moved)
        assertTrue(file.isFile)
        assertEquals(file.absolutePath, settings.downloadRecord(-100, 3)!!.localPath)
    }

    @Test
    fun `a switch waits for the file in hand and the move stops after it`() = runBlocking {
        download(4)
        download(5)
        val inHand = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val mover: suspend (File, File, String, (Long, Long) -> Unit) -> File = { src, dir, name, progress ->
            if (!inHand.isCompleted) {
                inHand.complete(Unit)
                letGo.await()
            }
            DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
        }
        val relocation = StorageRelocation(
            settings, prefs, host, ::layout, { notifier }, mover = mover,
            owner = { a }, mayAct = { true }, profileFolders = { listOf(null) },
        )
        val moving = async(kotlinx.coroutines.Dispatchers.IO) { relocation.move(newRoot) }
        inHand.await()
        assertEquals(com.tmplayer.i18n.L.profilesWaitingMoving, relocation.waitingLabel())
        val hook = async(kotlinx.coroutines.Dispatchers.IO) { relocation.stopForSwitch(waitMs = 10_000) }
        kotlinx.coroutines.delay(100)
        assertFalse("the hook waits for the move", hook.isCompleted)
        letGo.complete(Unit)
        withTimeout(10_000) { hook.await() }
        val outcome = moving.await()

        assertEquals(1, outcome.moved)
        assertTrue(outcome.stop is StorageRelocation.Stop.Cancelled)
        assertEquals(1, defaults.downloadsDir.list()!!.size)
        assertEquals(null, relocation.waitingLabel())
    }

    @Test
    fun `the other profiles' caches at the old root go with the move, their downloads stay`() = runBlocking {
        val other = defaults.forProfile("pbbbbb")
        val theirCache = File(other.cacheDir, "videos/cached.mp4").apply { parentFile.mkdirs(); writeText("cache") }
        val theirDownload = File(other.downloadsDir, "Theirs.mkv").apply { parentFile.mkdirs(); writeText("film") }
        val relocation = StorageRelocation(
            settings, prefs, host, ::layout, { notifier },
            owner = { a }, mayAct = { true }, profileFolders = { listOf(null, "pbbbbb") },
        )

        relocation.move(newRoot)

        assertFalse(theirCache.exists())
        assertFalse(other.cacheDir.exists())
        assertTrue(theirDownload.isFile)
    }
}
