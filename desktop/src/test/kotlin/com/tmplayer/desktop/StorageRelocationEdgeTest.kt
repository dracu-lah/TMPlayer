package com.tmplayer.desktop

import com.tmplayer.data.DownloadFiles
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/**
 * The edges of a storage move (HX57, desktop): a full disk part way, Cancel, a drive or folder that
 * vanishes, the same move twice, a link, a locked file, downloads left behind and moved later. Over
 * temp folders and a real settings store; the disk's behaviour (free space, a mount that is there
 * or not, which files share a drive, how one copy goes) is faked through the move's own seams.
 */
class StorageRelocationEdgeTest {

    private val tmp = Files.createTempDirectory("tm-relocate-edge").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(File(tmp, "store/" + SettingsStore.FILE_NAME)))
    private val prefs = DesktopPrefs(File(tmp, "desktop.properties"))
    private val defaults = StorageLayout(File(tmp, "home/cache"), File(tmp, "home/Downloads/TMPlayer"), File(tmp, "home/updates"))
    private val newRoot = File(tmp, "big").apply { mkdirs() }
    private val gb = 1024L * 1024 * 1024

    @After
    fun cleanUp() {
        tmp.deleteRecursively()
    }

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
        override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) { notes += "begin" }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = Unit
        override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) { notes += "complete" }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) { notes += "fail" }
        override fun cancel(id: Long) { notes += "cancel" }
        override val capabilities = emptySet<TransferNotifier.Capability>()
    }

    private fun relocationWith(
        free: Long = 100 * gb,
        usable: (File) -> Boolean = { true },
        sameDrive: (File, File) -> Boolean = { _, _ -> false },
        mover: suspend (File, File, String, (Long, Long) -> Unit) -> File =
            { src, dir, name, progress -> DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress) },
        host: StorageRelocation.Host = this.host,
    ) = StorageRelocation(settings, prefs, host, ::layout, { notifier }, freeSpace = { free }, usable = usable, sameDrive = sameDrive, mover = mover)

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
    fun `downloads stay where they are when not asked to move, and move later from Settings`() = runBlocking {
        val kept = download(1)
        val r = relocationWith()
        val outcome = r.move(newRoot, moveDownloads = false)
        assertEquals(0, outcome.total)
        assertTrue(kept.isFile)
        assertEquals(kept.absolutePath, settings.downloadRecord(-100, 1)!!.localPath)
        assertEquals(newRoot.path, prefs.now.storageRoot)
        assertEquals("", prefs.now.storageRootPending)
        // Not removed with the old folder: it still holds a download.
        assertTrue(defaults.downloadsDir.exists())
        assertEquals(1 to 1000L, r.outside())

        val later = r.moveRemaining()
        assertEquals(1, later.moved)
        assertFalse(kept.exists())
        assertEquals(0, r.outside().first)
        assertTrue(File(layout(newRoot).downloadsDir, "Episode 1.mkv").isFile)
    }

    @Test
    fun `a full disk stops at the first file that hits it, says how much more, and leaves the rest alone`() = runBlocking {
        val a = download(1, 4000)
        val b = download(2, 4000)
        val c = download(3, 4000)
        var attempts = 0
        val r = relocationWith(mover = { src, dir, name, progress ->
            attempts++
            if (attempts == 2) throw IOException("No space left on device")
            DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
        })
        val outcome = r.move(newRoot)
        // 8000 bytes were still to go when the second file hit the wall, and the fake says there is room for none.
        assertTrue(outcome.stop is StorageRelocation.Stop.NoSpace)
        assertEquals(1, outcome.moved)
        assertEquals(2, attempts)
        assertTrue(outcome.failed.isEmpty())
        assertEquals(2, listOf(a, b, c).count { it.exists() })
        assertEquals("", prefs.now.storageRootPending)
        assertTrue(outcome.message.startsWith("Moving stopped because "))
        assertTrue(outcome.message.endsWith("1 of 3 moved. The rest stay where they were and keep playing."))
        assertTrue(outcome.needsReport)
        assertEquals(1, layout(newRoot).downloadsDir.list()!!.size)
        assertEquals(listOf("begin", "fail"), notes)
        assertEquals(2, r.outside().first)
    }

    @Test
    fun `cancel stops after the file in hand, removes its half copy and keeps what moved`() = runBlocking {
        val a = download(1)
        val b = download(2)
        val c = download(3)
        lateinit var r: StorageRelocation
        var attempts = 0
        r = relocationWith(mover = { src, dir, name, progress ->
            attempts++
            if (attempts == 1) {
                DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
            } else {
                val part = File(dir, "$name.part").apply { writeText("half") }
                try {
                    r.cancel()
                    awaitCancellation()
                } finally {
                    part.delete()
                }
            }
        })
        val outcome = r.move(newRoot)
        assertEquals(StorageRelocation.Stop.Cancelled, outcome.stop)
        assertEquals(1, outcome.moved)
        assertEquals(2, attempts)
        assertEquals(2, listOf(a, b, c).count { it.isFile })
        assertEquals(1, layout(newRoot).downloadsDir.list()!!.size)
        assertEquals("", prefs.now.storageRootPending)
        assertEquals("Stopped. 1 of 3 moved. The rest stay where they were and keep playing.", outcome.message)
        assertNull(r.moving.value)
        // Not a failure that needs a dialog: the viewer asked for it.
        assertFalse(outcome.needsReport)
        assertEquals(listOf("begin", "cancel"), notes)
        assertEquals(2, r.outside().first)
    }

    @Test
    fun `cancel with no move running does nothing`() = runBlocking {
        val r = relocationWith()
        r.cancel()
        download(1)
        assertEquals(1, r.move(newRoot).moved)
    }

    @Test
    fun `a folder that vanished or lost its permission is refused before anything is touched`() = runBlocking {
        download(1)
        val failure = runCatching { relocationWith(usable = { false }).move(newRoot) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("TMPlayer cannot write there.", failure!!.message)
        assertTrue(calls.isEmpty())
        assertEquals("", prefs.now.storageRoot)
        assertEquals("", prefs.now.storageRootPending)
    }

    @Test
    fun `room is checked again at the move with the gigabyte spare, before anything is touched`() = runBlocking {
        download(1, 5000)
        val r = relocationWith(free = 512L * 1024 * 1024)
        val failure = runCatching { r.move(newRoot) }.exceptionOrNull()
        assertEquals("That drive has 512 MB free. The downloads and 1 GB to spare need 1.0 GB.", failure!!.message)
        assertTrue(calls.isEmpty())
        // Left behind, only the folder itself has to fit, so the same drive takes the move.
        assertEquals(0, r.move(newRoot, moveDownloads = false).total)
        assertEquals(listOf("pause", "clear", "restart big", "resume"), calls)
    }

    @Test
    fun `files on the same drive are renamed, so they need no room`() = runBlocking {
        download(1, 5000)
        val r = relocationWith(free = 0, sameDrive = { _, _ -> true })
        assertEquals(1, r.move(newRoot).moved)
    }

    @Test
    fun `a second move while one runs is refused, not queued`() = runBlocking {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : StorageRelocation.Host by host {
            override suspend fun pause() {
                inside.complete(Unit)
                release.await()
            }
        }
        val r = relocationWith(host = slow)
        val first = launch { r.move(newRoot) }
        inside.await()
        val failure = runCatching { r.move(newRoot) }.exceptionOrNull()
        assertEquals("A move is already running.", failure!!.message)
        release.complete(Unit)
        first.join()
    }

    @Test
    fun `a link is left where it is and said so, a file gone meanwhile is not a failure`() = runBlocking {
        val real = download(1)
        val target = File(tmp, "elsewhere.mkv").apply { writeText("real") }
        val link = File(defaults.downloadsDir, "Linked.mkv")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        if (linked) settings.noteDownload(item(2, 4), "Chat", link.absolutePath)
        val gone = download(3)
        val r = relocationWith(mover = { src, dir, name, progress ->
            if (src.name == "Episode 3.mkv") {
                src.delete()
                throw FileNotFoundException("Nothing to move at ${src.path}")
            }
            DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
        })
        val outcome = r.move(newRoot)
        assertEquals(1, outcome.moved)
        assertTrue(outcome.failed.isEmpty())
        assertTrue(outcome.missing >= 1)
        assertFalse(gone.exists() || real.exists())
        if (linked) {
            assertEquals(listOf("Linked.mkv"), outcome.skippedLinks)
            assertTrue(link.exists() && target.isFile)
            assertTrue(outcome.needsReport)
            assertTrue(outcome.report().any { it.startsWith("Linked.mkv was left where it is") })
        }
    }

    @Test
    fun `a drive that goes away mid move stops the move and leaves the rest where they are`() = runBlocking {
        val a = download(1)
        val b = download(2)
        var attempts = 0
        val r = relocationWith(mover = { src, dir, name, progress ->
            attempts++
            if (attempts == 1) {
                DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
            } else {
                // The drive is pulled: its folders go with it.
                File(newRoot, "TMPlayer").deleteRecursively()
                throw IOException("Input/output error")
            }
        })
        val outcome = r.move(newRoot)
        assertEquals(StorageRelocation.Stop.VolumeGone, outcome.stop)
        assertEquals(1, outcome.moved)
        assertEquals(1, listOf(a, b).count { it.isFile })
        assertEquals("", prefs.now.storageRootPending)
        assertTrue(outcome.message.contains("was disconnected during the move"))
        assertTrue(outcome.needsReport)
    }

    @Test
    fun `a locked file is named with its reason and tried again at the next launch`() = runBlocking {
        val kept = download(1)
        download(2)
        val r = relocationWith(mover = { src, dir, name, progress ->
            if (src.name == "Episode 1.mkv") throw IOException("The process cannot access the file because it is being used by another process")
            DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
        })
        val outcome = r.move(newRoot)
        assertEquals(listOf("Episode 1.mkv"), outcome.failed)
        assertEquals(StorageRelocation.Reason.InUse, outcome.reasons["Episode 1.mkv"])
        assertEquals(1, outcome.moved)
        assertTrue(kept.isFile)
        assertTrue(prefs.now.storageRootPending.isNotBlank())
        assertTrue(outcome.report().contains("Episode 1.mkv did not move because another program is using it."))
        assertTrue(outcome.report().contains("1 download moved."))
    }

    @Test
    fun `a move waiting for its drive does not make folders on the mount point, and can be forgotten`() = runBlocking {
        download(1)
        val pending = StorageRelocationPlan.Pending(newRoot.path, defaults.cacheDir.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = newRoot.path, storageRootPending = pending.encode()) }
        val r = relocationWith(usable = { false })
        assertNull(r.resumePending())
        assertFalse(File(newRoot, "TMPlayer").exists())
        assertTrue(r.pending() != null)
        r.forgetPending()
        assertNull(r.pending())
        assertTrue(File(defaults.downloadsDir, "Episode 1.mkv").isFile)
    }

    @Test
    fun `Cancel heard after a file landed still records it, so no file is orphaned in the new folder`() = runBlocking {
        val a = download(1)
        download(2)
        lateinit var r: StorageRelocation
        r = relocationWith(mover = { src, dir, name, progress ->
            // The move completes, then Cancel arrives before the mover has returned.
            val target = DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
            r.cancel()
            target
        })
        val outcome = r.move(newRoot)
        assertEquals(StorageRelocation.Stop.Cancelled, outcome.stop)
        assertEquals(1, outcome.moved)
        val landed = layout(newRoot).downloadsDir.list()!!.single()
        val record = listOf(1L, 2L).map { settings.downloadRecord(-100, it)!!.localPath }
        assertEquals(1, record.count { it == File(layout(newRoot).downloadsDir, landed).absolutePath })
        assertEquals(1, listOf(a, File(defaults.downloadsDir, "Episode 2.mkv")).count { !it.exists() })
    }

    @Test
    fun `Cancel that surfaces as a cancelled return after the file landed is recorded from where it landed`() = runBlocking {
        val a = download(1)
        lateinit var r: StorageRelocation
        r = relocationWith(mover = { src, dir, name, progress ->
            DownloadFiles.moveIntoDownloads(src, dir, name, onProgress = progress)
            r.cancel()
            throw kotlinx.coroutines.CancellationException("cancelled on the way back")
        })
        val outcome = r.move(newRoot)
        assertEquals(StorageRelocation.Stop.Cancelled, outcome.stop)
        assertEquals(1, outcome.moved)
        assertFalse(a.exists())
        assertEquals(File(layout(newRoot).downloadsDir, a.name).absolutePath, settings.downloadRecord(-100, 1)!!.localPath)
    }

    @Test
    fun `any failure of the mover, not only an IO one, is reported for that file and does not escape`() = runBlocking {
        val kept = download(1)
        val outcome = relocationWith(mover = { _, _, _, _ -> throw IllegalStateException("odd") }).move(newRoot)
        assertEquals(listOf("Episode 1.mkv"), outcome.failed)
        assertTrue(kept.isFile)
        assertTrue(prefs.now.storageRootPending.isNotBlank())
    }

    @Test
    fun `a file that landed with no record is found by name and recorded at the next finish`() = runBlocking {
        val a = download(1)
        val target = layout(newRoot).downloadsDir.apply { mkdirs() }
        val landed = File(target, a.name)
        a.renameTo(landed)
        val pending = StorageRelocationPlan.Pending(newRoot.path, defaults.cacheDir.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = newRoot.path, storageRootPending = pending.encode()) }
        val outcome = relocationWith().resumePending()!!
        assertEquals(0, outcome.failed.size)
        assertEquals(landed.absolutePath, settings.downloadRecord(-100, 1)!!.localPath)
        assertTrue(landed.isFile)
    }

    @Test
    fun `a half copy a kill left is swept at resume, and only the names of our downloads`() = runBlocking {
        download(1)
        val target = layout(newRoot).downloadsDir.apply { mkdirs() }
        val ours = File(target, "Episode 1.mkv.part").apply { writeText("half") }
        val theirs = File(target, "something-else.mkv.part").apply { writeText("not ours") }
        val pending = StorageRelocationPlan.Pending(newRoot.path, defaults.cacheDir.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = newRoot.path, storageRootPending = pending.encode()) }
        val outcome = relocationWith().resumePending()!!
        assertEquals(1, outcome.moved)
        assertFalse(ours.exists())
        assertTrue(theirs.exists())
        assertEquals(setOf("Episode 1.mkv", "something-else.mkv.part"), target.list()!!.toSet())
    }

    @Test
    fun `forgetting a move resets the location to the default folders and removes the old cache`() = runBlocking {
        val oldRoot = File(tmp, "oldroot")
        val oldCache = File(oldRoot, "TMPlayer/cache").apply { mkdirs() }
        File(oldCache, "junk").writeText("x")
        val kept = download(1)
        val pending = StorageRelocationPlan.Pending(File(tmp, "gone").path, oldCache.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = File(tmp, "gone").path, storageRootPending = pending.encode()) }
        defaults.cacheDir.mkdirs()
        val r = relocationWith(usable = { false })
        r.forgetPending()
        assertEquals("", prefs.now.storageRoot)
        assertNull(r.pending())
        assertFalse(oldCache.exists())
        // The cache in use and the downloads are not touched.
        assertTrue(defaults.cacheDir.isDirectory)
        assertTrue(kept.isFile)
    }

    @Test
    fun `forgetting never deletes the cache that is in use`() = runBlocking {
        defaults.cacheDir.mkdirs()
        File(defaults.cacheDir, "live").writeText("x")
        val pending = StorageRelocationPlan.Pending(File(tmp, "gone").path, defaults.cacheDir.path, defaults.downloadsDir.path)
        prefs.update { it.copy(storageRoot = File(tmp, "gone").path, storageRootPending = pending.encode()) }
        relocationWith(usable = { false }).forgetPending()
        assertTrue(File(defaults.cacheDir, "live").isFile)
    }

    @Test
    fun `a move that was not to carry the downloads does not carry them when finished at launch either`() = runBlocking {
        val kept = download(1)
        val pending = StorageRelocationPlan.Pending(newRoot.path, defaults.cacheDir.path, defaults.downloadsDir.path, moveDownloads = false)
        prefs.update { it.copy(storageRoot = newRoot.path, storageRootPending = pending.encode()) }
        val outcome = relocationWith().resumePending()!!
        assertEquals(0, outcome.moved)
        assertTrue(kept.isFile)
        assertEquals("", prefs.now.storageRootPending)
    }
}
