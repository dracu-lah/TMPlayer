package com.tmplayer.data

import com.tmplayer.online.MetaImageSize
import com.tmplayer.online.MetaPoster
import com.tmplayer.online.MetaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Home filled from the chats with videos, its movies and shows apart, its cache, and title logos. */
class HomeAutoFillTest {

    private var nextId = 1L

    private fun plainName(n: Long) = "Holiday " + ('a' + (n % 26).toInt()) + ('a' + (n / 26 % 26).toInt()) + ".mp4"

    private fun video(chatId: Long, fileName: String = plainName(nextId), date: Int = nextId.toInt()) = MediaItem(
        chatId = chatId, messageId = nextId++, fileId = 1, title = fileName, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = date, fileName = fileName,
    )

    private fun chat(id: Long, archived: Boolean = false) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel, isArchived = archived)

    @Test
    fun `Home picks the chats with the most videos after the starred ones`() {
        val chats = listOf(chat(1), chat(2), chat(3), chat(4, archived = true), chat(5))
        val recent = listOf(video(2), video(3), video(3), video(3), video(4), video(4), video(4), video(4), video(9))
        val home = HomeRows.homeChats(chats, favourites = setOf(5), recent = recent)
        // Starred first; then by count; the archived chat and one not in the list are never picked.
        assertEquals(listOf(5L, 3L, 2L), home.map { it.id })
        // Before the search answers, only the starred chats.
        assertEquals(listOf(5L), HomeRows.homeChats(chats, setOf(5), recent = null).map { it.id })
        assertEquals(1, HomeRows.homeChats(chats, emptySet(), recent, limit = 1).size)
    }

    @Test
    fun `Saved Messages is always one of Home's chats, after the starred ones`() {
        val saved = ChatSummary(7, "Saved Messages", null, 0, ChatKind.Saved)
        val chats = listOf(chat(1), chat(2), saved, chat(5))
        // Nothing of it among the newest videos across the account, as with a library kept there.
        val recent = listOf(video(1), video(2), video(2))
        assertEquals(listOf(5L, 7L, 2L, 1L), HomeRows.homeChats(chats, favourites = setOf(5), recent = recent).map { it.id })
        // Known before the search answers, so its videos are asked for at once.
        assertEquals(listOf(7L), HomeRows.homeChats(chats, emptySet(), recent = null).map { it.id })
        // Starred, it keeps its starred place and is not listed twice.
        assertEquals(listOf(7L, 2L, 1L), HomeRows.homeChats(chats, setOf(7), recent).map { it.id })
    }

    @Test
    fun `pinned chats are read after Saved Messages, before the busiest ones`() {
        val pinned = ChatSummary(8, "Pinned shows", null, 0, ChatKind.Channel, isPinned = true)
        val archivedPin = ChatSummary(9, "Old pin", null, 0, ChatKind.Channel, isPinned = true, isArchived = true)
        val chats = listOf(chat(1), chat(2), pinned, archivedPin)
        val recent = listOf(video(1), video(2), video(2))
        assertEquals(listOf(8L, 2L, 1L), HomeRows.homeChats(chats, emptySet(), recent).map { it.id })
        assertEquals(listOf(8L), HomeRows.homeChats(chats, emptySet(), recent = null).map { it.id })
    }

    @Test
    fun `nothing starred and the search still running holds the carousel's place`() {
        val rows = HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = null, recentMinStarred = 1, autoFill = true)
        val recent = rows.single() as HomeRow.Recent
        assertFalse(recent.loaded)
        // Without auto fill an empty Home is the first-run state.
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = null, recentMinStarred = 1).isEmpty())
    }

    @Test
    fun `Recently added keeps the plain heading while Home's chats are not known`() {
        val chats = listOf(chat(1), chat(2))
        val a = video(1)
        val b = video(2)
        // The search across chats is still running: only the starred chat is picked so far.
        val early = HomeRows.homeChats(chats, favourites = setOf(1), recent = null)
        val first = HomeRows.build(
            emptyList(), early, mapOf(1L to listOf(a)), recent = null, recentMinStarred = 1,
            starred = setOf(1), autoFill = true,
        ).filterIsInstance<HomeRow.Recent>().single()
        assertFalse(first.starredOnly)
        // The chat list is not in yet, only the last run's videos.
        val pending = HomeRows.build(
            emptyList(), emptyList(), emptyMap(), recent = listOf(a, b), recentMinStarred = 1,
            starred = setOf(1), autoFill = true, chatsPending = true,
        ).filterIsInstance<HomeRow.Recent>().single()
        assertFalse(pending.starredOnly)
        // Once known, the busiest chats join and the heading stays the plain one: no flip.
        val home = HomeRows.homeChats(chats, favourites = setOf(1), recent = listOf(a, b))
        val known = HomeRows.build(
            emptyList(), home, mapOf(1L to listOf(a), 2L to listOf(b)), recent = listOf(a, b), recentMinStarred = 1,
            starred = setOf(1), autoFill = true,
        ).filterIsInstance<HomeRow.Recent>().single()
        assertFalse(known.starredOnly)
        // Without auto fill Home is the starred chats only, known from the start.
        val starredHome = HomeRows.build(
            emptyList(), listOf(chat(1)), mapOf(1L to listOf(a)), recent = null, recentMinStarred = 1,
        ).filterIsInstance<HomeRow.Recent>().single()
        assertTrue(starredHome.starredOnly)
    }

    @Test
    fun `Home is Continue watching, Recently added, then a row for each starred chat`() {
        val a = video(1, "Big Buck Bunny (2008).mkv")
        val b = video(1, "Harbour Notes S01E01.mkv")
        val c = video(2, "Harbour Notes S01E02.mkv")
        val d = video(3, "Sintel (2010).mkv")
        val e = video(3, "Lecture one.mp4")
        val chats = listOf(chat(1), chat(2), chat(3))
        val loaded = mapOf(1L to listOf(a, b), 2L to listOf(c), 3L to listOf(d, e))
        val resume = ResumeRecord(
            chatId = 3, messageId = e.messageId, fileId = 1, title = "Lecture one.mp4", chatTitle = "Chat 3",
            sizeBytes = 1, durationSec = 60, positionMs = 5_000, durationMs = 60_000, updatedAt = 1,
        )
        val home = HomeRows.homeChats(chats, favourites = setOf(3, 1), recent = listOf(a, b, c, d, e))
        val rows = HomeRows.build(
            listOf(resume), home, loaded, recent = listOf(a, b, c, d, e), recentMinStarred = 1,
            starred = setOf(3, 1), autoFill = true,
        )
        // Starred chats in the chat list's order; chat 2, picked by Home itself, only feeds Recently added.
        assertEquals(listOf("continue", "recent", "chat-1", "chat-3"), rows.map { it.key })
        val recent = rows.filterIsInstance<HomeRow.Recent>().single()
        assertFalse("not every chat is starred", recent.starredOnly)
        assertTrue(recent.hasMore)
        // What is part way through stays in Continue and nowhere else.
        val chat3 = rows.filterIsInstance<HomeRow.Chat>().single { it.chatId == 3L }
        assertEquals(listOf(d.messageId), chat3.entries.map { (it as ShelfEntry.File).item.messageId })
    }

    @Test
    fun `with nothing starred Home is Continue watching and Recently added`() {
        val a = video(1, "Sintel (2010).mkv")
        val rows = HomeRows.build(
            emptyList(), HomeRows.homeChats(listOf(chat(1)), emptySet(), listOf(a)), mapOf(1L to listOf(a)),
            recent = listOf(a), recentMinStarred = 1, starred = emptySet(), autoFill = true,
        )
        assertEquals(listOf("recent"), rows.map { it.key })
    }

    @Test
    fun `a starred chat still loading holds its row's place, and one with nothing is left out`() {
        val a = video(1, "Sintel (2010).mkv")
        val chats = listOf(chat(1), chat(2), chat(3))
        val rows = HomeRows.build(
            emptyList(), HomeRows.homeChats(chats, setOf(2, 3), listOf(a)), mapOf(1L to listOf(a), 3L to emptyList()),
            recent = listOf(a), recentMinStarred = 1, starred = setOf(2, 3), autoFill = true,
        )
        val chatRows = rows.filterIsInstance<HomeRow.Chat>()
        assertEquals(listOf(2L), chatRows.map { it.chatId })
        assertFalse(chatRows.single().loaded)
    }

    @Test
    fun `the cache gives back what it was given`() {
        val dir = Files.createTempDirectory("tm-home-cache").toFile()
        HomeCache.file = java.io.File(dir, "home.json")
        try {
            val item = video(7, "Sintel (2010).mkv").copy(miniThumbnail = byteArrayOf(1, 2, 3), versions = listOf(video(7)), locality = MediaItem.Locality.Downloaded, onDevice = true)
            val snapshot = HomeCache.Snapshot(mapOf(7L to listOf(item)), listOf(item), setOf(7L), mapOf("7:1" to item), 1234L)
            val decoded = HomeCache.decode(HomeCache.encode(snapshot))
            assertNotNull(decoded)
            assertEquals(snapshot, decoded)
            HomeCache.save(snapshot)
            assertTrue(HomeCache.file!!.isFile)
            HomeCache.clear()
            assertNull(HomeCache.load())
            assertFalse(HomeCache.file!!.exists())
            assertNull("an unreadable file is no cache", HomeCache.decode("{not json"))
        } finally {
            HomeCache.file = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a logo in the UI language beats English, and only PNGs that read as a line`() {
        fun logo(path: String, lang: String?, aspect: Double = 3.0, width: Int = 500, votes: Double = 5.0) =
            MetaPoster(path = path, language = lang, aspect = aspect, width = width, voteAverage = votes)
        val logos = listOf(
            logo("/en.png", "en", votes = 9.0),
            logo("/ml.png", "ml"),
            logo("/ml.svg", "ml", votes = 10.0),
            logo("/tall.png", "ml", aspect = 0.9, votes = 10.0),
            logo("/small.png", "ml", width = 100, votes = 10.0),
        )
        assertEquals("/ml.png", MetaRules.logo(logos, "ml-IN")?.path)
        assertEquals("/en.png", MetaRules.logo(logos, "de")?.path)
        assertNull(MetaRules.logo(listOf(logo("/x.svg", "en")), "en"))
    }

    @Test
    fun `the small stand-in copy of a TMDB picture`() {
        assertEquals("https://image.tmdb.org/t/p/w92/a.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/w342/a.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/b.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/w780/b.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/b.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/original/b.jpg"))
        assertNull(MetaImageSize.tiny("https://image.tmdb.org/t/p/w92/a.jpg"))
        assertNull(MetaImageSize.tiny("https://static.tvmaze.com/uploads/images/medium_portrait/1/1.jpg"))
    }

    @Test
    fun `a card's warm-up hint reaches the warmer once, and nothing without one`() {
        val told = mutableListOf<Long>()
        PlaybackWarmup.warm(video(1))
        PlaybackWarmup.warmer = PlaybackWarmup.Warmer { told += it.messageId }
        try {
            val a = video(1)
            PlaybackWarmup.warm(a)
            PlaybackWarmup.warm(a)
            val b = video(1)
            PlaybackWarmup.warm(b)
            assertEquals(listOf(a.messageId, b.messageId), told)
        } finally {
            PlaybackWarmup.warmer = null
        }
    }
}
