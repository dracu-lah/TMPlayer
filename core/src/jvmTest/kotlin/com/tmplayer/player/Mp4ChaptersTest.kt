package com.tmplayer.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Chapter marks read out of hand-built MP4 files, laid out the way ffmpeg and mp4v2 write them. */
class Mp4ChaptersTest {

    // ---- a tiny MP4 writer -------------------------------------------------------------------

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    }

    private fun u32(vararg values: Long): ByteArray {
        val b = ByteBuffer.allocate(4 * values.size)
        values.forEach { b.putInt(it.toInt()) }
        return b.array()
    }

    private fun u64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()

    private fun full(version: Int = 0) = byteArrayOf(version.toByte(), 0, 0, 0)

    private val ftyp = box("ftyp", "isom".toByteArray(), u32(0x200), "isomiso2mp41".toByteArray())

    private fun tkhd(id: Long) = box("tkhd", full(), u32(0, 0, id, 0, 0), ByteArray(60))

    private fun hdlr(kind: String) = box("hdlr", full(), u32(0), kind.toByteArray(), ByteArray(12), byteArrayOf(0))

    private fun mdhd(timescale: Long) = box("mdhd", full(), u32(0, 0, timescale, 0), ByteArray(4))

    private fun videoTrak(chapterTrack: Long?) = box(
        "trak",
        tkhd(1),
        if (chapterTrack != null) box("tref", box("chap", u32(chapterTrack))) else ByteArray(0),
        box("mdia", mdhd(90_000), hdlr("vide"), box("minf", box("stbl", box("stsz", full(), u32(0, 0))))),
    )

    private fun textSample(title: String): ByteArray {
        val text = title.toByteArray()
        return ByteBuffer.allocate(2 + text.size + 12).putShort(text.size.toShort()).put(text)
            // An "encd" box after the text, as Apple's tools write: not part of the title.
            .putInt(12).put("encd".toByteArray()).putInt(0x100).array()
    }

    /** A text track of [titles] starting at [startsMs] (timescale 1000), its samples in one chunk at [chunkAt]. */
    private fun textTrak(id: Long, startsMs: List<Long>, samples: List<ByteArray>, chunkAt: Long, wide: Boolean = false): ByteArray {
        val durations = startsMs.zipWithNext { a, b -> b - a } + 1_000L
        val stts = box("stts", full(), u32(durations.size.toLong()), *durations.map { u32(1, it) }.toTypedArray())
        val stsz = box("stsz", full(), u32(0, samples.size.toLong()), *samples.map { u32(it.size.toLong()) }.toTypedArray())
        val stsc = box("stsc", full(), u32(1), u32(1, samples.size.toLong(), 1))
        val stco = if (wide) box("co64", full(), u32(1), u64(chunkAt)) else box("stco", full(), u32(1, chunkAt))
        return box(
            "trak",
            tkhd(id),
            box("mdia", mdhd(1000), hdlr("text"), box("minf", box("stbl", box("stsd", full(), u32(0)), stts, stsz, stsc, stco))),
        )
    }

    private fun chpl(vararg chapters: Pair<Long, String>, version: Int = 1): ByteArray {
        val entries = chapters.map { (ms, title) ->
            val t = title.toByteArray()
            u64(ms * 10_000) + byteArrayOf(t.size.toByte()) + t
        }
        return box(
            "chpl",
            full(version),
            if (version >= 1) ByteArray(4) else ByteArray(0),
            byteArrayOf(chapters.size.toByte()),
            *entries.toTypedArray(),
        )
    }

    private val mvhd = box("mvhd", full(), ByteArray(96))

    /**
     * A faststart file: ftyp, moov, then mdat holding the chapter titles. The chunk offset depends
     * on the size of moov, which does not depend on the offset's value, so it is built twice.
     */
    private fun faststart(
        titles: List<String>,
        startsMs: List<Long>,
        nero: ByteArray? = null,
        wide: Boolean = false,
        referenced: Boolean = true,
    ): ByteArray {
        val samples = titles.map(::textSample)
        fun moov(chunkAt: Long) = box(
            "moov",
            mvhd,
            videoTrak(if (referenced) 2 else null),
            textTrak(2, startsMs, samples, chunkAt, wide),
            if (nero != null) box("udta", nero) else ByteArray(0),
        )
        val chunkAt = (ftyp.size + moov(0).size + 8).toLong()
        val mdat = box("mdat", *samples.toTypedArray(), ByteArray(4096))
        return ftyp + moov(chunkAt) + mdat
    }

    private fun reader(file: ByteArray, onDisk: Long = file.size.toLong()): suspend (Long, Int) -> ByteArray? = { offset, length ->
        if (offset >= file.size) {
            null
        } else {
            val end = minOf(file.size.toLong(), offset + length)
            if (end > onDisk) null else file.copyOfRange(offset.toInt(), end.toInt())
        }
    }

    private fun read(file: ByteArray, onDisk: Long = file.size.toLong()) = runBlocking { Mp4Chapters.read(reader(file, onDisk)) }

    // ---- the tests ---------------------------------------------------------------------------

    @Test
    fun `a QuickTime chapter track gives each sample's start and title`() {
        val file = faststart(listOf("Opening", "The Station", "Night Train"), listOf(0, 95_000, 1_180_000))
        assertEquals(
            listOf(Chapter(0, "Opening"), Chapter(95_000, "The Station"), Chapter(1_180_000, "Night Train")),
            read(file),
        )
    }

    @Test
    fun `sixty four bit chunk offsets read the same`() {
        val file = faststart(listOf("One", "Two"), listOf(0, 60_000), wide = true)
        assertEquals(listOf(Chapter(0, "One"), Chapter(60_000, "Two")), read(file))
    }

    @Test
    fun `a Nero chpl list is read when there is no chapter track`() {
        val nero = chpl(0L to "Intro", 312_500L to "Part two", 900_000L to "Credits")
        val file = faststart(emptyList(), emptyList(), nero = nero, referenced = false)
        assertEquals(listOf(Chapter(0, "Intro"), Chapter(312_500, "Part two"), Chapter(900_000, "Credits")), read(file))
    }

    @Test
    fun `version zero chpl has no reserved word`() {
        val payload = chpl(0L to "A", 5_000L to "B", version = 0).copyOfRange(8, chpl(0L to "A", 5_000L to "B", version = 0).size)
        assertEquals(listOf(Chapter(0, "A"), Chapter(5_000, "B")), Mp4Chapters.parseChpl(payload))
    }

    @Test
    fun `the chapter track wins over chpl, as in ffmpeg`() {
        val nero = chpl(0L to "Nero one", 10_000L to "Nero two")
        val file = faststart(listOf("Track one", "Track two"), listOf(0, 20_000), nero = nero)
        assertEquals(listOf(Chapter(0, "Track one"), Chapter(20_000, "Track two")), read(file))
    }

    @Test
    fun `a text track nothing points at is a subtitle, not chapters`() {
        val file = faststart(listOf("Hello"), listOf(0), referenced = false)
        assertTrue(read(file).isEmpty())
    }

    @Test
    fun `moov after the media, not downloaded yet, finds nothing`() {
        val chapters = faststart(listOf("A", "B"), listOf(0, 1_000))
        // ftyp, a big mdat, then a moov with nothing usable: only the head is on the disk.
        val mdat = box("mdat", ByteArray(200_000))
        val tail = box("moov", mvhd)
        val file = ftyp + mdat + tail
        assertTrue(read(file, onDisk = 100_000).isEmpty())
        // A faststart file with only its head and the first of the media there still reads: moov is first.
        assertEquals(listOf(Chapter(0, "A"), Chapter(1_000, "B")), read(chapters, onDisk = chapters.size - 4_000L))
    }

    @Test
    fun `moov at the end is read once the file is all there`() {
        val nero = chpl(0L to "Start", 42_000L to "Middle")
        val mdat = box("mdat", ByteArray(50_000))
        val file = ftyp + mdat + box("moov", mvhd, box("udta", nero))
        assertEquals(listOf(Chapter(0, "Start"), Chapter(42_000, "Middle")), read(file))
    }

    @Test
    fun `a file that is not MP4 is left alone`() {
        assertTrue(read(ByteArray(4096) { 7 }).isEmpty())
        assertTrue(read(ByteArray(0)).isEmpty())
    }

    @Test
    fun `text samples in UTF-8 and UTF-16 with a byte order mark`() {
        assertEquals("Café", Mp4Chapters.sampleText(textSample("Café")))
        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Kapitel".toByteArray(Charsets.UTF_16BE)
        val sample = ByteBuffer.allocate(2 + utf16.size).putShort(utf16.size.toShort()).put(utf16).array()
        assertEquals("Kapitel", Mp4Chapters.sampleText(sample))
        assertEquals(null, Mp4Chapters.sampleText(byteArrayOf(0, 0)))
    }

    @Test
    fun `samples spread over chunks find their own bytes`() {
        val stsc = box("stsc", full(), u32(2), u32(1, 2, 1), u32(2, 1, 1)).copyOfRange(8, 8 + 4 + 4 + 24)
        val offsets = Mp4Chapters.sampleOffsets(stsc, chunks = listOf(1_000, 5_000), sizes = listOf(10, 20, 30))
        assertEquals(listOf(1_000L, 1_010L, 5_000L), offsets)
    }

    @Test
    fun `the file's first bytes pick the reader`() {
        val file = faststart(listOf("Only"), listOf(0))
        assertEquals(listOf(Chapter(0, "Only")), runBlocking { FileChapters.read(null, reader(file)) })
        assertTrue(runBlocking { FileChapters.read(null, reader(ByteArray(64))) }.isEmpty())
    }

    @Test
    fun `an unsorted chpl list with a repeated time is sorted and kept once`() {
        val nero = chpl(900_000L to "Credits", 0L to "Intro", 900_000L to "Again", 312_500L to "Part two")
        val file = faststart(emptyList(), emptyList(), nero = nero, referenced = false)
        assertEquals(listOf(0L, 312_500L, 900_000L), read(file).map { it.startMs })
    }
}
