package com.tmplayer.data

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderCopyTest {

    @Test
    fun namesKeepTheVideosNameAndTheFilesExtension() {
        assertEquals("Harbour Notes.mkv", FolderCopy.nameFor("Harbour Notes", "123.mkv"))
        assertEquals("Harbour Notes.mkv", FolderCopy.nameFor("Harbour Notes.mkv", "123.MKV"))
        assertEquals("clip.mp4", FolderCopy.nameFor("", "clip.mp4"))
        assertEquals("video.mp4", FolderCopy.nameFor("   ", ""))
    }

    @Test
    fun unsafeNamesAreMadeSafe() {
        assertEquals("a b c d.mp4", FolderCopy.nameFor("a/b\\c:d", "x.mp4"))
        assertEquals("what now.mp4", FolderCopy.nameFor("what?* now\u0000", "x.mp4"))
        // Leading and trailing dots and spaces, which Windows drops or refuses.
        assertEquals("hidden.mp4", FolderCopy.nameFor("..hidden. ", "x.mp4"))
        // Windows' reserved device names.
        assertEquals("CON_.mp4", FolderCopy.nameFor("CON", "x.mp4"))
        assertEquals("lpt1_.mkv", FolderCopy.nameFor("lpt1", "x.mkv"))
        // A long name is cut short.
        val long = FolderCopy.nameFor("x".repeat(500), "a.mp4")
        assertEquals(FolderCopy.NAME_MAX + ".mp4".length, long.length)
        // An odd extension on the source does not leak into the name.
        assertEquals("Film.mp4", FolderCopy.nameFor("Film", "weird.e x t"))
    }

    @Test
    fun aTakenNameGetsTheNextNumber() {
        val taken = setOf("film.mkv", "film (2).mkv")
        assertEquals("Film (3).mkv", FolderCopy.freeName("Film.mkv") { it.lowercase() in taken })
        assertEquals("Film.mkv", FolderCopy.freeName("Film.mkv") { false })
        assertEquals("README (2)", FolderCopy.freeName("README") { it == "README" })
    }

    @Test
    fun sortSkipsUnfinishedAndForbiddenAndCountsThem() = runTest {
        data class V(val name: String, val whole: Boolean, val savable: Boolean)
        val items = listOf(V("a", true, true), V("b", false, true), V("c", true, false), V("d", false, false), V("e", true, true))
        val sorted = FolderCopy.sort(items, whole = { it.whole }, canBeSaved = { it.savable })
        assertEquals(listOf("a", "e"), sorted.copy.map { it.name })
        assertEquals(1, sorted.forbidden)
        // An unfinished one is not asked about at all, whether or not its chat allows saving.
        assertEquals(2, sorted.unfinished)
    }

    @Test
    fun fittingTakesTheNewestThatFit() {
        data class V(val name: String, val size: Long, val at: Long)
        val items = listOf(V("old small", 10, 1), V("new big", 100, 3), V("mid", 30, 2))
        val fit = FolderCopy.fitting(items, { it.size }, { it.at }, free = 50)
        // The newest is too big, so the next newest and then the oldest go in, in the list's order.
        assertEquals(listOf("old small", "mid"), fit.map { it.name })

        val plan = FolderCopy.plan(items, "Movies", 50, 0, 0, { it.size }, { it.at })
        assertFalse(plan.fits)
        assertEquals(140, plan.totalBytes)
        assertEquals(40, plan.fittingBytes)

        val roomy = FolderCopy.plan(items, "Movies", 1000, 0, 0, { it.size }, { it.at })
        assertTrue(roomy.fits)
        assertEquals(items, roomy.fitting)
        val unknown = FolderCopy.plan(items, "Movies", null, 0, 0, { it.size }, { it.at })
        assertTrue(unknown.fits)
    }

    @Test
    fun errorWordsAreRead() {
        assertTrue(FolderCopy.isNoSpace("write failed: ENOSPC (No space left on device)"))
        assertTrue(FolderCopy.isNoSpace("There is not enough space on the disk"))
        assertFalse(FolderCopy.isNoSpace("Permission denied"))
        assertTrue(FolderCopy.isTooLarge("write failed: EFBIG (File too large)"))
        assertTrue(FolderCopy.isFat("vfat"))
        assertTrue(FolderCopy.isFat("FAT32"))
        assertFalse(FolderCopy.isFat("exfat"))
        assertFalse(FolderCopy.isFat("ntfs"))
    }

    /** A folder in memory, with switches for the ways a real one fails. */
    private class FakeFolder(
        val names: MutableSet<String> = mutableSetOf(),
        var failAfterBytes: Long? = null,
        var failWith: () -> Exception = { IOException("write failed: ENOSPC (No space left on device)") },
        var lostOnError: Boolean = false,
        var max: Long? = null,
        var free: Long? = null,
        var canWrite: Boolean = true,
        var shortBy: Long = 0,
    ) : FolderCopyIo.Folder<String> {
        val written = LinkedHashMap<String, ByteArrayOutputStream>()
        val discarded = ArrayList<String>()
        override val label = "Movies"
        override fun writable() = canWrite
        override fun existing(): Collection<String> = names
        override fun freeBytes() = free
        override fun maxFileBytes() = max
        override fun create(name: String, mimeType: String): String {
            names += name
            written[name] = ByteArrayOutputStream()
            return name
        }
        override fun open(handle: String): OutputStream = object : OutputStream() {
            val out = written.getValue(handle)
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val limit = failAfterBytes
                if (limit != null && out.size() + len > limit) throw failWith()
                out.write(b, off, len)
            }
        }
        override fun size(handle: String): Long? = written[handle]?.size()?.toLong()?.minus(shortBy)
        override fun discard(handle: String) {
            discarded += handle
            written.remove(handle)
            names.remove(handle)
        }
        override fun lost(error: Throwable) = lostOnError
    }

    private fun video(dir: File, name: String, bytes: Int): FolderCopyIo.Source {
        val file = File(dir, name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }
        return FolderCopyIo.Source(file, name.substringBeforeLast('.'), name, "", key = name)
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("foldercopy").toFile().apply { deleteOnExit() }

    @Test
    fun copiesEveryVideoUnderAFreeNameAndVerifiesIt() {
        val dir = tempDir()
        val folder = FakeFolder(names = mutableSetOf("A.mp4"))
        val sources = listOf(video(dir, "A.mp4", 1000), video(dir, "B.mp4", 2000))
        val out = FolderCopyIo.copyAll(folder, sources, forbidden = 1, unfinished = 2, cancelled = { false }, onProgress = {})
        assertEquals(2, out.copied)
        assertEquals(listOf("A (2).mp4", "B.mp4"), folder.written.keys.toList())
        assertEquals(listOf("A.mp4", "B.mp4"), out.verifiedKeys)
        assertNull(out.stop)
        assertFalse(out.clean) // one forbidden and two unfinished were skipped
        assertEquals(1, out.forbidden)
        assertEquals(2, out.unfinished)
        // The originals are untouched.
        assertTrue(sources.all { it.exists() })
    }

    @Test
    fun aVideoReadThroughAStreamIsCopiedAndAGoneOneIsReported() {
        val bytes = ByteArray(500) { it.toByte() }
        val here = FolderCopyIo.Source("Doc", "Doc", "", "k1", "doc.mp4", { bytes.size.toLong() }, { bytes.inputStream() })
        val gone = FolderCopyIo.Source("Gone", "Gone", "", "k2", "gone.mp4", { null }, { error("not opened") })
        val folder = FakeFolder()
        val out = FolderCopyIo.copyAll(folder, listOf(here, gone), 0, 0, { false }) {}
        assertEquals(1, out.copied)
        assertEquals(listOf("k1"), out.verifiedKeys)
        assertEquals(1, out.failed.size)
    }

    @Test
    fun theSameSelectionTwiceGetsNumberedCopies() {
        val dir = tempDir()
        val folder = FakeFolder()
        val sources = listOf(video(dir, "A.mp4", 10))
        FolderCopyIo.copyAll(folder, sources, 0, 0, { false }) {}
        val again = FolderCopyIo.copyAll(folder, sources, 0, 0, { false }) {}
        assertTrue(again.clean)
        assertEquals(listOf("A.mp4", "A (2).mp4"), folder.written.keys.toList())
    }

    @Test
    fun aFullDriveStopsTheBatchAndRemovesThePartCopy() {
        val dir = tempDir()
        val folder = FakeFolder(failAfterBytes = 1_500_000)
        val sources = listOf(video(dir, "A.mp4", 1_000_000), video(dir, "B.mp4", 1_000_000), video(dir, "C.mp4", 10))
        // The fake counts per file, so A fits and B runs out part way.
        folder.failAfterBytes = 600_000
        val small = listOf(video(dir, "S.mp4", 100)) + sources
        val out = FolderCopyIo.copyAll(folder, small, 0, 0, { false }) {}
        assertEquals(FolderCopy.Stop.NoSpace, out.stop)
        assertEquals(listOf("S.mp4"), out.saved)
        assertEquals(3, out.notReached)
        assertEquals(listOf("A.mp4"), folder.discarded)
        assertEquals(listOf("S.mp4"), folder.written.keys.toList())
    }

    @Test
    fun aLostFolderStopsTheBatch() {
        val dir = tempDir()
        val folder = FakeFolder(failAfterBytes = 0, failWith = { SecurityException("revoked") }, lostOnError = true)
        val out = FolderCopyIo.copyAll(folder, listOf(video(dir, "A.mp4", 10), video(dir, "B.mp4", 10)), 0, 0, { false }) {}
        assertEquals(FolderCopy.Stop.FolderLost, out.stop)
        assertEquals(2, out.notReached)
        assertTrue(folder.written.isEmpty())
    }

    @Test
    fun aReadOnlyFolderStopsBeforeWriting() {
        val dir = tempDir()
        val folder = FakeFolder(canWrite = false)
        val out = FolderCopyIo.copyAll(folder, listOf(video(dir, "A.mp4", 10)), 0, 0, { false }) {}
        assertEquals(FolderCopy.Stop.ReadOnly, out.stop)
        assertEquals(0, out.copied)
    }

    @Test
    fun cancelPartWayLeavesNoPartFile() {
        val dir = tempDir()
        val folder = FakeFolder()
        var calls = 0
        // Cancelled on the copy's first buffer check of the second video.
        val sources = listOf(video(dir, "A.mp4", 10), video(dir, "B.mp4", 10))
        val out = FolderCopyIo.copyAll(folder, sources, 0, 0, cancelled = { calls++ >= 3 }) {}
        assertEquals(FolderCopy.Stop.Cancelled, out.stop)
        assertEquals(1, out.copied)
        assertEquals(listOf("A.mp4"), folder.written.keys.toList())
    }

    @Test
    fun aGoneFileATooLargeOneAndAShortCopyAreFailuresNotStops() {
        val dir = tempDir()
        val folder = FakeFolder(max = 50)
        val gone = video(dir, "Gone.mp4", 10).also { File(dir, "Gone.mp4").delete() }
        val big = video(dir, "Big.mp4", 100)
        val fine = video(dir, "Fine.mp4", 10)
        val out = FolderCopyIo.copyAll(folder, listOf(gone, big, fine), 0, 0, { false }) {}
        assertNull(out.stop)
        assertEquals(1, out.copied)
        assertEquals(listOf("Gone.mp4", "Big.mp4"), out.failed.map { it.title })

        val shortFolder = FakeFolder(shortBy = 1)
        val short = FolderCopyIo.copyAll(shortFolder, listOf(video(dir, "X.mp4", 10)), 0, 0, { false }) {}
        assertEquals(0, short.copied)
        assertEquals(listOf("X.mp4"), shortFolder.discarded)
        assertTrue(short.verifiedKeys.isEmpty())
    }

    @Test
    fun tooLittleRoomStopsBeforeAByteIsWritten() {
        val dir = tempDir()
        val folder = FakeFolder(free = 5)
        val out = FolderCopyIo.copyAll(folder, listOf(video(dir, "A.mp4", 10)), 0, 0, { false }) {}
        assertEquals(FolderCopy.Stop.NoSpace, out.stop)
        assertTrue(folder.written.isEmpty())
    }

    @Test
    fun aSizeTheFolderWillNotGiveIsSavedButNotVerified() {
        val dir = tempDir()
        val folder = object : FolderCopyIo.Folder<String> by FakeFolder() {
            override fun size(handle: String): Long? = null
        }
        val out = FolderCopyIo.copyAll(folder, listOf(video(dir, "A.mp4", 10)), 0, 0, { false }) {}
        assertEquals(1, out.copied)
        assertTrue(out.verifiedKeys.isEmpty())
    }
}
