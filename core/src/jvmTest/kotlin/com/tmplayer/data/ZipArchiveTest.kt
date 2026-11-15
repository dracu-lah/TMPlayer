package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry as JdkEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAX_ENTRIES_PLUS_ONE = ZipArchive.MAX_ENTRIES + 1

/** A video inside a ZIP: found from the tail, stored or not, split or not, and never trusted. */
class ZipArchiveTest {

    private class Bytes(val data: ByteArray, val fail: Boolean = false) : ZipSource {
        override val size get() = data.size.toLong()
        override suspend fun read(offset: Long, length: Int): ByteArray? {
            if (fail || offset < 0 || offset > data.size) return null
            val end = minOf(data.size.toLong(), offset + length).toInt()
            return data.copyOfRange(offset.toInt(), end)
        }
    }

    private fun payload(size: Int, seed: Int = 1) = Random(seed).nextBytes(size)

    private fun stored(name: String, content: ByteArray, out: ZipOutputStream) {
        val entry = JdkEntry(name).apply {
            method = JdkEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = CRC32().apply { update(content) }.value
        }
        out.putNextEntry(entry)
        out.write(content)
        out.closeEntry()
    }

    private fun zip(comment: String? = null, build: (ZipOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            comment?.let { out.setComment(it) }
            build(out)
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a stored video is found and its bytes are where the header says`() = runBlocking {
        val video = payload(300_000)
        val archive = zip { stored("Movie.2160p.mkv", video, it); stored("info.txt", payload(40, 2), it) }
        val source = Bytes(archive)
        val entries = assertNotNull(ZipArchive.entries(source))
        val best = assertNotNull(ZipArchive.bestVideo(entries))
        assertEquals("Movie.2160p.mkv", best.name)
        assertTrue(best.streamable)
        val start = assertNotNull(ZipArchive.dataOffset(source, best))
        assertTrue(video.contentEquals(archive.copyOfRange(start.toInt(), start.toInt() + video.size)))
    }

    @Test
    fun `a comment after the directory does not hide it`() = runBlocking {
        val archive = zip(comment = "x".repeat(5000)) { stored("a.mp4", payload(1000), it) }
        assertEquals(listOf("a.mp4"), ZipArchive.entries(Bytes(archive))?.map { it.name })
    }

    @Test
    fun `a deflated video is named as compressed and never streamed`() = runBlocking {
        val archive = zip { out ->
            out.putNextEntry(JdkEntry("Movie.mkv"))
            out.write(ByteArray(100_000))
            out.closeEntry()
        }
        val entries = assertNotNull(ZipArchive.entries(Bytes(archive)))
        assertNull(ZipArchive.bestVideo(entries))
        assertEquals(ZipBlocker.Compressed, ZipArchive.blockedVideo(entries)?.blocker)
        assertNull(ZipArchive.dataOffset(Bytes(archive), entries.first()))
    }

    @Test
    fun `the biggest stored video wins, a sample next to it does not`() = runBlocking {
        val archive = zip { stored("Sample/sample.mkv", payload(1_000), it); stored("Movie.mkv", payload(90_000, 3), it) }
        assertEquals("Movie.mkv", ZipArchive.bestVideo(ZipArchive.entries(Bytes(archive))!!)?.name)
    }

    @Test
    fun `pieces joined end to end are one archive`() = runBlocking {
        val video = payload(250_000)
        val archive = zip { stored("v.mkv", video, it) }
        val cut = archive.size / 3
        val pieces = listOf(archive.copyOfRange(0, cut), archive.copyOfRange(cut, 2 * cut), archive.copyOfRange(2 * cut, archive.size))
        val joined = Bytes(pieces.reduce { a, b -> a + b })
        val entry = ZipArchive.entries(joined)!!.single()
        val start = ZipArchive.dataOffset(joined, entry)!!
        assertTrue(video.contentEquals(joined.data.copyOfRange(start.toInt(), start.toInt() + video.size)))
    }

    @Test
    fun `a missing last piece is not a zip`() = runBlocking {
        val archive = zip { stored("v.mkv", payload(250_000), it) }
        assertNull(ZipArchive.entries(Bytes(archive.copyOfRange(0, archive.size - 400))))
    }

    @Test
    fun `a source that cannot be read answers null`() = runBlocking {
        assertNull(ZipArchive.entries(Bytes(zip { stored("v.mkv", payload(5000), it) }, fail = true)))
    }

    @Test
    fun `disks map into the joined stream`() = runBlocking {
        // A real spanned archive counts a disk and an offset in it: disk 1 starts where disk 0 ends.
        val video = payload(60_000)
        val archive = zip { stored("v.mkv", video, it) }
        val cut = 30_000
        // Rewrite nothing: pretend the whole archive is disk 0 and a second, empty disk follows.
        val entries = ZipArchive.entries(Bytes(archive), diskStarts = listOf(0L, cut.toLong()))
        assertEquals(1, entries?.size)
    }

    @Test
    fun `a disk that does not exist drops the entry`() = runBlocking {
        val archive = zip { stored("v.mkv", payload(5000), it) }
        // Point the one entry at disk 3 in its central directory record (offset 34 in the record).
        val eocd = archive.size - 22
        val directoryAt = readInt(archive, eocd + 16)
        archive[directoryAt + 34] = 3
        assertEquals(emptyList(), ZipArchive.entries(Bytes(archive), diskStarts = listOf(0L, 100L)))
    }

    @Test
    fun `zip64 sizes and offsets are read from the extra field`() = runBlocking {
        val video = payload(10_000)
        val archive = zip64Archive("Big.mkv", video)
        val source = Bytes(archive)
        val entry = ZipArchive.entries(source)!!.single()
        assertEquals("Big.mkv", entry.name)
        assertEquals(video.size.toLong(), entry.uncompressedSize)
        val start = ZipArchive.dataOffset(source, entry)!!
        assertTrue(video.contentEquals(archive.copyOfRange(start.toInt(), start.toInt() + video.size)))
    }

    @Test
    fun `not a zip, and a zip that only looks like one`() = runBlocking {
        assertNull(ZipArchive.entries(Bytes(ByteArray(0))))
        assertNull(ZipArchive.entries(Bytes(payload(100_000))))
        assertFalse(ZipArchive.looksLikeZip(byteArrayOf(0x50, 0x4b)))
        assertTrue(ZipArchive.looksLikeZip(zip { stored("a.mkv", payload(10), it) }.copyOf(8)))
        // An end record whose comment length runs past the file is bytes by chance, not a directory.
        val liar = zip { stored("a.mkv", payload(100), it) }
        liar[liar.size - 22 + 20] = 0x7F
        assertNull(ZipArchive.entries(Bytes(liar)))
    }

    private fun patch16(archive: ByteArray, at: Int, value: Int) {
        archive[at] = (value and 0xFF).toByte()
        archive[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun patch32(archive: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) archive[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    @Test
    fun `every verdict has the sentence the player shows, and only an unreadable one is worth a retry`() {
        val l = com.tmplayer.i18n.L
        assertNull(ZipVerdict(ZipVerdict.Kind.Playable).reason)
        assertEquals(l.playerZipCompressed, ZipVerdict(ZipVerdict.Kind.Compressed).reason)
        assertEquals(l.playerZipEncrypted, ZipVerdict(ZipVerdict.Kind.Encrypted).reason)
        assertEquals(l.playerZipTooLarge, ZipVerdict(ZipVerdict.Kind.TooLarge).reason)
        assertEquals(l.playerZipNoVideo, ZipVerdict(ZipVerdict.Kind.NoVideo).reason)
        assertEquals(l.playerZipUnreadable, ZipVerdict(ZipVerdict.Kind.Unreadable).reason)
        for (kind in ZipVerdict.Kind.entries) assertEquals(kind == ZipVerdict.Kind.Unreadable, ZipVerdict(kind).retryable)
        assertEquals(ZipVerdict.Kind.entries.size - 1, ZipVerdict.Kind.entries.mapNotNull { ZipVerdict(it).reason }.toSet().size)
    }

    @Test
    fun `inspect says playable for a stored video`() = runBlocking {
        val archive = zip { stored("Movie.mkv", payload(50_000), it) }
        val verdict = ZipArchive.inspect(Bytes(archive))
        assertEquals(ZipVerdict.Kind.Playable, verdict.kind)
        assertEquals("Movie.mkv", verdict.entry?.name)
    }

    @Test
    fun `inspect names a compressed video and a locked one`() = runBlocking {
        val deflated = zip { out ->
            out.putNextEntry(JdkEntry("Movie.mkv"))
            out.write(ByteArray(100_000))
            out.closeEntry()
        }
        assertEquals(ZipVerdict.Kind.Compressed, ZipArchive.inspect(Bytes(deflated)).kind)

        val locked = zip { stored("Movie.mkv", payload(5_000), it) }
        // General purpose flag bit 0 of the one central directory record is "encrypted".
        val directoryAt = readInt(locked, locked.size - 22 + 16)
        locked[directoryAt + 8] = (locked[directoryAt + 8].toInt() or 1).toByte()
        val verdict = ZipArchive.inspect(Bytes(locked))
        assertEquals(ZipVerdict.Kind.Encrypted, verdict.kind)
        assertNull(ZipArchive.dataOffset(Bytes(locked), verdict.entry!!))
    }

    @Test
    fun `inspect names a zip without a video and one that is cut short`() = runBlocking {
        assertEquals(ZipVerdict.Kind.NoVideo, ZipArchive.inspect(Bytes(zip { stored("readme.txt", payload(100), it) })).kind)
        val archive = zip { stored("v.mkv", payload(250_000), it) }
        assertEquals(ZipVerdict.Kind.Unreadable, ZipArchive.inspect(Bytes(archive.copyOfRange(0, archive.size - 400))).kind)
        assertEquals(ZipVerdict.Kind.Unreadable, ZipArchive.inspect(Bytes(archive, fail = true)).kind)
        assertEquals(ZipVerdict.Kind.Unreadable, ZipArchive.inspect(Bytes(ByteArray(0))).kind)
    }

    @Test
    fun `a huge directory is named and never read`() = runBlocking {
        val reads = ArrayList<Pair<Long, Int>>()
        val many = zip { stored("v.mkv", payload(1_000), it) }
        patch16(many, many.size - 22 + 10, MAX_ENTRIES_PLUS_ONE)
        val spy = object : ZipSource {
            val inner = Bytes(many)
            override val size get() = inner.size
            override suspend fun read(offset: Long, length: Int): ByteArray? = inner.read(offset, length).also { reads += offset to length }
        }
        assertEquals(ZipVerdict.Kind.TooLarge, ZipArchive.inspect(spy).kind)
        // Only the tail was read: the directory itself was never asked for.
        assertTrue(reads.all { (_, length) -> length <= 22 + 65_535 })
        assertEquals(1, reads.size)

        val wide = zip { stored("v.mkv", payload(1_000), it) }
        patch32(wide, wide.size - 22 + 12, ZipArchive.MAX_DIRECTORY + 1)
        assertEquals(ZipVerdict.Kind.TooLarge, ZipArchive.inspect(Bytes(wide)).kind)
        // The same count that is only just allowed is not called huge.
        val allowed = zip { stored("v.mkv", payload(1_000), it) }
        patch16(allowed, allowed.size - 22 + 10, ZipArchive.MAX_ENTRIES)
        assertTrue(ZipArchive.inspect(Bytes(allowed)).kind != ZipVerdict.Kind.TooLarge)
    }

    @Test
    fun `a zip64 archive is inspected like any other`() = runBlocking {
        val verdict = ZipArchive.inspect(Bytes(zip64Archive("Big.mkv", payload(10_000))))
        assertEquals(ZipVerdict.Kind.Playable, verdict.kind)
        assertEquals("Big.mkv", verdict.entry?.name)
    }

    @Test
    fun `garbage in every shape never throws`() = runBlocking {
        val base = zip { stored("a.mkv", payload(5000), it) }
        val random = Random(9)
        repeat(400) { round ->
            val copy = base.copyOf()
            repeat(1 + round % 8) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            val cut = if (round % 3 == 0) copy.copyOf(random.nextInt(copy.size)) else copy
            ZipArchive.entries(Bytes(cut))?.forEach { ZipArchive.dataOffset(Bytes(cut), it) }
            ZipArchive.entries(Bytes(cut), diskStarts = listOf(0L, random.nextLong(0, 9_000)))
        }
    }

    private fun readInt(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** A one-entry archive written by hand the way a ZIP64 writer does: the sizes and offset in the extra field. */
    private fun zip64Archive(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(value: Long, bytes: Int) = repeat(bytes) { out.write(((value shr (8 * it)) and 0xFF).toInt()) }
        val nameBytes = name.toByteArray()
        val crc = CRC32().apply { update(content) }.value
        // Local header
        le(0x04034b50, 4); le(45, 2); le(0, 2); le(0, 2); le(0, 2); le(0, 2)
        le(crc, 4); le(content.size.toLong(), 4); le(content.size.toLong(), 4); le(nameBytes.size.toLong(), 2); le(0, 2)
        out.write(nameBytes); out.write(content)
        val directoryAt = out.size().toLong()
        // Central directory record: sizes and offset all ones, the true ones in the extra field.
        le(0x02014b50, 4); le(45, 2); le(45, 2); le(0, 2); le(0, 2); le(0, 2); le(0, 2)
        le(crc, 4); le(0xFFFFFFFF, 4); le(0xFFFFFFFF, 4); le(nameBytes.size.toLong(), 2); le(28, 2); le(0, 2)
        le(0, 2); le(0, 2); le(0, 4); le(0xFFFFFFFF, 4)
        out.write(nameBytes)
        le(1, 2); le(24, 2); le(content.size.toLong(), 8); le(content.size.toLong(), 8); le(0, 8)
        val directorySize = out.size().toLong() - directoryAt
        val record64At = out.size().toLong()
        le(0x06064b50, 4); le(44, 8); le(45, 2); le(45, 2); le(0, 4); le(0, 4); le(1, 8); le(1, 8); le(directorySize, 8); le(directoryAt, 8)
        le(0x07064b50, 4); le(0, 4); le(record64At, 8); le(1, 4)
        le(0x06054b50, 4); le(0, 2); le(0, 2); le(0xFFFF, 2); le(0xFFFF, 2); le(0xFFFFFFFF, 4); le(0xFFFFFFFF, 4); le(0, 2)
        return out.toByteArray()
    }
}
