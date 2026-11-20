package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {

    private var nextId = 1L

    /** A name with no number in it, so no file is mistaken for an episode unless a test says so. */
    private fun plainName(n: Long) = "Holiday " + ('a' + (n % 26).toInt()) + ('a' + (n / 26 % 26).toInt()) + ".mp4"

    private fun video(chatId: Long, fileName: String = plainName(nextId), date: Int = nextId.toInt()) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    private fun chat(id: Long, title: String = "Chat $id") = ChatSummary(id, title, null, 0, ChatKind.Channel)

    private fun resume(item: MediaItem, updatedAt: Long = 0) = ResumeRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 1,
        title = item.title,
        chatTitle = "",
        sizeBytes = 1,
        durationSec = 60,
        positionMs = 10_000,
        durationMs = 60_000,
        updatedAt = updatedAt,
    )

    private fun ids(row: HomeRow): List<Long> = when (row) {
        is HomeRow.Continue -> row.records.map { it.messageId }
        is HomeRow.WatchLater -> row.records.map { it.messageId }
        is HomeRow.Listed -> row.records.map { it.messageId }
        is HomeRow.Chat -> row.entries.flatMap(::idsOf)
        is HomeRow.NewEpisodes -> row.episodes.map { it.item.messageId }
        is HomeRow.Recent -> row.entries.flatMap(::idsOf)
    }

    private fun idsOf(entry: ShelfEntry): List<Long> = when (entry) {
        is ShelfEntry.File -> listOf(entry.item.messageId)
        is ShelfEntry.Show -> entry.series.episodes.map { it.item.messageId }
    }

    @Test
    fun `rows come in order continue, recent, then starred chats`() {
        val a = video(1)
        val b = video(2)
        val c = video(3)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(video(9))),
            favourites = listOf(chat(2), chat(1)),
            loaded = mapOf(1L to listOf(a), 2L to listOf(b)),
            recent = listOf(c),
        )
        assertEquals(listOf("continue", "recent", "chat-2", "chat-1"), rows.map { it.key })
    }

    @Test
    fun `the recent row waits while a starred chat is still loading, and goes once all are in and empty`() {
        val waiting = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), loaded = mapOf(1L to emptyList()), recent = emptyList(), recentMinStarred = 1)
        val recent = waiting.filterIsInstance<HomeRow.Recent>().single()
        assertEquals(false, recent.loaded)
        val done = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), loaded = mapOf(1L to emptyList(), 2L to emptyList()), recent = emptyList(), recentMinStarred = 1)
        assertEquals(emptyList<HomeRow.Recent>(), done.filterIsInstance<HomeRow.Recent>())
    }

    @Test
    fun `the recent row holds its place while the chat list is still coming in after a cold start`() {
        val kept = listOf(video(1), video(2))
        val pending = HomeRows.build(listOf(resume(video(9))), emptyList(), emptyMap(), recent = kept, recentMinStarred = 1, autoFill = true, chatsPending = true)
        assertEquals(listOf("continue", "recent"), pending.map { it.key })
        assertEquals(false, pending.filterIsInstance<HomeRow.Recent>().single().loaded)
        // Once the chats are known the row is the real one, and with no chats at all it goes.
        val none = HomeRows.build(listOf(resume(video(9))), emptyList(), emptyMap(), recent = kept, recentMinStarred = 1, autoFill = true)
        assertEquals(listOf("continue"), none.map { it.key })
    }

    @Test
    fun `starred chats follow the chat list order, not the order they were starred`() {
        val chats = listOf(chat(5), chat(3), chat(8), chat(1))
        val starred = HomeRows.favouriteChats(chats, linkedSetOf(1L, 8L, 5L))
        assertEquals(listOf(5L, 8L, 1L), starred.map { it.id })
    }

    @Test
    fun `each row is newest first`() {
        val old = video(1, date = 10)
        val new = video(1, date = 30)
        val mid = video(1, date = 20)
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to listOf(old, new, mid)), recent = emptyList())
        assertEquals(listOf(new.messageId, mid.messageId, old.messageId), ids(rows.single()))
    }

    @Test
    fun `no row holds more than ten tiles`() {
        val many = (1..25).map { video(1) }
        val history = (1..14).map { resume(video(4)) }
        val rows = HomeRows.build(history, listOf(chat(1)), mapOf(1L to many), recent = (1..30).map { video(2) })
        rows.forEach { row ->
            val size = when (row) {
                is HomeRow.Continue -> row.records.size
                is HomeRow.WatchLater -> row.records.size
                is HomeRow.Listed -> row.records.size
                is HomeRow.Chat -> row.entries.size
                is HomeRow.NewEpisodes -> row.episodes.size
                is HomeRow.Recent -> row.entries.size
            }
            assertEquals(row.key, HomeRows.LIMIT, size)
        }
    }

    @Test
    fun `a folded show counts as one tile, so a row of ten can hold more than ten files`() {
        // The loose files first, so the show is the newest upload and sits at the front of the row.
        val loose = (1..12).map { video(1) }
        val episodes = (1..8).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to episodes + loose), recent = emptyList())
        val row = rows.single() as HomeRow.Chat
        assertEquals(HomeRows.LIMIT, row.entries.size)
        assertEquals(1, row.entries.count { it is ShelfEntry.Show })
    }

    @Test
    fun `a show's episodes always fold into one tile`() {
        val episodes = (1..3).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to episodes), recent = emptyList())
        assertTrue((rows.single() as HomeRow.Chat).entries.single() is ShelfEntry.Show)
    }

    @Test
    fun `a video part way through is only in continue`() {
        val started = video(1)
        val other = video(1)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(started, other)),
            recent = listOf(started, video(2)),
        )
        val shown = rows.flatMap(::ids)
        assertEquals(1, shown.count { it == started.messageId })
        assertEquals(listOf(started.messageId), ids(rows.first()))
    }

    @Test
    fun `recent holds only the starred chats, newest first across them`() {
        val older = video(1, date = 10)
        val newer = video(2, date = 30)
        val fromSearch = video(2, date = 20)
        val groupChat = video(7, date = 40)
        val started = video(1, date = 50)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to listOf(older, started), 2L to listOf(newer)),
            recent = listOf(groupChat, fromSearch, newer, started),
        )
        assertEquals(listOf("continue", "recent", "chat-1", "chat-2"), rows.map { it.key })
        val recent = rows[1] as HomeRow.Recent
        // Nothing from an unstarred chat, nothing that is in Continue, and the search and the
        // chats' own fetches merged without a repeat.
        assertEquals(listOf(newer.messageId, fromSearch.messageId, older.messageId), ids(recent))
    }

    @Test
    fun `recent takes at most three from one chat while others have videos, newest first`() {
        val flood = (1..12).map { video(1, date = 1000 + it) }
        val other = (1..4).map { video(2, date = 100 + it) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to flood, 2L to other),
            recent = flood + other,
        )
        val recent = rows.filterIsInstance<HomeRow.Recent>().single()
        val items = recent.entries.filterIsInstance<ShelfEntry.File>().map { it.item }
        assertEquals(HomeRows.LIMIT, items.size)
        // Three each first, then topped up one more per chat: chat 2 is never crowded out.
        assertEquals(4, items.count { it.chatId == 2L })
        assertEquals(6, items.count { it.chatId == 1L })
        assertEquals(items.sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }), items)
            }

    @Test
    fun `recent is still full when one chat is all there is`() {
        val flood = (1..12).map { video(1, date = 1000 + it) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to flood, 2L to emptyList()),
            recent = flood,
        )
        assertEquals(HomeRows.LIMIT, rows.filterIsInstance<HomeRow.Recent>().single().entries.size)
    }

    @Test
    fun `recent needs two starred chats, as one already has its own row`() {
        val one = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to listOf(video(1))), recent = listOf(video(1)))
        assertEquals(listOf("chat-1"), one.map { it.key })
        val loading = HomeRows.build(emptyList(), listOf(chat(1)), emptyMap(), recent = null)
        assertEquals(listOf("chat-1"), loading.map { it.key })
    }

    @Test
    fun `no favourites means no chat rows and no recent`() {
        val rows = HomeRows.build(emptyList(), favourites = emptyList(), loaded = emptyMap(), recent = listOf(video(1), video(2)))
        assertTrue(rows.isEmpty())
        assertTrue(HomeRows.favouriteChats(listOf(chat(1)), emptySet()).isEmpty())
    }

    @Test
    fun `a starred chat still loading is a placeholder row, and one with nothing is left out`() {
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(2L to emptyList()),
            recent = null,
        )
        assertEquals(listOf("recent", "chat-1"), rows.map { it.key })
        assertFalse((rows[0] as HomeRow.Recent).loaded)
        assertFalse((rows[1] as HomeRow.Chat).loaded)
    }

    @Test
    fun `nothing anywhere is no rows at all`() {
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = emptyList()).isEmpty())
    }

    @Test
    fun `See all is offered only when a row leaves something out`() {
        val few = (1..3).map { video(1) }
        val many = (1..14).map { video(2) }
        val history = (1..12).map { resume(video(3)) }
        val rows = HomeRows.build(history, listOf(chat(1), chat(2)), mapOf(1L to few, 2L to many), recent = emptyList())

        val resume = rows[0] as HomeRow.Continue
        assertTrue(resume.hasMore)
        assertEquals(12, resume.total)
        assertEquals(2, resume.more.size)

        val chats = rows.filterIsInstance<HomeRow.Chat>()
        val small = chats[0]
        assertFalse(small.hasMore)
        assertEquals(3, small.total)

        val big = chats[1]
        assertTrue(big.hasMore)
        assertEquals(14, big.total)
        assertFalse(big.totalAtLeast)
        // The four oldest, newest first: what the tile's picture is made of.
        assertEquals(many.take(4).map { it.id }.reversed(), big.more.map { it.id })
    }

    @Test
    fun `a chat fetched only in part still offers See all, and counts at least what it has`() {
        val few = (1..3).map { video(1) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to few), recent = emptyList(), partial = setOf(1L))
        val row = rows[0] as HomeRow.Chat
        assertTrue(row.more.isEmpty())
        assertTrue(row.hasMore)
        assertTrue(row.totalAtLeast)
    }

    @Test
    fun `watch later follows Continue, leaves out what was started, and is not repeated below`() {
        val saved = (1..3).map { video(1) }
        val started = saved[0]
        val later = saved.mapIndexed { i, item -> WatchLaterRecord.of(item, "Chat 1", addedAt = 10L - i) }
        val rows = HomeRows.build(
            listOf(resume(started)),
            listOf(chat(1)),
            mapOf(1L to saved + video(1)),
            recent = emptyList(),
            watchLater = later,
        )
        assertEquals(listOf("continue", "watch-later", "chat-1"), rows.map { it.key })
        assertEquals(saved.drop(1).map { it.messageId }, ids(rows[1]))
        assertEquals(1, ids(rows[2]).size)
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = emptyList(), watchLater = emptyList()).isEmpty())
    }

    @Test
    fun `two copies of one film in continue watching are one card, the one watched last`() {
        val hd = video(1, "Bethlehem Kudumba Unit (2024) 1080p WEB-DL x264.mkv")
        val sd = video(1, "Bethlehem Kudumba Unit (2024) 720p WEB-DL x264.mkv")
        val other = video(1, "Night Train (2019) 1080p.mkv")
        val rows = HomeRows.build(
            continueWatching = listOf(resume(sd, updatedAt = 30), resume(other, updatedAt = 20), resume(hd, updatedAt = 10)),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(hd, sd, other)),
            recent = null,
        )
        val continuing = rows.first() as HomeRow.Continue
        assertEquals(listOf(sd.messageId, other.messageId), continuing.records.map { it.messageId })
        // The other copy is not offered again in the chat's row either.
        assertTrue(rows.drop(1).none { row -> hd.messageId in ids(row) })
    }

    @Test
    fun `continue watching keeps apart two copies the viewer said are not the same video`() {
        val hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val sd = video(2, "Loki.S02E05.720p.WEB.x264.mkv")
        // An old record keeps only its title; a new one its file name too.
        val records = listOf(resume(hd, updatedAt = 20), resume(sd, updatedAt = 10).copy(fileName = sd.fileName))
        try {
            assertEquals(1, HomeRows.foldCopies(records).size)
            VideoCorrections.set(mapOf(hd.id to VideoCorrection(separate = true)))
            assertEquals(2, HomeRows.foldCopies(records).size)
        } finally {
            VideoCorrections.set(emptyMap())
        }
    }

    @Test
    fun `the watched list folds copies like continue watching, and unmarking the card covers them all`() {
        val hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val sd = video(2, "Loki.S02E05.720p.WEB.x264.mkv")
        val next = video(1, "Loki.S02E06.720p.WEB.x264.mkv")
        val records = listOf(
            WatchedRecord.of(hd, "Chat 1", watchedAt = 30, manual = false),
            WatchedRecord.of(next, "Chat 1", watchedAt = 20, manual = false),
            WatchedRecord.of(sd, "Chat 2", watchedAt = 10, manual = true),
        )
        assertEquals(listOf(hd.messageId, next.messageId), HomeRows.foldWatched(records).map { it.messageId })
        val card = HomeRows.watchedItem(records[0], records)
        assertEquals(setOf(hd.messageId, sd.messageId), card.versions.map { it.messageId }.toSet())
        assertTrue(HomeRows.watchedItem(records[1], records).versions.isEmpty())
    }

    @Test
    fun `copies of one episode fold, in this chat and others, and other episodes stay apart`() {
        val e1hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val e1sd = video(1, "Loki.S02E05.720p.WEB.x264.mkv")
        val e2 = video(1, "Loki.S02E06.720p.WEB.x264.mkv")
        val elsewhere = video(2, "Loki.S02E05.480p.mkv")
        val clip = video(1, "Holiday clip.mp4")
        val clip2 = video(1, "Holiday clip.mp4")
        val records = listOf(
            resume(e1hd, updatedAt = 50),
            resume(e2, updatedAt = 40),
            resume(e1sd, updatedAt = 30),
            resume(elsewhere, updatedAt = 20),
            resume(clip, updatedAt = 10),
            resume(clip2, updatedAt = 5),
        )
        val folded = HomeRows.foldCopies(records)
        // The 480p posted in another chat is the same episode: one card for it, as for the 720p.
        assertEquals(listOf(e1hd, e2, clip, clip2).map { it.messageId }, folded.map { it.messageId })
        // Out of order, the newest copy still wins, in the place the first of them held.
        val kept = HomeRows.foldCopies(listOf(resume(e1sd, updatedAt = 1), resume(e1hd, updatedAt = 9)))
        assertEquals(listOf(e1hd.messageId), kept.map { it.messageId })
        // Removing the folded card takes every copy, and nothing else.
        assertEquals(
            setOf(e1hd.messageId, e1sd.messageId, elsewhere.messageId),
            HomeRows.copiesOf(records[0], records).map { it.messageId }.toSet(),
        )
        assertEquals(listOf(clip.messageId), HomeRows.copiesOf(records[4], records).map { it.messageId })
    }

    @Test
    fun `a film started in one copy keeps its other copies out of the rows below`() {
        val sd = video(1, "Thunderbolts.2025.720p.HS.WEB-DL.x264.mkv")
        val hd = video(1, "Thunderbolts.2025.1080p.HS.WEB-DL.x265.mkv")
        val e1 = video(1, "Loki.S01E01.720p.mkv")
        val e1hd = video(1, "Loki.S01E01.1080p.mkv")
        val e2 = video(1, "Loki.S01E02.720p.mkv")
        val other = video(1, "Night Train (2019) 1080p.mkv")
        val loaded = listOf(sd, hd, e1, e1hd, e2, other)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(sd, updatedAt = 20), resume(e1, updatedAt = 10)),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to loaded, 2L to emptyList()),
            recent = loaded,
        )
        val below = rows.drop(1).flatMap(::ids)
        assertFalse(hd.messageId in below)
        assertTrue(other.messageId in below)
        // An episode in progress still counts towards its show below, and is never a tile alone.
        val show = rows.drop(1).flatMap { row ->
            when (row) {
                is HomeRow.Recent -> row.entries
                is HomeRow.Chat -> row.entries
                else -> emptyList()
            }
        }.filterIsInstance<ShelfEntry.Show>().first()
        assertEquals(2, show.series.episodeCount)
        assertTrue(rows.drop(1).none { row -> row is HomeRow.Recent && row.entries.any { it is ShelfEntry.File && it.item.messageId == e1.messageId } })
    }
}
