package com.tmplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * "Other folder" (HX57): a folder picked through the system picker takes a download whole, is
 * read back as a [LocalVideo], and reads as missing (never a crash) once its grant is gone. The
 * provider is [FakeTreeProvider], a plain folder served the way the device's own storage is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var tree: Uri

    @Before
    fun setUp() {
        FakeTreeProvider.base = folder.newFolder("storage")
        FakeTreeProvider.revoked = false
        FakeTreeProvider.full = false
        File(FakeTreeProvider.base, "TMPlayer").mkdirs()
        Robolectric.setupContentProvider(FakeTreeProvider::class.java, StoredLocation.LOCAL_PROVIDER)
        tree = DocumentsContract.buildTreeDocumentUri(StoredLocation.LOCAL_PROVIDER, "primary:TMPlayer")
        AndroidStorage.appContext = context
    }

    @After
    fun tearDown() {
        FakeTreeProvider.revoked = false
        FakeTreeProvider.full = false
        AndroidStorage.currentRoot = ""
    }

    private fun record(path: String, size: Long) = ResumeRecord(
        chatId = 1, messageId = 1, fileId = 1, title = "t", chatTitle = "c", sizeBytes = size,
        durationSec = 0, positionMs = 0, durationMs = 0, updatedAt = 0, localPath = path,
    )

    private fun source(name: String, bytes: Int): File =
        folder.newFile(name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    @Test
    fun `a picked local folder parses and a cloud or garbage one does not`() {
        assertNotNull(DownloadTree.parse(tree.toString()))
        assertNull(DownloadTree.parse("content://com.google.android.apps.docs.storage/tree/abc"))
        assertNull(DownloadTree.parse("content://"))
        assertNull(DownloadTree.parse("/storage/1A2B-3C4D"))
        assertNull(DownloadTree.parse(""))
        assertNull(DownloadTree.parse(null))
    }

    @Test
    fun `the grant is kept and checked before the folder counts as usable`() {
        assertFalse(DownloadTree.usable(context, tree))

        assertTrue(DownloadTree.take(context, tree))

        assertTrue(DownloadTree.hasGrant(context, tree))
        assertTrue(DownloadTree.usable(context, tree))

        DownloadTree.release(context, tree)

        assertFalse(DownloadTree.usable(context, tree))
    }

    @Test
    fun `a video is copied in whole, named free, and read back as a document`() = runBlocking {
        DownloadTree.take(context, tree)
        val file = source("a.mkv", 5_000)

        val first = DownloadTree.putFile(context, tree, file, "Show.mkv")
        val second = DownloadTree.putFile(context, tree, file, "Show.mkv")

        assertTrue(StoredLocation.documentIsIn(tree.toString(), first.toString()))
        assertEquals(setOf("Show.mkv", "Show (2).mkv"), File(FakeTreeProvider.base, "TMPlayer").list()!!.toSet())
        val video = LocalVideo.of(context, first.toString())
        assertTrue(video is LocalVideo.InTree)
        assertEquals(5_000L, video!!.length())
        assertEquals("Show.mkv", video.name())
        assertEquals(file.readBytes().toList().subList(100, 200), video.readAt(100, 100)!!.toList())
        assertNull(video.readAt(5_000, 10))
        assertTrue(second.toString() != first.toString())
    }

    @Test
    fun `a full folder leaves no partial copy`() = runBlocking {
        DownloadTree.take(context, tree)
        FakeTreeProvider.full = true
        val file = source("b.mkv", 5_000)

        val failure = runCatching { DownloadTree.putFile(context, tree, file, "Full.mkv") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(DownloadRelocation.isNoSpace(failure!!))
        assertEquals(emptyList<String>(), File(FakeTreeProvider.base, "TMPlayer").list()!!.toList())
        assertTrue(file.isFile)
    }

    @Test
    fun `a taken back grant reads as missing and never throws`() = runBlocking {
        DownloadTree.take(context, tree)
        val document = DownloadTree.putFile(context, tree, source("c.mkv", 2_000), "Gone.mkv")
        FakeTreeProvider.revoked = true
        DownloadTree.release(context, tree)

        val video = LocalVideo.of(context, document.toString())

        assertNull(video!!.length())
        assertFalse(video.exists())
        assertNull(video.readAt(0, 10))
        assertFalse(DownloadTree.usable(context, tree))
        assertEquals(LocalDownloads.FileState.Missing, LocalDownloads.stateOf(record(document.toString(), 2_000)))
        val failure = runCatching { DownloadTree.putFile(context, tree, source("d.mkv", 10), "X.mkv") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun `a garbage document string is a video that is not there`() {
        listOf("content://", "content://x/y", "content:///", "content://com.android.externalstorage.documents/tree/x/document/").forEach {
            val video = LocalVideo.of(context, it)
            assertNull(video?.length())
            assertFalse(video?.exists() ?: false)
        }
    }

    @Test
    fun `pull brings a document out as a whole file with no part left`() = runBlocking {
        DownloadTree.take(context, tree)
        val file = source("e.mkv", 70_000)
        val document = DownloadTree.putFile(context, tree, file, "Out.mkv")
        val dir = folder.newFolder("out")

        val pulled = DownloadTree.pull(LocalVideo.of(context, document.toString()) as LocalVideo.InTree, dir, "Out.mkv")

        assertEquals(file.readBytes().toList(), pulled.readBytes().toList())
        assertEquals(listOf("Out.mkv"), dir.list()!!.toList())
    }

    @Test
    fun `an unused grant is given back and one holding a download is kept`() = runBlocking {
        val other = DocumentsContract.buildTreeDocumentUri(StoredLocation.LOCAL_PROVIDER, "primary:Other")
        File(FakeTreeProvider.base, "Other").mkdirs()
        DownloadTree.take(context, tree)
        DownloadTree.take(context, other)
        val settings = SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
        settings.setDownloadRoot(tree.toString())

        DownloadTree.releaseUnused(context, settings)

        assertTrue(DownloadTree.hasGrant(context, tree))
        assertFalse(DownloadTree.hasGrant(context, other))
    }
}
