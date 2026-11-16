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
        // Every video by default; the clip switch is 50 MB; the old 400 MB default reads as unset.
        assertEquals(0.0, limits.defaultMin)
        assertEquals(50 * mb, limits.clipMin)
        assertEquals(50 * mb, limits.homeMin)
        assertEquals(0.0, sizeEffectiveMin(400 * mb))
        assertEquals(0.0, sizeEffectiveMin(null))
        assertEquals(50 * mb, sizeEffectiveMin(50 * mb))
        assertEquals(700 * mb, sizeEffectiveMin(700 * mb))
        assertEquals(700 * mb, sizeHomeMin(700 * mb))
        assertEquals(50 * mb, sizeHomeMin(0.0))
        assertTrue(sizeMatches(0.0, limits.clipMin, limits.ceiling))
        assertTrue(!sizeMatches(10 * mb, limits.clipMin, limits.ceiling))
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
        order: String? = null,
    ): WebHomeLayout {
        val o: dynamic = js("({})")
        if (order != null) o.order = order
        o.carousel = carousel
        o.continueWatching = continueWatching
        o.recentlyAdded = recentlyAdded
        o.starredRows = starredRows
        return o.unsafeCast<WebHomeLayout>()
    }

    @Test
    fun `Settings Home: the stored order moves the rows, garbage and unknown keys change nothing`() {
        val saved = chat(1.0, "Me", saved = true)
        val starredChat = chat(2.0, "Harbour")
        val mine = videosIn(1.0, 10 to "Quiet.Coast.2021.1080p.mkv")
        val harbour = videosIn(2.0, 20 to "Harbour.Notes.S01E01.mkv", 22 to "Night.Ferry.2020.mkv")
        fun kinds(order: String?) = home(
            emptyArray(), arrayOf(starredChat, saved), arrayOf(2.0), arrayOf(chatVideos(1.0, mine), chatVideos(2.0, harbour)),
            emptyArray(), false, layout(order = order), null, false,
        ).map { it.kind }
        assertContentEquals(listOf("recent", "chat"), kinds(null))
        assertContentEquals(listOf("chat", "recent"), kinds("chat-2,recent"))
        assertContentEquals(listOf("chat", "recent"), kinds("future-row,chat-2,chat-99,recent"))
        // A row the order has not heard of stands at its default place: Recently added is first by default.
        assertContentEquals(listOf("recent", "chat"), kinds("future-row,chat-2,chat-99"))
        assertContentEquals(listOf("recent", "chat"), kinds("!!!,,\u0000"))
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

    private fun photo(id: Int, album: String?, caption: String?, hasPicture: Boolean = true): RawPhoto {
        val o: dynamic = js("({})")
        o.chatId = -1001234567890.0
        o.messageId = id.toDouble()
        o.albumId = album
        o.caption = caption
        o.hasPicture = hasPicture
        return o.unsafeCast<RawPhoto>()
    }

    @Test
    fun `an album's photo gives its videos a caption and a picture`() {
        val page = screenAlbums(
            arrayOf(
                raw(11, "video.mp4", albumId = "7", kind = "video", mime = "video/mp4"),
                raw(12, "Harbour.Notes.S01E02.mkv", albumId = "7", caption = "Own words"),
                raw(20, "Alone.2020.mkv"),
            ),
            arrayOf(photo(10, "7", "Harbour Notes, season one")),
        )
        val first = page.videos.first { it.messageId == 11.0 }
        assertEquals("Harbour Notes, season one", first.caption)
        assertEquals("Harbour Notes, season one", first.title)
        assertTrue(first.albumPhoto)
        val second = page.videos.first { it.messageId == 12.0 }
        assertEquals("Own words", second.caption)
        assertTrue(second.albumPhoto)
        assertFalse(page.videos.first { it.messageId == 20.0 }.albumPhoto)
        isPlain(first)
    }

    @Test
    fun `a photo with no picture, no album or an album of two shows changes no picture`() {
        val noPicture = screenAlbums(arrayOf(raw(11, "video.mp4", albumId = "7")), arrayOf(photo(10, "7", "Words", hasPicture = false)))
        assertFalse(noPicture.videos[0].albumPhoto)
        assertEquals("Words", noPicture.videos[0].caption)
        val alone = screenAlbums(arrayOf(raw(11, "video.mp4")), arrayOf(photo(10, null, "Words"), photo(9, "0", "Words")))
        assertEquals("", alone.videos[0].caption)
        val mixed = screenAlbums(
            arrayOf(raw(11, "Harbour.Notes.S01E01.mkv", albumId = "7"), raw(12, "Night.Lantern.2022.1080p.mkv", albumId = "7")),
            arrayOf(photo(10, "7", "Harbour Notes")),
        )
        assertTrue(mixed.videos.none { it.albumPhoto || it.caption.isNotEmpty() })
        // No photos given: exactly screen().
        assertEquals(screen(arrayOf(raw(11, "video.mp4"))).videos[0].caption, screenAlbums(arrayOf(raw(11, "video.mp4")), emptyArray()).videos[0].caption)
    }

    @Test
    fun `garbage photos never throw`() {
        val big = "x".repeat(4096)
        val photos = arrayOf(photo(1, "", big), photo(2, "not a number", null), photo(3, "-5", "\u0000"), photo(4, "9223372036854775807", big, hasPicture = false))
        val page = screenAlbums(arrayOf(raw(11, "video.mp4", albumId = "9223372036854775807"), raw(12, "v.mp4", albumId = "-5")), photos)
        assertEquals(2, page.videos.size)
        val ten = Array(10) { photo(it + 1, "7", if (it == 0) big else null, hasPicture = it % 2 == 0) }
        assertEquals(0, screenAlbums(emptyArray(), ten).videos.size)
    }

    private fun chatDoc(id: Int, name: String, size: Double = 40_000.0, date: Double = 1_700_000_000.0, albumId: String? = null, replyTo: Double = 0.0): ChatDoc {
        val o: dynamic = js("({})")
        o.messageId = id.toDouble()
        o.fileName = name
        o.size = size
        o.date = date
        o.albumId = albumId
        o.replyToMessageId = replyTo
        return o.unsafeCast<ChatDoc>()
    }

    @Test
    fun `chat subtitles come back best first as plain objects pointing into the documents`() {
        val docs = arrayOf(
            chatDoc(101, "Show.S01E03.srt"),
            chatDoc(102, "Show.S01E02.eng.forced.srt"),
            chatDoc(103, "Show.S01E02.eng.srt"),
            chatDoc(104, "Show.S01E02.srt", size = 0.0),
            chatDoc(99, "notes.txt"),
        )
        val hits = chatSubtitles("Show.S01E02.1080p.mkv", null, 100.0, 1_700_000_000.0, null, 0.0, docs, arrayOf("en"))
        assertEquals(listOf(2, 1), hits.map { it.index })
        assertEquals("en", hits[0].language)
        assertTrue(hits[1].forced)
        assertEquals("Neighbour", hits[0].relation)
        hits.forEach { isPlain(it) }
        assertEquals(0, chatSubtitles("", null, 0.0, 0.0, null, 0.0, emptyArray(), emptyArray()).size)
    }
}
