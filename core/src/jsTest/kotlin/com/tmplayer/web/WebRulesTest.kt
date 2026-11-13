package com.tmplayer.web

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web player's rules as the worker calls them: plain JS objects in, plain objects out, so they
 * read nothing through Kotlin's own classes and cross postMessage as they are.
 */
class WebRulesTest {

    private val mb = 1024.0 * 1024

    private fun raw(
        id: Int,
        fileName: String,
        size: Double = 700 * mb,
        kind: String = "document",
        caption: String? = null,
        canBeSaved: Boolean = true,
        selfDestructs: Boolean = false,
        withheld: Boolean = false,
        albumId: String? = null,
        mime: String? = null,
    ): RawVideo {
        val o: dynamic = js("({})")
        o.chatId = -1001234567890.0
        o.messageId = id.toDouble()
        o.kind = kind
        o.fileName = fileName
        o.mimeType = mime ?: if (fileName.endsWith(".pdf")) "application/pdf" else "video/x-matroska"
        o.caption = caption
        o.size = size
        o.duration = 0.0
        o.width = 0.0
        o.height = 0.0
        o.date = 1_700_000_000.0 + id
        o.topicId = 0.0
        o.albumId = albumId
        o.canBeSaved = canBeSaved
        o.selfDestructs = selfDestructs
        o.withheld = withheld
        return o.unsafeCast<RawVideo>()
    }

    /** Has only its own fields, as postMessage's structured clone keeps them. */
    private fun isPlain(value: Any?) {
        val proto: dynamic = js("Object.getPrototypeOf(value)")
        assertTrue(proto == js("Object.prototype"), "a plain object")
    }

    @Test
    fun `a page keeps the videos, drops the rest and counts the self destructing`() {
        val page = screen(
            arrayOf(
                raw(1, "Harbour.Notes.S01E01.1080p.mkv"),
                raw(2, "notes.pdf"),
                raw(3, "Harbour.Notes.S01E02.mkv", selfDestructs = true),
                raw(4, "Harbour.Notes.S01E03.mkv", withheld = true),
                raw(5, "Harbour.Notes.S01E04.mkv", canBeSaved = false),
            ),
        )
        assertEquals(listOf("-1001234567890:1", "-1001234567890:5"), page.videos.map { it.id })
        assertEquals(1, page.selfDestructing)
        assertTrue(page.videos[0].canBeSaved)
        assertFalse(page.videos[1].canBeSaved)
        isPlain(page)
        isPlain(page.videos[0])
    }

    @Test
    fun `episodes fold into one show and copies into one film`() {
        val videos = screen(
            arrayOf(
                raw(1, "Harbour.Notes.S01E01.720p.mkv", size = 400 * mb),
                raw(2, "Harbour.Notes.S01E02.720p.mkv", size = 410 * mb),
                raw(3, "Quiet.Valley.2019.720p.mkv", size = 900 * mb),
                raw(4, "Quiet.Valley.2019.1080p.mkv", size = 1900 * mb),
            ),
        ).videos
        val shelf = shelf(videos, "Newest", 50 * mb, 8 * 1024 * mb, emptyArray(), emptyArray())
        assertEquals(listOf("show", "file"), shelf.tiles.map { it.kind })
        val show = shelf.tiles[0]
        assertEquals(listOf("S01E01", "S01E02"), show.seasons.flatMap { it.episodes.toList() }.map { it.code }.sorted())
        assertEquals(2, shelf.tiles[1].copies.size)
        assertEquals(0, shelf.hiddenBySize)
        isPlain(show)
        isPlain(show.seasons[0].episodes[0])
    }

    @Test
    fun `the size limits hide what is outside them and say how many`() {
        val videos = screen(arrayOf(raw(1, "A.mkv", size = 10 * mb), raw(2, "B.mkv", size = 0.0), raw(3, "C.mkv"))).videos
        val shelf = shelf(videos, "Name", 50 * mb, 8 * 1024 * mb, emptyArray(), emptyArray())
        assertEquals(1, shelf.hiddenBySize)
        assertEquals(listOf("B", "C"), shelf.tiles.map { it.title })
    }

    @Test
    fun `next and previous episode`() {
        val videos = screen((1..3).map { raw(it, "Harbour.Notes.S01E0$it.mkv") }.toTypedArray()).videos
        val steps = steps(videos[1], videos)
        assertEquals(videos[0].id, steps.previous?.id)
        assertEquals(videos[2].id, steps.next?.id)
        assertEquals("S01E02", steps.current?.code)
        assertEquals("show", steps.series?.kind)
    }

    @Test
    fun `a name is parsed as the apps parse it`() {
        val name = parse("Harbour.Notes.S02E05.1080p.WEB-DL.mkv", null)
        assertEquals("Harbour Notes", name.title)
        assertEquals(2, name.season)
        assertEquals(5, name.episode)
        assertEquals("S02E05", name.code)
        val film = parse("Quiet.Valley.2019.720p.mkv", null)
        assertEquals(2019, film.year)
        assertNull(film.code)
    }

    @Test
    fun `the poster question is the one the apps ask`() {
        val videos = screen(
            arrayOf(
                raw(1, "Clap.2022.1080p.WEBRip.Tamil-Hindi.x265.mkv"),
                raw(2, "Harbour.Notes.S01E02.720p.mkv"),
                raw(3, "1080p.x265.mkv"),
            ),
        ).videos
        val film = posterAsk(videos[0])!!
        assertEquals("Clap", film.title)
        assertEquals(2022, film.year)
        assertEquals("film", film.kind)
        assertFalse(film.series)
        assertContentEquals(arrayOf("ta", "hi"), film.languages)
        isPlain(film)
        val show = posterAsk(videos[1])!!
        assertEquals("Harbour Notes", show.title)
        assertEquals("show", show.kind)
        assertTrue(show.series)
        assertNull(show.year)
        // A lone episode on Home's carousel names its episode on Play; a film has none.
        assertEquals("S01E02", show.episode)
        assertNull(film.episode)
        assertNull(posterAsk(videos[2]))
    }

    @Test
    fun `a caption becomes the overview without the file's facts`() {
        val video = screen(arrayOf(raw(1, "Harbour.Notes.2019.1080p.mkv", caption = "A harbour town keeps its notes.\nSize: 1.2 GB\nQuality: 1080p"))).videos[0]
        assertEquals("A harbour town keeps its notes.", overviewOf(video))
        val bare = screen(arrayOf(raw(2, "Harbour.Notes.2019.1080p.mkv", caption = "Size: 1.2 GB"))).videos[0]
        assertNull(overviewOf(bare))
    }

    @Test
    fun `a Continue watching card is named as the apps name it`() {
        val videos = screen(
            arrayOf(
                raw(1, "Harbour.Notes.S01E02.720p.mkv"),
                raw(2, "Clap.2022.1080p.WEBRip.x265.mkv"),
            ),
        ).videos
        assertEquals("S1 E2  \u00B7  Harbour Notes", continueTitle(videos[0]))
        assertEquals("Clap", continueTitle(videos[1]))
    }

    @Test
    fun `a copy is told by its quality, its picture codec and where it came from`() {
        val videos = screen(
            arrayOf(
                raw(1, "Clap.2022.1080p.BluRay.x264.mkv"),
                raw(2, "Clap.2022.2160p.HDR.HEVC.mkv"),
                raw(3, "clap.mkv"),
            ),
        ).videos
        assertContentEquals(arrayOf("1080p", "H.264", "Blu-ray"), copyFacts(videos[0]))
        assertEquals("4K", copyFacts(videos[1])[0])
        assertEquals("1080p", qualityLabel(videos[0]))
        assertNull(qualityLabel(videos[2]))
    }

    @Test
    fun `fuzzy search forgives a typo and ranks the whole phrase first`() {
        val texts = arrayOf("Quiet Valley", "Harbour Notes", "Harbor Notes Extra")
        assertContentEquals(arrayOf(1, 2), rank(texts, "harbour notes"))
        assertContentEquals(arrayOf(0, 1, 2), rank(texts, " "))
    }

    @Test
    fun `saving rules, resume rewind and the size slider`() {
        assertEquals("StreamOnly", protection(canBeSaved = true, chatProtected = true, selfDestructs = false))
        assertEquals("Hidden", protection(canBeSaved = true, chatProtected = false, selfDestructs = true))
        assertEquals(57_000.0, resumeStart(60_000.0, 3_000.0))
        assertEquals(5_000.0, resumeStart(5_000.0, 3_000.0))
        assertEquals(60_000.0, resumeKept(60_000.0, 57_200.0, 3_000.0))
        assertEquals(3_000.0, rewindSetting(7.0))
        val limits = sizeLimits()
        assertEquals(400 * mb, limits.defaultMin)
        assertEquals(listOf(0.0, 50 * mb, 100 * mb, 200 * mb, 400 * mb, 1024 * mb), limits.quick.toList())
        assertEquals(100 * mb, sizeStep(50 * mb, 1))
        assertEquals(0.05, sizeFraction(50 * mb), 0.0001)
        assertEquals(limits.ceiling, sizeAt(1.0))
        assertTrue(sizeMatches(0.0, limits.defaultMin, limits.ceiling))
    }

    @Test
    fun `archives are named with their kind and volumes told apart`() {
        val zip = archive("Harbour Notes S01.zip", null)!!
        isPlain(zip)
        assertEquals("ZIP", zip.kind)
        assertFalse(zip.volume)
        assertTrue(zip.reason.isNotBlank())
        assertTrue(archive("Harbour.Notes.part2.rar", null)!!.volume)
        assertEquals("7z", archive("notes", "application/x-7z-compressed")!!.kind)
        assertNull(archive("Harbour.Notes.S01E02.mkv", "video/x-matroska"))
        assertTrue(sizeLabel(50 * mb).isNotBlank())
    }

    @Test
    fun `a video kept in a zip is listed, its pieces fold into one card with the summed size`() {
        val gb4 = 4_194_304_000.0
        val zip = "application/zip"
        val name = "Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip"
        val pieces = (1..18).map { n -> raw(n, "$name.${n.toString().padStart(3, '0')}", size = if (n == 18) 3_769_561_462.0 else gb4, mime = zip) }
        val endgame = listOf(
            "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.001" to gb4,
            "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.002" to gb4,
            "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.003" to 1_233_811_809.0,
        ).mapIndexed { i, (n, size) -> raw(100 + i, n, size = size, mime = zip) }
        val others = listOf(
            raw(200, "IvyWalletBackup_20260930-1830.zip", size = 165_710.0, mime = zip),
            raw(201, "signed-ota_update.zip", size = 2_152_708_427.0, mime = zip),
        )
        assertTrue(looksLikeVideoArchive("Night.Train.2019.1080p.BluRay.mkv.zip", 2_000 * mb))
        assertFalse(looksLikeVideoArchive("signed-ota_update.zip", 2_152_708_427.0))
        val kept = screen((pieces + endgame + others).toTypedArray()).videos
        assertEquals(21, kept.size)
        assertFalse(kept.any { it.fileName.startsWith("Ivy") || it.fileName.startsWith("signed") })
        val shelf = shelf(kept, "Newest", 0.0, 64 * 1024 * mb, emptyArray(), emptyArray())
        assertEquals(2, shelf.tiles.size)
        val colony = shelf.tiles.single { it.video!!.fileName.startsWith("Colony") }
        assertEquals(18, colony.pieces.size)
        assertEquals(17 * gb4 + 3_769_561_462.0, colony.video!!.size)
        assertEquals(18, colony.piecesTotal)
        assertEquals(0, colony.piecesFirstMissing)
        assertEquals(18, colony.piecesPlayable)
        val endgameTile = shelf.tiles.single { it.video!!.fileName.startsWith("Avengers") }
        assertEquals(3, endgameTile.pieces.size)
        assertEquals(2 * gb4 + 1_233_811_809.0, endgameTile.video!!.size)
        // Pieces come in order, whatever order the chat listed them in.
        assertEquals(listOf("-1001234567890:100", "-1001234567890:101", "-1001234567890:102"), endgameTile.pieces.toList())
        isPlain(endgameTile)
    }

    @Test
    fun `a gap in a set of pieces says where it plays up to`() {
        val gb4 = 4_194_304_000.0
        val zip = "application/zip"
        val base = "Night.Train.2019.1080p.BluRay.mkv"
        val kept = screen(
            listOf(1, 2, 4).map { n -> raw(n, "$base.00$n", size = gb4, mime = zip) }.toTypedArray(),
        ).videos
        val tile = shelf(kept, "Newest", 0.0, 64 * 1024 * mb, emptyArray(), emptyArray()).tiles.single()
        assertEquals(4, tile.piecesTotal)
        assertEquals(3, tile.piecesFirstMissing)
        assertEquals(2, tile.piecesPlayable)
        val lone = screen(arrayOf(raw(9, "Harbour.Notes.S01E01.mkv"))).videos
        val one = shelf(lone, "Newest", 0.0, 64 * 1024 * mb, emptyArray(), emptyArray()).tiles.single()
        assertEquals(0, one.piecesTotal)
        assertEquals(0, one.piecesPlayable)
    }

    private fun chat(id: Double, title: String, saved: Boolean = false, pinned: Boolean = false): WebChat {
        val o: dynamic = js("({})")
        o.id = id
        o.title = title
        o.saved = saved
        o.pinned = pinned
        return o.unsafeCast<WebChat>()
    }

    private fun videosIn(chatId: Double, vararg names: Pair<Int, String>): Array<Video> =
        screen(names.map { (id, name) -> raw(id, name).also { it.asDynamic().chatId = chatId } }.toTypedArray()).videos

    private fun chatVideos(chatId: Double, videos: Array<Video>, complete: Boolean = true): ChatVideos {
        val o: dynamic = js("({})")
        o.chatId = chatId
        o.videos = videos
        o.complete = complete
        return o.unsafeCast<ChatVideos>()
    }

    @Test
    fun `home picks its chats and builds Continue, Recently added and a row per starred chat`() {
        val saved = chat(1.0, "Me", saved = true)
        val starredChat = chat(2.0, "Harbour")
        val other = chat(3.0, "News")
        assertContentEquals(arrayOf(2.0, 1.0), homeChats(arrayOf(saved, starredChat, other), arrayOf(2.0), null))

        val mine = videosIn(1.0, 10 to "Quiet.Coast.2021.1080p.mkv", 11 to "Long.Road.2019.mkv")
        val harbour = videosIn(2.0, 20 to "Harbour.Notes.S01E01.mkv", 21 to "Harbour.Notes.S01E02.mkv", 22 to "Night.Ferry.2020.mkv")
        val resume: dynamic = js("({})")
        resume.video = mine[0]
        resume.chatTitle = "Me"
        resume.positionMs = 60_000.0
        resume.durationMs = 3_600_000.0
        resume.updatedAt = 1_700_000_000_000.0
        val rows = home(
            arrayOf(resume.unsafeCast<WebResume>()),
            arrayOf(starredChat, saved),
            arrayOf(2.0),
            arrayOf(chatVideos(1.0, mine), chatVideos(2.0, harbour, complete = false)),
            emptyArray(),
            false,
            layout(),
            null,
            false,
        )
        rows.forEach(::isPlain)
        assertContentEquals(arrayOf("continue", "recent", "chat"), rows.map { it.kind }.toTypedArray())
        assertContentEquals(arrayOf(mine[0].id), rows[0].resumes)
        // What is in Continue is in no other row; the show folds into one tile.
        assertFalse(rows[1].tiles.any { it.video?.id == mine[0].id })
        assertTrue(rows[1].starredOnly.not())
        assertEquals(2.0, rows[2].chatId)
        assertEquals("Harbour", rows[2].title)
        assertEquals(1, rows[2].tiles.count { it.kind == "show" })
        assertTrue(rows[2].totalAtLeast)
        // Before the search across chats answers, Recently added holds its place.
        val waiting = home(emptyArray(), arrayOf(starredChat, saved), arrayOf(2.0), emptyArray(), null, true, layout(), null, false)
        assertFalse(waiting.first { it.kind == "recent" }.loaded)
    }

    private fun layout(
        carousel: Boolean = true,
        continueWatching: Boolean = true,
        recentlyAdded: Boolean = true,
        starredRows: Boolean = true,
    ): WebHomeLayout {
        val o: dynamic = js("({})")
        o.carousel = carousel
        o.continueWatching = continueWatching
        o.recentlyAdded = recentlyAdded
        o.starredRows = starredRows
        return o.unsafeCast<WebHomeLayout>()
    }

    @Test
    fun `Settings Home: rows off are not built, added chats feed Recently added alone`() {
        val saved = chat(1.0, "Me", saved = true)
        val starredChat = chat(2.0, "Harbour")
        val other = chat(3.0, "News")
        val chats = arrayOf(saved, starredChat, other)
        val mine = videosIn(1.0, 10 to "Quiet.Coast.2021.1080p.mkv")
        val news = videosIn(3.0, 30 to "Night.Ferry.2020.mkv")
        val resume: dynamic = js("({})")
        resume.video = mine[0]
        resume.chatTitle = "Me"
        resume.positionMs = 60_000.0
        resume.durationMs = 3_600_000.0
        resume.updatedAt = 1_700_000_000_000.0

        // Continue and starred rows off: neither is built, and no starred chat is read for a row.
        val quiet = layout(continueWatching = false, starredRows = false)
        val rows = home(arrayOf(resume.unsafeCast<WebResume>()), chats, arrayOf(2.0), arrayOf(chatVideos(1.0, mine)), emptyArray(), false, quiet, null, false)
        assertContentEquals(arrayOf("recent"), rows.map { it.kind }.toTypedArray())
        assertTrue(rows[0].asRow)

        // Recently added off with the carousel on: kept for the carousel, not drawn as a row.
        val forCarousel = home(emptyArray(), chats, arrayOf(2.0), arrayOf(chatVideos(1.0, mine)), emptyArray(), false, layout(recentlyAdded = false), null, false)
        assertFalse(forCarousel.first { it.kind == "recent" }.asRow)
        // Both off: no Recently added at all, and no search across chats wanted.
        val bare = layout(recentlyAdded = false, carousel = false)
        assertTrue(home(emptyArray(), chats, arrayOf(2.0), emptyArray(), emptyArray(), false, bare, null, false).none { it.kind == "recent" })
        assertFalse(homePlan(chats, arrayOf(2.0), null, bare, null, true).needsRecent)

        // Added chats: only those are read for Recently added, starred rows still picked.
        val plan = homePlan(chats, arrayOf(2.0), null, layout(), arrayOf(3.0), true)
        assertContentEquals(arrayOf(3.0), plan.recentFrom)
        assertContentEquals(arrayOf(3.0, 2.0), plan.picked)
        assertFalse(plan.noneChosen)
        assertFalse(plan.autoPick)
        val fromChosen = home(emptyArray(), chats, arrayOf(2.0), arrayOf(chatVideos(3.0, news), chatVideos(2.0, emptyArray())), emptyArray(), false, layout(), plan.recentFrom, plan.noneChosen)
        assertTrue(fromChosen.first { it.kind == "recent" }.tiles.all { it.video?.chatId == 3.0 })

        // Picked, and nothing added: the line in the row's place.
        val none = homePlan(chats, arrayOf(2.0), null, layout(), emptyArray(), true)
        assertTrue(none.noneChosen)
        assertTrue(home(emptyArray(), chats, arrayOf(2.0), emptyArray(), emptyArray(), false, layout(), none.recentFrom, true).first { it.kind == "recent" }.noneChosen)
        // Nothing picked at all: Home picks its own chats.
        assertTrue(homePlan(chats, arrayOf(2.0), null, layout(), null, true).autoPick)
    }
}
