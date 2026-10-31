package com.tmplayer.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Moving the downloads folder to another volume, or back to internal storage (HX26): the file
 * lands, the record's path follows it, a crash mid move is picked up again, and a cancel leaves
 * nothing half written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRelocationTest {

    /**
     * The move runs on a real background thread of its own, but posts its result to the main
     * thread, which Robolectric only runs when its looper is idled; a plain [CountDownLatch.await]
     * on its own never sees that fire.
     */
    private fun awaitOnMain(latch: CountDownLatch) {
        var waited = 0
        while (latch.count > 0 && waited < 100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.await(50, TimeUnit.MILLISECONDS)) return
            waited++
        }
        assertTrue("timed out waiting for the move to finish", latch.count == 0L)
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings by lazy { SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}"))) }
    private val internalDownloads by lazy { File(context.filesDir, AndroidPaths.DOWNLOADS).apply { mkdirs() } }

    private fun download(fileId: Long, file: File, bytes: Int = 64): Unit = runBlocking {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { it.toByte() })
        val request = DownloadRequest(
            fileId = fileId.toInt(),
            title = "Episode $fileId",
            sizeBytes = bytes.toLong(),
            chatId = -100L,
            messageId = fileId,
            chatTitle = "Chat",
            durationSec = 60,
            mimeType = "video/x-matroska",
            fileName = file.name,
        )
        settings.noteDownload(request.item(), "Chat", file.absolutePath)
    }

    @Test
    fun `a chosen volume with nothing to move only changes where new downloads go`() = runBlocking {
        AndroidStorage.currentRoot = ""
        val card = folder.newFolder("card")

        DownloadRelocation.useWithoutMoving(settings, card)

        assertEquals(card.path, settings.downloadRootNow())
        assertEquals(card.path, AndroidStorage.currentRoot)
        assertEquals(AndroidStorage.downloadsDirOf(card), AndroidStorage.downloadsDir(context))
    }

    @Test
    fun `moving takes every download into the new folder and updates its record`() {
        val src = File(internalDownloads, "Episode 1.mkv")
        download(1, src)
        val card = folder.newFolder("card")
        AndroidStorage.currentRoot = ""

        val latch = CountDownLatch(1)
        var outcome: DownloadRelocation.Outcome? = null
        DownloadRelocation.start(
            context = context,
            settings = settings,
            root = card,
            label = "Card",
            onOutcome = { outcome = it; latch.countDown() },
            onCancelled = { latch.countDown() },
        )
        awaitOnMain(latch)

        val moved = File(AndroidStorage.downloadsDirOf(card), "Episode 1.mkv")
        assertTrue(moved.isFile)
        assertFalse(src.exists())
        assertEquals(1, outcome?.movedCount)
        assertTrue(outcome?.failed.orEmpty().isEmpty())
        val record = runBlocking { settings.downloadRecord(-100L, 1L) }
        assertEquals(moved.absolutePath, record?.localPath)
        assertEquals(card.path, runBlocking { settings.downloadRootNow() })
        assertEquals("", runBlocking { settings.downloadRootPendingNow() })
    }

    @Test
    fun `an unfinished move is written down and resumed on the next launch`() = runBlocking {
        val src = File(internalDownloads, "Episode 2.mkv")
        download(2, src)
        val card = folder.newFolder("card2")
        AndroidStorage.currentRoot = ""
        // What a kill mid move leaves behind: the new root and a pending note, nothing moved yet.
        settings.setDownloadRoot(card.path)
        settings.setDownloadRootPending(
            StorageRelocationPlan.Pending(root = card.path, oldCache = "", oldDownloads = internalDownloads.path).encode(),
        )

        DownloadRelocation.resumePending(context, settings)

        val moved = File(AndroidStorage.downloadsDirOf(card), "Episode 2.mkv")
        assertTrue(moved.isFile)
        assertFalse(src.exists())
        assertEquals(moved.absolutePath, settings.downloadRecord(-100L, 2L)?.localPath)
        assertEquals("", settings.downloadRootPendingNow())
    }

    @Test
    fun `switching back to internal storage moves a download off the card`() {
        val card = folder.newFolder("card3")
        val cardDownloads = AndroidStorage.downloadsDirOf(card)
        val src = File(cardDownloads, "Episode 3.mkv")
        download(3, src)
        AndroidStorage.currentRoot = card.path
        runBlocking { settings.setDownloadRoot(card.path) }

        val latch = CountDownLatch(1)
        var outcome: DownloadRelocation.Outcome? = null
        DownloadRelocation.start(
            context = context,
            settings = settings,
            root = null,
            label = "Internal storage",
            onOutcome = { outcome = it; latch.countDown() },
            onCancelled = { latch.countDown() },
        )
        awaitOnMain(latch)

        val moved = File(internalDownloads, "Episode 3.mkv")
        assertTrue(moved.isFile)
        assertFalse(src.exists())
        assertEquals(1, outcome?.movedCount)
        assertEquals("", runBlocking { settings.downloadRootNow() })
    }

    @Test
    fun `a folder that cannot be written to is refused before anything moves`() {
        val blocked = File(folder.root, "blocked").apply { writeText("not a folder") }

        val candidate = AndroidStorage.inspect(context, blocked)

        assertFalse(candidate.writable)
    }

    @Test
    fun `volumes always offers the internal default`() {
        val volumes = AndroidStorage.volumes(context)

        assertTrue(volumes.isNotEmpty())
        assertNull(volumes.first().root)
    }
}
