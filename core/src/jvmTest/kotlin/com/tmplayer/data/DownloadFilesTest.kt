package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.random.Random

/**
 * Moving a finished file into Downloads, on one volume and across two, and the names it lands
 * under.
 *
 * The cross volume cases need two file stores. On Linux `/dev/shm` is a tmpfs and the build
 * directory is on the disk, which is what CI has; where the two turn out to be one store, those
 * cases are skipped rather than passing without having copied anything.
 */
class DownloadFilesTest {

    private val disk: File = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "tm-move").toFile()
    private val shm: File? = File("/dev/shm").takeIf { it.isDirectory && it.canWrite() }
        ?.let { Files.createTempDirectory(it.toPath(), "tm-move").toFile() }

    @After
    fun cleanUp() {
        disk.deleteRecursively()
        shm?.deleteRecursively()
    }

    private fun video(dir: File, name: String, bytes: Int): Pair<File, ByteArray> {
        val content = Random(bytes).nextBytes(bytes)
        val file = File(dir, "cache/videos/$name").apply { parentFile.mkdirs(); writeBytes(content) }
        return file to content
    }

    // ---- moving ------------------------------------------------------------------------------

    @Test
    fun `on one store the file is renamed and progress is told once, whole`() = runBlocking {
        val (src, content) = video(disk, "5.mkv", 4096)
        val downloads = File(disk, "downloads")
        val heard = mutableListOf<Pair<Long, Long>>()

        val moved = DownloadFiles.moveIntoDownloads(src, downloads, "Episode 5.mkv") { done, total -> heard += done to total }

        assertEquals(File(downloads, "Episode 5.mkv"), moved)
        assertArrayEquals(content, moved.readBytes())
        assertFalse(src.exists())
        assertEquals(listOf(4096L to 4096L), heard)
        assertTrue(DownloadFiles.sameStore(src, downloads))
    }

    @Test
    fun `across stores the file is copied whole, the part file is gone and the source deleted`() = runBlocking {
        assumeTrue("needs /dev/shm", shm != null)
        val downloads = File(shm, "downloads")
        assumeTrue("needs two file stores", !DownloadFiles.sameStore(disk, shm!!))
        val (src, content) = video(disk, "6.mkv", 300_000)
        val heard = mutableListOf<Long>()

        val moved = DownloadFiles.moveIntoDownloads(src, downloads, "Episode 6.mkv") { done, _ -> heard += done }

        assertArrayEquals(content, moved.readBytes())
        assertFalse(src.exists())
        assertFalse(File(downloads, "Episode 6.mkv.part").exists())
        assertEquals(300_000L, heard.last())
        assertEquals(heard.sorted(), heard)
    }

    @Test
    fun `the other way across, tmpfs to disk, works the same`() = runBlocking {
        assumeTrue("needs /dev/shm", shm != null)
        assumeTrue("needs two file stores", !DownloadFiles.sameStore(disk, shm!!))
        val (src, content) = video(shm, "7.mp4", 70_000)
        val moved = DownloadFiles.moveIntoDownloads(src, File(disk, "downloads"), "Seven.mp4")
        assertArrayEquals(content, moved.readBytes())
        assertFalse(src.exists())
    }

    @Test
    fun `a name already taken gets a number, and the first file is untouched`() = runBlocking {
        val downloads = File(disk, "downloads").apply { mkdirs() }
        File(downloads, "Film.mkv").writeText("already here")
        val (src, _) = video(disk, "8.mkv", 100)

        val moved = DownloadFiles.moveIntoDownloads(src, downloads, "Film.mkv")

        assertEquals("Film (2).mkv", moved.name)
        assertEquals("already here", File(downloads, "Film.mkv").readText())
    }

    @Test
    fun `a missing source fails and creates nothing`() {
        val downloads = File(disk, "downloads")
        try {
            runBlocking { DownloadFiles.moveIntoDownloads(File(disk, "nope.mkv"), downloads, "Nope.mkv") }
            fail("expected the move to refuse")
        } catch (_: FileNotFoundException) {
        }
        assertFalse(File(downloads, "Nope.mkv").exists())
    }

    @Test
    fun `a copy cancelled part way leaves the source and no part file`() {
        assumeTrue("needs /dev/shm", shm != null)
        assumeTrue("needs two file stores", !DownloadFiles.sameStore(disk, shm!!))
        val (src, content) = video(disk, "9.mkv", 50_000)
        val downloads = File(shm, "downloads")
        try {
            runBlocking {
                DownloadFiles.moveIntoDownloads(src, downloads, "Nine.mkv") { _, _ ->
                    throw CancellationException("viewer pressed cancel")
                }
            }
            fail("expected the cancellation to come through")
        } catch (_: CancellationException) {
        }
        assertArrayEquals(content, src.readBytes())
        assertFalse(File(downloads, "Nine.mkv.part").exists())
        assertFalse(File(downloads, "Nine.mkv").exists())
    }

    // ---- names -------------------------------------------------------------------------------

    @Test
    fun `the title gives the name and the original file the extension`() {
        assertEquals("Harbour Notes (2026) 720p.mkv", DownloadFiles.safeName("Harbour Notes (2026) 720p", "75.mkv"))
        // A title that already ends in the extension is not given it twice.
        assertEquals("Harbour Notes 720p.mkv", DownloadFiles.safeName("Harbour Notes 720p.mkv", "x.mkv"))
    }

    @Test
    fun `a dotted release name keeps its dots and only a real container counts as an extension`() {
        assertEquals("Show.S01E02.1080p", DownloadFiles.safeName("Show.S01E02.1080p"))
        assertEquals("Show.S01E02.1080p.mkv", DownloadFiles.safeName("Show.S01E02.1080p.mkv"))
    }

    @Test
    fun `characters no file system takes are replaced`() {
        assertEquals("Show Part 1 Who What.mp4", DownloadFiles.safeName("Show: Part 1 / Who? \"What\"", "a.mp4"))
        assertEquals("tab and newline.mkv", DownloadFiles.safeName("tab\tand\nnewline", "a.mkv"))
    }

    @Test
    fun `trailing dots and spaces go, since Windows drops them`() {
        assertEquals("The End.mkv", DownloadFiles.safeName("The End... ", "a.mkv"))
        assertEquals("Hidden.mkv", DownloadFiles.safeName("..Hidden", "a.mkv"))
    }

    @Test
    fun `Windows device names are made into ordinary ones`() {
        assertEquals("CON_.mkv", DownloadFiles.safeName("CON", "a.mkv"))
        assertEquals("com1_.mp4", DownloadFiles.safeName("com1", "a.mp4"))
        assertEquals("Console.mkv", DownloadFiles.safeName("Console", "a.mkv"))
    }

    @Test
    fun `an empty title falls back to the file name, then to a word`() {
        assertEquals("Original Name.mkv", DownloadFiles.safeName("", "Original Name.mkv"))
        assertEquals("video.mkv", DownloadFiles.safeName("???", "???.mkv"))
        assertEquals("video", DownloadFiles.safeName(""))
    }

    @Test
    fun `long names are cut to the byte limit without splitting a character`() {
        val malayalam = "ഹാർബർ നോട്ട്സ് ".repeat(30)
        val name = DownloadFiles.safeName(malayalam, "a.mkv")
        val bytes = name.toByteArray(Charsets.UTF_8).size
        assertTrue("was $bytes bytes", bytes <= DownloadFiles.MAX_NAME_BYTES)
        assertTrue(name.endsWith(".mkv"))
        assertFalse(name.contains('�'))
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))

        val emoji = "🎬".repeat(100)
        val cut = DownloadFiles.safeName(emoji, "a.mp4")
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= DownloadFiles.MAX_NAME_BYTES)
        assertEquals(0, cut.removeSuffix(".mp4").length % 2)
    }

    @Test
    fun `free names count up past every taken one, part files included`() {
        val dir = File(disk, "names").apply { mkdirs() }
        assertEquals("A.mkv", DownloadFiles.freeName(dir, "A.mkv").name)
        File(dir, "A.mkv").writeText("1")
        File(dir, "A (2).mkv").writeText("2")
        File(dir, "A (3).mkv.part").writeText("3")
        assertEquals("A (4).mkv", DownloadFiles.freeName(dir, "A.mkv").name)
        File(dir, "noext").writeText("x")
        assertEquals("noext (2)", DownloadFiles.freeName(dir, "noext").name)
    }

    @Test
    fun `a directory that does not exist yet is judged by its parent`() {
        assertTrue(DownloadFiles.sameStore(disk, File(disk, "not/yet/made")))
    }
}
