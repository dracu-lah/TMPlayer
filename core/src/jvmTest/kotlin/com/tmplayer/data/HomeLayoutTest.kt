package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's rows as Settings turns them on and off, and Recently added read from chosen chats. */
class HomeLayoutTest {

    private var nextId = 1L

    private fun video(chatId: Long, name: String = "Holiday ${nextId}x.mp4") = MediaItem(
        chatId = chatId, messageId = nextId, fileId = 1, title = name, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (nextId++).toInt(), fileName = name,
    )

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    private fun resume(item: MediaItem) = ResumeRecord(
        chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title, chatTitle = "",
        sizeBytes = 1, durationSec = 60, positionMs = 10_000, durationMs = 60_000, updatedAt = 1,
    )

    private fun ids(row: HomeRow): List<String> = when (row) {
        is HomeRow.Chat -> row.entries
        is HomeRow.Recent -> row.entries
        else -> emptyList()
    }.flatMap { entry ->
        when (entry) {
            is ShelfEntry.File -> listOf(entry.item.id)
            is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.map { it.id }
        }
    }

    private val chats = listOf(chat(1), chat(2), chat(3))
    private val one = video(1)
    private val loaded = mapOf(1L to listOf(one, video(1)), 2L to listOf(video(2), video(2)), 3L to listOf(video(3)))
    private val recent = loaded.values.flatten()

    private fun build(layout: HomeLayout, home: List<ChatSummary> = chats.take(2), recentFrom: List<Long>? = null, noneChosen: Boolean = false) =
        HomeRows.build(
            continueWatching = listOf(resume(one)), favourites = home, loaded = loaded, recent = recent,
            recentMinStarred = 1, starred = setOf(1, 2), autoFill = true,
            layout = layout, recentFrom = recentFrom, noneChosen = noneChosen,
        )

    @Test
    fun `every row on is Home as it was`() {
        val rows = build(HomeLayout())
        assertEquals(listOf("continue", "recent", "chat-1", "chat-2"), rows.map { it.key })
        assertTrue((rows[1] as HomeRow.Recent).asRow)
    }

    @Test
    fun `a row turned off is not built`() {
        assertEquals(listOf("recent", "chat-1", "chat-2"), build(HomeLayout(continueWatching = false)).map { it.key })
        assertEquals(listOf("continue", "recent"), build(HomeLayout(starredRows = false)).map { it.key })
        // Recently added and the carousel both off: no Recently added at all.
        assertEquals(listOf("continue", "chat-1", "chat-2"), build(HomeLayout(recentlyAdded = false, carousel = false)).map { it.key })
        assertTrue(build(HomeLayout(false, false, false, false)).isEmpty())
        assertTrue(HomeLayout(false, false, false, false).allOff)
    }

    @Test
    fun `the row off with the carousel on is kept for the carousel and not drawn`() {
        val recent = build(HomeLayout(recentlyAdded = false)).filterIsInstance<HomeRow.Recent>().single()
        assertFalse(recent.asRow)
        assertTrue(recent.entries.isNotEmpty())
    }

    @Test
    fun `Continue watching off leaves the video part way through in its chat's row`() {
        val on = build(HomeLayout()).filterIsInstance<HomeRow.Chat>().first { it.chatId == 1L }
        assertFalse(one.id in ids(on))
        val off = build(HomeLayout(continueWatching = false)).filterIsInstance<HomeRow.Chat>().first { it.chatId == 1L }
        assertTrue(one.id in ids(off))
    }

    @Test
    fun `chosen chats feed Recently added alone`() {
        val layout = HomeLayout(recentSource = RecentSource.Chosen, recentChats = setOf(3))
        val rows = build(layout, home = listOf(chat(3)) + chats.take(2), recentFrom = listOf(3))
        val recentRow = rows.filterIsInstance<HomeRow.Recent>().single()
        assertTrue(recentRow.entries.isNotEmpty())
        assertTrue(recentRow.entries.all { it is ShelfEntry.File && it.item.chatId == 3L })
        assertEquals(listOf(3L), recentRow.chatIds)
        // The starred chats keep their rows; the chosen one, not starred, has none.
        assertEquals(listOf(1L, 2L), rows.filterIsInstance<HomeRow.Chat>().map { it.chatId })
    }

    @Test
    fun `none chosen says so in the row's place`() {
        val layout = HomeLayout(recentSource = RecentSource.Chosen)
        val recentRow = build(layout, recentFrom = emptyList(), noneChosen = true).filterIsInstance<HomeRow.Recent>().single()
        assertTrue(recentRow.noneChosen)
        assertTrue(recentRow.loaded)
        assertFalse(recentRow.hasMore)
        // Chosen chats not in the list yet (it is on its way): loading, not the line.
        val waiting = build(layout.copy(recentChats = setOf(9)), recentFrom = emptyList()).filterIsInstance<HomeRow.Recent>().single()
        assertFalse(waiting.loaded)
        assertFalse(waiting.noneChosen)
    }

    @Test
    fun `chosen chats that were left or hidden are skipped, and the list is bounded`() {
        assertEquals(listOf(1L, 3L), HomeLayout.chosenChats(chats, setOf(3, 1, 99)).map { it.id })
        assertTrue(HomeLayout.chosenChats(chats, emptySet()).isEmpty())
        val many = (1L..3_000L).map { chat(it) }
        assertEquals(HomeLayout.CHOSEN_MAX, HomeLayout.chosenChats(many, many.mapTo(HashSet()) { it.id }).size)
    }

    @Test
    fun `stored values read back, and garbage reads as the defaults`() {
        val ids = setOf(5L, -1001234567890L, 42L)
        assertEquals(ids, HomeLayout.decodeChats(HomeLayout.encodeChats(ids)))
        assertEquals(setOf(1L, 2L), HomeLayout.decodeChats(" 1, x,,2\n0\n1e9, 9999999999999999999999"))
        assertTrue(HomeLayout.decodeChats(null).isEmpty())
        assertEquals(RecentSource.Chosen, RecentSource.of(" chosen "))
        assertEquals(RecentSource.Auto, RecentSource.of("{}"))
        assertEquals(HomeLayout(), HomeLayout.of(null, null, null, null, "nonsense", "[]"))
        val huge = (1..20_000).joinToString(",")
        assertEquals(HomeLayout.STORED_MAX, HomeLayout.decodeChats(huge).size)
    }
}
