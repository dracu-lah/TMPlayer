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
        is HomeRow.Chat -> row.entries.flatMap(::idsOf)
        is HomeRow.Recent -> row.entries.flatMap(::idsOf)
    }

    private fun idsOf(entry: ShelfEntry): List<Long> = when (entry) {
        is ShelfEntry.File -> listOf(entry.item.messageId)
        is ShelfEntry.Show -> entry.series.episodes.map { it.item.messageId }
    }

    @Test
    fun `rows come in order continue, starred chats, then recent`() {
        val a = video(1)
        val b = video(2)
        val c = video(3)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(video(9))),
            favourites = listOf(chat(2), chat(1)),
            loaded = mapOf(1L to listOf(a), 2L to listOf(b)),
            recent = listOf(c),
        )
        assertEquals(listOf("continue", "chat-2", "chat-1", "recent"), rows.map { it.key })
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
                is HomeRow.Chat -> row.entries.size
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
        val recent = rows.last() as HomeRow.Recent
        // Nothing from an unstarred chat, nothing that is in Continue, and the search and the
        // chats' own fetches merged without a repeat.
        assertEquals(listOf(newer.messageId, fromSearch.messageId, older.messageId), ids(recent))
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
        assertEquals(listOf("chat-1", "recent"), rows.map { it.key })
        assertFalse((rows[0] as HomeRow.Chat).loaded)
        assertFalse((rows[1] as HomeRow.Recent).loaded)
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

        val small = rows[1] as HomeRow.Chat
        assertFalse(small.hasMore)
        assertEquals(3, small.total)

        val big = rows[2] as HomeRow.Chat
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
}
