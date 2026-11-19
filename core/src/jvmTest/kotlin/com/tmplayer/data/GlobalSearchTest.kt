package com.tmplayer.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class GlobalSearchTest {

    private fun video(
        chatId: Long,
        messageId: Long,
        fileName: String,
        date: Int = messageId.toInt(),
        size: Long = 900 * SizeFilter.MB,
        duration: Int = 6_000,
        caption: String = "",
        onDevice: Boolean = false,
    ) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = messageId.toInt(),
        title = fileName,
        sizeBytes = size,
        durationSec = duration,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
        onDevice = onDevice,
    )

    private fun chat(id: Long, title: String) = ChatSummary(id, title, null, 0, ChatKind.Channel)

    private fun q(text: String) = GlobalSearch.parse(text, maxYear = 2026)

    // ---- reading the query ----------------------------------------------------------------

    @Test
    fun `cleans spacing, pasted links and a leading handle`() {
        assertEquals("night train", GlobalSearch.clean("  night \t  train "))
        assertEquals("night train", GlobalSearch.clean("@moviechannel night train"))
        assertEquals("moviechannel", GlobalSearch.clean("@moviechannel"))
        assertEquals("night train", GlobalSearch.clean("https://t.me/somechannel/42 night train"))
        assertEquals("", GlobalSearch.clean("t.me/somechannel"))
        assertEquals(GlobalSearch.MAX_QUERY, GlobalSearch.clean("a".repeat(200)).length)
    }

    @Test
    fun `one letter is not searched, one ideograph is`() {
        assertFalse(GlobalSearch.searchable("a"))
        assertTrue(GlobalSearch.searchable("ab"))
        assertTrue(GlobalSearch.searchable("海"))
        assertTrue(q("x").isEmpty)
    }

    @Test
    fun `a quality alone is a filter, a year alone is still a title`() {
        val year = q("1917")
        assertEquals(1917, year.year)
        assertFalse(year.filterOnly)
        assertEquals("1917", year.server)
        assertEquals(listOf("1917"), year.titleWords)

        val quality = q("1080p")
        assertEquals(SearchQuality.FullHd, quality.quality)
        assertTrue(quality.filterOnly)

        val mixed = q("dune 1080p")
        assertEquals("dune", mixed.server)
        assertEquals(SearchQuality.FullHd, mixed.quality)
        assertFalse(mixed.filterOnly)

        // A year among other words stays part of the title: "1917" and "2049" are in names.
        val titled = q("blade runner 2049")
        assertEquals("blade runner 2049", titled.server)
        assertEquals(null, titled.year)

        // "hd" is in HDRip and HDTV, and names no picture size.
        assertEquals(SearchQuality.Any, q("night train hd").quality)
        assertEquals("night train hd", q("night train hd").server)
    }

    @Test
    fun `underscored and dotted spellings for a query of several words`() {
        assertEquals(listOf("harbour_notes", "harbour.notes"), GlobalSearch.variants("Harbour Notes"))
        assertTrue(GlobalSearch.variants("harbour").isEmpty())
    }

    // ---- local matching -------------------------------------------------------------------

    @Test
    fun `local fallback finds a file Telegram's word search misses`() {
        val known = listOf(
            video(1, 1, "Harbour_Notes_2024_1080p.mkv"),
            video(1, 2, "Ledger.of.Small.Hours.2019.720p.mkv"),
            video(2, 3, "IMG_2231.mp4", caption = "Harbour notes, the director's talk"),
        )
        val found = GlobalSearch.localVideos(known, q("harbour")).map { it.messageId }
        assertEquals(listOf(1L, 3L), found)
        // Case, accents and a typo are forgiven.
        assertEquals(listOf(2L), GlobalSearch.localVideos(known, q("LEDGER of smal hours")).map { it.messageId })
        // An online title the file name does not carry.
        val named = GlobalSearch.localVideos(known, q("quiet harbour")) { if (it.messageId == 2L) listOf("The Quiet Harbour") else emptyList() }
        assertTrue(2L in named.map { it.messageId })
    }

    @Test
    fun `local fallback keeps to the kids lock and lists each video once`() {
        val known = listOf(video(1, 1, "Harbour.Notes.2024.mkv"), video(1, 1, "Harbour.Notes.2024.mkv"), video(5, 9, "Harbour.Notes.2024.mkv"))
        assertEquals(listOf(1L), GlobalSearch.localVideos(known, q("harbour"), allowed = setOf(1L)).map { it.chatId })
    }

    @Test
    fun `a year alone ranks the titles holding it first, then films of that year`() {
        val found = listOf(
            video(1, 1, "Harbour.Days.1917.Remastered.mkv", date = 90),
            video(1, 2, "1917.2019.1080p.mkv", date = 10),
            video(1, 3, "Ledger.of.Small.Hours.1917.mkv", date = 50),
        )
        val groups = GlobalSearch.merge(emptyList(), found, q("1917"))
        assertEquals(2L, groups.first().lead.messageId)
        assertEquals(3, groups.size)
    }

    @Test
    fun `local and server results merge by message id`() {
        val local = video(1, 1, "Night.Train.2019.1080p.mkv")
        val first = GlobalSearch.merge(emptyList(), listOf(local), q("night train"))
        val again = GlobalSearch.merge(first, listOf(local, video(2, 7, "Night.Train.2019.720p.mkv")), q("night train"))
        assertEquals(1, again.size)
        assertEquals(2, again.single().copies.size)
    }

    // ---- grouping and ranking -------------------------------------------------------------

    @Test
    fun `copies of a film across chats are one card, best copy first`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 10, "Night.Train.2019.720p.mkv", date = 30),
                video(2, 11, "Night.Train.2019.2160p.mkv", date = 10),
                video(3, 12, "Night.Train.2019.1080p.mkv", date = 20, onDevice = true),
            ),
            q("night train"),
        )
        val card = groups.single()
        // On this device first, then the picture a phone suits (up to 1080p); the 4K stays selectable after (HX38).
        assertEquals(listOf(12L, 10L, 11L), card.copies.map { it.messageId })
        assertEquals(listOf(3L, 1L, 2L), card.chatIds)
        assertEquals("4K", card.bestQuality)
        assertEquals(3, card.asItem().versions.size)
    }

    @Test
    fun `copies lead with the preferred audio language the name carries, then names with none, then other languages`() {
        val tamil = video(1, 10, "Night.Train.2022.1080p.Tamil.WEB-DL.mkv", date = 30)
        val hindi = video(2, 11, "Night.Train.2022.720p.Hindi.WEB-DL.mkv", date = 20)
        val plain = video(3, 12, "Night.Train.2022.1080p.WEB-DL.mkv", date = 10)
        val copies = listOf(tamil, plain, hindi)
        // No preference: the best picture then the newest, as before.
        assertEquals(listOf(10L, 12L, 11L), GlobalSearch.copyOrder(copies).map { it.messageId })
        // Hindi wanted: the Hindi copy even at 720p, then the copy naming no language, then the Tamil one.
        assertEquals(listOf(11L, 12L, 10L), GlobalSearch.copyOrder(copies, "hi").map { it.messageId })
        assertEquals(listOf(10L, 12L, 11L), GlobalSearch.copyOrder(copies, "ta").map { it.messageId })
        // A copy on this device still leads, whatever its language.
        val local = video(4, 13, "Night.Train.2022.720p.Tamil.mkv", onDevice = true)
        assertEquals(13L, GlobalSearch.copyOrder(copies + local, "hi").first().messageId)
        // Two audio tracks named: either wanted language finds it.
        val dual = video(5, 14, "Night.Train.2022.480p.Hindi.Tamil.mkv", date = 1)
        assertEquals(listOf(10L, 14L, 12L, 11L), GlobalSearch.copyOrder(copies + dual, "ta").map { it.messageId })
    }

    @Test
    fun `the identity with a preferred language orders merged cards by it, and without one is the default`() {
        val tamil = video(1, 10, "Night.Train.2022.1080p.Tamil.mkv")
        val hindi = video(2, 11, "Night.Train.2022.720p.Hindi.mkv")
        assertTrue(SearchIdentity.preferring(SearchIdentity.Default, null) === SearchIdentity.Default)
        val groups = GlobalSearch.merge(emptyList(), listOf(tamil, hindi), q("night train"), SearchSort.BestMatch, SearchIdentity.preferring(SearchIdentity.Default, "hi"))
        assertEquals(1, groups.size)
        assertEquals(11L, groups.single().lead.messageId)
    }

    @Test
    fun `the count line counts videos once and the chats they are in, a show by its episodes`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 10, "Night.Train.2022.1080p.mkv"),
                video(2, 11, "Night.Train.2022.720p.mkv"),
                video(1, 20, "Harbour.Notes.S01E01.mkv"),
                video(3, 21, "Harbour.Notes.S01E02.mkv"),
            ),
            q("night"),
        )
        val tally = GlobalSearch.tally(groups)
        assertEquals(3, tally.videos)
        assertEquals(3, tally.chats)
        assertEquals(GlobalSearch.Tally(0, 0), GlobalSearch.tally(emptyList()))
    }

    @Test
    fun `a name alone does not make copies across chats`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                // No year: two lectures that happen to share a name.
                video(1, 1, "Lecture.1080p.mp4", duration = 3_000),
                video(2, 2, "Lecture.1080p.mp4", duration = 3_000),
                // Same name and year, lengths far apart: a different cut is not a copy.
                video(3, 3, "Night.Train.2019.1080p.mkv", duration = 6_000),
                video(4, 4, "Night.Train.2019.1080p.mkv", duration = 9_000),
            ),
            q("lecture"),
        )
        assertEquals(4, groups.size)
    }

    @Test
    fun `the same file forwarded to two chats is one card`() {
        val a = video(1, 1, "IMG_2231.mp4")
        val b = video(2, 2, "IMG_2231.mp4").copy(fileId = a.fileId)
        val identity = object : SearchIdentity by SearchIdentity.Default {
            override fun bucket(item: MediaItem) = item.fileName
        }
        assertEquals(1, GlobalSearch.merge(emptyList(), listOf(a, b), q("img"), identity = identity).size)
    }

    @Test
    fun `a trailer never folds into the film and ranks after it`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "Night.Train.2019.Trailer.1080p.mp4", date = 50, duration = 140),
                video(1, 2, "Night.Train.2019.1080p.mkv", date = 10),
                video(2, 3, "Night.Train.2019.mp4", date = 40, duration = 300, size = 40 * SizeFilter.MB),
            ),
            q("night train"),
        )
        assertEquals(listOf(listOf(2L)), groups.filterNot { it.extra }.map { g -> g.copies.map { it.messageId } })
        assertTrue(groups.first().copies.single().messageId == 2L)
        assertTrue(groups.drop(1).all { it.extra })
    }

    @Test
    fun `exact title beats prefix beats every word beats a caption mention`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "IMG_0001.mp4", caption = "we talked about the night train", date = 90),
                video(1, 2, "The.Last.Night.Train.Home.2021.mkv", date = 80),
                video(1, 3, "Night.Train.Express.2020.mkv", date = 70),
                video(1, 4, "Night.Train.2019.mkv", date = 60),
            ),
            q("night train"),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), groups.map { it.lead.messageId })
    }

    @Test
    fun `paging never moves a card already on screen`() {
        val first = GlobalSearch.merge(emptyList(), listOf(video(1, 1, "Night.Train.Express.2020.mkv")), q("night train"))
        val next = GlobalSearch.merge(first, listOf(video(1, 2, "Night.Train.2019.mkv")), q("night train"))
        assertEquals(listOf(1L, 2L), next.map { it.lead.messageId })
    }

    @Test
    fun `other sorts reorder what came back`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "Alpha.Story.2019.720p.mkv", date = 10, size = 3 * SizeFilter.GB),
                video(1, 2, "Beta.Story.2020.2160p.mkv", date = 5, size = 1 * SizeFilter.GB),
                video(1, 3, "Gamma.Story.2021.1080p.mkv", date = 30, size = 2 * SizeFilter.GB),
            ),
            q("story"),
        )
        assertEquals(listOf(3L, 1L, 2L), GlobalSearch.order(groups, q("story"), SearchSort.Newest).map { it.lead.messageId })
        // A page arriving under another sort is sorted in with the rest, not after it.
        val more = GlobalSearch.merge(groups, listOf(video(1, 4, "Delta.Story.2022.mkv", date = 99)), q("story"), SearchSort.Newest)
        assertEquals(4L, more.first().lead.messageId)
        assertEquals(listOf(2L, 3L, 1L), GlobalSearch.order(groups, q("story"), SearchSort.BestQuality).map { it.lead.messageId })
        assertEquals(listOf(1L, 3L, 2L), GlobalSearch.order(groups, q("story"), SearchSort.Largest).map { it.lead.messageId })
    }

    @Test
    fun `episodes of one show are one card, whichever chats they are in`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "Harbour.Notes.S01E01.1080p.mkv"),
                video(1, 2, "Harbour.Notes.S01E02.1080p.mkv"),
                video(2, 3, "Harbour.Notes.S01E01.720p.mkv"),
            ),
            q("harbour notes"),
        )
        val show = groups.single()
        assertTrue(show.isShow)
        assertEquals(2, show.episodeCount)
        assertEquals(listOf(1L, 2L), show.chatIds.sorted())
    }

    @Test
    fun `a season pack and a file matched to the show join the show's card, on a later page too`() {
        val matched = object : SearchIdentity by SearchIdentity.Default {
            override fun onlineKey(item: MediaItem): String? =
                if (item.fileName.startsWith("Wednesday") || item.fileName.startsWith("wed_")) "tmdb:show:119051" else null
        }
        val first = GlobalSearch.merge(
            emptyList(),
            listOf(video(2, 9, "wed_hd_upload.mkv")),
            q("wednesday"),
            identity = matched,
        )
        assertEquals(1, first.size)
        val groups = GlobalSearch.merge(
            first,
            listOf(
                video(1, 1, "Wednesday.S01E01.1080p.mkv"),
                video(1, 2, "Wednesday.S01E02.1080p.mkv"),
                video(3, 3, "Wednesday.Season.1.Complete.720p.mkv"),
            ),
            q("wednesday"),
            identity = matched,
        )
        val show = groups.single()
        assertTrue(show.isShow)
        assertEquals(setOf(1L, 2L, 3L, 9L), show.copies.map { it.messageId }.toSet())
    }

    @Test
    fun `one film under two names, with its year only in a caption, is one card led by the best copy`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "Khalifa.mkv", caption = "Khalifa (2026)", duration = 9_000),
                video(2, 2, "Khalifa 2026 1080p WEB-DL.mkv", duration = 8_100),
            ),
            q("khalifa"),
        )
        val film = groups.single()
        assertEquals(2L, film.lead.messageId)
        assertEquals(2, film.copies.size)
    }

    // ---- filters --------------------------------------------------------------------------

    @Test
    fun `quality and small clip filters keep the copies that pass, in place`() {
        val groups = GlobalSearch.merge(
            emptyList(),
            listOf(
                video(1, 1, "Night.Train.2019.720p.mkv", size = 30 * SizeFilter.MB),
                video(2, 2, "Night.Train.2019.1080p.mkv"),
                video(1, 3, "Ledger.of.Small.Hours.2019.720p.mkv"),
            ),
            q("2019"),
        )
        val hd = GlobalSearch.filter(groups, SearchFilters(quality = SearchQuality.FullHd))
        assertEquals(listOf(2L), hd.flatMap { it.copies }.map { it.messageId })
        val big = GlobalSearch.filter(groups, SearchFilters(hideSmall = true))
        assertEquals(setOf(2L, 3L), big.flatMap { it.copies }.map { it.messageId }.toSet())
        // A quality named in the query filters too.
        assertEquals(listOf(2L), GlobalSearch.filter(groups, SearchFilters(), q("1080p")).flatMap { it.copies }.map { it.messageId })
    }

    @Test
    fun `says which filter hides everything`() {
        val groups = GlobalSearch.merge(emptyList(), listOf(video(1, 1, "Night.Train.2019.720p.mkv")), q("night train"))
        assertEquals(
            listOf(HidingFilter.Quality),
            GlobalSearch.hiddenBy(groups, SearchFilters(quality = SearchQuality.UHD), q("night train")),
        )
        assertEquals(
            listOf(HidingFilter.ChatType),
            GlobalSearch.hiddenBy(emptyList(), SearchFilters(chatType = SearchChatType.Groups), q("night train")),
        )
    }

    @Test
    fun `chats match fuzzily, starred first on a tie`() {
        val chats = listOf(chat(1, "Film Club"), chat(2, "Film Club"), chat(3, "Cooking"))
        assertEquals(listOf(2L, 1L), GlobalSearch.localChats(chats, "film club", favourites = setOf(2L)).map { it.id })
        assertEquals(listOf(3L), GlobalSearch.localChats(chats, "cookng").map { it.id })
    }

    @Test
    fun `the first answer asks again while it is thin`() {
        assertTrue(GlobalSearch.wantsMore(groups = 3, pagesAsked = 1, done = false))
        assertFalse(GlobalSearch.wantsMore(groups = 3, pagesAsked = 3, done = false))
        assertFalse(GlobalSearch.wantsMore(groups = 12, pagesAsked = 1, done = false))
        assertFalse(GlobalSearch.wantsMore(groups = 3, pagesAsked = 1, done = true))
    }
}
