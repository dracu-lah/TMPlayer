package com.tmplayer.data

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
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * HX69: a copy that fails for any reason leaves no part file and the source whole, a phone that
 * cannot map the file finishes the copy through a buffer, and a big file moves on a tiny heap.
 *
 * Copies need two file stores, as in [DownloadFilesTest]: /dev/shm and the build directory.
 */
class DownloadFilesHardeningTest {

    private val disk: File = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "tm-harden").toFile()
    private val shm: File? = File("/dev/shm").takeIf { it.isDirectory && it.canWrite() }
        ?.let { Files.createTempDirectory(it.toPath(), "tm-harden").toFile() }

    @After
    fun cleanUp() {
        disk.deleteRecursively()
        shm?.deleteRecursively()
    }

    private fun twoStores() {
        assumeTrue("needs /dev/shm", shm != null)
        assumeTrue("needs two file stores", !DownloadFiles.sameStore(disk, shm!!))
    }

    private fun video(bytes: Int): Pair<File, ByteArray> {
        val content = Random(bytes).nextBytes(bytes)
        return File(disk, "cache/$bytes.mkv").apply { parentFile.mkdirs(); writeBytes(content) } to content
    }

    @Test
    fun `a copy that cannot map the file carries on through a buffer and comes out identical`() = runBlocking {
        twoStores()
        val (src, content) = video(5_000_000)
        val downloads = File(shm, "downloads")
        var calls = 0
        // Two chunks of 1 MB go through, then the mapping fails the way a short address space does.
        val flaky = DownloadFiles.Transfer { input, position, count, output ->
            if (++calls > 2) throw IOException("Map failed", OutOfMemoryError("mmap"))
            input.transferTo(position, minOf(count, 1_000_000L), output)
        }

        val moved = DownloadFiles.moveIntoDownloads(src, downloads, "Big.mkv", transfer = flaky)

        assertArrayEquals(content, moved.readBytes())
        assertFalse(src.exists())
        assertFalse(File(downloads, "Big.mkv.part").exists())
        assertTrue(calls >= 3)
    }

    @Test
    fun `an OutOfMemoryError straight from transferTo is handled the same way`() = runBlocking {
        twoStores()
        val (src, content) = video(300_000)
        val downloads = File(shm, "downloads")
        val oom = DownloadFiles.Transfer { _, _, _, _ -> throw OutOfMemoryError("Map failed") }

        val moved = DownloadFiles.moveIntoDownloads(src, downloads, "Small.mkv", transfer = oom)

        assertArrayEquals(content, moved.readBytes())
    }

    @Test
    fun `a failure of any kind part way leaves the source whole and no part file`() {
        twoStores()
        val failures = listOf<() -> Throwable>(
            { SecurityException("permission revoked") },
            { IllegalStateException("provider went away") },
            { IOException("No space left on device") },
        )
        for ((index, make) in failures.withIndex()) {
            val (src, content) = video(200_000 + index)
            val downloads = File(shm, "downloads$index")
            var calls = 0
            // The first chunk is written, then the failure comes: a part file exists by then.
            val failing = DownloadFiles.Transfer { input, position, count, output ->
                if (++calls > 1) throw make()
                input.transferTo(position, 50_000L, output)
            }
            try {
                runBlocking { DownloadFiles.moveIntoDownloads(src, downloads, "Fail.mkv", transfer = failing) }
                fail("expected the failure to come through")
            } catch (expected: Exception) {
                assertEquals(make()::class, expected::class)
            }
            assertArrayEquals(content, src.readBytes())
            assertEquals(emptyList<String>(), downloads.list()?.toList().orEmpty())
        }
    }

    @Test
    fun `a file that is gone is a refusal that creates nothing`() {
        val downloads = File(disk, "downloads")
        try {
            runBlocking { DownloadFiles.moveIntoDownloads(File(disk, "gone.mkv"), downloads, "Gone.mkv") }
            fail("expected a refusal")
        } catch (_: java.io.FileNotFoundException) {
        }
        assertFalse(downloads.exists() && downloads.list().orEmpty().isNotEmpty())
    }

    @Test
    fun `a 300 MB file crosses stores in a JVM with a 24 MB heap`() {
        twoStores()
        val size = 300L * 1024 * 1024
        val src = File(disk, "cache/huge.mkv").apply { parentFile.mkdirs() }
        // Sparse, so the test costs no disk; the copy still reads and writes every byte.
        RandomAccessFile(src, "rw").use { it.setLength(size); it.seek(size - 1); it.write(7) }
        val downloads = File(shm, "downloads")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Xmx24m", "-cp", System.getProperty("java.class.path"),
            "com.tmplayer.data.SmallHeapMove", src.path, downloads.path, "Huge.mkv",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("copy did not finish in time", process.waitFor(120, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        val moved = File(downloads, "Huge.mkv")
        assertEquals(size, moved.length())
        assertFalse(src.exists())
        assertFalse(File(downloads, "Huge.mkv.part").exists())
    }
}

/** The child process of the small heap test: one move, exit code 0 when it came out whole. */
object SmallHeapMove {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking { DownloadFiles.moveIntoDownloads(File(args[0]), File(args[1]), args[2]) }
    }
}
