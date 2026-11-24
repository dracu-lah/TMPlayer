package com.tmplayer.web

import com.tmplayer.data.ReleaseHighlights
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
    fun `a poster tile wears one resolution pill from 720p up and nothing for SD or an unknown size`() {
        val videos = screen(
            arrayOf(
                raw(1, "Clap.2022.1080p.BluRay.x264.mkv"),
                raw(2, "Clap.2022.2160p.HDR.HEVC.mkv"),
                raw(3, "clap.mkv"),
                raw(4, "Clap.2022.480p.mkv"),
            ),
        ).videos
        assertEquals("1080p", tileTag(videos[0]))
        assertEquals("4K", tileTag(videos[1]))
        assertNull(tileTag(videos[2]))
        assertNull(tileTag(videos[3]))
    }

    @Test
    fun `a fansub name asks AniList first and a plain film does not`() {
        val videos = screen(
            arrayOf(
                raw(1, "[Group] Harbour Notes - 12 [1080p].mkv"),
                raw(2, "Clap.2022.1080p.mkv"),
            ),
        ).videos
        assertTrue(posterAsk(videos[0])!!.anime)
        assertFalse(posterAsk(videos[1])!!.anime)
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

    // ---- WB1: what the web gaps plan reads from the rules ------------------------------------------

    /** [video] as the page would hold it with the size Telegram states for it (the raw message has none here). */
    private fun stated(video: Video, width: Int, height: Int): Video {
        val o: dynamic = js("Object.assign({}, video)")
        o.width = width.toDouble()
        o.height = height.toDouble()
        return o.unsafeCast<Video>()
    }

    private fun films(vararg names: String): Array<Video> =
        screen(names.mapIndexed { i, name -> raw(i + 1, name, size = (10 - i) * 300 * mb) }.toTypedArray()).videos

    @Test
    fun `one quality read: the stated size beats the name, a cam is low grade`() {
        val v = films("Clap.2022.720p.WEBRip.mkv", "Clap.2022.HDCAM.mkv", "clap.mkv")
        val mislabelled = qualityRead(stated(v[0], 1920, 1080))
        assertEquals("1080p", mislabelled.label)
        assertEquals(1080, mislabelled.height)
        assertTrue(mislabelled.mismatch)
        assertFalse(mislabelled.lowGrade)
        isPlain(mislabelled)
        val named = qualityRead(v[0])
        assertEquals("720p", named.label)
        assertFalse(named.mismatch)
        assertTrue(qualityRead(v[1]).lowGrade)
        assertNull(qualityRead(v[2]).label)
        assertNull(qualityRead(v[2]).height)
    }

    @Test
    fun `the recommended copy leads: not a cam, smooth here, the picture the device suits`() {
        val v = films("Clap.2022.2160p.mkv", "Clap.2022.1080p.mkv", "Clap.2022.720p.mkv", "Clap.2022.1080p.HDCAM.mkv")
        try {
            setCopyDevice("phone")
            // A phone shows 1080p in full: the 4K copy stays listed, after the ones that fit; the cam is last.
            assertContentEquals(arrayOf(v[1].id, v[2].id, v[0].id, v[3].id), copyOrder(v, null, emptyArray()))
            assertEquals(v[1].id, bestCopy(v, emptyArray()))
            setCopyDevice("computer")
            assertEquals(v[0].id, copyOrder(v, null, emptyArray())[0])
            // A copy this browser cannot play natively gives way while another can.
            assertEquals(v[1].id, copyOrder(v, null, arrayOf(v[0].id))[0])
            assertEquals(v[1].id, bestCopy(v, arrayOf(v[0].id)))
            // Not a known device word: nothing changes.
            setCopyDevice("fridge")
            assertEquals(v[0].id, bestCopy(v, emptyArray()))
        } finally {
            setCopyDevice("phone")
        }
    }

    @Test
    fun `the copy Play opens follows the viewer's pick, what they started, their language and their habit`() {
        val v = films("Clap.2022.2160p.mkv", "Clap.2022.1080p.Tamil.mkv", "Clap.2022.720p.Hindi.mkv")
        try {
            setCopyDevice("computer")
            assertEquals(v[2].id, pickCopy(v, v[2].id, emptyArray(), null, "ta", emptyArray()))
            assertEquals(v[2].id, pickCopy(v, null, arrayOf(v[2].id), null, "ta", emptyArray()))
            assertEquals(v[1].id, pickCopy(v, null, emptyArray(), null, "ta", emptyArray()))
            val habit = habitNoted(habitNoted(null, 720), 720)
            assertEquals("720:2", habit)
            assertEquals(v[2].id, pickCopy(v, null, emptyArray(), habit, null, emptyArray()))
            assertEquals(v[0].id, pickCopy(v, null, emptyArray(), null, null, emptyArray()))
            assertNull(pickCopy(emptyArray(), null, emptyArray(), null, null, emptyArray()))
            // Garbage habit lines count for nothing; a height that is not one is not noted.
            assertEquals("", habitNoted("not,a:habit", null))
            assertEquals("", habitNoted(null, 0))
        } finally {
            setCopyDevice("phone")
        }
    }

    @Test
    fun `the version count folds the same file posted twice and handles 600 copies`() {
        val v = screen(
            arrayOf(
                raw(1, "Clap.2022.1080p.mkv", size = 800 * mb),
                raw(2, "Clap.2022.1080p.mkv", size = 800 * mb),
                raw(3, "Clap.2022.720p.mkv", size = 400 * mb),
            ),
        ).videos
        assertEquals(2, versionCount(v))
        assertEquals(1, versionCount(arrayOf(v[0])))
        assertEquals(1, versionCount(emptyArray()))
        val many = screen(Array(600) { raw(it + 1, "Clap.2022.copy$it.mkv", size = (100 + it) * mb) }).videos
        assertEquals(600, versionCount(many))
        // The tile of a film says the same number as the page.
        val tile = shelf(v, "Newest", 0.0, 1e12, emptyArray(), emptyArray()).tiles.single()
        assertEquals(2, tile.versions)
        assertEquals(1, shelf(films("Harbour.Notes.S01E01.mkv", "Harbour.Notes.S01E02.mkv"), "Newest", 0.0, 1e12, emptyArray(), emptyArray()).tiles.single().versions)
    }

    @Test
    fun `a chat named for a language gives the poster question its hint, and a rename replaces it`() {
        val film = films("Clap.2022.1080p.mkv")[0]
        try {
            assertEquals(0, posterAsk(film)!!.hints.size)
            noteChat(-1001234567890.0, "Malayalam Movies HD")
            assertContentEquals(arrayOf("ml"), posterAsk(film)!!.hints)
            isPlain(posterAsk(film)!!)
            noteChat(-1001234567890.0, "Weekend films")
            assertEquals(0, posterAsk(film)!!.hints.size)
            noteChat(-1001234567890.0, "Tamil Hindi Shows")
            forgetChats()
            assertEquals(0, posterAsk(film)!!.hints.size)
        } finally {
            forgetChats()
        }
        assertContentEquals(arrayOf("ta", "hi"), chatLanguages("Tamil and Hindi shows"))
        assertEquals(0, chatLanguages("").size)
        assertEquals(0, chatLanguages("x".repeat(100_000)).size)
    }

    @Test
    fun `corrections are read, edited and fed to the grouping`() {
        assertEquals("separate", correctionSet(null, "separate", "1"))
        assertEquals("", correctionSet("separate", "separate", "0"))
        val renamed = correctionSet("separate", "title", "  Night Train (2019)  ")
        assertEquals("separate\ntitle=Night Train (2019)", renamed)
        assertEquals("Night Train (2019)", correctionRead(renamed).title)
        assertTrue(correctionRead(renamed).separate)
        assertEquals("separate", correctionSet(renamed, "title", "   "))
        assertEquals(120, correctionRead(correctionSet(null, "title", "x".repeat(500))).title!!.length)
        // A pick lifts "wrong", turning "wrong" on drops the pick, a bad pick line is no pick.
        val picked = correctionSet("wrong", "pick", "film:123")
        assertEquals("film:123", correctionRead(picked).pick)
        assertFalse(correctionRead(picked).wrong)
        assertNull(correctionRead(correctionSet(picked, "wrong", "1")).pick)
        assertNull(correctionRead(correctionSet(null, "pick", "film:abc")).pick)
        assertNull(correctionRead(correctionSet(null, "pick", "film:0")).pick)
        assertEquals("", correctionSet(null, "nonsense", "1"))
        assertNull(correctionRead("\u0000garbage\n=\ntmdb=film:99999999999").pick)
        isPlain(correctionRead(picked))

        val v = films("Clap.2022.1080p.mkv", "Clap.2022.720p.mkv")
        try {
            assertEquals(1, shelf(v, "Newest", 0.0, 1e12, emptyArray(), emptyArray()).tiles.size)
            setCorrections(arrayOf(v[1].id, "not:a:key"), arrayOf("separate", ""))
            assertEquals(2, shelf(v, "Newest", 0.0, 1e12, emptyArray(), emptyArray()).tiles.size)
        } finally {
            setCorrections(emptyArray(), emptyArray())
        }
        assertEquals(1, shelf(v, "Newest", 0.0, 1e12, emptyArray(), emptyArray()).tiles.size)
    }

    @Test
    fun `the sort menu's filters are one line that is read and edited a part at a time`() {
        val line = facetsWith(facetsWith(facetsWith(null, "length", "short"), "picture", "1080"), "hdr", "1")
        assertEquals("len=short;pic=1080;hdr=1", line)
        val f = facetsRead(line)
        assertEquals("short", f.length)
        assertEquals("1080", f.picture)
        assertTrue(f.hdr)
        assertEquals(3, f.count)
        isPlain(f)
        assertEquals("len=short;pic=1080", facetsWith(line, "hdr", "0"))
        assertEquals("", facetsWith(line, "clear", null))
        assertEquals("lang=ml", facetsWith(null, "language", "ML"))
        assertEquals("", facetsWith(null, "language", "malayalam1"))
        assertEquals("", facetsWith(null, "length", "forever"))
        assertEquals(0, facetsRead("len=;;;pic=??;lang=%%;add=never;=").count)
        assertEquals(0, facetsRead(null).count)
        assertContentEquals(arrayOf("All", "Unwatched", "InProgress", "Watched", "OnDevice", "Shows", "Singles", "HighDefinition"), videoFilters())
        assertEquals("Newest", videoSorts()[0])
    }

    @Test
    fun `a chat's view applies the filters, counts what they hide and offers the languages`() {
        val v = screen(
            arrayOf(
                raw(1, "Clap.2022.1080p.Tamil.mkv", size = 800 * mb),
                raw(2, "Rain.2021.720p.Hindi.mkv", size = 500 * mb),
                raw(3, "Dawn.2020.mkv", size = 600 * mb),
                raw(4, "Harbour.Notes.S01E01.1080p.mkv", size = 400 * mb),
                raw(5, "Harbour.Notes.S01E02.1080p.mkv", size = 410 * mb),
            ),
        ).videos
        val none = emptyArray<String>()
        val all = shelfView(v, "Newest", "All", null, 0.0, 1e12, none, none, none, emptyArray(), 1_700_100_000.0)
        assertEquals(4, all.tiles.size)
        assertEquals(4, all.total)
        assertEquals(0, all.hiddenByFilter)
        assertContentEquals(arrayOf("hi", "ta"), all.languages)
        isPlain(all)
        // 1080p and up: Dawn names no picture, so it is kept and counted as unknown, after the matches.
        val sharp = shelfView(v, "Newest", "All", "pic=1080", 0.0, 1e12, none, none, none, emptyArray(), 1_700_100_000.0)
        assertEquals(3, sharp.tiles.size)
        assertEquals(1, sharp.unknown)
        assertEquals(1, sharp.hiddenByFilter)
        // Shows, singles, watched and unwatched.
        assertEquals(1, shelfView(v, "Newest", "Shows", null, 0.0, 1e12, none, none, none, emptyArray(), 0.0).tiles.size)
        assertEquals(3, shelfView(v, "Newest", "Singles", null, 0.0, 1e12, none, none, none, emptyArray(), 0.0).tiles.size)
        val seen = shelfView(v, "Newest", "Watched", null, 0.0, 1e12, arrayOf(v[0].id), none, none, emptyArray(), 0.0)
        assertEquals(listOf(v[0].id), seen.tiles.map { it.video!!.id })
        assertEquals(3, shelfView(v, "Newest", "Unwatched", null, 0.0, 1e12, arrayOf(v[0].id), none, none, emptyArray(), 0.0).tiles.size)
        // An unknown filter name is All; the Language group keeps the picked language offered.
        assertEquals(4, shelfView(v, "Newest", "Nonsense", null, 0.0, 1e12, none, none, none, emptyArray(), 0.0).tiles.size)
        assertEquals(1, shelfView(v, "Newest", "All", "lang=ta", 0.0, 1e12, none, none, none, emptyArray(), 0.0).tiles.size)
        // Last played: the one touched most recently first, never played last.
        val played = shelfView(v, "LastPlayed", "All", null, 0.0, 1e12, none, none, arrayOf(v[1].id, v[0].id), arrayOf(50.0, 90.0), 0.0)
        assertEquals(v[0].id, played.tiles[0].video!!.id)
        assertEquals(v[1].id, played.tiles[1].video!!.id)
        // Garbage in: mismatched arrays and an empty chat never throw.
        assertEquals(0, shelfView(emptyArray(), "Name", "All", "x=y", 0.0, 1e12, none, none, arrayOf("a"), emptyArray(), Double.NaN).tiles.size)
    }

    @Test
    fun `play random never repeats and prefers what is unwatched`() {
        val v = films("A.2020.mkv", "B.2021.mkv", "C.2022.mkv")
        val none = emptyArray<String>()
        assertNull(playRandom(emptyArray(), none, null))
        assertEquals(v[0].id, playRandom(arrayOf(v[0]), arrayOf(v[0].id), v[0].id))
        repeat(40) {
            assertTrue(playRandom(v, arrayOf(v[0].id), null) != v[0].id)
            assertTrue(playRandom(v, none, v[1].id) != v[1].id)
        }
        // Only the last is unwatched: a watched one beats a repeat.
        repeat(20) { assertTrue(playRandom(v, arrayOf(v[0].id, v[1].id), v[2].id) != v[2].id) }
    }

    @Test
    fun `a speed belongs to the series, else the chat, and 1x forgets`() {
        val v = screen(arrayOf(raw(1, "Harbour.Notes.S01E01.mkv"), raw(2, "Clap.2022.mkv"))).videos
        val episode = speedScopes(v[0])
        assertEquals("series:Harbour Notes", episode.series)
        assertEquals("chat:-1001234567890", episode.chat)
        assertEquals(episode.series, episode.target)
        assertContentEquals(arrayOf("series:Harbour Notes", "chat:-1001234567890"), episode.all)
        isPlain(episode)
        val film = speedScopes(v[1])
        assertNull(film.series)
        assertEquals(film.chat, film.target)
        assertEquals("speed_series:harbournotes", speedStoreKey("series:Harbour Notes"))
        assertEquals("speed_chat:-1001234567890:34", speedStoreKey("chat:-1001234567890:34"))
        assertEquals(80 + "speed_".length, speedStoreKey("a".repeat(500)).length)
        assertEquals(1.5, speedResolve(null, 1.5, 2.0), 0.0001)
        assertEquals(2.0, speedResolve(null, null, 2.0), 0.0001)
        assertEquals(1.25, speedResolve(1.25, 1.5, 2.0), 0.0001)
        assertEquals(1.0, speedResolve(Double.NaN, -3.0, null), 0.0001)
        for (exact in listOf(0.7, 0.9, 1.1, 1.25, 2.5)) {
            assertEquals(exact, speedKeep(exact))
            assertEquals(exact, speedResolve(exact, null, null))
        }
        assertNull(speedKeep(1.0))
        assertNull(speedKeep(Double.NaN))
        assertEquals(1.75, speedKeep(1.7500001)!!, 0.0001)
        assertEquals(4.0, speedKeep(900.0)!!, 0.0001)
    }

    @Test
    fun `settings rows and the search come from the apps' map`() {
        val phone = settingsRows("phone")
        assertTrue(phone.size > 60)
        assertTrue(phone.all { it.title.isNotBlank() && it.groupTitle.isNotBlank() })
        assertTrue(phone.any { it.id == "subtitle_style" && it.group == "Subtitles" })
        // A television has no box and a phone no mouse rows.
        assertTrue(settingsRows("desktop").any { it.id == "wheel_seeks" })
        assertTrue(phone.none { it.id == "wheel_seeks" })
        isPlain(phone[0])
        assertTrue("subtitle_style" in settingsSearch("subtitle", "phone", null))
        assertTrue("subtitle_style" !in settingsSearch("subtitle", "phone", arrayOf("autoplay")))
        assertEquals(listOf("autoplay"), settingsSearch("next episode", "phone", arrayOf("autoplay", "theme")).toList())
        assertEquals(0, settingsSearch("   ", "phone", null).size)
        assertEquals(0, settingsSearch("zzzzqqq", "desktop", null).size)
        assertTrue(settingsSearch("wallpapr colours bigg ".repeat(5000), "desktop", null).isEmpty())
        assertTrue(settingsSearch("Subtitles", "weird form", null).isNotEmpty())
    }

    @Test
    fun `what's new is the apps' sheet, version, address and lines`() {
        val n = whatsNew()
        assertEquals(ReleaseHighlights.VERSION, n.version)
        assertTrue(n.changelog.startsWith("https://"))
        assertEquals(ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Web).size, n.highlights.size)
        assertEquals(n.highlights.size, n.sections.sumOf { it.lines.size })
        assertTrue(n.sections.all { it.title.isNotBlank() })
        assertTrue(n.highlights.isNotEmpty() && n.highlights.all { it.isNotBlank() })
        isPlain(n)
    }

    @Test
    fun `a post link is the one Telegram copies, with its topic and time`() {
        assertEquals("https://t.me/clap_films/42", tgPostLink("clap_films", -1001234567890.0, 0.0, 42.0, 0.0))
        assertEquals("https://t.me/clap_films/42", tgPostLink("@clap_films", 5.0, 0.0, 42.0, Double.NaN))
        assertEquals("https://t.me/clap_films/7/42?t=90", tgPostLink("clap_films", -1001234567890.0, 7.0, 42.0, 90.0))
        assertEquals("https://t.me/c/1234567890/42", tgPostLink(null, -1001234567890.0, 0.0, 42.0, 0.0))
        assertEquals("https://t.me/c/1234567890/7/42?t=3", tgPostLink("  ", -1001234567890.0, 7.0, 42.0, 3.9))
        // A person, a small group, no message, garbage.
        assertNull(tgPostLink(null, 777.0, 0.0, 42.0, 0.0))
        assertNull(tgPostLink(null, -4242.0, 0.0, 42.0, 0.0))
        assertNull(tgPostLink(null, -1_000_000_000_000.0, 0.0, 42.0, 0.0))
        assertNull(tgPostLink("clap_films", -1001234567890.0, 0.0, 0.0, 0.0))
        assertNull(tgPostLink(null, Double.NaN, 0.0, 42.0, 0.0))
    }

    @Test
    fun `subtitle picks are filed under the post and the video itself`() {
        val v = screen(arrayOf(raw(9, "Clap.2022.1080p.mkv"), raw(10, "Clap.2022.720p.mkv"))).videos
        val a = subtitlePickKeys(v[0])
        val b = subtitlePickKeys(v[1])
        assertEquals("msg:-1001234567890:9", a[0])
        assertEquals(2, a.size)
        // Another copy of the same video finds the pick by the second key.
        assertEquals(a[1], b[1])
    }

    @Test
    fun `a stutter is the apps' definition, and garbage is no evidence`() {
        assertEquals("slow", stutterSymptom(0.5, 0.0, 300.0, 0.0, false))
        assertEquals("dropped", stutterSymptom(1.0, 60.0, 300.0, 0.0, false))
        assertEquals("underruns", stutterSymptom(1.0, 0.0, 300.0, 3.0, false))
        assertEquals("late", stutterSymptom(1.0, 12.0, 300.0, 0.0, true))
        assertNull(stutterSymptom(1.0, 0.0, 300.0, 0.0, true))
        assertNull(stutterSymptom(1.0, 5.0, 300.0, 0.0, false))
        assertNull(stutterSymptom(Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, -4.0, false))
        assertEquals("dropped", stutterSymptom(1.0, 1e300, 0.0, 0.0, false))
        assertEquals(2000.0, stutterSettleMs(), 0.0)
        assertEquals(12000.0, stutterWindowMs(), 0.0)
    }

    @Test
    fun `the languages the apps ship, and which one a browser gets`() {
        val all = languageList()
        assertEquals(22, all.size)
        assertEquals("en", all[0].tag)
        assertTrue(all.single { it.tag == "ar" }.rtl)
        assertTrue(all.none { it.tag == "de" && it.rtl })
        isPlain(all[0])
        assertEquals("ar", languageResolve("", arrayOf("ar-SA", "en")))
        assertEquals("de", languageResolve("de", arrayOf("ar")))
        assertEquals("en", languageResolve(null, emptyArray()))
        assertEquals("en", languageResolve("xx-garbage", arrayOf("")))
    }
    private fun topic(id: Int, name: String, pinned: Boolean = false, closed: Boolean = false, hasVideos: Boolean = true): WebTopic {
        val o: dynamic = js("({})")
        o.id = id.toDouble()
        o.name = name
        o.pinned = pinned
        o.closed = closed
        o.hasVideos = hasVideos
        return o.unsafeCast<WebTopic>()
    }

    private fun forum(id: Int, title: String, archived: Boolean = false): WebTopicChat {
        val o: dynamic = js("({})")
        o.id = id.toDouble()
        o.title = title
        o.archived = archived
        return o.unsafeCast<WebTopicChat>()
    }

    @Test
    fun `topics are found by name inside the apps' budget`() {
        val budget = topicBudget()
        assertEquals(12.0, budget.chats)
        assertEquals(3.0, budget.atOnce)
        assertEquals(30.0, budget.maxHits)
        isPlain(budget)
        // Accents and emoji fold, a word one letter out lands, and every word has to.
        assertTrue(topicScore("☕ Café talks", "cafe") > 0)
        assertTrue(topicScore("Season 2", "season 7") == 0.0)
        assertEquals(0.0, topicScore("Season 2", "   "))
        assertEquals(0.0, topicScore("Season 2", "🙂"))
        val hits = topicHits(5.0, "Course", arrayOf(topic(1, "Season 2"), topic(2, "Season 7", closed = true), topic(2, "Season 7"), topic(3, "Chat")), "season", false)
        // A topic listed twice is one hit, and a topic whose name does not answer is none.
        assertEquals(2, hits.size)
        isPlain(hits[0])
        // Telegram's own match is kept when our folding misses it, at the lowest score.
        val kept = topicHits(5.0, "Course", arrayOf(topic(9, "Qwerty")), "zzz", true)
        assertEquals(1.0, kept.single().score)
        assertEquals(0, topicHits(5.0, "Course", arrayOf(topic(9, "Qwerty")), "zzz", false).size)
        // Best first: a topic with videos over one with none, pinned, open, then the chat list's order.
        val ranked = topicRank(
            arrayOf(
                topicHits(6.0, "B", arrayOf(topic(1, "Lessons", hasVideos = false)), "lessons", false)[0],
                topicHits(5.0, "A", arrayOf(topic(1, "Lessons", closed = true)), "lessons", false)[0],
                topicHits(7.0, "C", arrayOf(topic(1, "Lessons", pinned = true)), "lessons", false)[0],
                topicHits(8.0, "D", arrayOf(topic(1, "Lessons")), "lessons", false)[0],
            ),
            arrayOf(8.0, 7.0, 6.0, 5.0),
        )
        assertContentEquals(arrayOf(7.0, 8.0, 5.0, 6.0), ranked.map { it.chatId }.toTypedArray())
        assertFalse(ranked.last().hasVideos)
        // 100 hits are cut to the budget's thirty.
        val many = (1..100).flatMap { topicHits(it.toDouble(), "G$it", arrayOf(topic(1, "Lessons")), "lessons", false).toList() }
        assertEquals(30, topicRank(many.toTypedArray(), emptyArray()).size)
    }

    @Test
    fun `topic candidates are the added groups, the named ones first, and at most twelve`() {
        val chats = arrayOf(forum(1, "Music"), forum(2, "Course Lessons"), forum(3, "Old", archived = true), forum(4, "Other"))
        assertContentEquals(arrayOf(2.0, 1.0, 4.0), topicCandidates(chats, "course lessons", arrayOf(1.0, 2.0, 4.0), false))
        assertContentEquals(arrayOf(2.0, 1.0, 3.0, 4.0), topicCandidates(chats, "course", null, true))
        assertContentEquals(arrayOf(1.0, 2.0, 4.0), topicCandidates(chats, "x", null, false))
        assertEquals(0, topicCandidates(chats, "x", emptyArray(), true).size)
        val big = Array(3000) { forum(it + 1, "Group $it") }
        assertEquals(12, topicCandidates(big, "x", null, true).size)
    }
}
