package com.tmplayer.desktop.player

import com.tmplayer.data.ZipArchive
import com.tmplayer.data.ZipSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A stored video inside a ZIP cut into pieces: found from the tail, then read as the one file it is. */
class ZipWindowBytesTest {

    private lateinit var dir: File
    private val video = Random(11).nextBytes(180_000)
    private lateinit var archive: ByteArray
    private lateinit var pieces: List<File>

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zipwindow").toFile()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("Movie.2160p.mkv").apply {
                method = ZipEntry.STORED
                size = video.size.toLong()
                compressedSize = video.size.toLong()
                crc = CRC32().apply { update(video) }.value
            }
            zip.putNextEntry(entry)
            zip.write(video)
            zip.closeEntry()
        }
        archive = out.toByteArray()
        val cut = archive.size / 3
        pieces = listOf(0 until cut, cut until 2 * cut, 2 * cut until archive.size).mapIndexed { i, range ->
            File(dir, "Movie.zip.%03d".format(i + 1)).apply { writeBytes(archive.copyOfRange(range.first, range.last + 1)) }
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun joined() = JoinedStreamBytes(
        sizes = pieces.map { it.length() },
        open = { index, _ -> LocalFileBytes(pieces[index]) },
    )

    private fun source(bytes: StreamBytes) = object : ZipSource {
        override val size: Long = bytes.size
        override suspend fun read(offset: Long, length: Int): ByteArray? = bytes.readRange(offset, length)
    }

    @Test
    fun `the zip is read from the last piece and its video comes back through the window`() = runBlocking {
        val bytes = joined()
        val archiveSource = source(bytes)
        val entry = ZipArchive.bestVideo(ZipArchive.entries(archiveSource)!!)!!
        val start = ZipArchive.dataOffset(archiveSource, entry)!!
        val window = WindowedBytes(bytes, start, entry.uncompressedSize)
        assertEquals(video.size.toLong(), window.size)
        assertArrayEquals(video, window.readRange(0, video.size))
        // A read in the middle, across a seam, and the last bytes.
        assertArrayEquals(video.copyOfRange(50_000, 90_000), window.readRange(50_000, 40_000))
        assertArrayEquals(video.copyOfRange(video.size - 10, video.size), window.readRange(video.size - 10L, 100))
        // Nothing past the end.
        assertEquals(0, window.readRange(video.size.toLong(), 10)?.size)
        assertNull(window.cachedAvailable(video.size.toLong()))
    }

    @Test
    fun `a window outside the archive is refused`() {
        val bytes = joined()
        var refused = false
        try {
            WindowedBytes(bytes, bytes.size - 5, 100)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertEquals(true, refused)
    }

    @Test
    fun `a missing last piece is not an archive`() = runBlocking {
        val short = JoinedStreamBytes(
            sizes = pieces.dropLast(1).map { it.length() },
            open = { index, _ -> LocalFileBytes(pieces[index]) },
        )
        assertNull(ZipArchive.entries(source(short)))
    }
}
