package com.tmplayer.data

import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.player.PieceMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Videos cut into raw byte pieces: which names are pieces, how a chat's pieces join, what is missing. */
class SplitPiecesTest {

    private var nextId = 1L

    @After
    fun forget() = VideoCorrections.set(emptyMap())

    private fun file(
        fileName: String,
        size: Long = PIECE,
        chatId: Long = 7,
        mime: String = "video/x-matroska",
        date: Int = nextId.toInt(),
    ) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = nextId.toInt() + 500,
        title = fileName,
        sizeBytes = size,
        durationSec = 0,
        mimeType = mime,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    /** The real chat's case: one 4K MKV cut into seven pieces of 3.9 GB, the last smaller. */
    private fun spiderPieces(numbers: List<Int> = (1..7).toList()) = numbers.map { n ->
        file("$SPIDER.part%03d".format(n), size = if (n == 7) 3_100 * MB else PIECE)
    }

    @Test
    fun `the exact name from the chat is a piece`() {
        val piece = SplitPieces.pieceOf("$SPIDER.part007")
        assertNotNull(piece)
        assertEquals(7, piece!!.number)
        assertEquals(SPIDER, piece.base)
        assertTrue(piece.certain)
        assertEquals(SplitPieces.pieceOf("$SPIDER.part001")!!.key, piece.key)
    }

    @Test
    fun `splitter names are pieces`() {
        mapOf(
            "Night.Train.2019.1080p.mkv.001" to 1,
            "Night.Train.2019.1080p.mkv.012" to 12,
            "Night Train 2019.mp4.00" to 0,
            "Night.Train.2019.mkv.part1" to 1,
            "Night.Train.2019.2160p.part7" to 7,
            "Night_Train_2019_part_003" to 3,
            "Night.Train.2019.1080p.001" to 1,
            "Night.Train.2019.part002.mkv" to 2,
        ).forEach { (name, number) ->
            assertEquals(name, number, SplitPieces.pieceOf(name)?.number)
        }
    }

    @Test
    fun `names that are not pieces`() {
        listOf(
            "Night.Train.2019.1080p.mkv",
            "Night Train Part 2 (2021) 720p.mkv",
            "Night_Train_Part_2",
            "Night.Train.1080",
            "Movie.720",
            "Night.Train.2019.CD1.avi",
            "Night.Train.Part1.mkv",
            "Lecture 07.mp4",
            "Night.Train.2019.7z.001",
            "Night.Train.2019.rar.part001",
        ).forEach { assertNull(it, SplitPieces.pieceOf(it)) }
        // A zip's pieces are the one archive kind that is a video's: its stored video plays out of it.
        assertEquals(2, SplitPieces.pieceOf("Night.Train.2019.zip.002")?.number)
    }

    @Test
    fun `a release kept in a zip is listed, a backup or a module is not`() {
        val gb4 = 4_194_304_000L
        // The names from a real chat: pieces of one zipped release, the name cut short by Telegram.
        listOf(
            "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.001",
            "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.003",
            "Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip.018",
        ).forEach { assertTrue(it, MediaText.looksLikeVideoArchive(it, if (it.endsWith("003") || it.endsWith("018")) 1_233_811_809L else gb4)) }
        // The last piece is the remainder, however small.
        assertTrue(MediaText.looksLikeVideoArchive("Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip.019", 5_000_000L))
        // One zip as large as a video, named like a release.
        assertTrue(MediaText.looksLikeVideoArchive("Night.Train.2019.1080p.BluRay.mkv.zip", 2_000 * MB))
        // What else is in a chat of files: nothing to play.
        listOf(
            "IvyWalletBackup_20260930-1830.zip" to 165_710L,
            "ReZygisk-v1.0.0-release.zip" to 506_334L,
            "Zenith-KSU-Miatoll-20260820-2142.zip" to 15_407_567L,
            "signed-ota_update.zip" to 2_152_708_427L,
            "Night.Train.2019.1080p.BluRay.zip" to 3_000_000L,
            "Film.mkv" to 2_000 * MB,
            "" to 0L,
        ).forEach { (name, size) -> assertFalse(name, MediaText.looksLikeVideoArchive(name, size)) }
    }

    @Test
    fun `eighteen zip pieces are one video, not eighteen`() {
        val gb4 = 4_194_304_000L
        val name = "Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip"
        val pieces = (1..18).map { n -> file("$name.%03d".format(n), size = if (n == 18) 3_769_561_462L else gb4, mime = "application/zip") }
        // Listed newest piece first, as a chat is.
        val listed = SplitPieces.join(pieces.reversed())
        assertEquals(1, listed.size)
        val joined = listed.single()
        assertEquals(18, joined.pieces.size)
        assertEquals((1..18).map { "$name.%03d".format(it) }, joined.pieces.map { it.fileName })
        assertEquals(17 * gb4 + 3_769_561_462L, joined.sizeBytes)
        assertEquals(0, SplitPieces.report(joined.pieces).missing.size)
    }

    @Test
    fun `a zip set with a piece missing reports it`() {
        val gb4 = 4_194_304_000L
        val name = "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip"
        val pieces = listOf(1, 3).map { n -> file("$name.%03d".format(n), size = gb4, mime = "application/zip") }
        val joined = SplitPieces.join(pieces).single()
        assertEquals(listOf(2), SplitPieces.report(joined.pieces).missing)
    }

    @Test
    fun `archive volumes are never listed as videos`() {
        listOf("Film.7z.001", "Film.zip.001", "Film.part1.rar", "Film.r00", "Film.rar").forEach {
            assertTrue(it, SplitPieces.isArchiveVolume(it))
            assertFalse(it, MediaMapper.looksLikeVideo(it, "application/octet-stream"))
        }
        assertFalse(MediaMapper.looksLikeVideo("Film.7z.001", "video/x-matroska"))
    }

    @Test
    fun `pieces with no media extension are listed when the name is a release`() {
        assertTrue(MediaMapper.looksLikeVideo("$SPIDER.part001", "application/octet-stream"))
        assertTrue(MediaMapper.looksLikeVideo("Holiday.mkv.001", "application/octet-stream"))
        assertFalse(MediaMapper.looksLikeVideo("backup.part001", "application/octet-stream"))
    }

    @Test
    fun `a piece reads as the video it is part of`() {
        val parsed = MediaName.parse("$SPIDER.part007")
        assertEquals("Spider Man Brand New Day", parsed.title)
        assertEquals(2026, parsed.year)
        assertNull(parsed.part)
        assertNull(LessonSteps.lessonOf(file("$SPIDER.part002")))
        assertEquals("Night Train (2019)", MediaName.tileTitle("Night.Train.2019.1080p.mkv.003"))
    }

    @Test
    fun `seven pieces are one video, never seven versions`() {
        val pieces = spiderPieces().shuffled(kotlin.random.Random(4))
        val entries = SeriesShelf.arrange(pieces)
        val video = entries.filterIsInstance<ShelfEntry.File>().single().item
        assertTrue(video.versions.isEmpty())
        assertEquals((1..7).toList(), video.pieces.map { it.splitPiece!!.number })
        assertEquals(pieces.first { it.fileName.endsWith("001") }.id, video.id)
        assertEquals(6 * PIECE + 3_100 * MB, video.sizeBytes)
        assertEquals(1, SeriesShelf.foldFilmCopies(pieces).size)
        assertEquals(1, SameVideo.group(pieces).size)
        Translator.use(Languages.ENGLISH)
        assertEquals("7 parts", SplitPieces.countLabel(video))
        assertNull(SplitPieces.missingLine(video))
        assertTrue(EpisodeCopies.label(video).contains("7 parts"))
        assertEquals("Spider Man Brand New Day (2026)", SeriesShelf.tileTitle(video))
    }

    @Test
    fun `the banner does not count pieces as a course`() {
        val progress = CourseProgress.of(spiderPieces(), onList = { false }, point = { null })
        assertNull(progress)
    }

    @Test
    fun `a missing piece is reported and the video plays up to it`() {
        Translator.use(Languages.ENGLISH)
        val video = SplitPieces.join(spiderPieces(listOf(1, 2, 3, 5, 6, 7))).single()
        val report = SplitPieces.report(video)!!
        assertEquals(7, report.total)
        assertEquals(listOf(4), report.missing)
        assertEquals(3, report.playable)
        assertEquals(3, SplitPieces.playablePieces(video.pieces).size)
        assertEquals("Part 4 of 7 is not in this chat", SplitPieces.missingLine(video))
        assertEquals("Part 4 of 7 is not in this chat, so the video plays up to it.", SplitPieces.playerNotice(video.pieces))
    }

    @Test
    fun `without the first piece nothing plays`() {
        Translator.use(Languages.ENGLISH)
        val video = SplitPieces.join(spiderPieces((2..7).toList())).single()
        assertEquals(0, SplitPieces.report(video)!!.playable)
        assertEquals(listOf(1), SplitPieces.report(video)!!.missing)
        assertTrue(SplitPieces.missingLine(video)!!.startsWith("Part 1 of 7"))
    }

    @Test
    fun `a piece posted twice keeps its newest post`() {
        val pieces = spiderPieces(listOf(1, 2, 3))
        val again = file("$SPIDER.part002", date = 99)
        val video = SplitPieces.join(pieces + again).single()
        assertEquals(listOf(1, 2, 3), video.pieces.map { it.splitPiece!!.number })
        assertEquals(again.id, video.pieces[1].id)
        assertEquals(listOf(pieces[1].id), SplitPieces.duplicates(pieces + again).map { it.id })
    }

    @Test
    fun `a splitter that counts from zero`() {
        val pieces = (0..2).map { file("Holiday.2019.mkv.%02d".format(it), size = if (it == 2) 10 * MB else 50 * MB) }
        val video = SplitPieces.join(pieces).single()
        val report = SplitPieces.report(video)!!
        assertEquals(3, report.total)
        assertTrue(report.complete)
        assertEquals(pieces[0].id, video.id)
    }

    @Test
    fun `a chat with real copies at other qualities keeps them as copies of the joined video`() {
        val pieces = spiderPieces()
        val hd = file("Spider.Man.Brand.New.Day.2026.1080p.WEB-DL.x264.mkv", size = 2_400 * MB)
        val sd = file("Spider Man Brand New Day (2026) 720p HDRip.mkv", size = 1_100 * MB)
        val other = file("Night.Train.2019.1080p.mkv", size = 2_000 * MB)
        val entries = SeriesShelf.arrange(pieces + hd + sd + other)
        val files = entries.filterIsInstance<ShelfEntry.File>().map { it.item }
        assertEquals(2, files.size)
        val spider = files.single { it.fileName != other.fileName }
        val versions = spider.versions
        assertEquals(3, versions.size)
        val joined = versions.single { it.isJoined }
        assertEquals(7, joined.pieces.size)
        assertTrue(versions.any { it.id == hd.id })
        assertTrue(versions.any { it.id == sd.id })
    }

    @Test
    fun `joining a list that already holds the joined video changes nothing`() {
        val pieces = spiderPieces()
        val video = SplitPieces.join(pieces).single()
        assertEquals(listOf(video), SplitPieces.join(listOf(video) + pieces.drop(1)))
        assertEquals(1, SeriesShelf.arrange(listOf(video) + pieces).size)
    }

    @Test
    fun `pieces in another chat are another set`() {
        val here = spiderPieces(listOf(1, 2))
        val there = listOf(file("$SPIDER.part001", chatId = 8), file("$SPIDER.part002", chatId = 8))
        assertEquals(2, SplitPieces.join(here + there).size)
    }

    @Test
    fun `names that could be whole files join only when cut alike`() {
        val cut = listOf(file("Holiday.part001.mkv", size = 100 * MB), file("Holiday.part002.mkv", size = 40 * MB))
        assertEquals(1, SplitPieces.join(cut).size)
        val whole = listOf(file("Holiday.2019.720p.part001.mkv", size = 690 * MB), file("Holiday.2019.720p.part002.mkv", size = 700 * MB))
        assertEquals(2, SplitPieces.join(whole).size)
        // Left as files, they are the two parts of a film that play one after another.
        val film = SeriesShelf.arrange(whole).filterIsInstance<ShelfEntry.File>().single().item
        assertEquals(2, film.parts.size)
    }

    @Test
    fun `playable parts stay parts`() {
        val cd1 = file("Coast Run 2014 720p CD1.avi", size = 700 * MB)
        val cd2 = file("Coast Run 2014 720p CD2.avi", size = 700 * MB)
        val film = SeriesShelf.arrange(listOf(cd2, cd1)).filterIsInstance<ShelfEntry.File>().single().item
        assertEquals(2, film.parts.size)
        assertFalse(film.isJoined)
    }

    @Test
    fun `the joined video takes the newest piece's place in the listing`() {
        val older = file("Night.Train.2019.1080p.mkv", size = 2_000 * MB, date = 10)
        val pieces = (1..3).map { file("Holiday.2019.mkv.%03d".format(it), size = 50 * MB, date = 20 + it) }
        val newer = file("Coast.Run.2014.1080p.mkv", size = 2_000 * MB, date = 40)
        val listed = listOf(newer) + pieces.reversed() + older
        val joined = SplitPieces.join(listed)
        assertEquals(listOf(newer.id, pieces[0].id, older.id), joined.map { it.id })
    }

    @Test
    fun `pieces found by searching the chat`() = runBlocking {
        val pieces = spiderPieces()
        val unrelated = file("Night.Train.2019.1080p.mkv")
        val found = SplitPieces.lookUp(pieces[0]) { _, cursors ->
            MediaPage(pieces.reversed() + unrelated, cursors.copy(videoDone = true, documentDone = true, animationDone = true), endReached = true)
        }
        assertEquals(pieces.map { it.id }, found!!.map { it.id })
        assertNull(SplitPieces.lookUp(unrelated) { _, _ -> null })
    }

    // The Optimus Prime chat on the stick: two sets of ZIP pieces, listed newest first as a window
    // of the chat's newest messages gives them (Home read to Colony's piece 2 and stopped).

    private fun colony(numbers: List<Int> = (1..18).toList()) = numbers.map { n ->
        file("$COLONY.zip.%03d".format(n), size = if (n == 18) 3_769_561_462 else 4_194_304_000, mime = "application/zip", date = 1_794_572_403)
    }

    private fun avengers() = (1..3).map { n ->
        file("$AVENGERS.zip.%03d".format(n), size = if (n == 3) 1_233_811_809 else 4_194_304_000, mime = "application/zip", date = 1_794_580_475)
    }

    /** Pages of [all] as the chat's search answers them: newest first, [size] a page. */
    private fun pagesOf(all: List<MediaItem>, size: Int = 5): suspend (String, MediaCursors) -> MediaPage? {
        val newest = all.sortedByDescending { it.messageId }
        return { _, cursors ->
            val from = cursors.document.toInt()
            val page = newest.drop(from).take(size)
            val end = from + size >= newest.size
            MediaPage(page, cursors.copy(document = (from + size).toLong(), videoDone = true, animationDone = true, documentDone = end), endReached = end)
        }
    }

    @Test
    fun `a window that ends at Colony's second piece is an open set and the Avengers are whole`() {
        val colony = colony()
        val window = (avengers() + colony.drop(1)).sortedByDescending { it.messageId }
        val open = SplitPieces.openSets(window)
        assertEquals(1, open.size)
        assertTrue(open.single().fileName.startsWith("Colony"))
        // Joined as it is, the set has 17 pieces and says its first is missing.
        val joined = SplitPieces.join(window).filter { it.fileName.startsWith("Colony") }.single()
        assertEquals(17, joined.pieces.size)
        assertEquals(1, SplitPieces.report(joined)!!.firstMissing)
        assertEquals(18, SplitPieces.report(joined)!!.total)
    }

    @Test
    fun `a lone piece 2 of Colony finds all 18 pieces including the first over several pages`() = runBlocking {
        val colony = colony()
        val chat = avengers() + colony + (1..6).map { file("Holiday.$it.mp4") }
        val found = SplitPieces.lookUp(colony[1], fetch = pagesOf(chat))!!
        assertEquals(colony.map { it.id }, found.map { it.id })
        assertTrue(SplitPieces.report(found).complete)
        assertEquals(1, found.first().splitPiece!!.number)
    }

    @Test
    fun `pieces handed over without the first are completed from the chat`() = runBlocking {
        val colony = colony()
        val chat = avengers() + colony
        val found = SplitPieces.lookUp(colony[1], known = colony.drop(1), fetch = pagesOf(chat))!!
        assertEquals(18, found.size)
        assertNull(SplitPieces.report(found).firstMissing)
    }

    @Test
    fun `a whole set is returned as handed over with no search`() = runBlocking {
        val colony = colony()
        var asked = 0
        val found = SplitPieces.lookUp(colony[3], known = colony) { _, _ -> asked++; null }!!
        assertEquals(18, found.size)
        assertEquals(0, asked)
    }

    @Test
    fun `the plain listing finds a first piece the title search does not match`() = runBlocking {
        val colony = colony()
        // Telegram's name search answers the pieces whose name it matched: here not the first.
        val byName = pagesOf(colony.drop(1))
        val listing = pagesOf(colony)
        val fetch: suspend (String, MediaCursors) -> MediaPage? = { query, cursors -> if (query.isEmpty()) listing(query, cursors) else byName(query, cursors) }
        val deep = SplitPieces.lookUp(colony[1], known = colony.drop(1), fetch = fetch)!!
        assertEquals(18, deep.size)
        // Home searches by the title only, and leaves the rest to the player.
        val shallow = SplitPieces.lookUp(colony[1], known = colony.drop(1), deep = false, fetch = fetch)!!
        assertEquals(17, shallow.size)
        assertEquals(1, SplitPieces.report(shallow).firstMissing)
    }

    @Test
    fun `a chat without the first piece still says it is missing`() = runBlocking {
        val colony = colony(numbers = (2..18).toList())
        val found = SplitPieces.lookUp(colony[0], fetch = pagesOf(colony))!!
        assertEquals(17, found.size)
        assertEquals(1, SplitPieces.report(found).firstMissing)
    }

    @Test
    fun `missingFrom adds the pieces a window lacks and so the shelf folds each set into one video`() = runBlocking {
        val colony = colony()
        val avengers = avengers()
        val window = (avengers + colony.drop(1)).sortedByDescending { it.messageId }
        val extra = SplitPieces.missingFrom(window) { lead, known ->
            SplitPieces.lookUp(lead, known = known, deep = false, fetch = pagesOf(avengers + colony))
        }
        assertEquals(listOf(colony[0].id), extra.map { it.id })
        val entries = SeriesShelf.arrange(window + extra).filterIsInstance<ShelfEntry.File>().map { it.item }
        // One entry for each video, each carrying all its pieces: what Play hands the player.
        assertEquals(2, entries.size)
        val colonyEntry = entries.single { it.fileName.startsWith("Colony") }
        assertEquals(18, colonyEntry.pieces.size)
        assertNull(SplitPieces.report(colonyEntry)!!.firstMissing)
        assertEquals(3, entries.single { it.fileName.startsWith("Avengers") }.pieces.size)
        assertTrue(SplitPieces.openSets(window + extra).isEmpty())
    }

    @Test
    fun `missingFrom looks at the newest sets only and survives a failing search`() = runBlocking {
        val sets = (1..5).map { n -> (2..4).map { file("Film.$n.2020.1080p.mkv.%03d".format(it), size = PIECE, date = 100 + n) } }
        var looked = 0
        val extra = SplitPieces.missingFrom(sets.flatten(), limit = 2) { _, _ -> looked++; error("offline") }
        assertEquals(2, looked)
        assertTrue(extra.isEmpty())
    }

    @Test
    fun `best prefers the reading that plays furthest`() {
        val colony = colony()
        assertEquals(colony.map { it.id }, SplitPieces.best(colony.drop(1), colony, null).map { it.id })
        assertEquals(colony.drop(1).map { it.id }, SplitPieces.best(colony.drop(1), listOf(colony[5])).map { it.id })
        assertTrue(SplitPieces.best(null, emptyList()).isEmpty())
    }

    @Test
    fun `lookUp takes whatever a garbage page holds`() = runBlocking {
        val colony = colony()
        val junk = listOf(file(""), file("."), file("zip.001"), file("x.zip.99999999999999999999"))
        val found = SplitPieces.lookUp(colony[1]) { _, cursors -> MediaPage(junk, cursors.copy(documentDone = true, videoDone = true, animationDone = true), endReached = true) }!!
        assertEquals(1, found.size)
    }

    @Test
    fun `a position in the joined stream maps to its piece`() {
        val map = PieceMap(listOf(100, 100, 40))
        assertEquals(240, map.total)
        assertEquals(PieceMap.Spot(0, 0), map.locate(0))
        assertEquals(PieceMap.Spot(0, 99), map.locate(99))
        assertEquals(PieceMap.Spot(1, 0), map.locate(100))
        assertEquals(PieceMap.Spot(2, 39), map.locate(239))
        assertNull(map.locate(240))
        assertNull(map.locate(-1))
        assertEquals(1, map.leftInPiece(99))
        assertEquals(40, map.leftInPiece(200))
        assertEquals(200, map.startOf(2))
        // An empty piece holds no byte: its seam is the next piece's first.
        assertEquals(PieceMap.Spot(2, 0), PieceMap(listOf(10, 0, 5)).locate(10))
    }

    private companion object {
        const val MB = 1024L * 1024
        const val PIECE = 3_900 * MB
        const val COLONY = "Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil "
        const val AVENGERS = "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL."
        const val SPIDER =
            "Spider Man Brand New Day 2026 TRUE WEB-DL - 4K HDR - 2160p HQ - HEVC - TAM + TEL + HIN + MAL + KAN + ENG ATMOS"
    }
}
