package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSearchTest {

    private fun topic(id: Int, name: String, pinned: Boolean = false, closed: Boolean = false, videos: Boolean = true) =
        ForumTopicSummary(id = id, name = name, isPinned = pinned, isClosed = closed, hasVideos = videos)

    private fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Group, archived: Boolean = false) =
        ChatSummary(id = id, title = title, miniThumbnail = null, photoFileId = 0, kind = kind, isArchived = archived)

    @Test
    fun `case, accents and emoji fold away`() {
        assertTrue(TopicSearch.score("☕ Café Talks", "cafe") > 0)
        assertTrue(TopicSearch.score("MALAYALAM MOVIES", "malayalam") > 0)
        assertTrue(TopicSearch.score("🎬 Movies", "movies") > 0)
        assertTrue(TopicSearch.score("Pokémon S01", "pokemon s01") > 0)
        assertEquals(0, TopicSearch.score("Rules", "season"))
    }

    @Test
    fun `a blank or emoji-only query matches nothing`() {
        assertEquals(0, TopicSearch.score("Season 1", ""))
        assertEquals(0, TopicSearch.score("Season 1", "   "))
        assertEquals(0, TopicSearch.score("🎬 Movies", "🎬"))
    }

    @Test
    fun `an exact name outranks a part of one`() {
        assertTrue(TopicSearch.score("Season 2", "season 2") > TopicSearch.score("Season 2 extras and more", "seas"))
    }

    @Test
    fun `only allowed groups are asked, and ones known to have no topics are skipped`() {
        val chats = listOf(
            chat(1, "News", kind = ChatKind.Channel),
            chat(2, "Course group"),
            chat(3, "Locked group"),
            chat(4, "Plain group"),
            chat(5, "Friend", kind = ChatKind.Direct),
            chat(6, "Old group", archived = true),
        )
        val asked = TopicSearch.candidates(chats, "x", allows = { it != 3L }, forum = { if (it == 4L) false else null })
        assertEquals(listOf(2L, 6L), asked.map { it.id })
        val noArchive = TopicSearch.candidates(chats, "x", allows = { true }, forum = { null }, includeArchived = false)
        assertEquals(listOf(2L, 3L, 4L), noArchive.map { it.id })
    }

    @Test
    fun `groups whose own title answers the words are asked first, the rest keep the list's order`() {
        val chats = listOf(chat(1, "Alpha"), chat(2, "Beta"), chat(3, "Season club"), chat(4, "Gamma"))
        assertEquals(listOf(3L, 1L, 2L, 4L), TopicSearch.candidates(chats, "season", { true }, { null }).map { it.id })
    }

    @Test
    fun `hits keep Telegram's own matches, ours need a score`() {
        val topics = listOf(topic(1, "Season 1"), topic(2, "Rules"), topic(1, "Season 1"))
        assertEquals(listOf(1), TopicSearch.hits(9, "Club", topics, "season", fromTelegram = false).map { it.topic.id })
        // Telegram matched "Rules" on its side (a spelling we do not fold): kept, last.
        val told = TopicSearch.hits(9, "Club", topics, "season", fromTelegram = true)
        assertEquals(listOf(1, 2), told.map { it.topic.id })
        assertEquals(1, told.last().score)
    }

    @Test
    fun `rank puts videos, pins and open topics first, then the chat list's order`() {
        val hits = listOf(
            TopicHit(1, "A", topic(1, "Season 1", videos = false), 10),
            TopicHit(2, "B", topic(1, "Season 1", closed = true), 10),
            TopicHit(3, "C", topic(1, "Season 1"), 10),
            TopicHit(4, "D", topic(1, "Season 1", pinned = true), 10),
            TopicHit(5, "E", topic(1, "Season 1"), 10),
        )
        val ranked = TopicSearch.rank(hits, chatOrder = mapOf(5L to 0, 3L to 1))
        assertEquals(listOf(4L, 5L, 3L, 2L, 1L), ranked.map { it.chatId })
    }

    @Test
    fun `the same topic name in many chats is one hit per chat, capped`() {
        val hits = (1L..100L).map { TopicHit(it, "Chat $it", topic(7, "Season 1"), 10) } + TopicHit(1, "Chat 1", topic(7, "Season 1"), 10)
        val ranked = TopicSearch.rank(hits)
        assertEquals(TopicSearch.MAX_HITS, ranked.size)
        assertEquals(ranked.size, ranked.map { it.key }.distinct().size)
    }

    @Test
    fun `untitled topics are matched by the name they are shown under`() {
        val untitled = topic(3, "Untitled topic")
        assertEquals(1, TopicSearch.hits(1, "C", listOf(untitled), "untitled", fromTelegram = false).size)
    }

    @Test
    fun `chips narrow to the matches and keep the chosen tab`() {
        val topics = (1..30).map { topic(it, if (it % 2 == 0) "Season $it" else "Lesson $it") }
        assertEquals(topics, TopicSearch.chips(topics, "", selected = 0))
        val narrowed = TopicSearch.chips(topics, "lesson", selected = 4)
        assertEquals(4, narrowed.first().id)
        assertTrue(narrowed.drop(1).all { it.name.startsWith("Lesson") })
        assertTrue(TopicSearch.chips(topics, "zzzz", selected = 0).isEmpty())
    }

    @Test
    fun `the field shows past twenty topics only`() {
        assertFalse(TopicSearch.chipSearchWanted(20))
        assertTrue(TopicSearch.chipSearchWanted(21))
    }
}
