package com.tmplayer.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Chapter marks read out of hand-built Matroska files, the way mkvmerge lays them out. */
class MkvChaptersTest {

    // ---- a tiny EBML writer ------------------------------------------------------------------

    private fun id(value: Long): ByteArray {
        val length = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFF -> 3
            else -> 4
        }
        return ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
    }

    /** Sizes as eight byte vints, which mkvmerge writes for the big elements. */
    private fun size(value: Long): ByteArray {
        val out = ByteArray(8) { i -> (value shr (8 * (7 - i))).toByte() }
        out[0] = 0x01
        return out
    }

    private fun el(id: Long, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { a, b -> a + b }
        return id(id) + size(payload.size.toLong()) + payload
    }

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value shr (8 * (7 - i))).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
        return el(id, if (bytes.isEmpty()) byteArrayOf(0) else bytes)
    }

    private fun str(id: Long, value: String) = el(id, value.toByteArray())

    private fun atom(startMs: Long, name: String?, lang: String = "eng", hidden: Boolean = false, extra: ByteArray = ByteArray(0)) = el(
        0xB6,
        uint(0x91, startMs * 1_000_000),
        if (hidden) uint(0x98, 1) else ByteArray(0),
        if (name != null) el(0x80, str(0x85, name), str(0x437C, lang)) else ByteArray(0),
        extra,
    )

    private fun edition(vararg atoms: ByteArray, default: Boolean = false) =
        el(0x45B9, if (default) uint(0x45DB, 1) else ByteArray(0), *atoms)

    private fun chapters(vararg editions: ByteArray) = el(0x1043A770, *editions)

    private val ebmlHeader = el(0x1A45DFA3, str(0x4282, "matroska"))
    private val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000))
    private val tracks = el(0x1654AE6B, el(0xAE, uint(0xD7, 1)))
    private val cluster = el(0x1F43B675, uint(0xE7, 0), ByteArray(64))

    private fun void(bytes: Int) = el(0xEC, ByteArray(bytes))

    private fun seekHead(chaptersAt: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)), el(0x53AC, ByteArray(8) { i -> (chaptersAt shr (8 * (7 - i))).toByte() })))

    /** EBML header, then a Segment of unknown size holding [children]. */
    private fun file(vararg children: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ebmlHeader)
        out.write(id(0x18538067))
        out.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1))
        children.forEach(out::write)
        return out.toByteArray()
    }

    private val three = chapters(edition(atom(0, "Opening"), atom(95_000, "Part A"), atom(1_310_000, "Ending")))

    // ---- tests -------------------------------------------------------------------------------

    @Test
    fun `chapters in the head are read with their names, in time order`() {
        val bytes = file(info, tracks, chapters(edition(atom(95_000, "Part A"), atom(0, "Opening"), atom(1_310_000, "Ending"))), cluster)
        val head = MkvChapters.readHead(bytes)
        assertEquals(
            MkvChapters.Head.Found(listOf(Chapter(0, "Opening"), Chapter(95_000, "Part A"), Chapter(1_310_000, "Ending"))),
            head,
        )
    }

    @Test
    fun `hidden and linked chapters are left out`() {
        val linked = el(0x6E67, ByteArray(16))
        val bytes = file(info, chapters(edition(atom(0, "Opening"), atom(60_000, "Secret", hidden = true), atom(90_000, "Elsewhere", extra = linked))), cluster)
        assertEquals(MkvChapters.Head.Found(listOf(Chapter(0, "Opening"))), MkvChapters.readHead(bytes))
    }

    @Test
    fun `the default edition wins over the first`() {
        val first = edition(atom(0, "Director"))
        val second = edition(atom(0, "Cinema"), atom(30_000, "Two"), default = true)
        val bytes = file(info, chapters(first, second), cluster)
        assertEquals(MkvChapters.Head.Found(listOf(Chapter(0, "Cinema"), Chapter(30_000, "Two"))), MkvChapters.readHead(bytes))
    }

    @Test
    fun `the name in the wanted language is preferred`() {
        val display = { name: String, lang: String -> el(0x80, str(0x85, name), str(0x437C, lang)) }
        val atom = el(0xB6, uint(0x91, 5_000_000_000), display("Opening", "eng"), display("Ouverture", "fre"))
        val bytes = file(chapters(el(0x45B9, atom)), cluster)
        assertEquals(MkvChapters.Head.Found(listOf(Chapter(5_000, "Ouverture"))), MkvChapters.readHead(bytes, language = "fr"))
        assertEquals(MkvChapters.Head.Found(listOf(Chapter(5_000, "Opening"))), MkvChapters.readHead(bytes, language = "de"))
    }

    @Test
    fun `chapters past the head are found through the seek head and read from there`() = runBlocking {
        // Attachments (fonts) of half a megabyte between the tracks and the chapters.
        val padding = void(600_000)
        val seekSize = seekHead(0).size
        val chaptersAt = (seekSize + info.size + tracks.size + padding.size).toLong()
        val bytes = file(seekHead(chaptersAt), info, tracks, padding, three, cluster)
        val segmentStart = ebmlHeader.size + 4 + 8
        val head = MkvChapters.readHead(bytes.copyOf(MkvChapters.HEAD_BYTES))
        assertEquals(MkvChapters.Head.At(segmentStart + chaptersAt), head)

        val read = MkvChapters.read { offset, length -> bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length)) }
        assertEquals(listOf(0L, 95_000L, 1_310_000L), read.map { it.startMs })
    }

    @Test
    fun `chapters not on the disk yet read as none`() = runBlocking {
        val padding = void(600_000)
        val chaptersAt = (seekHead(0).size + info.size + padding.size).toLong()
        val bytes = file(seekHead(chaptersAt), info, padding, three, cluster)
        val read = MkvChapters.read { offset, length ->
            if (offset > 0) null else bytes.copyOfRange(0, minOf(bytes.size, length))
        }
        assertTrue(read.isEmpty())
    }

    @Test
    fun `a file whose media starts before any chapters has none`() {
        assertEquals(MkvChapters.Head.None, MkvChapters.readHead(file(info, tracks, cluster, three)))
    }

    @Test
    fun `a chapters element cut off by the head is asked for in full`() {
        val bytes = file(info, three, cluster)
        val cut = bytes.copyOf(ebmlHeader.size + 12 + info.size + 20)
        assertEquals(MkvChapters.Head.At((ebmlHeader.size + 12 + info.size).toLong()), MkvChapters.readHead(cut))
    }

    @Test
    fun `an mp4 is not read`() {
        val mp4 = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(64)
        assertEquals(MkvChapters.Head.NotMatroska, MkvChapters.readHead(mp4))
        assertEquals(MkvChapters.Head.NotMatroska, MkvChapters.readHead(ByteArray(0)))
    }

    @Test
    fun `the bar marks every start but the very first`() {
        val list = listOf(Chapter(0, "Opening"), Chapter(95_000, "Part A"), Chapter(1_310_000, "Ending"))
        assertEquals(listOf(95_000L, 1_310_000L), Chapters.marks(list, 1_400_000))
        assertEquals(listOf(95_000L), Chapters.marks(list, 1_000_000))
        assertEquals("Chapter 2 of 3: Part A", Chapters.labelAt(list, 100_000))
        assertEquals(null, Chapters.labelAt(listOf(Chapter(10_000, null)), 0))
    }

    @Test
    fun `two chapters at one time keep one, and an unsorted list is sorted`() {
        val bytes = file(info, chapters(edition(atom(90_000, "Late"), atom(0, "Early"), atom(90_000, "Same time"))), cluster)
        val found = (MkvChapters.readHead(bytes) as MkvChapters.Head.Found).chapters
        assertEquals(listOf(0L, 90_000L), found.map { it.startMs })
    }
}
