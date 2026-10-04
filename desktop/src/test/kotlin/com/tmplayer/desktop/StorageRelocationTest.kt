package com.tmplayer.desktop

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A storage move over real folders and a real settings store, with TDLib, the player and the
 * notifier faked: what moves, in what order, what is written down, and how a move cut short is
 * finished on the next launch.
 */
class StorageRelocationTest {

    private val tmp = Files.createTempDirectory("tm-relocate").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(File(tmp, "store/" + SettingsStore.FILE_NAME)))
    private val prefs = DesktopPrefs(File(tmp, "desktop.properties"))
    private val defaults = StorageLayout(File(tmp, "home/cache"), File(tmp, "home/Downloads/TMPlayer"), File(tmp, "home/updates"))
    private val newRoot = File(tmp, "big")

    private fun layout(root: File?): StorageLayout = if (root == null) defaults else {
        val base = File(root, "TMPlayer")
        StorageLayout(File(base, "cache"), File(base, "downloads"), File(base, "updates"))
    }

    private val calls = mutableListOf<String>()
    private val host = object : StorageRelocation.Host {
        override suspend fun pause() { calls += "pause" }
        override suspend fun clearCache() { calls += "clear" }
        override suspend fun restart(root: File?) { calls += "restart ${root?.name}" }
        override suspend fun resume() { calls += "resume" }
    }

    private val notes = mutableListOf<String>()
    private val notifier = object : TransferNotifier {
        override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) { notes += "begin $kind" }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = Unit
        override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) { notes += "complete" }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) { notes += "fail" }
        override fun cancel(id: Long) = Unit
        override val capabilities = emptySet<TransferNotifier.Capability>()
    }

    private val relocation = StorageRelocation(settings, prefs, host, ::layout, { notifier })

    private fun item(id: Long, size: Long) = MediaItem(
        chatId = -100, messageId = id, fileId = id.toInt(), title = "Episode $id", sizeBytes = size,
        durationSec = 0, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    /** A download in the default folder, recorded in the index. */
    private suspend fun download(id: Long, bytes: Int = 1000): File {
        val file = File(defaults.downloadsDir.apply { mkdirs() }, "Episode $id.mkv")
        file.writeBytes(ByteArray(bytes) { it.toByte() })
        settings.noteDownload(item(id, bytes.toLong()), "Chat", file.absolutePath)
        return file
    }

    @Test
    fun `a move stops everything, clears the cache, restarts and carries every download across`() = runBlocking {
        val a = download(1)
        val b = download(2, 2000)
        File(defaults.cacheDir, "videos").mkdirs()
        File(defaults.cacheDir, "videos/cached.mp4").writeText("cache")

        val outcome = relocation.move(newRoot)

        assertEquals(listOf("pause", "clear", "restart big", "resume"), calls)
        assertEquals(2, outcome.moved)
        assertTrue(outcome.failed.isEmpty())
        val target = layout(newRoot).downloadsDir
        assertEquals(setOf("Episode 1.mkv", "Episode 2.mkv"), target.list()!!.toSet())
        assertFalse(a.exists() || b.exists())
        assertEquals(File(target, "Episode 2.mkv").absolutePath, settings.downloadRecord(-100, 2)!!.localPath)
        // The settings say where things are, and that nothing is left to do.
        assertEquals(newRoot.path, prefs.now.storageRoot)
        assertEquals("", prefs.now.storageRootPending)
        // The old cache is gone, the old downloads folder with it once empty, the marker is there.
        assertFalse(defaults.cacheDir.exists())
        assertFalse(defaults.downloadsDir.exists())
        assertTrue(File(newRoot, "TMPlayer/${StorageRelocationPlan.MARKER}").isFile)
        assertEquals(listOf("begin Relocate", "complete"), notes)
    }

    @Test
    fun `reset to the default folders brings the downloads back`() = runBlocking {
        download(1)
        relocation.move(newRoot)
        calls.clear()
        relocation.move(null)
        assertEquals(listOf("pause", "clear", "restart null", "resume"), calls)
        assertTrue(File(defaults.downloadsDir, "Episode 1.mkv").isFile)
        assertEquals("", prefs.now.storageRoot)
        // TMPlayer's folder on the other drive keeps its marker; only the emptied downloads go.
        assertTrue(File(newRoot, "TMPlayer/${StorageRelocationPlan.MARKER}").isFile)
        assertFalse(File(newRoot, "TMPlayer/downloads").exists())
    }

    @Test
    fun `a move cut short is finished on the next launch`() = runBlocking {
        download(1)
        download(2)
        // As a crash would leave it: the new root written, one file across, the other not.
        val pending = StorageRelocationPlan.Pending(newRoot.path, defaults.cacheDir.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = newRoot.path, storageRootPending = pending.encode()) }
        val target = layout(newRoot).downloadsDir.apply { mkdirs() }
        val moved = File(target, "Episode 1.mkv")
        File(defaults.downloadsDir, "Episode 1.mkv").renameTo(moved)
        settings.setDownloadPath(-100, 1, moved.absolutePath)

        val outcome = relocation.resumePending()!!

        assertEquals(1, outcome.moved)
        assertTrue(File(target, "Episode 2.mkv").isFile)
        assertEquals("", prefs.now.storageRootPending)
        assertTrue(calls.isEmpty())
        // Nothing pending now: a second launch does nothing.
        assertEquals(null, relocation.resumePending())
    }

    @Test
    fun `a download that will not move is reported and left pending for next launch`() = runBlocking {
        val kept = download(1)
        // A file where the new downloads folder should be: every move into it fails.
        layout(newRoot).downloadsDir.apply { parentFile.mkdirs() }.writeText("in the way")
        val outcome = relocation.move(newRoot)
        assertEquals(listOf("Episode 1.mkv"), outcome.failed)
        assertTrue(kept.isFile)
        assertEquals(kept.absolutePath, settings.downloadRecord(-100, 1)!!.localPath)
        assertTrue(prefs.now.storageRootPending.isNotBlank())
        assertEquals(listOf("begin Relocate", "fail"), notes)
    }
}
