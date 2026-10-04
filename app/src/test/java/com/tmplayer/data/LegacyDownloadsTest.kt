package com.tmplayer.data

import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one time move of downloads made before they had a folder, over a fake TDLib.
 */
class LegacyDownloadsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val settings by lazy {
        SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
    }
    private val cache by lazy { File(folder.root, "cache/documents").apply { mkdirs() } }
    private val downloads by lazy { File(folder.root, "downloads") }

    private fun item(id: Int) = MediaItem(
        chatId = -100L, messageId = id.toLong(), fileId = id, title = "Film $id", sizeBytes = 32,
        durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private fun cached(fileId: Int): File =
        File(cache, "film_$fileId.mp4").apply { writeBytes(ByteArray(32)) }

    /** Records every call, so the aggregate notification can be checked. */
    private class Recorder : TransferNotifier {
        val calls = mutableListOf<String>()
        override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) { calls += "begin $kind $title" }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) { calls += "progress $done/$total" }
        override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) { calls += "complete $body" }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) { calls += "fail $reason" }
        override fun cancel(id: Long) { calls += "cancel" }
        override val capabilities: Set<TransferNotifier.Capability> = emptySet()
    }

    @Test
    fun `whole files move, part files and missing ones stay, and it happens once`() = runBlocking {
        // 1 and 2 are whole, 3 is half there, 4 has nothing left, 5 is in the folder already.
        val td = FakeTdFiles(
            bytes = mutableMapOf(1 to 32L, 2 to 32L, 3 to 16L),
            paths = mutableMapOf(1 to cached(1).path, 2 to cached(2).path),
        )
        (1..4).forEach { settings.noteDownload(item(it), "Chat") }
        settings.noteDownload(item(5), "Chat", "/elsewhere/Film 5.mp4")
        val notes = Recorder()

        val result = LegacyDownloads(settings, { downloads }, td, { td.paths[it] }, notes).migrateOnce()!!

        assertEquals(LegacyDownloads.Result(moved = 2, partial = 1, failed = 0), result)
        assertEquals(File(downloads, "Film 1.mp4").absolutePath, settings.downloadRecord(-100L, 1L)!!.localPath)
        assertEquals(File(downloads, "Film 2.mp4").absolutePath, settings.downloadRecord(-100L, 2L)!!.localPath)
        assertNull(settings.downloadRecord(-100L, 3L)!!.localPath)
        assertNull(settings.downloadRecord(-100L, 4L)!!.localPath)
        assertEquals("/elsewhere/Film 5.mp4", settings.downloadRecord(-100L, 5L)!!.localPath)
        assertEquals(setOf(1, 2), td.deleted.toSet())
        assertTrue(File(downloads, "Film 1.mp4").isFile)

        assertEquals("begin Migrate ${LegacyDownloads.title(2)}", notes.calls.first())
        assertEquals("progress 2/2", notes.calls[2])
        assertTrue(notes.calls.last().startsWith("complete 2 downloads"))
        assertEquals("2 downloads moved into ${LegacyDownloads.FOLDER}.", LegacyDownloads.toast(result))

        assertTrue(settings.downloadsMigratedNow())
        assertNull(LegacyDownloads(settings, { downloads }, td, { td.paths[it] }).migrateOnce())
    }

    @Test
    fun `a saved id from an earlier session is looked up again before anything moves`() = runBlocking {
        val td = FakeTdFiles(
            bytes = mutableMapOf(70 to 32L),
            paths = mutableMapOf(70 to cached(70).path),
            renumbered = mapOf(7 to 70),
        )
        settings.noteDownload(item(7), "Chat")

        LegacyDownloads(settings, { downloads }, td, { td.paths[it] }).migrate()

        assertEquals(listOf(70), td.deleted)
        assertEquals(File(downloads, "Film 7.mp4").absolutePath, settings.downloadRecord(-100L, 7L)!!.localPath)
    }

    @Test
    fun `a move that fails is tried again on the next launch`() = runBlocking {
        val source = cached(8)
        val td = FakeTdFiles(bytes = mutableMapOf(8 to 32L), paths = mutableMapOf(8 to source.path))
        settings.noteDownload(item(8), "Chat")
        val blocked = File(folder.root, "blocked").apply { writeText("not a folder") }
        val notes = Recorder()

        val result = LegacyDownloads(settings, { blocked }, td, { td.paths[it] }, notes).migrateOnce()!!

        assertEquals(LegacyDownloads.Result(moved = 0, partial = 0, failed = 1), result)
        assertFalse(settings.downloadsMigratedNow())
        assertTrue(source.isFile)
        assertNull(settings.downloadRecord(-100L, 8L)!!.localPath)
        assertTrue(notes.calls.last().startsWith("fail"))
        assertNull(LegacyDownloads.toast(result))

        // The next launch, with somewhere to put it.
        val again = LegacyDownloads(settings, { downloads }, td, { td.paths[it] }).migrateOnce()!!
        assertEquals(1, again.moved)
        assertTrue(settings.downloadsMigratedNow())
    }

    @Test
    fun `nothing to move says nothing and still marks the move done`() = runBlocking {
        val notes = Recorder()
        val result = LegacyDownloads(settings, { downloads }, FakeTdFiles(), { null }, notes).migrateOnce()!!
        assertEquals(LegacyDownloads.Result(0, 0, 0), result)
        assertTrue(notes.calls.isEmpty())
        assertTrue(settings.downloadsMigratedNow())
    }
}
