package com.tmplayer.data

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Moving downloads into and out of a folder the viewer picked (HX57 "Other folder"): the record
 * follows the file, a full folder or a lost grant stops the move at the first file with the
 * original untouched and nothing half written, a cancel removes the copy in progress, and a
 * download finishing into the folder is recorded as a document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRelocationTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}"))) }
    private val internalDownloads by lazy { File(context.filesDir, AndroidPaths.DOWNLOADS).apply { mkdirs() } }
    private lateinit var tree: Uri
    private lateinit var treeDir: File

    @Before
    fun setUp() {
        FakeTreeProvider.base = folder.newFolder("storage")
        FakeTreeProvider.revoked = false
        FakeTreeProvider.full = false
        treeDir = File(FakeTreeProvider.base, "TMPlayer").apply { mkdirs() }
        Robolectric.setupContentProvider(FakeTreeProvider::class.java, StoredLocation.LOCAL_PROVIDER)
        tree = DocumentsContract.buildTreeDocumentUri(StoredLocation.LOCAL_PROVIDER, "primary:TMPlayer")
        AndroidStorage.appContext = context
        AndroidStorage.currentRoot = ""
        DownloadTree.take(context, tree)
    }

    @After
    fun tearDown() {
        FakeTreeProvider.revoked = false
        FakeTreeProvider.full = false
        AndroidStorage.currentRoot = ""
    }

    private fun download(id: Long, file: File, path: String = file.absolutePath, bytes: Int = 4_000) = runBlocking {
        file.parentFile?.mkdirs()
        if (!StoredLocation.isDocument(path)) file.writeBytes(ByteArray(bytes) { (it % 200).toByte() })
        val request = DownloadRequest(
            fileId = id.toInt(), title = "Episode $id", sizeBytes = bytes.toLong(), chatId = -100L, messageId = id,
            chatTitle = "Chat", durationSec = 60, mimeType = "video/x-matroska", fileName = file.name,
        )
        settings.noteDownload(request.item(), "Chat", path)
    }

    private fun awaitOnMain(latch: CountDownLatch) {
        var waited = 0
        while (latch.count > 0 && waited < 100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.await(50, TimeUnit.MILLISECONDS)) return
            waited++
        }
        assertTrue("timed out waiting for the move to finish", latch.count == 0L)
    }

    private fun move(destination: DownloadRelocation.Destination, label: String = "Folder"): DownloadRelocation.Outcome? {
        val latch = CountDownLatch(1)
        var outcome: DownloadRelocation.Outcome? = null
        DownloadRelocation.start(
            context = context, settings = settings, destination = destination, label = label,
            onOutcome = { outcome = it; latch.countDown() },
            onCancelled = { latch.countDown() },
        )
        awaitOnMain(latch)
        return outcome
    }

    @Test
    fun `moving into a picked folder records the document and removes the original`() {
        val src = File(internalDownloads, "Episode 1.mkv")
        download(1, src)

        val outcome = move(DownloadRelocation.Destination.Tree(tree))

        assertEquals(1, outcome?.movedCount)
        assertNull(outcome?.stoppedBy)
        assertFalse(src.exists())
        assertEquals(listOf("Episode 1.mkv"), treeDir.list()!!.toList())
        val record = runBlocking { settings.downloadRecord(-100L, 1L) }!!
        assertTrue(StoredLocation.documentIsIn(tree.toString(), record.localPath.orEmpty()))
        assertEquals(LocalDownloads.FileState.Present, LocalDownloads.stateOf(record))
        assertEquals(tree.toString(), runBlocking { settings.downloadRootNow() })
        assertEquals("", runBlocking { settings.downloadRootPendingNow() })
        assertEquals(tree, AndroidStorage.currentTree())
        // Pressing it again moves nothing: the video is in the folder already.
        assertEquals(0, DownloadRelocation.toMove(context, runBlocking { settings.downloadsNow() }, DownloadRelocation.Destination.Tree(tree)).size)
    }

    @Test
    fun `moving out of a picked folder to internal storage brings the file back`() {
        val document = runBlocking {
            File(treeDir, "Episode 2.mkv").writeBytes(ByteArray(4_000) { (it % 200).toByte() })
            DocumentsContract.buildDocumentUriUsingTree(tree, "primary:TMPlayer/Episode 2.mkv").toString()
        }
        download(2, File(treeDir, "Episode 2.mkv"), path = document)
        AndroidStorage.currentRoot = tree.toString()
        runBlocking { settings.setDownloadRoot(tree.toString()) }

        val outcome = move(DownloadRelocation.Destination.Internal, "Internal storage")

        assertEquals(1, outcome?.movedCount)
        assertTrue(File(internalDownloads, "Episode 2.mkv").isFile)
        assertEquals(4_000L, File(internalDownloads, "Episode 2.mkv").length())
        assertFalse(File(treeDir, "Episode 2.mkv").exists())
        assertEquals(File(internalDownloads, "Episode 2.mkv").absolutePath, runBlocking { settings.downloadRecord(-100L, 2L) }?.localPath)
        assertEquals("", runBlocking { settings.downloadRootNow() })
        assertEquals(null, AndroidStorage.currentTree())
    }

    @Test
    fun `a full picked folder stops the move at the first file and leaves every original`() {
        val first = File(internalDownloads, "Episode 3.mkv")
        val second = File(internalDownloads, "Episode 4.mkv")
        download(3, first)
        download(4, second)
        FakeTreeProvider.full = true

        val outcome = move(DownloadRelocation.Destination.Tree(tree), "Videos")

        assertTrue(outcome?.stoppedBy is DownloadRelocation.Stop.NoSpace)
        assertEquals(1, outcome?.failed?.size)
        assertEquals(0, outcome?.movedCount)
        assertTrue(first.isFile && second.isFile)
        assertEquals(emptyList<String>(), treeDir.list()!!.toList())
        assertTrue(outcome?.message.orEmpty().contains("Videos"))
        assertEquals(first.absolutePath, runBlocking { settings.downloadRecord(-100L, 3L) }?.localPath)
        // Still pending: the next launch, or Move pressed again, carries on.
        assertTrue(runBlocking { settings.downloadRootPendingNow() }.isNotEmpty())
    }

    @Test
    fun `a lost grant stops the move and a pending move waits for the folder`() = runBlocking {
        val src = File(internalDownloads, "Episode 5.mkv")
        download(5, src)
        DownloadTree.release(context, tree)
        settings.setDownloadRoot(tree.toString())
        settings.setDownloadRootPending(
            StorageRelocationPlan.Pending(root = tree.toString(), oldCache = "", oldDownloads = internalDownloads.path).encode(),
        )

        DownloadRelocation.resumePending(context, settings)

        assertTrue(src.isFile)
        assertTrue(settings.downloadRootPendingNow().isNotEmpty())
        assertTrue(DownloadRelocation.waitingFor(context, settings) != null)

        // The folder is chosen again: the move finishes.
        DownloadTree.take(context, tree)
        DownloadRelocation.resumePending(context, settings)

        assertFalse(src.exists())
        assertEquals(listOf("Episode 5.mkv"), treeDir.list()!!.toList())
        assertEquals("", settings.downloadRootPendingNow())
        assertNull(DownloadRelocation.waitingFor(context, settings))
    }

    @Test
    fun `new downloads fall back to internal storage while the picked folder cannot be reached`() = runBlocking {
        DownloadRelocation.useWithoutMoving(settings, DownloadRelocation.Destination.Tree(tree))

        assertTrue(AndroidStorage.chosenIsReachable())
        assertEquals(tree, AndroidStorage.currentTree())

        DownloadTree.release(context, tree)

        assertFalse(AndroidStorage.chosenIsReachable())
        assertEquals(File(context.filesDir, AndroidPaths.DOWNLOADS), AndroidStorage.downloadsDir(context))
    }

    @Test
    fun `a cancel part way removes the copy in progress`() = runBlocking {
        val job = Job()
        val big = 3 * 1024 * 1024
        val source = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < big) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= big) return -1
                // Cancelled while the first buffer is in flight: the loop notices before the next.
                job.cancel()
                val n = minOf(len, big - sent)
                sent += n
                return n
            }
        }
        var failure: Throwable? = null
        val running = launch(Dispatchers.IO + job) {
            failure = runCatching { DownloadTree.put(context, tree, "Cancelled.mkv", big.toLong(), { source }) }.exceptionOrNull()
        }
        running.join()

        assertTrue(failure is CancellationException)
        assertEquals(emptyList<String>(), treeDir.list()!!.toList())
    }

    @Test
    fun `a download that finishes into the picked folder is recorded as a document and the cache lets go`() = runBlocking {
        val cache = File(folder.root, "cache").apply { mkdirs() }
        val cached = File(cache, "9.mkv").apply { writeBytes(ByteArray(2_000) { it.toByte() }) }
        val td = FakeTdFiles(paths = mutableMapOf(9 to cached.path), bytes = mutableMapOf(9 to 2_000L))
        val request = DownloadRequest(
            fileId = 9, title = "Episode 9", sizeBytes = 0, chatId = -100L, messageId = 9, chatTitle = "Chat",
            durationSec = 60, mimeType = "video/x-matroska", fileName = "Episode.9.mkv",
        )
        val target = object : DownloadFinisher.TreeTarget {
            override val label = "Videos"
            override fun freeBytes(): Long? = null
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String =
                DownloadTree.putFile(context, tree, source, name, onProgress).toString()
        }

        val outcome = DownloadFinisher(settings, { File(folder.root, "unused") }, td, { td.paths[it] }, { false }, tree = { target })
            .finish(request)

        val uri = (outcome as DownloadFinisher.Outcome.MovedToTree).uri
        assertTrue(StoredLocation.documentIsIn(tree.toString(), uri))
        assertEquals(listOf("Episode 9.mkv"), treeDir.list()!!.toList())
        assertEquals(uri, settings.downloadRecord(-100L, 9L)?.localPath)
        assertEquals(2_000L, settings.downloadRecord(-100L, 9L)?.sizeBytes)
        assertFalse(cached.exists())
        assertEquals(listOf(9), td.deleted)
    }

    @Test
    fun `a download into a folder that fills stays whole in the cache with the reason`() = runBlocking {
        val cache = File(folder.root, "cache2").apply { mkdirs() }
        val cached = File(cache, "10.mkv").apply { writeBytes(ByteArray(2_000) { it.toByte() }) }
        val td = FakeTdFiles(paths = mutableMapOf(10 to cached.path), bytes = mutableMapOf(10 to 2_000L))
        val request = DownloadRequest(
            fileId = 10, title = "Episode 10", sizeBytes = 0, chatId = -100L, messageId = 10, chatTitle = "Chat",
            durationSec = 60, mimeType = "video/x-matroska", fileName = "Episode.10.mkv",
        )
        val target = object : DownloadFinisher.TreeTarget {
            override val label = "Videos"
            override fun freeBytes(): Long? = 500L
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String = error("not reached")
        }

        val outcome = DownloadFinisher(settings, { File(folder.root, "unused") }, td, { td.paths[it] }, { false }, tree = { target })
            .finish(request)

        assertTrue(outcome is DownloadFinisher.Outcome.Failed)
        assertTrue((outcome as DownloadFinisher.Outcome.Failed).reason.contains("Videos"))
        assertTrue(cached.isFile)
        assertNull(settings.downloadRecord(-100L, 10L))
        assertTrue(td.deleted.isEmpty())
    }
}
