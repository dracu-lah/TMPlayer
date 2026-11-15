package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The search inside one chat (the bug of 2026-11-15: "resident evil" found by the search across
 * chats, not by the chat's own search box, in a chat of a few thousand videos).
 *
 * [TelegramChat] answers `searchChatMessages` the way Telegram treats a release name: the words of
 * a file name are what spaces part, and a word matches whole, so
 * `Resident.Evil.Welcome.To.Raccoon.City.2021.1080p.mkv` is one word that neither "resident evil",
 * "resident" nor "evil" matches. The search across chats found it through its local half (the
 * videos the app had seen); the chat's search asked Telegram alone.
 */
class ChatSearchTest {

    private val chatId = 77L

    private fun video(index: Int, name: String) = MediaItem(
        chatId = chatId, messageId = messageIdOf(index), fileId = index + 1, title = name.substringBeforeLast('.'),
        sizeBytes = 900L * 1024 * 1024, durationSec = 5_000, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = null, date = 1_700_000_000 - index, fileName = name,
    )

    /** Newest first: video [index] is message 16 * (count - index), with a text message (8 less) after each. */
    private fun messageIdOf(index: Int) = (COUNT - index).toLong() * 16

    /**
     * A chat of [COUNT] videos with a text message after each, the ones in [named] under those
     * names and the rest `Show.N.S01E05.mkv`.
     */
    inner class TelegramChat(private val named: Map<Int, String>) : ChatSearchSource {
        val videos = List(COUNT) { video(it, named[it] ?: "Show.$it.S01E05.mkv") }
        var searches = 0
        var histories = 0
        var online = true
        /** Failures to throw, in turn, before answering again. */
        val searchFailures = ArrayDeque<Exception>()
        val historyFailures = ArrayDeque<Exception>()

        private fun telegramMatches(item: MediaItem, query: String): Boolean {
            val words = item.fileName.lowercase().split(' ').filter { it.isNotEmpty() }
            return query.lowercase().split(' ').filter { it.isNotEmpty() }.all { it in words }
        }

        override suspend fun search(query: String, cursors: MediaCursors, limit: Int): MediaPage {
            searches++
            searchFailures.removeFirstOrNull()?.let { throw it }
            val from = cursors.video
            val found = videos.asSequence().filter { from == 0L || it.messageId < from }.filter { telegramMatches(it, query) }.take(limit).toList()
            val done = found.size < limit
            val next = cursors.copy(video = found.lastOrNull()?.messageId ?: from, videoDone = done, documentDone = true, animationDone = true)
            return MediaPage(found, next, done)
        }

        override suspend fun history(fromMessageId: Long, limit: Int, onlyLocal: Boolean): ChatHistoryPage {
            histories++
            historyFailures.removeFirstOrNull()?.let { throw it }
            // Every message id, videos and the text messages between them, newest first.
            val top = COUNT.toLong() * 16
            val start = if (fromMessageId == 0L) top else fromMessageId - 8
            val ids = generateSequence(start) { it - 8 }.takeWhile { it > 0 }.take(limit).toList()
            val byId = videos.associateBy { it.messageId }
            val items = ids.mapNotNull { byId[it] }
            return ChatHistoryPage(items, ids.size, if (ids.size < limit) 0L else ids.last())
        }

        override suspend fun online(): Boolean = online
    }

    /** The chat's search as it was until this fix (BrowseViewModel.firstPage): Telegram alone, the query whole, then its longest words. */
    private suspend fun searchedAsBefore(chat: TelegramChat, query: String): List<MediaItem> {
        val whole = chat.search(query, MediaCursors(), 40).items
        if (whole.isNotEmpty()) return Fuzzy.rank(whole, query) { it.searchText }
        for (word in Fuzzy.tokens(query).filter { it.length >= 3 }.sortedByDescending { it.length }.take(3)) {
            val page = chat.search(word, MediaCursors(), 40).items
            if (page.isNotEmpty()) return Fuzzy.rank(page, query) { it.searchText }
        }
        return emptyList()
    }

    private val dotted = "Resident.Evil.Welcome.To.Raccoon.City.2021.1080p.mkv"

    @Test
    fun `the bug a dotted name outside the loaded window was never found and now is`() = runBlocking {
        // Index 200: past the 120 videos the grid had loaded, inside the newest 500 messages.
        val chat = TelegramChat(mapOf(200 to dotted))
        val loaded = chat.videos.take(120)
        assertEquals(emptyList(), searchedAsBefore(chat, "resident evil"), "the search before the fix")

        val run = ChatSearchRun(chat, "resident evil", loaded, chatId)
        assertTrue(run.local().items.isEmpty(), "not among what was loaded")
        val answer = run.first()
        assertEquals(listOf(dotted), answer.items.map { it.fileName })
        assertEquals(ChatSearchHow.Scanned, answer.how)
        assertTrue(answer.canGoDeeper, "a chat of 5,000 videos is not read to its start in one go")
        assertEquals(ChatSearch.SCAN_PAGES * ChatSearch.SCAN_PAGE, answer.scanned)
    }

    @Test
    fun `a dotted name already loaded is the first answer before Telegram is asked`() = runBlocking {
        val chat = TelegramChat(mapOf(30 to dotted))
        val run = ChatSearchRun(chat, "  Resident   EVIL ", chat.videos.take(120), chatId)
        assertEquals(listOf(dotted), run.local().items.map { it.fileName })
        assertEquals(0, chat.searches + chat.histories)
        // And Telegram's empty answer does not take it away.
        assertEquals(listOf(dotted), run.first().items.map { it.fileName })
    }

    @Test
    fun `a match deep in the chat is reached by Search deeper, a bounded round at a time, and the start ends it`() = runBlocking {
        val chat = TelegramChat(mapOf(3_000 to dotted))
        val run = ChatSearchRun(chat, "raccoon city", emptyList(), chatId)
        var answer = run.first()
        assertTrue(answer.items.isEmpty())
        var rounds = 0
        while (answer.items.isEmpty() && answer.canGoDeeper) {
            val before = chat.histories
            answer = run.deeper()
            assertTrue(chat.histories - before <= ChatSearch.SCAN_PAGES + 2, "one round is bounded")
            rounds++
        }
        assertEquals(listOf(dotted), answer.items.map { it.fileName })
        assertTrue(rounds in 10..13, "rounds $rounds")
        while (answer.canGoDeeper) answer = run.deeper()
        val after = chat.histories
        assertFalse(run.deeper().canGoDeeper)
        assertEquals(after, chat.histories, "the chat's start is never asked again")
        assertEquals(COUNT * 2, answer.scanned)
    }

    @Test
    fun `Telegram's answer pages on with its cursors and adds under what is listed without reordering it`() = runBlocking {
        val named = (0 until 180).associate { it * 10 to "Night Train ${it * 10}.mkv" }
        val chat = TelegramChat(named)
        // One copy loaded already, an old one.
        val run = ChatSearchRun(chat, "night train", chat.videos.take(1_000).filter { it.fileName == "Night Train 990.mkv" }, chatId)
        assertEquals(listOf("Night Train 990.mkv"), run.local().items.map { it.fileName })
        var answer = run.first()
        assertEquals(ChatSearchHow.Server, answer.how)
        assertEquals("Night Train 990.mkv", answer.items.first().fileName, "what was listed stays first")
        assertEquals(ChatSearch.PAGE + 1, answer.items.size)
        assertFalse(answer.endReached)
        val shown = answer.items
        answer = run.more()
        assertEquals(shown, answer.items.take(shown.size), "a page never moves a card above it")
        while (!answer.endReached) answer = run.more()
        assertEquals(180, answer.items.size)
        assertEquals(180, answer.items.map { it.messageId }.toSet().size, "each once")
        assertTrue(chat.histories <= ChatSearch.SCAN_PAGES + 2, "one round of the history beside Telegram, no more")
    }

    @Test
    fun `a dotted copy in the newest part is found beside what Telegram's search found`() = runBlocking {
        val chat = TelegramChat(mapOf(100 to dotted, 4_500 to "Resident Evil 2002.mkv"))
        val answer = ChatSearchRun(chat, "resident evil", emptyList(), chatId).first()
        assertEquals(ChatSearchHow.Server, answer.how)
        assertEquals(listOf("Resident Evil 2002.mkv", dotted), answer.items.map { it.fileName })
    }

    @Test
    fun `the release name spellings are asked when the words find nothing`() = runBlocking {
        val chat = TelegramChat(mapOf(4_000 to "Resident_Evil 2002.mkv"))
        val answer = ChatSearchRun(chat, "resident evil", emptyList(), chatId).first()
        assertEquals(listOf("Resident_Evil 2002.mkv"), answer.items.map { it.fileName })
        assertEquals(ChatSearchHow.Server, answer.how)
        assertEquals(listOf("Resident evil", "resident_evil", "resident.evil", "resident", "evil"), ChatSearch.spellings("Resident  evil"))
    }

    @Test
    fun `a short flood wait is waited out once by this search, a long one reads the history instead`() = runBlocking {
        val chat = TelegramChat(mapOf(10 to "Night Train.mkv"))
        chat.searchFailures += Exception("FLOOD_WAIT_5")
        val waited = mutableListOf<Long>()
        val answer = ChatSearchRun(chat, "night train", emptyList(), chatId, wait = { waited += it }).first()
        assertEquals(listOf(5_000L), waited)
        assertEquals(ChatSearchHow.Server, answer.how)

        val longer = TelegramChat(mapOf(10 to "Night Train.mkv"))
        longer.searchFailures += Exception("Too Many Requests: retry after 600")
        val waitedLong = mutableListOf<Long>()
        val fallback = ChatSearchRun(longer, "night train", emptyList(), chatId, wait = { waitedLong += it }).first()
        assertTrue(waitedLong.isEmpty())
        assertEquals(ChatSearchHow.Scanned, fallback.how)
        assertEquals(listOf("Night Train.mkv"), fallback.items.map { it.fileName })

        // Two flood waits in a row: the second is not waited out, so retries stop by themselves.
        val twice = TelegramChat(mapOf(10 to "Night Train.mkv"))
        twice.searchFailures += Exception("FLOOD_WAIT_3")
        twice.searchFailures += Exception("FLOOD_WAIT_3")
        val waitedTwice = mutableListOf<Long>()
        val run = ChatSearchRun(twice, "night train", emptyList(), chatId, wait = { waitedTwice += it })
        assertEquals(ChatSearchHow.Scanned, run.first().how)
        assertEquals(listOf(3_000L), waitedTwice)
        assertEquals(2, twice.searches)
    }

    @Test
    fun `a failed search reads the history, and when that fails too the failure is the answer`() = runBlocking {
        val chat = TelegramChat(mapOf(10 to "Night Train.mkv"))
        chat.searchFailures += Exception("Request aborted")
        assertEquals(listOf("Night Train.mkv"), ChatSearchRun(chat, "night", emptyList(), chatId).first().items.map { it.fileName })

        val broken = TelegramChat(emptyMap())
        broken.searchFailures += Exception("Request aborted")
        broken.historyFailures += Exception("Chat not found")
        val answer = ChatSearchRun(broken, "night", emptyList(), chatId).first()
        assertTrue(answer.items.isEmpty())
        assertNotNull(answer.failed)
        assertEquals(1, broken.histories)
    }

    @Test
    fun `offline the search reads only what is on the device and says so`() = runBlocking {
        val chat = TelegramChat(mapOf(10 to dotted))
        chat.online = false
        val answer = ChatSearchRun(chat, "raccoon", emptyList(), chatId).first()
        assertEquals(ChatSearchHow.Offline, answer.how)
        assertEquals(0, chat.searches)
        assertEquals(listOf(dotted), answer.items.map { it.fileName })
    }

    @Test
    fun `the TDLib calls of a first answer are bounded`() = runBlocking {
        val chat = TelegramChat(emptyMap())
        val run = ChatSearchRun(chat, "one two three four five six", emptyList(), chatId)
        run.first()
        assertTrue(chat.searches <= 7, "searches ${chat.searches}")
        assertTrue(chat.histories <= ChatSearch.SCAN_PAGES + 2, "histories ${chat.histories}")
        assertNull(run.first().failed)
    }

    @Test
    fun `the local match takes every word as a prefix in any order, folding case, accents and punctuation`() {
        assertTrue(ChatSearch.matches(dotted, "evil resid"))
        assertTrue(ChatSearch.matches(dotted, "RACCOON  city 2021"))
        assertTrue(ChatSearch.matches("Pokémon_The+Movie-2000.mkv", "pokemon movie"))
        assertFalse(ChatSearch.matches(dotted, "resident evil apocalypse"))
        assertFalse(ChatSearch.matches(dotted, "vil"), "a prefix, not any substring")
        assertFalse(ChatSearch.matches(dotted, " ... "))
        assertEquals(emptyList(), ChatSearch.spellings("   "))
        assertEquals(listOf("severance"), ChatSearch.spellings("severance"))
    }

    @Test
    fun `merge keeps what is listed and adds the rest once`() {
        val a = video(1, "a.mkv")
        val b = video(2, "b.mkv")
        val c = video(3, "c.mkv")
        assertEquals(listOf(b, a, c), ChatSearch.merge(listOf(b, a), listOf(a, c, b)))
        val listed = listOf(a)
        assertTrue(ChatSearch.merge(listed, listOf(a)) === listed)
    }

    private companion object {
        const val COUNT = 5_000
    }
}
